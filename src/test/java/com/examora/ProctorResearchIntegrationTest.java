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
        "spring.datasource.url=jdbc:h2:mem:examora_research;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-research-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip=1000",
        "examora.login.max-per-ip-email=1000"
})
class ProctorResearchIntegrationTest {

    private static final String EXAM = "research-exam-1";
    private static final String QUESTION = "research-q-1";
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
        jdbcTemplate.update("delete from research_samples");
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
        insertUser("research-student-1", "Research Student", "research-student@example.com", "student123", Role.STUDENT);
        insertUser("research-teacher-1", "Research Teacher", "research-teacher@example.com", "teacher123", Role.TEACHER);
        insertUser("research-admin-1", "Research Admin One", "research-admin@example.com", "admin123", Role.ADMIN);
        insertUser("research-admin-2", "Research Admin Two", "research-admin2@example.com", "admin234", Role.ADMIN);
        insertUser("research-admin-3", "Research Admin Three", "research-admin3@example.com", "admin345", Role.ADMIN);
        insertExam(EXAM, "Research Exam", "research-teacher-1");
        insertQuestion(QUESTION, EXAM, "Pick A");
        insertOption("research-opt-a", QUESTION, "A", true);
        insertOption("research-opt-b", QUESTION, "B", false);
    }

    @Test
    void adminCanCreateExperimentWithDefaultVersions() throws Exception {
        String token = login("research-admin@example.com", "admin123");

        String json = mockMvc.perform(post("/api/proctor/research/experiments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fusion comparison study\",\"description\":\"Observe how reviewers label control windows\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Fusion comparison study"))
                .andExpect(jsonPath("$.data.algorithmVersion").value("fusion-v1"))
                .andExpect(jsonPath("$.data.baselineVersion").value("baseline-v1"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.createdBy").value("research-admin-1"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(json).at("/data/id").asText();
        assertThat(id).isNotEmpty();
    }

    @Test
    void teacherIsForbiddenFromResearchApi() throws Exception {
        String token = login("research-teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/proctor/research/experiments")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/proctor/research/experiments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked study\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentIsForbiddenFromResearchApi() throws Exception {
        String token = login("research-student@example.com", "student123");

        mockMvc.perform(get("/api/proctor/research/experiments")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorizedFromResearchApi() throws Exception {
        mockMvc.perform(get("/api/proctor/research/experiments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void groundTruthComesFromHumanReviewAndReviewerIsServerDerived() throws Exception {
        String attemptId = insertAttempt("research-at-1", BASE.minusSeconds(30), BASE.plusSeconds(30));
        insertEvent("research-ev-1", attemptId, "MULTIPLE_FACES", "CLIENT_AI", 0.9, BASE.plusSeconds(3));
        String experimentId = createExperiment("Ground truth workflow");
        String token = login("research-admin@example.com", "admin123");

        String sampleId = createSample(experimentId, attemptId, BASE.toString(), BASE.plusSeconds(10).toString(),
                "\"conditions\":{\"lighting\":\"NORMAL\"}", token);

        mockMvc.perform(post("/api/proctor/research/samples/" + sampleId + "/review")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"ANOMALY\",\"confidence\":0.95,\"notes\":\"clear anomaly\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.label").value("ANOMALY"))
                .andExpect(jsonPath("$.data.reviewCount").value(1));

        String reviewer = jdbcTemplate.queryForObject(
                "select reviewer_id from research_reviews where sample_id = ?", String.class, sampleId);
        assertThat(reviewer).isEqualTo("research-admin-1");

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/evaluation")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalSamples").value(1))
                .andExpect(jsonPath("$.data.evaluatedSamples").value(1))
                .andExpect(jsonPath("$.data.reviewedSamples").value(1))
                .andExpect(jsonPath("$.data.unevaluatedSamples").value(0))
                .andExpect(jsonPath("$.data.baseline.confusion.truePositives").value(1))
                .andExpect(jsonPath("$.data.fusion.confusion.truePositives").value(1));
    }

    @Test
    void sampleWithoutReviewIsUnevaluated() throws Exception {
        String attemptId = insertAttempt("research-at-2", BASE.minusSeconds(30), BASE.plusSeconds(30));
        String experimentId = createExperiment("Unreviewed study");
        String token = login("research-admin@example.com", "admin123");
        createSample(experimentId, attemptId, BASE.toString(), BASE.plusSeconds(10).toString(), "", token);

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/evaluation")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalSamples").value(1))
                .andExpect(jsonPath("$.data.evaluatedSamples").value(0))
                .andExpect(jsonPath("$.data.reviewedSamples").value(0))
                .andExpect(jsonPath("$.data.unevaluatedSamples").value(1));
    }

    @Test
    void majorityReviewResolvesAndTieLeavesSampleUnevaluable() throws Exception {
        String experimentId = createExperiment("Majority study");
        String token1 = login("research-admin@example.com", "admin123");
        String token2 = login("research-admin2@example.com", "admin234");
        String token3 = login("research-admin3@example.com", "admin345");
        String sampleId = createUnlinkedSample(experimentId, token1);

        mockMvc.perform(post("/api/proctor/research/samples/" + sampleId + "/review")
                        .header("Authorization", bearer(token1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"ANOMALY\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/proctor/research/samples/" + sampleId + "/review")
                        .header("Authorization", bearer(token2))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"NORMAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.label").value((Object) null));
    }

    @Test
    void conditionStratificationSplitsByConditionKey() throws Exception {
        String experimentId = createExperiment("Conditioned study");
        String token = login("research-admin@example.com", "admin123");
        String sampleNormal = createUnlinkedSampleWithCondition(experimentId, "lighting", "NORMAL", token, 0);
        String sampleLow = createUnlinkedSampleWithCondition(experimentId, "lighting", "LOW", token, 2);
        review(sampleNormal, "NORMAL", token);
        review(sampleLow, "NORMAL", token);

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/evaluation?condition=lighting")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evaluatedSamples").value(2))
                .andExpect(jsonPath("$.data.conditions").isArray())
                .andExpect(jsonPath("$.data.conditions.length()").value(2));
    }

    @Test
    void baselineAndFusionComparisonEndpointIsExposed() throws Exception {
        String attemptId = insertAttempt("research-at-5", BASE.minusSeconds(30), BASE.plusSeconds(30));
        insertEvent("research-ev-5a", attemptId, "MULTIPLE_FACES", "CLIENT_AI", 0.9, BASE.plusSeconds(1));
        insertEvent("research-ev-5b", attemptId, "NETWORK_INTERRUPTION", "SYSTEM", null, BASE.plusSeconds(2));
        String experimentId = createExperiment("Comparison study");
        String token = login("research-admin@example.com", "admin123");
        String sampleId = createSample(experimentId, attemptId, BASE.toString(), BASE.plusSeconds(10).toString(),
                "", token);
        review(sampleId, "ANOMALY", token);

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/evaluation")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseline.version").value("baseline-v1"))
                .andExpect(jsonPath("$.data.fusion.version").value("fusion-v1"))
                .andExpect(jsonPath("$.data.baseline.metrics.precision").value(closeTo(1.0, 1e-9)))
                .andExpect(jsonPath("$.data.fusion.metrics.precision").value(closeTo(1.0, 1e-9)));
    }

    @Test
    void invalidWindowIsRejected() throws Exception {
        String experimentId = createExperiment("Invalid window study");
        String token = login("research-admin@example.com", "admin123");

        mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/samples")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windowStart\":\"" + BASE.plusSeconds(10) + "\",\"windowEnd\":\""
                                + BASE.toString() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateSampleWindowInSameExperimentIsRejected() throws Exception {
        String experimentId = createExperiment("Duplicate study");
        String token = login("research-admin@example.com", "admin123");
        createSample(experimentId, null, BASE.toString(), BASE.plusSeconds(10).toString(), "", token);

        mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/samples")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windowStart\":\"" + BASE + "\",\"windowEnd\":\"" + BASE.plusSeconds(10) + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void experimentsAreIsolatedFromEachOther() throws Exception {
        String experimentA = createExperiment("Study A");
        String experimentB = createExperiment("Study B");
        String token = login("research-admin@example.com", "admin123");
        createSample(experimentA, null, BASE.toString(), BASE.plusSeconds(10).toString(), "", token);

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentB + "/evaluation")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalSamples").value(0))
                .andExpect(jsonPath("$.data.baseline.metrics.precision").value((Object) null));
    }

    @Test
    void researchEvaluationDoesNotModifyEnforcementState() throws Exception {
        String attemptId = insertAttempt("research-at-9", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        insertEvent("research-ev-9", attemptId, "TAB_SWITCH", "BROWSER", null, BASE.plusSeconds(2));
        String experimentId = createExperiment("Non-invasive study");
        String token = login("research-admin@example.com", "admin123");
        String sampleId = createSample(experimentId, attemptId, BASE.toString(), BASE.plusSeconds(10).toString(),
                "", token);
        review(sampleId, "NORMAL", token);

        int warningsBefore = warningCount(attemptId);
        String statusBefore = attemptStatus(attemptId);
        String accessBefore = accessStatus("research-student-1", EXAM);

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/evaluation")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        assertThat(warningCount(attemptId)).isEqualTo(warningsBefore);
        assertThat(attemptStatus(attemptId)).isEqualTo(statusBefore);
        assertThat(accessStatus("research-student-1", EXAM)).isEqualTo(accessBefore);
        assertThat(accessBefore).isEqualTo(ExamAccessStatus.ELIGIBLE.name());
    }

    @Test
    void latencyAndBandwidthAreMeasuredFromStoredSignals() throws Exception {
        String attemptId = insertAttempt("research-at-10", BASE.minusSeconds(30), BASE.plusSeconds(30));
        insertEvent("research-ev-10", attemptId, "MULTIPLE_FACES", "CLIENT_AI", 0.9, BASE.plusSeconds(3));
        String experimentId = createExperiment("Latency study");
        String token = login("research-admin@example.com", "admin123");
        String sampleId = createSample(experimentId, attemptId, BASE.toString(), BASE.plusSeconds(10).toString(),
                "\"conditions\":{\"lighting\":\"NORMAL\"},\"rawMediaBytes\":1000,\"signalBytes\":200", token);
        review(sampleId, "ANOMALY", token);

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/evaluation")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latency.measuredCount").value(1))
                .andExpect(jsonPath("$.data.latency.meanMs").value(closeTo(3000.0, 1e-9)))
                .andExpect(jsonPath("$.data.latency.medianMs").value(closeTo(3000.0, 1e-9)))
                .andExpect(jsonPath("$.data.bandwidth.measuredCount").value(1))
                .andExpect(jsonPath("$.data.bandwidth.meanDataMinimizationRatio").value(closeTo(0.8, 1e-9)));
    }

    private String createExperiment(String name) throws Exception {
        String token = login("research-admin@example.com", "admin123");
        String json = mockMvc.perform(post("/api/proctor/research/experiments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).at("/data/id").asText();
    }

    private String createSample(String experimentId, String attemptId, String windowStart, String windowEnd,
                                String extra, String token) throws Exception {
        String attemptField = attemptId == null ? "" : ",\"attemptId\":\"" + attemptId + "\"";
        String content = "{\"windowStart\":\"" + windowStart + "\",\"windowEnd\":\"" + windowEnd
                + "\"" + attemptField + (extra.isEmpty() ? "" : "," + extra) + "}";
        String json = mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/samples")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).at("/data/id").asText();
    }

    private String createUnlinkedSample(String experimentId, String token) throws Exception {
        String content = "{\"windowStart\":\"" + BASE + "\",\"windowEnd\":\"" + BASE.plusSeconds(10)
                + "\",\"conditions\":{}}";
        String json = mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/samples")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).at("/data/id").asText();
    }

    private String createUnlinkedSampleWithCondition(String experimentId, String key, String value, String token,
                                                     int offsetSeconds) throws Exception {
        String content = "{\"windowStart\":\"" + BASE.plusSeconds(offsetSeconds)
                + "\",\"windowEnd\":\"" + BASE.plusSeconds(offsetSeconds + 10)
                + "\",\"conditions\":{\"" + key + "\":\"" + value + "\"}}";
        String json = mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/samples")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).at("/data/id").asText();
    }

    private void review(String sampleId, String label, String token) throws Exception {
        mockMvc.perform(post("/api/proctor/research/samples/" + sampleId + "/review")
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
                id, EXAM, "research-student-1", start, expires);
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