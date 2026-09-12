package com.examora;

import com.examora.model.Role;
import com.examora.model.User;
import com.examora.security.JwtService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        "examora.jwt.secret=test-secret-for-examora-authorization-bypass-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class AuthorizationBypassIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

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
    void tamperedJwtIsRejectedWithUnauthorized() throws Exception {
        String token = login("student@example.com", "student123");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("aa") ? "bb" : "aa");

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forgedAdminRoleClaimIsIgnoredBecauseDatabaseRoleIsAuthoritative() throws Exception {
        User fakeAdmin = new User("student-1", "Student One", "student@example.com", Role.ADMIN, null);
        String forgedToken = jwtService.generateToken(fakeAdmin);

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void registrationRoleSmugglingCannotCreatePrivilegedAccount() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sneaky User\",\"email\":\"sneaky@example.com\",\"password\":\"password123\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("STUDENT"));
    }

    @Test
    void teacherCannotAccessTheAdminIdResolvedByPath() throws Exception {
        String token = login("teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/users/admin-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/users/teacher-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherCannotModifyUsersEvenWithATargetedId() throws Exception {
        String token = login("teacher@example.com", "teacher123");

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked\",\"email\":\"blocked@example.com\",\"password\":\"password123\",\"role\":\"TEACHER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadAnArbitraryUserByPathId() throws Exception {
        String token = login("admin@example.com", "admin123");

        mockMvc.perform(get("/api/users/student-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("student@example.com"));
    }

    @Test
    void accountEndpointsRejectAnonymousCallers() throws Exception {
        mockMvc.perform(post("/api/auth/2fa/setup"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/2fa/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password123\",\"newPassword\":\"newpassword123\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("token").asText();
    }

    private void insertUser(String id, String name, String email, String password, String role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role);
    }
}