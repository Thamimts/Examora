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
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-student-proctor;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-student-proctor-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class StudentProctorSubmissionIntegrationTest {
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

        insertUser("student-a", "Student A", "student-a@example.com", "studentA123", "STUDENT");
        insertUser("student-b", "Student B", "student-b@example.com", "studentB123", "STUDENT");
        insertUser("teacher-a", "Teacher A", "teacher-a@example.com", "teacherA123", "TEACHER");
        insertUser("admin-1", "Admin One", "admin@example.com", "admin123", "ADMIN");

        insertExam("exam-a", "Algebra", "teacher-a");
        insertExam("exam-b", "Geometry", "teacher-a");

        insertAttempt("attempt-a1", "exam-a", "student-a", "STARTED");
        insertAttempt("attempt-a2", "exam-b", "student-a", "STARTED");
        insertAttempt("attempt-b1", "exam-b", "student-b", "STARTED");

        insertAttempt("attempt-submitted", "exam-a", "student-b", "SUBMITTED", 2);
        insertAttempt("attempt-expired", "exam-a", "student-b", "EXPIRED", 3);
    }

    @Test
    void studentCanSubmitEventForOwnActiveAttempt() throws Exception {
        String token = token("student-a@example.com", "studentA123");
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, eventsFor("attempt-a1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
    }

    @Test
    void studentCannotSubmitEventForAnotherStudentAttempt() throws Exception {
        String token = token("student-a@example.com", "studentA123");
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, eventsFor("attempt-b1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("attempt belongs to another student")));
    }

    @Test
    void studentCannotSubmitEventForAnotherStudentsAttemptOnAnotherExam() throws Exception {
        String token = token("student-a@example.com", "studentA123");
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, eventsFor("attempt-submitted")))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherCannotSubmitStudentProctorEventsForAnotherTeachersExam() throws Exception {
        insertUser("teacher-b", "Teacher B", "teacher-b@example.com", "teacherB123", "TEACHER");
        insertExam("exam-x", "Physics", "teacher-b");
        insertAttempt("attempt-x1", "exam-x", "student-a", "STARTED");

        String teacherAToken = token("teacher-a@example.com", "teacherA123");
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", teacherAToken, eventsFor("attempt-x1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", null, eventsFor("attempt-a1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsupportedEventTypeIsRejected() throws Exception {
        String token = token("student-a@example.com", "studentA123");
        String body = "{\"events\":[{\"eventId\":\"bad-type\",\"attemptId\":\"attempt-a1\",\"type\":\"ILLEGAL_CHEAT_DETECTED\",\"occurredAt\":\"2026-09-12T00:00:00Z\"}]}";
        mockMvc.perform(post("/api/proctor/events/batch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Unsupported proctor event type")));
    }

    @Test
    void oversizedMetadataIsRejected() throws Exception {
        String token = token("student-a@example.com", "studentA123");
        StringBuilder payload = new StringBuilder("{\"events\":[{\"eventId\":\"large-meta\",\"attemptId\":\"attempt-a1\",\"type\":\"TAB_SWITCH\",\"occurredAt\":\"2026-09-12T00:00:00Z\",\"metadata\":{");
        payload.append("\"key\":\"");
        for (int i = 0; i < 5000; i++) payload.append("X");
        payload.append("\"}}]}");
        mockMvc.perform(post("/api/proctor/events/batch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("metadata is too large")));
    }

    @Test
    void eventAfterSubmittedAttemptIsRejected() throws Exception {
        String token = token("student-b@example.com", "studentB123");
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, eventsFor("attempt-submitted")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("not active")));
    }

    @Test
    void eventAfterExpiredAttemptIsRejectedAndPersistsExpired() throws Exception {
        String token = token("student-b@example.com", "studentB123");
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, eventsFor("attempt-expired")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("not active")));
        String status = jdbcTemplate.queryForObject(
                "select status from exam_attempts where id='attempt-expired'", String.class);
        org.junit.jupiter.api.Assertions.assertEquals("EXPIRED", status);
    }

    @Test
    void riskScoreIsCalculatedCorrectly() throws Exception {
        String token = token("student-a@example.com", "studentA123");
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, multiEvents("attempt-a1",
                        "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(3));

        String teacherToken = token("teacher-a@example.com", "teacherA123");
        mockMvc.perform(get("/api/proctor/exams/exam-a/monitor").header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attempts[?(@.attemptId == 'attempt-a1')].riskScore").value(45.0))
                .andExpect(jsonPath("$.data.attempts[?(@.attemptId == 'attempt-a1')].riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data.attempts[?(@.attemptId == 'attempt-a1')].eventCount").value(3));
    }

    @Test
    void duplicateEventIdIsDeduplicated() throws Exception {
        String token = token("student-a@example.com", "studentA123");
        String body = "{\"events\":[{\"eventId\":\"dup-student-1\",\"attemptId\":\"attempt-a1\",\"type\":\"TAB_SWITCH\",\"occurredAt\":\"2026-09-12T00:00:00Z\"}]}";
        mockMvc.perform(post("/api/proctor/events/batch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
        mockMvc.perform(post("/api/proctor/events/batch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(0));
    }

    @Test
    void eventsDoNotLeakAnswersScoresOrOptions() throws Exception {
        String token = token("student-a@example.com", "studentA123");
        mockMvc.perform(proctor("POST", "/api/proctor/events/batch", token, eventsFor("attempt-a1")))
                .andExpect(status().isOk());

        String teacherToken = token("teacher-a@example.com", "teacherA123");
        MvcResult result = mockMvc.perform(get("/api/proctor/attempts/attempt-a1/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(
                !json.contains("\"question\"") && !json.contains("\"correct\"") && !json.contains("\"answer\""),
                "Proctor response must not contain question, answer, or correct fields");

        MvcResult monitorResult = mockMvc.perform(get("/api/proctor/exams/exam-a/monitor")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andReturn();
        String monitorJson = monitorResult.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                monitorJson.contains("\"correct\"") || monitorJson.contains("\"answer\""),
                "Monitor response must not contain answer or correct fields");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder proctor(
            String method, String path, String token, String body) {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON);
        if (token != null) builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) builder.content(body);
        return builder;
    }

    private String eventsFor(String attemptId) {
        return "{\"events\":[{\"eventId\":\"" + attemptId + "-evt\",\"attemptId\":\"" + attemptId
                + "\",\"type\":\"WINDOW_BLUR\",\"occurredAt\":\"2030-01-01T00:00:00Z\"}]}";
    }

    private String multiEvents(String attemptId, String... types) {
        StringBuilder sb = new StringBuilder("{\"events\":[");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"eventId\":\"")
                    .append(attemptId).append("-evt-").append(i).append("\",\"attemptId\":\"")
                    .append(attemptId).append("\",\"type\":\"").append(types[i])
                    .append("\",\"occurredAt\":\"2030-01-01T00:00:0").append(i).append("Z\"}");
        }
        sb.append("]}");
        return sb.toString();
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

    private void insertAttempt(String id, String examId, String studentId, String status) {
        insertAttempt(id, examId, studentId, status, 1);
    }

    private void insertAttempt(String id, String examId, String studentId, String status, int attemptNumber) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) values (?, ?, ?, ?, ?, ?, ?, 0)",
                id, examId, studentId, attemptNumber, status, Timestamp.from(now), Timestamp.from(now.plus(1, ChronoUnit.HOURS)));
    }
}