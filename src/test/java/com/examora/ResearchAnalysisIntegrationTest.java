package com.examora;

import com.examora.model.ExamAccessStatus;
import com.examora.model.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for the read-only research statistical analysis endpoint. Real experiments,
 * runs, attempts, signals, captures and reviews only — the endpoint reports real persisted
 * counts, never fabricated data. Covers authorization, experiment-wide vs run-scoped scope,
 * eligible-run selection, empty experiments, real confusion/disagreement/coverage, review
 * agreement, reproducibility (only generatedAt varies), enforcement isolation, non-persistence,
 * and the absence of raw media, demographic fields and student identifiers in the payload.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_research_analysis;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-research-analysis-tests",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip=1000",
        "examora.login.max-per-ip-email=1000"
})
class ResearchAnalysisIntegrationTest {

    private static final String EXAM = "rra-exam-1";
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
        insertUser("rra-admin", "Admin", "rra-admin@example.com", "admin123", Role.ADMIN);
        insertUser("rra-admin2", "Admin2", "rra-admin2@example.com", "admin234", Role.ADMIN);
        insertUser("rra-student", "Student", "rra-student@example.com", "student123", Role.STUDENT);
        insertUser("rra-teacher", "Teacher", "rra-teacher@example.com", "teacher123", Role.TEACHER);
        insertExam(EXAM, "RRA Exam", "rra-admin");
    }

    @Test
    void analysisEndpointRequiresAdmin() throws Exception {
        String experimentId = createExperiment("Auth analysis study", EXAM);
        String admin = login("rra-admin@example.com", "admin123");
        String runId = createRun(experimentId, "auth-analysis-run", admin);

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis")
                        .header("Authorization", bearer(login("rra-student@example.com", "student123"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis")
                        .header("Authorization", bearer(login("rra-teacher@example.com", "teacher123"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis?runId=" + runId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analysisForUnknownExperimentIsNotFound() throws Exception {
        String admin = login("rra-admin@example.com", "admin123");
        mockMvc.perform(get("/api/proctor/research/experiments/no-such-experiment/analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void analysisRejectsRunFromAnotherExperiment() throws Exception {
        String admin = login("rra-admin@example.com", "admin123");
        String experimentA = createExperiment("Scope study A", EXAM);
        String experimentB = createExperiment("Scope study B", EXAM);
        String runA = createRun(experimentA, "scope-run-a", admin);
        createRun(experimentB, "scope-run-b", admin);

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentA + "/analysis?runId=" + runA)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentB + "/analysis?runId=" + runA)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void analysisReportsRealExperimentWideCountsAndConfusion() throws Exception {
        String experimentId = createExperiment("Pilot analysis study", EXAM);
        String admin = login("rra-admin@example.com", "admin123");
        String runId = createRun(experimentId, "pilot-analysis-run", admin);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        String tabAttempt = insertAttempt("rra-tab-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String normalAttempt = insertAttempt("rra-normal-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String unreviewedAttempt = insertAttempt("rra-un-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));

        String tabSample = createPlannedSample(runId, tabAttempt, "TAB_SWITCH",
                Map.of("lighting", "GOOD", "network", "STABLE"), admin);
        observe(runId, tabSample, admin);
        insertEvent("rra-tab-ev", tabAttempt, "TAB_SWITCH", "BROWSER", null, Instant.now());
        capture(runId, tabSample, admin, "\"measuredLatencyMs\":1500,\"rawMediaBytes\":2000,\"signalBytes\":400");
        reviewRunSample(tabSample, "TAB_SWITCH", admin);

        String normalSample = createPlannedSample(runId, normalAttempt, "NORMAL",
                Map.of("lighting", "GOOD", "network", "STABLE"), admin);
        observe(runId, normalSample, admin);
        capture(runId, normalSample, admin, "\"measuredLatencyMs\":900,\"rawMediaBytes\":1500,\"signalBytes\":200");
        reviewRunSample(normalSample, "NORMAL", admin);

        String unreviewedSample = createPlannedSample(runId, unreviewedAttempt, "NORMAL",
                Map.of("lighting", "GOOD"), admin);
        observe(runId, unreviewedSample, admin);
        capture(runId, unreviewedSample, admin, "");

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/complete")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        String result = mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.experimentId").value(experimentId))
                .andExpect(jsonPath("$.data.registeredSamples").value(3))
                .andExpect(jsonPath("$.data.capturedSamples").value(3))
                .andExpect(jsonPath("$.data.reviewedSamples").value(2))
                .andExpect(jsonPath("$.data.evaluableSamples").value(2))
                .andExpect(jsonPath("$.data.unreviewedSamples").value(1))
                .andExpect(jsonPath("$.data.tiedSamples").value(0))
                .andExpect(jsonPath("$.data.invalidSamples").value(0))
                .andExpect(jsonPath("$.data.evaluableCoverage").value(2.0 / 3.0))
                .andExpect(jsonPath("$.data.baseline.confusion.truePositives").value(1))
                .andExpect(jsonPath("$.data.baseline.confusion.trueNegatives").value(1))
                .andExpect(jsonPath("$.data.baseline.confusion.falsePositives").value(0))
                .andExpect(jsonPath("$.data.baseline.confusion.falseNegatives").value(0))
                .andExpect(jsonPath("$.data.fusion.confusion.truePositives").value(0))
                .andExpect(jsonPath("$.data.fusion.confusion.trueNegatives").value(1))
                .andExpect(jsonPath("$.data.fusion.confusion.falsePositives").value(0))
                .andExpect(jsonPath("$.data.fusion.confusion.falseNegatives").value(1))
                .andExpect(jsonPath("$.data.metricDeltas").isArray())
                .andExpect(jsonPath("$.data.reviewAgreement.reviewedSamples").value(2))
                .andExpect(jsonPath("$.data.reviewAgreement.resolvedSamples").value(2))
                .andExpect(jsonPath("$.data.reviewAgreement.tiedSamples").value(0))
                .andExpect(jsonPath("$.data.reviewAgreement.agreementRate").value(1.0))
                .andExpect(jsonPath("$.data.disagreementSamples").isArray())
                .andExpect(jsonPath("$.data.datasetVersion").value("dataset-v1"))
                .andExpect(jsonPath("$.data.baselineVersion").value("baseline-v1"))
                .andExpect(jsonPath("$.data.fusionVersion").value("fusion-v1"))
                .andExpect(jsonPath("$.data.analysisVersion").value("analysis-v1"))
                .andExpect(jsonPath("$.data.generatedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(result).at("/data");

        assertThat(data.at("/metricDeltas").size()).isEqualTo(8);
        assertThat(data.at("/disagreementSamples").size()).isEqualTo(1);
        assertThat(data.at("/disagreementSamples/0/scenario").asText()).isEqualTo("TAB_SWITCH");
        assertThat(data.at("/disagreementSamples/0/baselinePositive").asBoolean()).isTrue();
        assertThat(data.at("/disagreementSamples/0/fusionPositive").asBoolean()).isFalse();
        assertThat(data.at("/disagreementSamples/0/groundTruthLabel").asText()).isEqualTo("TAB_SWITCH");

        assertThat(data.at("/scenarios").size()).isEqualTo(2);
        assertThat(data.at("/scenarios/0/value").asText()).isEqualTo("NORMAL");
        assertThat(data.at("/scenarios/0/sampleCount").asInt()).isEqualTo(1);
        assertThat(data.at("/scenarios/1/value").asText()).isEqualTo("TAB_SWITCH");
        assertThat(data.at("/scenarios/1/sampleCount").asInt()).isEqualTo(1);

        assertThat(data.at("/confidenceIntervals").size()).isGreaterThan(0);
        boolean lightingGroupPresent = false;
        for (JsonNode group : data.at("/conditions")) {
            if ("lighting=GOOD".equals(group.get("value").asText())) {
                lightingGroupPresent = true;
                assertThat(group.get("sampleCount").asInt()).isEqualTo(2);
            }
        }
        assertThat(lightingGroupPresent).isTrue();

        assertThat(data.at("/latency/measured/measuredCount").asInt()).isEqualTo(2);
        assertThat(data.at("/latency/measured/mean").asDouble()).isGreaterThanOrEqualTo(0.0);
        assertThat(data.at("/bandwidth/rawMedia/measuredCount").asInt()).isEqualTo(2);
        assertThat(data.at("/bandwidth/signalBytes/measuredCount").asInt()).isEqualTo(2);
        assertThat(data.at("/transitions/changedPredictionCount").asInt()).isEqualTo(1);
        assertThat(data.at("/transitions/baselineCorrectToFusionWrong").asInt()).isEqualTo(1);

        assertNoNanNorInfinity(data);
        assertPayloadContainsNoStudentIdentifiersOrDemographics(result);
    }

    @Test
    void analysisScopesToASingleRunWhenRunIdGiven() throws Exception {
        String experimentId = createExperiment("Scope analysis study", EXAM);
        String admin = login("rra-admin@example.com", "admin123");
        String runA = createRunAndComplete(experimentId, "scope-run-a", admin, 2, "rra-scope-a");
        String runB = createRunAndComplete(experimentId, "scope-run-b", admin, 1, "rra-scope-b");

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capturedSamples").value(3))
                .andExpect(jsonPath("$.data.runId", org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis?runId=" + runA)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(runA))
                .andExpect(jsonPath("$.data.capturedSamples").value(2))
                .andExpect(jsonPath("$.data.registeredSamples").value(2));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis?runId=" + runB)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capturedSamples").value(1));
    }

    @Test
    void analysisOverEmptyExperimentIsStableAndValid() throws Exception {
        String experimentId = createExperiment("Empty analysis study", EXAM);
        String admin = login("rra-admin@example.com", "admin123");

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capturedSamples").value(0))
                .andExpect(jsonPath("$.data.reviewedSamples").value(0))
                .andExpect(jsonPath("$.data.evaluableSamples").value(0))
                .andExpect(jsonPath("$.data.evaluableCoverage", org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.baseline.confusion.truePositives").value(0))
                .andExpect(jsonPath("$.data.baseline.metrics.precision", org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.fusion.metrics.accuracy", org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.metricDeltas").isArray())
                .andExpect(jsonPath("$.data.disagreementSamples").isEmpty())
                .andExpect(jsonPath("$.data.scenarios").isEmpty())
                .andExpect(jsonPath("$.data.latency.measured.measuredCount").value(0))
                .andExpect(jsonPath("$.data.bandwidth.rawMedia.measuredCount").value(0))
                .andExpect(jsonPath("$.data.reviewAgreement.reviewedSamples").value(0))
                .andExpect(jsonPath("$.data.reviewAgreement.agreementRate", org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.confidenceIntervals").isEmpty())
                .andExpect(jsonPath("$.data.transitions.changedPredictionCount").value(0))
                .andExpect(jsonPath("$.data.datasetVersion").value("dataset-v1"));
    }

    @Test
    void analysisExcludesNonEligibleRunsFromExperimentWideScope() throws Exception {
        String experimentId = createExperiment("Eligible analysis study", EXAM);
        String admin = login("rra-admin@example.com", "admin123");
        String runA = createRunAndComplete(experimentId, "eligible-run-a", admin, 1, "rra-elig-a");
        String runC = createRun(experimentId, "eligible-run-c", admin);
        mockMvc.perform(post("/api/proctor/research/runs/" + runC + "/start")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        String canceledAttempt = insertAttempt("rra-elig-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String canceledSample = createPlannedSample(runC, canceledAttempt, "NORMAL", Map.of("lighting", "GOOD"), admin);
        observe(runC, canceledSample, admin);
        capture(runC, canceledSample, admin, "");
        mockMvc.perform(post("/api/proctor/research/runs/" + runC + "/cancel")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capturedSamples").value(1));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis?runId=" + runC)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capturedSamples").value(1));
    }

    @Test
    void analysisIsReproducibleExceptGeneratedAt() throws Exception {
        String experimentId = createExperiment("Repro analysis study", EXAM);
        String admin = login("rra-admin@example.com", "admin123");
        createRunAndComplete(experimentId, "repro-analysis-run", admin, 2, "rra-repro");

        MvcResult first = getAnalysis(experimentId, null, admin);
        Thread.sleep(10);
        MvcResult second = getAnalysis(experimentId, null, admin);

        JsonNode firstNode = objectMapper.readTree(first.getResponse().getContentAsString()).at("/data");
        JsonNode secondNode = objectMapper.readTree(second.getResponse().getContentAsString()).at("/data");
        String firstStamp = firstNode.at("/generatedAt").asText();
        String secondStamp = secondNode.at("/generatedAt").asText();

        ((ObjectNode) firstNode).remove("generatedAt");
        ((ObjectNode) secondNode).remove("generatedAt");

        assertThat(firstStamp).isNotBlank();
        assertThat(secondStamp).isNotBlank();
        assertThat(secondStamp).isNotEqualTo(firstStamp);
        assertThat(firstNode).isEqualTo(secondNode);
    }

    @Test
    void analysisDoesNotTouchEnforcementState() throws Exception {
        String experimentId = createExperiment("Isolation analysis study", EXAM);
        String admin = login("rra-admin@example.com", "admin123");
        String runId = createRun(experimentId, "iso-analysis-run", admin);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        String attemptId = insertAttempt("rra-isol-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));

        Integer warningsBefore = warningCount(attemptId);
        String statusBefore = attemptStatus(attemptId);
        String accessBefore = accessStatus("rra-student", EXAM);
        Instant terminatedBefore = terminatedAt(attemptId);
        String terminatedReasonBefore = terminatedReason(attemptId);

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        assertThat(warningCount(attemptId)).isEqualTo(warningsBefore);
        assertThat(attemptStatus(attemptId)).isEqualTo(statusBefore);
        assertThat(accessStatus("rra-student", EXAM)).isEqualTo(accessBefore);
        assertThat(terminatedAt(attemptId)).isEqualTo(terminatedBefore);
        assertThat(terminatedReason(attemptId)).isEqualTo(terminatedReasonBefore);
        assertThat(statusBefore).isEqualTo("STARTED");
        assertThat(accessBefore).isEqualTo(ExamAccessStatus.ELIGIBLE.name());
    }

    @Test
    void analysisPersistsNothingAndLeavesFusionAndBaselineUntouched() throws Exception {
        String experimentId = createExperiment("Persist analysis study", EXAM);
        String admin = login("rra-admin@example.com", "admin123");
        createRunAndComplete(experimentId, "persist-analysis-run", admin, 2, "rra-persist");

        Integer experimentsBefore = count("research_experiments");
        Integer samplesBefore = count("research_samples");
        Integer runSamplesBefore = count("research_run_samples");
        Integer reviewsBefore = count("research_reviews");
        Integer fusionResultsBefore = count("proctor_fusion_results");
        Integer eventsBefore = count("proctor_events");

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        assertThat(count("research_experiments")).isEqualTo(experimentsBefore);
        assertThat(count("research_samples")).isEqualTo(samplesBefore);
        assertThat(count("research_run_samples")).isEqualTo(runSamplesBefore);
        assertThat(count("research_reviews")).isEqualTo(reviewsBefore);
        assertThat(count("proctor_fusion_results")).isEqualTo(fusionResultsBefore);
        assertThat(count("proctor_events")).isEqualTo(eventsBefore);
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

    private String createRunAndComplete(String experimentId, String runCode, String token,
                                        int sampleCount, String attemptPrefix) throws Exception {
        String runId = createRun(experimentId, runCode, token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        for (int i = 1; i <= sampleCount; i++) {
            String attemptId = insertAttempt(attemptPrefix + "-" + i,
                    BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
            String scenario = i % 2 == 0 ? "TAB_SWITCH" : "NORMAL";
            String sampleId = createPlannedSample(runId, attemptId, scenario,
                    Map.of("lighting", "GOOD"), token);
            observe(runId, sampleId, token);
            if ("TAB_SWITCH".equals(scenario)) {
                insertEvent(attemptPrefix + "-ev-" + i, attemptId, "TAB_SWITCH", "BROWSER", null, Instant.now());
            }
            capture(runId, sampleId, token, "");
            reviewRunSample(sampleId, scenario, token);
        }
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        return runId;
    }

    private void assertNoNanNorInfinity(JsonNode node) {
        if (node.isFloatingPointNumber()) {
            String text = node.asText();
            assertThat(text).doesNotContain("NaN").doesNotContain("Infinity");
        }
        if (node.isContainerNode()) {
            node.forEach(this::assertNoNanNorInfinity);
        }
    }

    private void assertPayloadContainsNoStudentIdentifiersOrDemographics(String payload) {
        assertThat(payload.toLowerCase())
                .doesNotContain("\"studentid\"")
                .doesNotContain("\"student_id\"")
                .doesNotContain("\"email\"")
                .doesNotContain("rra-student")
                .doesNotContain("gender")
                .doesNotContain("ethnicity")
                .doesNotContain("signalBytes:null");
    }

    private MvcResult getAnalysis(String experimentId, String runId, String token) throws Exception {
        String url = "/api/proctor/research/experiments/" + experimentId + "/analysis"
                + (runId == null ? "" : "?runId=" + runId);
        return mockMvc.perform(get(url).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private int count(String table) {
        Integer value = jdbcTemplate.queryForObject(
                "select count(*) from " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private String createExperiment(String name, String examId) throws Exception {
        String token = login("rra-admin@example.com", "admin123");
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
                id, EXAM, "rra-student", attemptSequence, start, expires);
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