package com.examora;

import com.examora.model.ExamAccessStatus;
import com.examora.model.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for the first controlled experiment run evaluation. Real experiments, runs,
 * attempts, signals, captures, and reviews only — the endpoint reports real persisted counts,
 * never fabricated research data. Covers authorization, target progress, scenario progress,
 * condition distribution, review agreement (AGREED/TIED/UNREVIEWED), baseline-v1 vs fusion-v1
 * confusion + FDR, a genuine baseline/fusion disagreement, latency/bandwidth, completion
 * summary, snapshot reproducibility (only evaluatedAt varies), enforcement isolation, and the
 * absence of raw media, demographic fields, and student identifiers in the payload.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_research_run_eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-research-run-evaluation-tests",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip=1000",
        "examora.login.max-per-ip-email=1000"
})
class ResearchRunEvaluationIntegrationTest {

    private static final String EXAM = "rre-exam-1";
    private static final Instant BASE = Instant.now().minusSeconds(120);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ObjectMapper objectMapper;

    private int attemptSequence;

    @BeforeEach
    void setUp() {
        attemptSequence = 0;
        jdbcTemplate.update("delete from research_run_samples");
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
        insertUser("rre-admin", "Admin", "rre-admin@example.com", "admin123", Role.ADMIN);
        insertUser("rre-admin2", "Admin2", "rre-admin2@example.com", "admin234", Role.ADMIN);
        insertUser("rre-student", "Student", "rre-student@example.com", "student123", Role.STUDENT);
        insertUser("rre-teacher", "Teacher", "rre-teacher@example.com", "teacher123", Role.TEACHER);
        insertExam(EXAM, "RRE Exam", "rre-admin");
    }

