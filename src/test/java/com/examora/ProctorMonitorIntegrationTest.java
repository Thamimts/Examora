package com.examora;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-proctor-monitor;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-proctor-monitor-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class ProctorMonitorIntegrationTest {
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
        insertUser("student-1", "Student One", "student-one@example.com", "student123", "STUDENT");
        insertUser("student-2", "Student Two", "student-two@example.com", "student123", "STUDENT");

        insertExam("exam-a", "Algebra", "teacher-a");
        insertExam("exam-b", "Geometry", "teacher-b");

        insertAttempt("attempt-1", "exam-a", "student-1", 1, "STARTED", "2030-06-01T10:00:00Z", "2030-06-01T11:00:00Z", null);
        insertAttempt("attempt-2", "exam-a", "student-1", 2, "SUBMITTED", "2030-06-02T10:00:00Z", "2030-06-02T11:00:00Z", "2030-06-02T10:30:00Z");
        insertAttempt("attempt-4", "exam-a", "student-2", 1, "EXPIRED", "2030-06-03T10:00:00Z", "2030-06-03T11:00:00Z", null);
        insertAttempt("attempt-3", "exam-b", "student-1", 1, "STARTED", "2030-06-04T10:00:00Z", "2030-06-04T11:00:00Z", null);

        insertEvent("evt-a", "attempt-2", "evt-a", "WINDOW_BLUR", "2030-06-02T00:00:00Z", null);
        insertEvent("evt-b", "attempt-2", "evt-b", "WINDOW_BLUR", "2030-06-02T00:01:00Z", null);
        insertEvent("evt-c", "attempt-2", "evt-c", "TAB_SWITCH", "2030-06-02T00:02:00Z", null);
        insertEvent("evt-d", "attempt-4", "evt-d", "TAB_SWITCH", "2030-06-03T00:00:00Z", null);
        insertEvent("evt-e", "attempt-4", "evt-e", "TAB_SWITCH", "2030-06-03T00:01:00Z", null);
        insertEvent("evt-f", "attempt-4", "evt-f", "MULTIPLE_FACES", "2030-06-03T01:00:00Z", "{\"count\":2,\"reason\":\"second-face\"}");
        insertEvent("evt-g", "attempt-3", "evt-g", "AUDIO_DETECTED", "2030-06-04T00:00:00Z", "{\"note\":\"noise\"}");
    }

    @Test
    void ownerTeacherCanMonitorOwnExam() throws Exception {
        String token = token("teacher-a@example.com", "teacherA123");
        mockMvc.perform(get("/api/proctor/exams/exam-a/monitor").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.examId").value("exam-a"))
                .andExpect(jsonPath("$.data.examTitle").value("Algebra"))
                .andExpect(jsonPath("$.data.attempts.length()").value(3))
                .andExpect(jsonPath("$.data.attempts[*].attemptId", not(hasItem("attempt-3"))))
                .andExpect(jsonPath("$.data.attempts[0].attemptId").value("attempt-4"))
                .andExpect(jsonPath("$.data.attempts[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.attempts[0].student.id").value("student-2"))
                .andExpect(jsonPath("$.data.attempts[0].student.name").value("Student Two"))
                .andExpect(jsonPath("$.data.attempts[0].student.email").value("student-two@example.com"))
                .andExpect(jsonPath("$.data.attempts[0].activeNow").value(false))
                .andExpect(jsonPath("$.data.attempts[0].riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.data.attempts[0].riskScore").value(60.0))
                .andExpect(jsonPath("$.data.attempts[0].eventCount").value(3))
                .andExpect(jsonPath("$.data.attempts[0].latestProctorEvent.type").value("MULTIPLE_FACES"))
                .andExpect(jsonPath("$.data.attempts[0].latestProctorEvent.metadata.count").value(2))
                .andExpect(jsonPath("$.data.attempts[0].lastActivityAt").value("2030-06-03T01:00:00Z"))
                .andExpect(jsonPath("$.data.attempts[1].attemptId").value("attempt-2"))
                .andExpect(jsonPath("$.data.attempts[1].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.attempts[1].activeNow").value(false))
                .andExpect(jsonPath("$.data.attempts[1].riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data.attempts[1].riskScore").value(35.0))
                .andExpect(jsonPath("$.data.attempts[1].eventCount").value(3))
                .andExpect(jsonPath("$.data.attempts[1].latestProctorEvent.type").value("TAB_SWITCH"))
                .andExpect(jsonPath("$.data.attempts[2].attemptId").value("attempt-1"))
                .andExpect(jsonPath("$.data.attempts[2].status").value("STARTED"))
                .andExpect(jsonPath("$.data.attempts[2].activeNow").value(true))
                .andExpect(jsonPath("$.data.attempts[2].riskLevel").value("LOW"))
                .andExpect(jsonPath("$.data.attempts[2].riskScore").value(0.0))
                .andExpect(jsonPath("$.data.attempts[2].eventCount").value(0))
                .andExpect(jsonPath("$.data.attempts[2].latestProctorEvent").value(nullValue()))
                .andExpect(jsonPath("$.data.attempts[2].lastActivityAt").value(nullValue()));
    }

    @Test
    void ownerTeacherCanReadAttemptEvents() throws Exception {
        String token = token("teacher-a@example.com", "teacherA123");
        mockMvc.perform(get("/api/proctor/attempts/attempt-2/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].type").value("TAB_SWITCH"))
                .andExpect(jsonPath("$.data[0].attemptId").value("attempt-2"))
                .andExpect(jsonPath("$.data[0].eventId").value("evt-c"));
    }

    @Test
    void wrongTeacherIsForbidden() throws Exception {
        String token = token("teacher-b@example.com", "teacherB123");
        mockMvc.perform(get("/api/proctor/exams/exam-a/monitor").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not have access to this exam's proctoring."));
        mockMvc.perform(get("/api/proctor/attempts/attempt-1/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentIsForbidden() throws Exception {
        String token = token("student-one@example.com", "student123");
        mockMvc.perform(get("/api/proctor/exams/exam-a/monitor").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("Access is denied")));
        mockMvc.perform(get("/api/proctor/attempts/attempt-1/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanMonitorAnyExam() throws Exception {
        String token = token("admin@example.com", "admin123");
        mockMvc.perform(get("/api/proctor/exams/exam-b/monitor").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.examTitle").value("Geometry"))
                .andExpect(jsonPath("$.data.attempts.length()").value(1))
                .andExpect(jsonPath("$.data.attempts[0].attemptId").value("attempt-3"))
                .andExpect(jsonPath("$.data.attempts[0].riskLevel").value("LOW"))
                .andExpect(jsonPath("$.data.attempts[0].riskScore").value(20.0))
                .andExpect(jsonPath("$.data.attempts[0].eventCount").value(1))
                .andExpect(jsonPath("$.data.attempts[0].latestProctorEvent.type").value("AUDIO_DETECTED"));
        mockMvc.perform(get("/api/proctor/attempts/attempt-3/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value("evt-g"))
                .andExpect(jsonPath("$.data[0].metadata.note").value("noise"));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/proctor/exams/exam-a/monitor"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/proctor/attempts/attempt-1/events"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownExamAndAttemptReturn404() throws Exception {
        String token = token("teacher-a@example.com", "teacherA123");
        mockMvc.perform(get("/api/proctor/exams/unknown-exam/monitor").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Exam not found."));
        mockMvc.perform(get("/api/proctor/attempts/unknown-attempt/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidLimitIsRejected() throws Exception {
        String token = token("teacher-a@example.com", "teacherA123");
        mockMvc.perform(get("/api/proctor/attempts/attempt-1/events").param("limit", "0").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/proctor/attempts/attempt-1/events").param("limit", "201").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eventMetadataIsPersisted() throws Exception {
        String token = token("teacher-a@example.com", "teacherA123");
        String body = "{\"events\":[{\"eventId\":\"meta-1\",\"attemptId\":\"attempt-1\",\"type\":\"CAMERA_OFF\",\"occurredAt\":\"2030-01-01T00:00:00Z\",\"metadata\":{\"faceCount\":2,\"windowFocusLost\":true}}]}";
        mockMvc.perform(post("/api/proctor/events/batch").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
        mockMvc.perform(get("/api/proctor/attempts/attempt-1/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attemptId").value("attempt-1"))
                .andExpect(jsonPath("$.data[0].eventId").value("meta-1"))
                .andExpect(jsonPath("$.data[0].type").value("CAMERA_OFF"))
                .andExpect(jsonPath("$.data[0].metadata.faceCount").value(2))
                .andExpect(jsonPath("$.data[0].metadata.windowFocusLost").value(true));
    }

    @Test
    void duplicateEventIdIsDeduplicated() throws Exception {
        String token = token("teacher-a@example.com", "teacherA123");
        String body = "{\"events\":[{\"eventId\":\"dup-1\",\"attemptId\":\"attempt-1\",\"type\":\"TAB_SWITCH\",\"occurredAt\":\"2030-01-01T00:00:00Z\"}]}";
        mockMvc.perform(post("/api/proctor/events/batch").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
        mockMvc.perform(post("/api/proctor/events/batch").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(0));
        mockMvc.perform(get("/api/proctor/attempts/attempt-1/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value("dup-1"));
    }

    @Test
    void batchAbove100IsRejected() throws Exception {
        String token = token("teacher-a@example.com", "teacherA123");
        StringBuilder body = new StringBuilder("{\"events\":[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) body.append(",");
            body.append("{\"eventId\":\"evt-" + i + "\",\"attemptId\":\"attempt-1\",\"type\":\"TAB_SWITCH\",\"occurredAt\":\"2030-01-01T00:00:00Z\"}");
        }
        body.append("]}");
        mockMvc.perform(post("/api/proctor/events/batch").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void riskCalculationIsDeterministic() throws Exception {
        String token = token("teacher-a@example.com", "teacherA123");
        String first = monitorJson(token, "exam-a");
        String second = monitorJson(token, "exam-a");
        assertEquals(riskScoreOf(first, 0), riskScoreOf(second, 0));
        assertEquals("HIGH", riskLevelOf(second, 0));
        assertEquals("60.0", riskScoreOf(second, 0));
    }

    private String monitorJson(String token, String examId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/proctor/exams/" + examId + "/monitor")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private String riskLevelOf(String json, int index) throws Exception {
        return objectMapper.readTree(json).at("/data/attempts/" + index + "/riskLevel").asText();
    }

    private String riskScoreOf(String json, int index) throws Exception {
        return objectMapper.readTree(json).at("/data/attempts/" + index + "/riskScore").asText();
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

    private void insertAttempt(String id, String examId, String studentId, int attemptNumber, String status,
                               String startedAt, String expiresAt, String submittedAt) {
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, submitted_at, version) values (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id, examId, studentId, attemptNumber, status,
                Timestamp.from(Instant.parse(startedAt)),
                Timestamp.from(Instant.parse(expiresAt)),
                submittedAt == null ? null : Timestamp.from(Instant.parse(submittedAt)));
    }

    private void insertEvent(String id, String attemptId, String eventId, String type, String occurredAt, String metadata) {
        jdbcTemplate.update(
                "insert into proctor_events (id, attempt_id, event_id, type, occurred_at, metadata) values (?, ?, ?, ?, ?, ?)",
                id, attemptId, eventId, type, occurredAt, metadata);
    }
}