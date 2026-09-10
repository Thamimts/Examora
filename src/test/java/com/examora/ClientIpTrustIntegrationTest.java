package com.examora;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-iptrust;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-iptrust-tests-change-in-real-use",
        "examora.login.max-per-ip-email=3",
        "examora.login.max-per-ip=8",
        "examora.login.window-seconds=60"
})
class ClientIpTrustIntegrationTest {
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
    void spoofedForwardedForCannotExtendPerAccountLockout() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(login("pair@example.com", "wrong-password", "198.51.100." + (10 + attempt), "203.0.113.200"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login("pair@example.com", "wrong-password", "198.51.100.99", "203.0.113.200"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many login attempts. Please try again later."));
    }

    @Test
    void spoofedForwardedForCannotBypassPerIpLimitAcrossAccounts() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            mockMvc.perform(login("spoof-" + attempt + "@example.com", "wrong-password", "198.51.100." + (20 + attempt), "203.0.113.201"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login("spoof-8@example.com", "wrong-password", "198.51.100.99", "203.0.113.201"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many login attempts. Please try again later."));
    }

    private MockHttpServletRequestBuilder login(String email, String password, String spoofedForwardedFor, String remoteAddr) {
        return post("/api/auth/login")
                .header("X-Forwarded-For", spoofedForwardedFor)
                .with(remoteAddr(remoteAddr))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private void insertUser(String id, String name, String email, String password, String role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role);
    }
}