    @Test
    void evaluationEndpointRequiresAdmin() throws Exception {
        String experimentId = createExperiment("Auth study", EXAM);
        String admin = login("rre-admin@example.com", "admin123");
        String runId = createRun(experimentId, "auth-run", admin);

        mockMvc.perform(get("/api/proctor/research/runs/" + runId + "/evaluation")
                        .header("Authorization", bearer(login("rre-student@example.com", "student123"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/proctor/research/runs/" + runId + "/evaluation")
                        .header("Authorization", bearer(login("rre-teacher@example.com", "teacher123"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/proctor/research/runs/" + runId + "/evaluation"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void evaluationForUnknownRunIsNotFound() throws Exception {
        String admin = login("rre-admin@example.com", "admin123");
        mockMvc.perform(get("/api/proctor/research/runs/no-such-run/evaluation")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void runEvaluationReportsRealProgressMetricsAndConfusion() throws Exception {
        String experimentId = createExperiment("Pilot study", EXAM);
        String admin = login("rre-admin@example.com", "admin123");
        String runId = createRun(experimentId, "pilot-run", admin);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        String tabAttempt = insertAttempt("rre-tab-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String normalAttempt = insertAttempt("rre-normal-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String tiedAttempt = insertAttempt("rre-tied-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String unreviewedAttempt = insertAttempt("rre-un-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));

        String tabSample = createPlannedSample(runId, tabAttempt, "TAB_SWITCH",
                Map.of("lighting", "GOOD", "network", "STABLE"), admin);
        observe(runId, tabSample, admin);
        insertEvent("rre-tab-ev", tabAttempt, "TAB_SWITCH", "BROWSER", null, Instant.now());
        capture(runId, tabSample, admin, "\"measuredLatencyMs\":1500,\"rawMediaBytes\":2000,\"signalBytes\":400");
        reviewRunSample(tabSample, "TAB_SWITCH", admin);

        String normalSample = createPlannedSample(runId, normalAttempt, "NORMAL",
                Map.of("lighting", "GOOD", "network", "STABLE"), admin);
        observe(runId, normalSample, admin);
        capture(runId, normalSample, admin, "\"measuredLatencyMs\":900,\"rawMediaBytes\":1500,\"signalBytes\":200");
        reviewRunSample(normalSample, "NORMAL", admin);

        String tiedSample = createPlannedSample(runId, tiedAttempt, "NORMAL",
                Map.of("lighting", "GOOD"), admin);
        observe(runId, tiedSample, admin);
        capture(runId, tiedSample, admin, "");
        review(linkedResearchSampleId(tiedSample), "NORMAL", admin);
        review(linkedResearchSampleId(tiedSample), "ANOMALY", login("rre-admin2@example.com", "admin234"));

        String unreviewedSample = createPlannedSample(runId, unreviewedAttempt, "NORMAL",
                Map.of("lighting", "GOOD"), admin);
        observe(runId, unreviewedSample, admin);
        capture(runId, unreviewedSample, admin, "");

        String result = mockMvc.perform(get("/api/proctor/research/runs/" + runId + "/evaluation")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress.target").value(80))
                .andExpect(jsonPath("$.data.progress.planned").value(0))
                .andExpect(jsonPath("$.data.progress.captured").value(4))
                .andExpect(jsonPath("$.data.progress.reviewed").value(3))
                .andExpect(jsonPath("$.data.progress.evaluable").value(2))
                .andExpect(jsonPath("$.data.dataQuality.tied").value(1))
                .andExpect(jsonPath("$.data.dataQuality.unreviewed").value(1))
                .andExpect(jsonPath("$.data.completion.targetObservations").value(80))
                .andExpect(jsonPath("$.data.completion.actualCaptured").value(4))
                .andExpect(jsonPath("$.data.completion.evaluable").value(2))
                .andExpect(jsonPath("$.data.completion.readyForEvaluation").value(true))
                .andExpect(jsonPath("$.data.completion.scenarioCoverage").value(2))
                .andExpect(jsonPath("$.data.completion.scenarioCells").value(8))
                .andExpect(jsonPath("$.data.completion.conditionCoverage").value(2))
                .andExpect(jsonPath("$.data.completion.conditionCells").value(8))
                .andExpect(jsonPath("$.data.baseline.confusion.truePositives").value(1))
                .andExpect(jsonPath("$.data.baseline.confusion.trueNegatives").value(1))
                .andExpect(jsonPath("$.data.baseline.confusion.falsePositives").value(0))
                .andExpect(jsonPath("$.data.baseline.confusion.falseNegatives").value(0))
                .andExpect(jsonPath("$.data.baseline.metrics.fdr").value(0.0))
                .andExpect(jsonPath("$.data.baseline.metrics.precision").value(1.0))
                .andExpect(jsonPath("$.data.fusion.confusion.truePositives").value(0))
                .andExpect(jsonPath("$.data.fusion.confusion.trueNegatives").value(1))
                .andExpect(jsonPath("$.data.fusion.confusion.falsePositives").value(0))
                .andExpect(jsonPath("$.data.fusion.confusion.falseNegatives").value(1))
                .andExpect(jsonPath("$.data.fusion.metrics.fdr", nullValue()))
                .andExpect(jsonPath("$.data.fusion.metrics.precision", nullValue()))
                .andExpect(jsonPath("$.data.fusion.metrics.recall").value(0.0))
                .andExpect(jsonPath("$.data.fusion.metrics.falseNegativeRate").value(1.0))
                .andReturn().getResponse().getContentAsString();

        JsonNode evaluation = objectMapper.readTree(result).at("/data");

        assertThat(evaluation.at("/scenarioProgress")).hasSize(8);
        assertThat(evaluation.at("/scenarioProgress/0/scenario").asText()).isEqualTo("NORMAL");
        assertThat(evaluation.at("/scenarioProgress/0/target").asInt()).isEqualTo(10);
        assertThat(evaluation.at("/scenarioProgress/1/scenario").asText()).isEqualTo("WINDOW_BLUR");
        assertThat(evaluation.at("/scenarioProgress/1/captured").asInt()).isZero();
        assertThat(evaluation.at("/scenarioProgress/2/captured").asInt()).isEqualTo(1);

        assertThat(evaluation.at("/conditionDistribution")).hasSize(4);
        assertThat(evaluation.at("/conditionDistribution/0/key").asText()).isEqualTo("lighting");
        assertThat(evaluation.at("/conditionDistribution/0/values").get(0).get("value").asText()).isEqualTo("GOOD");
        assertThat(evaluation.at("/conditionDistribution/0/values").get(0).get("count").asInt()).isEqualTo(4);
        assertThat(evaluation.at("/conditionDistribution/1/key").asText()).isEqualTo("cameraQuality");
        assertThat(evaluation.at("/conditionDistribution/1/values")).isEmpty();
        assertThat(evaluation.at("/conditionDistribution/2/key").asText()).isEqualTo("network");
        assertThat(evaluation.at("/conditionDistribution/2/values").get(0).get("value").asText()).isEqualTo("STABLE");
        assertThat(evaluation.at("/conditionDistribution/2/values").get(0).get("count").asInt()).isEqualTo(2);

        assertThat(evaluation.at("/reviewAgreement")).hasSize(4);
        reviewAgreementStates(evaluation);

        assertThat(evaluation.at("/disagreements")).hasSize(1);
        JsonNode disagreement = evaluation.at("/disagreements/0");
        assertThat(disagreement.get("scenario").asText()).isEqualTo("TAB_SWITCH");
        assertThat(disagreement.get("baselinePositive").asBoolean()).isTrue();
        assertThat(disagreement.get("fusionPositive").asBoolean()).isFalse();
        assertThat(disagreement.get("groundTruthLabel").asText()).isEqualTo("TAB_SWITCH");
        assertThat(disagreement.get("conditionsKey").asText())
                .isEqualTo("lighting=GOOD;network=STABLE");

        assertThat(evaluation.at("/conditions").size()).isGreaterThan(0);
        boolean lightingGroupPresent = false;
        for (JsonNode group : evaluation.at("/conditions")) {
            if ("lighting".equals(group.get("key").asText())
                    && "GOOD".equals(group.get("conditionValue").asText())) {
                lightingGroupPresent = true;
                assertThat(group.get("sampleCount").asInt()).isEqualTo(2);
                assertThat(group.at("/baseline/metrics/fdr").isNull()
                        || group.at("/baseline/metrics/fdr").asDouble() >= 0.0).isTrue();
            }
        }
        assertThat(lightingGroupPresent).isTrue();

        assertThat(evaluation.at("/latency/measuredCount").asInt()).isEqualTo(1);
        assertThat(evaluation.at("/latency/meanMs").asDouble()).isGreaterThanOrEqualTo(0.0);
        assertThat(evaluation.at("/bandwidth/measuredCount").asInt()).isEqualTo(2);

        assertThat(evaluation.at("/snapshot/datasetVersion").asText()).isEqualTo("dataset-v1");
        assertThat(evaluation.at("/snapshot/baselineVersion").asText()).isEqualTo("baseline-v1");
        assertThat(evaluation.at("/snapshot/fusionVersion").asText()).isEqualTo("fusion-v1");
        assertThat(evaluation.at("/snapshot/evaluableSampleCount").asInt()).isEqualTo(2);
        assertThat(evaluation.at("/snapshot/evaluatedAt").asText()).isNotBlank();

        assertNoNanNorInfinity(evaluation);
        assertPayloadContainsNoStudentIdentifiersOrDemographics(result);
    }

    @Test
    void runEvaluationIsReproducibleExceptEvaluatedAt() throws Exception {
        String experimentId = createExperiment("Repro study", EXAM);
        String admin = login("rre-admin@example.com", "admin123");
        String runId = createRun(experimentId, "repro-run", admin);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        String attemptId = insertAttempt("rre-repro-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        insertEvent("rre-repro-ev", attemptId, "NORMAL", "BROWSER", null, BASE.plusSeconds(2));

        MvcResult first = getEvaluation(runId, admin);
        Thread.sleep(10);
        MvcResult second = getEvaluation(runId, admin);

        JsonNode firstNode = objectMapper.readTree(first.getResponse().getContentAsString()).at("/data");
        JsonNode secondNode = objectMapper.readTree(second.getResponse().getContentAsString()).at("/data");
        String firstStamp = firstNode.at("/snapshot/evaluatedAt").asText();
        String secondStamp = secondNode.at("/snapshot/evaluatedAt").asText();

        ((com.fasterxml.jackson.databind.node.ObjectNode) firstNode.at("/snapshot")).remove("evaluatedAt");
        ((com.fasterxml.jackson.databind.node.ObjectNode) secondNode.at("/snapshot")).remove("evaluatedAt");

        assertThat(firstStamp).isNotBlank();
        assertThat(secondStamp).isNotBlank();
        assertThat(secondStamp).isNotEqualTo(firstStamp);
        assertThat(firstNode).isEqualTo(secondNode);
    }

    @Test
    void runEvaluationDoesNotTouchEnforcementState() throws Exception {
        String experimentId = createExperiment("Isolation eval study", EXAM);
        String admin = login("rre-admin@example.com", "admin123");
        String runId = createRun(experimentId, "iso-eval-run", admin);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        String attemptId = insertAttempt("rre-isol-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));

        Integer warningsBefore = warningCount(attemptId);
        String statusBefore = attemptStatus(attemptId);
        String accessBefore = accessStatus("rre-student", EXAM);
        Instant terminatedBefore = terminatedAt(attemptId);
        String terminatedReasonBefore = terminatedReason(attemptId);

        mockMvc.perform(get("/api/proctor/research/runs/" + runId + "/evaluation")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        assertThat(warningCount(attemptId)).isEqualTo(warningsBefore);
        assertThat(attemptStatus(attemptId)).isEqualTo(statusBefore);
        assertThat(accessStatus("rre-student", EXAM)).isEqualTo(accessBefore);
        assertThat(terminatedAt(attemptId)).isEqualTo(terminatedBefore);
        assertThat(terminatedReason(attemptId)).isEqualTo(terminatedReasonBefore);
        assertThat(accessBefore).isEqualTo(ExamAccessStatus.ELIGIBLE.name());
    }

    @Test
    void researchTablesPersistNoRawMediaNoBiometricsNoDemographics() throws Exception {
        Integer mediaColumns = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_name in ('research_runs', 'research_run_samples', 'research_samples') "
                        + "and lower(column_name) in ('video_blob', 'audio_blob', 'raw_video', 'raw_audio')",
                Integer.class);
        assertThat(mediaColumns).isZero();
        Integer sensitiveColumns = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_name in ('research_runs', 'research_run_samples', 'research_samples', 'research_reviews') "
                        + "and lower(column_name) in ('gender', 'ethnicity', 'age', 'face_embedding', 'biometric_signature')",
                Integer.class);
        assertThat(sensitiveColumns).isZero();
    }

    private void reviewAgreementStates(JsonNode evaluation) {
        int agreed = 0;
        int tied = 0;
        int unreviewed = 0;
        for (JsonNode state : evaluation.at("/reviewAgreement")) {
            switch (state.get("agreementState").asText()) {
                case "AGREED" -> agreed++;
                case "TIED" -> tied++;
                case "UNREVIEWED" -> unreviewed++;
                default -> throw new AssertionError("unexpected agreement state " + state);
            }
        }
        assertThat(agreed).isEqualTo(2);
        assertThat(tied).isEqualTo(1);
        assertThat(unreviewed).isEqualTo(1);
    }

    private void assertNoNanNorInfinity(JsonNode node) {
        if (node.isFloatingPointNumber()) {
            String text = node.asText();
            assertThat(text).doesNotContain("NaN").doesNotContain("Infinity");
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                assertNoNanNorInfinity(child);
            }
        }
    }

    private void assertPayloadContainsNoStudentIdentifiersOrDemographics(String payload) {
        assertThat(payload.toLowerCase())
                .doesNotContain("\"studentid\"")
                .doesNotContain("\"student_id\"")
                .doesNotContain("\"email\"")
                .doesNotContain("rre-student")
                .doesNotContain("gender")
                .doesNotContain("ethnicity");
    }

    private MvcResult getEvaluation(String runId, String token) throws Exception {
        return mockMvc.perform(get("/api/proctor/research/runs/" + runId + "/evaluation")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String createExperiment(String name, String examId) throws Exception {
        String token = login("rre-admin@example.com", "admin123");
        String json = mockMvc.perform(post("/api/proctor/research/experiments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"examId\":\"" + examId + "\"}"))
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
        MvcResult result = mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples/"
                        + sampleId + "/observe")
                        .header("Authorization", bearer(token)))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("observe -> " + result.getResponse().getStatus()
                    + " body=" + result.getResponse().getContentAsString());
        }
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

    private void review(String sampleId, String label, String token) throws Exception {
        mockMvc.perform(post("/api/proctor/research/samples/" + sampleId + "/review")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"" + label + "\"}"))
                .andExpect(status().isOk());
    }

    private void reviewRunSample(String runSampleId, String label, String token) throws Exception {
        review(linkedResearchSampleId(runSampleId), label, token);
    }

    private String linkedResearchSampleId(String runSampleId) {
        String researchSampleId = jdbcTemplate.queryForObject(
                "select research_sample_id from research_run_samples where id = ?", String.class, runSampleId);
        assertThat(researchSampleId).isNotNull();
        return researchSampleId;
    }

    private String insertAttempt(String id, Instant start, Instant expires) {
        attemptSequence++;
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version, warning_count) "
                        + "values (?, ?, ?, ?, 'STARTED', ?, ?, 0, 0)",
                id, EXAM, "rre-student", attemptSequence, start, expires);
        return id;
    }

    private void insertEvent(String eventId, String attemptId, String type, String source,
                             Double confidence, Instant occurredAt) {
        jdbcTemplate.update(
                "insert into proctor_events (id, attempt_id, event_id, type, occurred_at, metadata, source, confidence, duration_ms) "
                        + "values (?, ?, ?, ?, ?, null, ?, ?, null)",
                UUID.randomUUID().toString(), attemptId, eventId, type, occurredAt.toString(), source, confidence);
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
        var statuses = jdbcTemplate.query(
                "select status from exam_access_state where student_id = ? and exam_id = ?",
                (rs, i) -> rs.getString("status"), studentId, examId);
        return statuses.isEmpty() ? ExamAccessStatus.ELIGIBLE.name() : statuses.getFirst();
    }

    private Instant terminatedAt(String attemptId) {
        java.sql.Timestamp value = jdbcTemplate.queryForObject(
                "select terminated_at from exam_attempts where id = ?", java.sql.Timestamp.class, attemptId);
        return value == null ? null : value.toInstant();
    }

    private String terminatedReason(String attemptId) {
        return jdbcTemplate.queryForObject(
                "select terminated_reason from exam_attempts where id = ?", String.class, attemptId);
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

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}