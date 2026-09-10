package com.examora;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-retest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-retest-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class RetestSecurityIntegrationTest {
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
        jdbcTemplate.update("delete from retest_requests");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("student-1", "Student One", "student@example.com", "student123", "STUDENT");
        insertUser("teacher-1", "Teacher One", "teacher@example.com", "teacher123", "TEACHER");
        insertUser("admin-1", "Admin One", "admin@example.com", "admin123", "ADMIN");
    }

    @Test
    void adminReviewWithoutStatusReturns400InsteadOf500() throws Exception {
        String adminToken = login("admin@example.com", "admin123");
        String requestId = createRetestRequestId();

        mockMvc.perform(post("/api/retest-requests/admin/" + requestId + "/review")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/retest-requests/admin/" + requestId + "/review")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"because\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminReviewWithInvalidStatusReturns400() throws Exception {
        String adminToken = login("admin@example.com", "admin123");
        String requestId = createRetestRequestId();

        mockMvc.perform(post("/api/retest-requests/admin/" + requestId + "/review")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonAdminCannotReviewRetestRequests() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String requestId = createRetestRequestId();

        mockMvc.perform(post("/api/retest-requests/admin/" + requestId + "/review")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/retest-requests/admin/" + requestId + "/review")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/retest-requests/admin").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewApprovesAndThenConcurrentReviewIsRejected() throws Exception {
        String adminToken = login("admin@example.com", "admin123");
        String requestId = createRetestRequestId();

        mockMvc.perform(post("/api/retest-requests/admin/" + requestId + "/review")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(post("/api/retest-requests/admin/" + requestId + "/review")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void studentSeesOnlyTheirOwnRetestRequests() throws Exception {
        String studentToken = login("student@example.com", "student123");
        String adminToken = login("admin@example.com", "admin123");
        createRetestRequestId();

        mockMvc.perform(get("/api/retest-requests").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/retest-requests/admin").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    private String createRetestRequestId() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken, studentToken);
        String created = mockMvc.perform(post("/api/retest-requests/" + examId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).path("data").path("id").asText();
    }

    private String createPublishedExam(String teacherToken, String studentToken) throws Exception {
        String created = mockMvc.perform(post("/api/exams")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Retest Exam\",\"subject\":\"Math\",\"date\":\"2026-09-01\",\"duration\":60}"))
                .andReturn().getResponse().getContentAsString();
        String examId = objectMapper.readTree(created).path("data").path("id").asText();
        mockMvc.perform(post("/api/exams/" + examId + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        String questionResponse = mockMvc.perform(post("/api/exams/" + examId + "/questions")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Q\",\"options\":[\"A\",\"B\"],\"answer\":\"A\"}"))
                .andReturn().getResponse().getContentAsString();
        String questionId = objectMapper.readTree(questionResponse).path("data").path("id").asText();
        mockMvc.perform(post("/api/exams/" + examId + "/start").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"A\"}]}"))
                .andExpect(status().isOk());
        return examId;
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode token = objectMapper.readTree(response).path("data").path("token");
        assertThat(token.asText()).isNotBlank();
        return token.asText();
    }

    private void insertUser(String id, String name, String email, String password, String role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}