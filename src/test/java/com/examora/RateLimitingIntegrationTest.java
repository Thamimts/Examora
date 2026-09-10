package com.examora;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-ratelimit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-ratelimit-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip-email=3",
        "examora.login.max-per-ip=4",
        "examora.login.window-seconds=60"
})
class RateLimitingIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from activity_events");
        jdbcTemplate.update("delete from proctor_events");
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("student-1", "Student One", "student@example.com", "student123", "STUDENT");
    }

    @Test
    void excessFailedAttemptsAreBlockedWith429AndRetryAfter() throws Exception {
        String ip = "203.0.113.10";
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(login("student@example.com", "wrong-password", ip))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login("student@example.com", "wrong-password", ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many login attempts. Please try again later."))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]*")));
    }

    @Test
    void successfulLoginAndOccasionalFailureRemainAllowed() throws Exception {
        String ip = "203.0.113.11";
        mockMvc.perform(login("student@example.com", "student123", ip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString());
        mockMvc.perform(login("student@example.com", "wrong-password", ip))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(login("student@example.com", "student123", ip))
                .andExpect(status().isOk());
    }

    @Test
    void perIpLimitAppliesAcrossDifferentAccounts() throws Exception {
        String ip = "203.0.113.12";
        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(login("other" + attempt + "@example.com", "wrong-password", ip))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login("other4@example.com", "wrong-password", ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many login attempts. Please try again later."));
    }

    @Test
    void wrongEmailAndWrongPasswordReturnIdenticalErrors() throws Exception {
        mockMvc.perform(login("unknown@example.com", "wrong-password", "203.0.113.13"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
        mockMvc.perform(login("student@example.com", "wrong-password", "203.0.113.13"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String email, String password, String ip) {
        return post("/api/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private void insertUser(String id, String name, String email, String password, String role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role);
    }
}