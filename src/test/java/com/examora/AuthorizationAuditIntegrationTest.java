package com.examora;

import com.examora.security.Base32;
import com.examora.security.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-authorization-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class AuthorizationAuditIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from two_factor_challenges");
        jdbcTemplate.update("delete from two_factor_recovery_codes");
        jdbcTemplate.update("delete from oauth_accounts");
        jdbcTemplate.update("delete from activity_events");
        jdbcTemplate.update("delete from users");

        insertUser("admin-1", "Admin One", "admin@example.com", "admin123", "ADMIN");
        insertUser("teacher-1", "Teacher One", "teacher@example.com", "teacher123", "TEACHER");
        insertUser("student-1", "Student One", "student@example.com", "student123", "STUDENT");
    }

    @Test
    void teacherCannotAccessAdminUserManagement() throws Exception {
        String token = login("teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked\",\"email\":\"blocked@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyAuthenticatedUserCanReadTheirOwnProfile() throws Exception {
        String token = login("student@example.com", "student123");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("student@example.com"));
    }

    @Test
    void adminCanCreateAndUpdateUsersAndEveryMutationIsAudited() throws Exception {
        String adminToken = login("admin@example.com", "admin123");

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        MvcResult created = mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Teacher\",\"email\":\"new-teacher@example.com\",\"password\":\"password123\",\"role\":\"TEACHER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("TEACHER"))
                .andReturn();
        String createdId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText();

        assertThat(countEvents("USER_CREATED", "admin-1")).isEqualTo(1);

        mockMvc.perform(put("/api/users/" + createdId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed Teacher\",\"email\":\"new-teacher@example.com\",\"role\":\"TEACHER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed Teacher"));

        assertThat(countEvents("USER_UPDATED", "admin-1")).isEqualTo(1);
    }

    @Test
    void adminCanDeleteUsersAndDeletionIsAudited() throws Exception {
        String adminToken = login("admin@example.com", "admin123");

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Doomed\",\"email\":\"doomed@example.com\",\"password\":\"password123\",\"role\":\"STUDENT\"}"))
                .andExpect(status().isOk());

        String id = jdbcTemplate.queryForObject("select id from users where email = 'doomed@example.com'", String.class);

        mockMvc.perform(delete("/api/users/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("select count(*) from users where id = ?", Integer.class, id)).isZero();
        assertThat(countEvents("USER_DELETED", "admin-1")).isEqualTo(1);
    }

    @Test
    void twoFactorLifecycleIsAuditedPerStudent() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"2FA Student\",\"email\":\"2fa-student@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        String token = data.path("token").asText();
        String id = data.path("user").path("id").asText();

        MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String secret = objectMapper.readTree(setup.getResponse().getContentAsString()).path("data").path("secret").asText();

        assertThat(countEvents("TWO_FACTOR_SETUP_REQUESTED", id)).isEqualTo(1);

        String code = Totp.generate(Base32.decode(secret), Instant.now(), 30, 6);
        mockMvc.perform(post("/api/auth/2fa/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryCodes.length()").value(10));

        assertThat(countEvents("TWO_FACTOR_ENABLED", id)).isEqualTo(1);
    }

    @Test
    void auditMessagesNeverContainSecretsOrTokens() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Secret Student\",\"email\":\"secret-student@example.com\",\"password\":\"supersecret123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        String token = data.path("token").asText();
        String id = data.path("user").path("id").asText();

        MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String secret = objectMapper.readTree(setup.getResponse().getContentAsString()).path("data").path("secret").asText();

        String code = Totp.generate(Base32.decode(secret), Instant.now(), 30, 6);
        MvcResult confirm = mockMvc.perform(post("/api/auth/2fa/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode recoveryCodes = objectMapper.readTree(confirm.getResponse().getContentAsString()).path("data").path("recoveryCodes");

        List<String> messages = jdbcTemplate.queryForList(
                "select message from activity_events where actor_id = ?", String.class, id);
        assertThat(messages).isNotEmpty();
        for (String message : messages) {
            assertThat(message).doesNotContain(secret);
            assertThat(message).doesNotContain("supersecret123");
            assertThat(message).doesNotContain(token);
            assertThat(message).doesNotContain("eyJ");
        }

        List<String> codes = new ArrayList<>();
        recoveryCodes.forEach(node -> codes.add(node.asText()));
        String allMessages = String.join(" ", messages);
        for (String recoveryCode : codes) {
            assertThat(allMessages).doesNotContain(recoveryCode);
            assertThat(allMessages).doesNotContain(recoveryCode.replace("-", ""));
        }
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    private int countEvents(String type, String actorId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from activity_events where type = ? and actor_id = ? and audience = 'ADMIN'",
                Integer.class, type, actorId);
    }

    private void insertUser(String id, String name, String email, String password, String role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role);
    }
}