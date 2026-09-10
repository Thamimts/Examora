package com.examora;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-proctor;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-proctor-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class ProctorSecurityIntegrationTest {
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

        insertUser("teacher-a", "Teacher A", "teacher-a@example.com", "teacherA123", "TEACHER");
        insertUser("teacher-b", "Teacher B", "teacher-b@example.com", "teacherB123", "TEACHER");
        insertUser("admin-1", "Admin One", "admin@example.com", "admin123", "ADMIN");
        insertUser("student-1", "Student One", "student@example.com", "student123", "STUDENT");

        insertExam("exam-a", "Algebra", "teacher-a");
        insertExam("exam-b", "Geometry", "teacher-b");
        insertAttempt("attempt-1", "exam-a", "student-1");
        insertAttempt("attempt-2", "exam-b", "student-1");
    }

    @Test
    void studentsAreDeniedEveryProctorEndpoint() throws Exception {
        String token = token("student@example.com", "student123");
        mockMvc.perform(proctor("POST", "/api/proctor/attempts/attempt-1/start", token, null))
                .andExpect(status().isForbidden());
        mockMvc.perform(proctor("POST", "/api/proctor/attempts/attempt-1/stop", token, null))
                .andExpect(status().isForbidden());
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, eventsFor("attempt-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("Access is denied")));
    }

    @Test
    void teacherCanStartAndStopAttemptsOnOwnExam() throws Exception {
        String token = token("teacher-a@example.com", "teacherA123");
        mockMvc.perform(proctor("POST", "/api/proctor/attempts/attempt-1/start", token, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("started"));
        mockMvc.perform(proctor("POST", "/api/proctor/attempts/attempt-1/stop", token, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("stopped"));
    }

    @Test
    void teacherCannotProctorAttemptsOnAnotherTeachersExam() throws Exception {
        String teacherBToken = token("teacher-b@example.com", "teacherB123");
        mockMvc.perform(proctor("POST", "/api/proctor/attempts/attempt-1/start", teacherBToken, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not have access to this exam's proctoring."));
        mockMvc.perform(proctor("POST", "/api/proctor/attempts/attempt-1/stop", teacherBToken, null))
                .andExpect(status().isForbidden());
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", teacherBToken, eventsFor("attempt-1")))
                .andExpect(status().isForbidden());

        String teacherAToken = token("teacher-a@example.com", "teacherA123");
        mockMvc.perform(proctor("POST", "/api/proctor/attempts/attempt-2/start", teacherAToken, null))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanProctorAnyAttempt() throws Exception {
        String token = token("admin@example.com", "admin123");
        mockMvc.perform(proctor("POST", "/api/proctor/attempts/attempt-2/start", token, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("started"));
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, eventsFor("attempt-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
    }

    @Test
    void teacherCanPostEventsOnlyForOwnExams() throws Exception {
        String token = token("teacher-a@example.com", "teacherA123");
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, eventsFor("attempt-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, eventsFor("attempt-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownOrMissingAttemptIsRejected() throws Exception {
        String token = token("teacher-a@example.com", "teacherA123");
        mockMvc.perform(proctor("POST", "/api/proctor/attempts/unknown-attempt/start", token, null))
                .andExpect(status().isNotFound());
        mockMvc.perform(proctor("POST", "/api/proctor/attempts/unknown-attempt/start", null, null))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, eventsFor("")))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder proctor(
            String method, String path, String token, String body) {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (body != null) {
            builder.content(body);
        }
        return builder;
    }

    private String eventsFor(String attemptId) {
        return "{\"events\":[{\"attemptId\":\"" + attemptId
                + "\",\"type\":\"WINDOW_BLUR\",\"occurredAt\":\"2030-01-01T00:00:00Z\"}]}";
    }

    private String token(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/token").asText();
    }

    private void insertUser(String id, String name, String email, String password, String role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role);
    }

    private void insertExam(String id, String title, String ownerId) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, title, "Math", "2030-06-01", 60, "PUBLISHED", 15, 0.0, ownerId);
    }

    private void insertAttempt(String id, String examId, String studentId) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) values (?, ?, ?, 1, 'STARTED', ?, ?, 0)",
                id, examId, studentId, Timestamp.from(now), Timestamp.from(now.plus(1, ChronoUnit.HOURS)));
    }
}