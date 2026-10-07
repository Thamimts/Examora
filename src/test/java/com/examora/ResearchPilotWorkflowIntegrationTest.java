package com.examora;

import com.examora.model.ExamAccessStatus;
import com.examora.model.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the admin research-pilot operator flow: ADMIN user
 * provisioning, experiment activation, run planning of the full 8-scenario
 * matrix, honest client-measured capture, distinct two-account reviewer A/B
 * review, and the run-sample review endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_pilot;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-pilot-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip=1000",
        "examora.login.max-per-ip-email=1000"
})
class ResearchPilotWorkflowIntegrationTest {

    private static final String EXAM = "pilot-exam-1";
    private static final String QUESTION = "pilot-q-1";
    private static final Instant BASE = Instant.now().minusSeconds(120);

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
        jdbcTemplate.update("delete from research_reviews");
        jdbcTemplate.update("delete from research_run_samples");
        jdbcTemplate.update("delete from research_samples");
        jdbcTemplate.update("delete from research_runs");
        jdbcTemplate.update("delete from research_experiments");
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
        insertUser("pilot-student-1", "Pilot Student", "pilot-student@example.com", "student123", Role.STUDENT);
        insertUser("pilot-teacher-1", "Pilot Teacher", "pilot-teacher@example.com", "teacher123", Role.TEACHER);
        insertUser("pilot-admin-1", "Pilot Admin One", "pilot-admin@example.com", "admin123", Role.ADMIN);
        insertUser("pilot-admin-2", "Pilot Admin Two", "pilot-admin2@example.com", "admin234", Role.ADMIN);
        insertExam(EXAM, "Pilot Exam", "pilot-teacher-1");
        insertQuestion(QUESTION, EXAM, "Pick A");
        insertOption("pilot-opt-a", QUESTION, "A", true);
        insertOption("pilot-opt-b", QUESTION, "B", false);
    }

    @Test
    void adminCanProvisionStudentTeacherAndAdminAccounts() throws Exception {
        String token = login("pilot-admin@example.com", "admin123");

        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Student\",\"email\":\"newstudent@example.com\",\"password\":\"studentpassword\",\"role\":\"STUDENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("STUDENT"));
        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Teacher\",\"email\":\"newteacher@example.com\",\"password\":\"teacherpassword\",\"role\":\"TEACHER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("TEACHER"));
        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Admin\",\"email\":\"newadmin@example.com\",\"password\":\"adminpassword123\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        login("newadmin@example.com", "adminpassword123");
    }

    @Test
    void duplicateEmailIsRejectedWithConflict() throws Exception {
        String token = login("pilot-admin@example.com", "admin123");
        String body = "{\"name\":\"Dup User\",\"email\":\"dup@example.com\",\"password\":\"password123\",\"role\":\"STUDENT\"}";
        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyAdminsMayCreateUsers() throws Exception {
        String studentToken = login("pilot-student@example.com", "student123");
        String teacherToken = login("pilot-teacher@example.com", "teacher123");
        String body = "{\"name\":\"Blocked\",\"email\":\"blocked@example.com\",\"password\":\"password123\",\"role\":\"STUDENT\"}";

        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pilotWorkflowCapturesHonestMeasurementsAndTwoAdminReviewers() throws Exception {
        String admin1 = login("pilot-admin@example.com", "admin123");
        String admin2 = login("pilot-admin2@example.com", "admin234");

        String experimentId = createExperiment("Controlled pilot", admin1);
        mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/status")
                        .header("Authorization", bearer(admin1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        String attemptId = insertAttempt("pilot-at-1", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String runId = createRun(experimentId, "pilot-a", admin1);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk());

        String sampleId = createPlannedSample(runId, attemptId, "TAB_SWITCH",
                Map.of("lighting", "GOOD", "cameraQuality", "HD", "network", "STABLE", "cameraAngle", "FRONT"), admin1);
        observe(runId, sampleId, admin1);
        capture(runId, sampleId, admin1,
                "\"measuredLatencyMs\":2400,\"rawMediaBytes\":1843200,\"signalBytes\":420");

        mockMvc.perform(get("/api/proctor/research/runs/" + runId)
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataQuality.captured").value(1))
                .andExpect(jsonPath("$.data.dataQuality.missingMeasuredLatency").value(0))
                .andExpect(jsonPath("$.data.dataQuality.missingBandwidth").value(0));

        mockMvc.perform(get("/api/proctor/research/run-samples/" + sampleId + "/detail")
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.measuredLatencyMs").value(2400))
                .andExpect(jsonPath("$.data.rawMediaBytes").value(1843200))
                .andExpect(jsonPath("$.data.signalBytes").value(420));

        String researchSampleId = linkedResearchSampleId(sampleId);
        review(researchSampleId, "TAB_SWITCH", admin1);
        review(researchSampleId, "WINDOW_BLUR", admin2);
        review(researchSampleId, "TAB_SWITCH", admin1);

        Integer distinctReviewers = jdbcTemplate.queryForObject(
                "select count(distinct reviewer_id) from research_reviews where sample_id = ?",
                Integer.class, researchSampleId);
        assertThat(distinctReviewers).isEqualTo(2);

        mockMvc.perform(get("/api/proctor/research/run-samples/" + sampleId + "/reviews")
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentUserReviewed").value(true))
                .andExpect(jsonPath("$.data.reviews.length()").value(2))
                .andExpect(jsonPath("$.data.reviews[*].reviewerEmail",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "pilot-admin2@example.com", "pilot-admin@example.com")));

        mockMvc.perform(get("/api/proctor/research/run-samples/" + sampleId + "/reviews")
                        .header("Authorization", bearer(admin2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentUserReviewed").value(true));

        mockMvc.perform(get("/api/proctor/research/run-samples/" + sampleId + "/reviews"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/proctor/research/run-samples/" + sampleId + "/reviews")
                        .header("Authorization", bearer(login("pilot-teacher@example.com", "teacher123"))))
                .andExpect(status().isForbidden());

        assertThat(warningCount(attemptId)).isZero();
        assertThat(attemptStatus(attemptId)).isEqualTo("STARTED");
        assertThat(accessStatus("pilot-student-1", EXAM)).isEqualTo(ExamAccessStatus.ELIGIBLE.name());
    }

    @Test
    void captureWithoutMeasurementsStaysTruthfullyMissing() throws Exception {
        String admin1 = login("pilot-admin@example.com", "admin123");
        String experimentId = createExperiment("Honest missing metrics", admin1);
        String attemptId = insertAttempt("pilot-at-2", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String runId = createRun(experimentId, "pilot-b", admin1);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk());

        String sampleId = createPlannedSample(runId, attemptId, "NORMAL",
                Map.of("lighting", "GOOD"), admin1);
        observe(runId, sampleId, admin1);
        capture(runId, sampleId, admin1, "");

        mockMvc.perform(get("/api/proctor/research/run-samples/" + sampleId + "/detail")
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.measuredLatencyMs", org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.rawMediaBytes", org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.signalBytes", org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(get("/api/proctor/research/runs/" + runId)
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataQuality.missingMeasuredLatency").value(1))
                .andExpect(jsonPath("$.data.dataQuality.missingBandwidth").value(1));
    }

    @Test
    void captureRequiresBandwidthPairOrNone() throws Exception {
        String admin1 = login("pilot-admin@example.com", "admin123");
        String experimentId = createExperiment("Bandwidth validation", admin1);
        String attemptId = insertAttempt("pilot-at-3", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String runId = createRun(experimentId, "pilot-c", admin1);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk());

        String sampleId = createPlannedSample(runId, attemptId, "TAB_SWITCH",
                Map.of("lighting", "GOOD"), admin1);
        observe(runId, sampleId, admin1);
        Thread.sleep(1200);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples/" + sampleId + "/capture")
                        .header("Authorization", bearer(admin1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endedAt\":\"" + Instant.now() + "\",\"rawMediaBytes\":1000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fullScenarioMatrixPlansEightDistinctSamplesAndCompletes() throws Exception {
        String admin1 = login("pilot-admin@example.com", "admin123");
        String experimentId = createExperiment("Eight scenario pilot", admin1);
        String attemptId = insertAttempt("pilot-at-4", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String runId = createRun(experimentId, "pilot-d", admin1);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk());

        String[] scenarios = {"NORMAL", "WINDOW_BLUR", "TAB_SWITCH", "CAMERA_OFF", "MULTIPLE_FACES",
                "AUDIO_DETECTED", "FULLSCREEN_EXIT", "NETWORK_INTERRUPTION"};
        Map<String, String> common = Map.of("lighting", "GOOD", "cameraQuality", "HD", "network", "STABLE", "cameraAngle", "FRONT");
        for (String scenario : scenarios) {
            createPlannedSample(runId, attemptId, scenario, common, admin1);
        }

        mockMvc.perform(get("/api/proctor/research/runs/" + runId)
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataQuality.planned").value(8))
                .andExpect(jsonPath("$.data.samples.length()").value(8))
                .andExpect(jsonPath("$.data.matrix.length()").value(8));

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/complete")
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk());
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
    }

    @Test
    void completingWhileCapturingIsRefused() throws Exception {
        String admin1 = login("pilot-admin@example.com", "admin123");
        String experimentId = createExperiment("Incomplete guard", admin1);
        String attemptId = insertAttempt("pilot-at-5", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String runId = createRun(experimentId, "pilot-e", admin1);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isOk());

        String sampleId = createPlannedSample(runId, attemptId, "NORMAL", Map.of("lighting", "GOOD"), admin1);
        observe(runId, sampleId, admin1);

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/complete")
                        .header("Authorization", bearer(admin1)))
                .andExpect(status().isConflict());
    }

    private String createExperiment(String name, String token) throws Exception {
        String json = mockMvc.perform(post("/api/proctor/research/experiments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"examId\":\"" + EXAM + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).at("/data/id").asText();
    }

    private String createRun(String experimentId, String runCode, String token) throws Exception {
        String json = mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/runs")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runCode\":\"" + runCode + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).at("/data/id").asText();
    }

    private String createPlannedSample(String runId, String attemptId, String scenario,
                                        Map<String, String> conditions, String token) throws Exception {
        String conditionsJson = objectMapper.writeValueAsString(conditions);
        MvcResult result = mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"" + attemptId + "\",\"scenario\":\"" + scenario
                                + "\",\"conditions\":" + conditionsJson + "}"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("createPlannedSample -> " + result.getResponse().getStatus()
                    + " body=" + body);
        }
        return objectMapper.readTree(body).at("/data/id").asText();
    }

    private void observe(String runId, String sampleId, String token) throws Exception {
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples/" + sampleId + "/observe")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private void capture(String runId, String sampleId, String token, String extra) throws Exception {
        Thread.sleep(1200);
        String fields = extra.isEmpty() ? "" : "," + extra;
        MvcResult result = mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples/"
                        + sampleId + "/capture")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endedAt\":\"" + Instant.now() + "\"" + fields + "}"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("capture -> " + result.getResponse().getStatus()
                    + " body=" + body);
        }
    }

    private String linkedResearchSampleId(String runSampleId) {
        String researchSampleId = jdbcTemplate.queryForObject(
                "select research_sample_id from research_run_samples where id = ?", String.class, runSampleId);
        assertThat(researchSampleId).isNotNull();
        return researchSampleId;
    }

    private void review(String researchSampleId, String label, String token) throws Exception {
        mockMvc.perform(post("/api/proctor/research/samples/" + researchSampleId + "/review")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"" + label + "\"}"))
                .andExpect(status().isOk());
    }

    private void insertEvent(String eventId, String attemptId, String type, String source,
                             Double confidence, Instant occurredAt) {
        jdbcTemplate.update(
                "insert into proctor_events (id, attempt_id, event_id, type, occurred_at, metadata, source, confidence, duration_ms) "
                        + "values (?, ?, ?, ?, ?, null, ?, ?, null)",
                UUID.randomUUID().toString(), attemptId, eventId, type, occurredAt.toString(), source, confidence);
    }

    private String insertAttempt(String id, Instant start, Instant expires) {
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version, warning_count) "
                        + "values (?, ?, ?, 1, 'STARTED', ?, ?, 0, 0)",
                id, EXAM, "pilot-student-1", start, expires);
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

    private String runStatus(String runId) {
        return jdbcTemplate.queryForObject("select status from research_runs where id = ?", String.class, runId);
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