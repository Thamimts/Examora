package com.examora;

import com.examora.model.ExamAccessStatus;
import com.examora.model.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_fusion;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-fusion-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip=1000",
        "examora.login.max-per-ip-email=1000"
})
class ProctorFusionIntegrationTest {

    private static final String EXAM = "fusion-exam-1";
    private static final String EXAM_2 = "fusion-exam-2";
    private static final String QUESTION = "fusion-q-1";

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
        jdbcTemplate.update("delete from exam_access_state");
        jdbcTemplate.update("delete from activity_events");
        jdbcTemplate.update("delete from proctor_fusion_results");
        jdbcTemplate.update("delete from proctor_events");
        jdbcTemplate.update("delete from exam_room_members");
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from retest_requests");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from exam_rooms");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("fusion-student-1", "Fusion Student One", "fusion-student@example.com", "student123", Role.STUDENT);
        insertUser("fusion-teacher-1", "Fusion Teacher One", "fusion-teacher@example.com", "teacher123", Role.TEACHER);
        insertUser("fusion-teacher-2", "Fusion Teacher Two", "fusion-teacher2@example.com", "teacher234", Role.TEACHER);
        insertUser("fusion-admin-1", "Fusion Admin One", "fusion-admin@example.com", "admin123", Role.ADMIN);

        insertExam(EXAM, "Fusion Exam", "fusion-teacher-1");
        insertExam(EXAM_2, "Fusion Exam Two", "fusion-teacher-2");
        insertQuestion(QUESTION, EXAM, "Pick A");
        insertOption("fusion-opt-a", QUESTION, "A", true);
        insertOption("fusion-opt-b", QUESTION, "B", false);
    }

    @Test
    void authorizedTeacherCanReadFusionResult() throws Exception {
        String attemptId = insertAttempt("fusion-at-1", EXAM, "fusion-student-1", "STARTED", 0);
        insertEvent("fusion-ev-1", attemptId, "TAB_SWITCH", "BROWSER", null);
        String token = login("fusion-teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/fusion")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptId").value(attemptId))
                .andExpect(jsonPath("$.data.algorithmVersion").value("fusion-v1"))
                .andExpect(jsonPath("$.data.evidenceCount").value(1))
                .andExpect(jsonPath("$.data.baselineScore").value(closeTo(1.0 / 3, 1e-9)))
                .andExpect(jsonPath("$.data.fusedScore").value(closeTo(0.432, 1e-9)))
                .andExpect(jsonPath("$.data.windowEnd").isNotEmpty())
                .andExpect(jsonPath("$.data.contributingSignals[0].type").value("TAB_SWITCH"))
                .andExpect(jsonPath("$.data.contributingSignals[0].contribution").value(closeTo(0.432, 1e-9)));
    }

    @Test
    void adminCanReadFusionResult() throws Exception {
        String attemptId = insertAttempt("fusion-at-1", EXAM, "fusion-student-1", "STARTED", 0);
        insertEvent("fusion-ev-1", attemptId, "FACE_COUNT_ANOMALY", "CLIENT_AI", 0.92);
        String token = login("fusion-admin@example.com", "admin123");

        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/fusion")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidenceCount").value(1))
                .andExpect(jsonPath("$.data.fusedScore").value(closeTo(0.7 * 1.0 * 0.92, 1e-9)));
    }

    @Test
    void studentIsForbiddenFromFusionResult() throws Exception {
        String attemptId = insertAttempt("fusion-at-1", EXAM, "fusion-student-1", "STARTED", 0);
        String token = login("fusion-student@example.com", "student123");

        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/fusion")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorizedForFusionResult() throws Exception {
        String attemptId = insertAttempt("fusion-at-1", EXAM, "fusion-student-1", "STARTED", 0);

        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/fusion"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void teacherCannotReadFusionForAnotherTeachersExam() throws Exception {
        String attemptId = insertAttempt("fusion-at-2", EXAM_2, "fusion-student-1", "STARTED", 0);
        String token = login("fusion-teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/fusion")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void fusionDoesNotModifyWarningCountOrAccessOrTermination() throws Exception {
        String attemptId = insertAttempt("fusion-at-1", EXAM, "fusion-student-1", "STARTED", 0);
        insertEvent("fusion-ev-1", attemptId, "TAB_SWITCH", "BROWSER", null);
        String token = login("fusion-teacher@example.com", "teacher123");

        int warningsBefore = warningCount(attemptId);
        String statusBefore = attemptStatus(attemptId);
        String accessBefore = accessStatus("fusion-student-1", EXAM);

        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/fusion")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        assertThat(warningCount(attemptId)).isEqualTo(warningsBefore);
        assertThat(attemptStatus(attemptId)).isEqualTo(statusBefore);
        assertThat(accessStatus("fusion-student-1", EXAM)).isEqualTo(accessBefore);
        assertThat(warningsBefore).isZero();
        assertThat(statusBefore).isEqualTo("STARTED");
        assertThat(accessBefore).isEqualTo(ExamAccessStatus.ELIGIBLE.name());
    }

    @Test
    void aiEvidenceNeverAdvancesWarningsOrTerminates() throws Exception {
        String attemptId = insertAttempt("fusion-at-1", EXAM, "fusion-student-1", "STARTED", 0);
        for (int i = 0; i < 10; i++) {
            insertEvent("fusion-ai-" + i, attemptId, "FACE_COUNT_ANOMALY", "CLIENT_AI", 0.99);
        }
        String token = login("fusion-teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/fusion")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidenceCount").value(10))
                .andExpect(jsonPath("$.data.fusedScore").value(1.0));

        assertThat(warningCount(attemptId)).isZero();
        assertThat(attemptStatus(attemptId)).isEqualTo("STARTED");
        assertThat(accessStatus("fusion-student-1", EXAM)).isEqualTo(ExamAccessStatus.ELIGIBLE.name());
    }

    @Test
    void fusionResultIsPersistedForResearch() throws Exception {
        String attemptId = insertAttempt("fusion-at-1", EXAM, "fusion-student-1", "STARTED", 0);
        insertEvent("fusion-ev-1", attemptId, "TAB_SWITCH", "BROWSER", null);
        insertEvent("fusion-ev-2", attemptId, "FACE_COUNT_ANOMALY", "CLIENT_AI", 0.8);
        String token = login("fusion-teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/fusion")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        Integer persisted = jdbcTemplate.queryForObject(
                "select count(*) from proctor_fusion_results "
                        + "where attempt_id = ? and algorithm_version = 'fusion-v1'",
                Integer.class, attemptId);
        assertThat(persisted).isEqualTo(1);
        Integer evidence = jdbcTemplate.queryForObject(
                "select evidence_count from proctor_fusion_results where attempt_id = ?",
                Integer.class, attemptId);
        assertThat(evidence).isEqualTo(2);
        Double baseline = jdbcTemplate.queryForObject(
                "select baseline_score from proctor_fusion_results where attempt_id = ?",
                Double.class, attemptId);
        assertThat(baseline).isCloseTo(2.0 / 3, within(1e-9));
    }

    @Test
    void commandCenterExposesShadowFusionWithoutEnforcement() throws Exception {
        String attemptId = insertAttempt("fusion-at-1", EXAM, "fusion-student-1", "STARTED", 0);
        insertEvent("fusion-ev-1", attemptId, "FACE_COUNT_ANOMALY", "CLIENT_AI", 0.8);
        insertEvent("fusion-ev-2", attemptId, "GAZE_ANOMALY", "CLIENT_AI", 0.6);
        String token = login("fusion-teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roster[0].studentId").value("fusion-student-1"))
                .andExpect(jsonPath("$.data.roster[0].shadowFusion.algorithmVersion").value("fusion-v1"))
                .andExpect(jsonPath("$.data.roster[0].shadowFusion.evidenceCount").value(2))
                .andExpect(jsonPath("$.data.roster[0].shadowFusion.baselineScore").value(closeTo(2.0 / 3, 1e-9)))
                .andExpect(jsonPath("$.data.roster[0].shadowFusion.fusedScore").value(closeTo(0.7 * 0.8 + 0.6 * 0.6, 1e-9)))
                .andExpect(jsonPath("$.data.roster[0].riskLevel").value("LOW"))
                .andExpect(jsonPath("$.data.roster[0].riskScore").value(0.0));
    }

    private void insertEvent(String eventId, String attemptId, String type, String source, Double confidence) {
        jdbcTemplate.update(
                "insert into proctor_events (id, attempt_id, event_id, type, occurred_at, metadata, source, confidence, duration_ms) "
                        + "values (?, ?, ?, ?, ?, null, ?, ?, null)",
                UUID.randomUUID().toString(), attemptId, eventId, type, Instant.now().toString(), source, confidence);
    }

    private String insertAttempt(String id, String examId, String studentId, String status, int warningCount) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version, warning_count) "
                        + "values (?, ?, ?, 1, ?, ?, ?, 0, ?)",
                id, examId, studentId, status, now, now.plusMillis(3_600_000), warningCount);
        return id;
    }

    private int warningCount(String attemptId) {
        Integer count = jdbcTemplate.queryForObject(
                "select warning_count from exam_attempts where id = ?", Integer.class, attemptId);
        return count == null ? 0 : count;
    }

    private String attemptStatus(String attemptId) {
        return jdbcTemplate.queryForObject("select status from exam_attempts where id = ?", String.class, attemptId);
    }

    private String accessStatus(String studentId, String examId) {
        List<String> statuses = jdbcTemplate.query(
                "select status from exam_access_state where student_id = ? and exam_id = ?",
                (rs, i) -> rs.getString("status"), studentId, examId);
        return statuses.isEmpty() ? ExamAccessStatus.ELIGIBLE.name() : statuses.getFirst();
    }

    private void insertUser(String id, String name, String email, String password, Role role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role.name());
    }

    private void insertExam(String id, String title, String ownerId) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, 'Math', '2030-06-01', 60, 'PUBLISHED', 0, null, ?)",
                id, title, ownerId);
    }

    private void insertQuestion(String id, String examId, String text) {
        jdbcTemplate.update("insert into questions (id, exam_id, text, difficulty) values (?, ?, ?, 2)", id, examId, text);
    }

    private void insertOption(String id, String questionId, String text, boolean correct) {
        jdbcTemplate.update(
                "insert into question_options (id, question_id, text, display_order, correct_answer) values (?, ?, ?, 1, ?)",
                id, questionId, text, correct);
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode token = objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/token");
        return token.asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}