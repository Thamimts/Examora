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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_research_runs;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-research-run-tests",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip=1000",
        "examora.login.max-per-ip-email=1000"
})
class ResearchRunIntegrationTest {

    private static final String EXAM = "rr-exam-1";
    private static final String OTHER_EXAM = "rr-exam-other";
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
        insertUser("rr-admin", "Admin", "rr-admin@example.com", "admin123", Role.ADMIN);
        insertUser("rr-student", "Student", "rr-student@example.com", "student123", Role.STUDENT);
        insertUser("rr-teacher", "Teacher", "rr-teacher@example.com", "teacher123", Role.TEACHER);
        insertUser("rr-admin2", "Admin2", "rr-admin2@example.com", "admin234", Role.ADMIN);
        insertExam(EXAM, "RR Exam", "rr-admin");
        insertExam(OTHER_EXAM, "Other Exam", "rr-admin");
    }

    @Test
    void adminCanCreateRunAndItStartsPlanned() throws Exception {
        String experimentId = createExperiment("Study 1", EXAM);
        String token = login("rr-admin@example.com", "admin123");

        String json = mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/runs")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runCode\":\"run-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runCode").value("run-1"))
                .andExpect(jsonPath("$.data.status").value("PLANNED"))
                .andExpect(jsonPath("$.data.experimentId").value(experimentId))
                .andReturn().getResponse().getContentAsString();

        String runId = objectMapper.readTree(json).at("/data/id").asText();
        assertThat(runId).isNotEmpty();

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/runs")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void studentIsForbiddenFromCreatingRuns() throws Exception {
        String experimentId = createExperiment("Study S", EXAM);
        String token = login("rr-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/runs")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runCode\":\"run-x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherIsForbiddenFromCreatingRuns() throws Exception {
        String experimentId = createExperiment("Study T", EXAM);
        String token = login("rr-teacher@example.com", "teacher123");
        mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/runs")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runCode\":\"run-y\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorizedFromRunsApi() throws Exception {
        String experimentId = createExperiment("Study A", EXAM);
        mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runCode\":\"run-z\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void startRunTransitionsToRunningAndIdempotentStartIsOk() throws Exception {
        String experimentId = createExperiment("Start study", EXAM);
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "start-run", token);

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty());

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void completeRunTransitionsToCompletedAndCancelTransitionsToCancelled() throws Exception {
        String experimentId = createExperiment("Transition study", EXAM);
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "trans-run", token);

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.endedAt").isNotEmpty());

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/cancel")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
    }

    @Test
    void repeatedCompletionIsIdempotent() throws Exception {
        String experimentId = createExperiment("Idempotent study", EXAM);
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "idem-run", token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void completeRunWhileSampleIsCapturingIsBlockedWith409() throws Exception {
        String experimentId = createExperiment("Capture-guard study", EXAM);
        String attemptId = insertAttempt("cap-at-1", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "cap-run", token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        String sampleId = createPlannedSample(runId, attemptId, "NORMAL", Map.of("lighting", "GOOD"), token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples/" + sampleId + "/observe")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/proctor/research/runs/" + runId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run.status").value("RUNNING"));
    }

    @Test
    void plannedSampleValidationRejectsInvalidScenarioAndConditions() throws Exception {
        String experimentId = createExperiment("Validate study", EXAM);
        String attemptId = insertAttempt("val-at-1", BASE.minusSeconds(30), BASE.plusSeconds(30));
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "val-run", token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"" + attemptId + "\",\"scenario\":\"INVENTED\","
                                + "\"conditions\":{\"lighting\":\"GOOD\"}}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"" + attemptId + "\",\"scenario\":\"NORMAL\","
                                + "\"conditions\":{\"gender\":\"FEMALE\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void plannedSampleForNonexistentAttemptIsNotFound() throws Exception {
        String experimentId = createExperiment("No attempt study", EXAM);
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "no-at-run", token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"no-such-attempt\",\"scenario\":\"NORMAL\","
                                + "\"conditions\":{\"lighting\":\"GOOD\"}}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void plannedSampleForAttemptOnDifferentExamIsRejected() throws Exception {
        String experimentId = createExperiment("Exam mismatch study", EXAM);
        String attemptId = insertAttempt("other-at-1", BASE.minusSeconds(30), BASE.plusSeconds(30), OTHER_EXAM);
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "mismatch-run", token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"" + attemptId + "\",\"scenario\":\"NORMAL\","
                                + "\"conditions\":{\"lighting\":\"GOOD\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicatePlannedSampleIsRejectedWith409() throws Exception {
        String experimentId = createExperiment("Dup study", EXAM);
        String attemptId = insertAttempt("dup-at-1", BASE.minusSeconds(30), BASE.plusSeconds(30));
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "dup-run", token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        createPlannedSample(runId, attemptId, "NORMAL", Map.of("lighting", "GOOD"), token);

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"" + attemptId + "\",\"scenario\":\"NORMAL\","
                                + "\"conditions\":{\"lighting\":\"GOOD\"}}"))
                .andExpect(status().isConflict());
    }

    @Test
    void observeThenCaptureCreatesCapturedSampleAndLinkedResearchSample() throws Exception {
        String experimentId = createExperiment("Lifecycle study", EXAM);
        String attemptId = insertAttempt("lc-at-1", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        insertEvent("lc-ev-1", attemptId, "TAB_SWITCH", "BROWSER", null, BASE.plusSeconds(3));
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "lc-run", token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        String sampleId = createPlannedSample(runId, attemptId, "TAB_SWITCH",
                Map.of("lighting", "GOOD", "network", "STABLE"), token);

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples/" + sampleId + "/observe")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CAPTURING"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty());

        capture(runId, sampleId, token, "\"measuredLatencyMs\":1500,\"rawMediaBytes\":2000,\"signalBytes\":400");

        Integer linkedCount = jdbcTemplate.queryForObject(
                "select count(*) from research_samples where id in "
                        + "(select research_sample_id from research_run_samples where id = ?)",
                Integer.class, sampleId);
        assertThat(linkedCount).isEqualTo(1);
    }

    @Test
    void captureFromNonCapturingStateIsRejected() throws Exception {
        String experimentId = createExperiment("No capture study", EXAM);
        String attemptId = insertAttempt("nc-at-1", BASE.minusSeconds(30), BASE.plusSeconds(30));
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "nc-run", token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        String sampleId = createPlannedSample(runId, attemptId, "NORMAL", Map.of("lighting", "GOOD"), token);

        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples/" + sampleId + "/capture")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endedAt\":\"" + Instant.now() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void runDetailIncludesDQAndMatrixAndScenarioInstructions() throws Exception {
        String experimentId = createExperiment("Detail study", EXAM);
        String attemptId = insertAttempt("dt-at-1", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        insertEvent("dt-ev-1", attemptId, "TAB_SWITCH", "BROWSER", null, BASE.plusSeconds(3));
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "dt-run", token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        String sampleId = createPlannedSample(runId, attemptId, "TAB_SWITCH",
                Map.of("lighting", "GOOD", "network", "STABLE"), token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples/" + sampleId + "/observe")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        capture(runId, sampleId, token, "\"measuredLatencyMs\":2000,\"rawMediaBytes\":1000,\"signalBytes\":300");

        mockMvc.perform(post("/api/proctor/research/samples/" + linkedResearchSampleId(sampleId) + "/review")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"TAB_SWITCH\",\"confidence\":0.9}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/proctor/research/runs/" + runId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataQuality.captured").value(1))
                .andExpect(jsonPath("$.data.dataQuality.reviewed").value(1))
                .andExpect(jsonPath("$.data.dataQuality.evaluable").value(1))
                .andExpect(jsonPath("$.data.dataQuality.missingMeasuredLatency").value(0))
                .andExpect(jsonPath("$.data.dataQuality.missingBandwidth").value(0))
                .andExpect(jsonPath("$.data.matrix").isArray())
                .andExpect(jsonPath("$.data.scenarios").isArray());
    }

    @Test
    void scenarioInstructionsEndpointReturnsAllScenarios() throws Exception {
        String token = login("rr-admin@example.com", "admin123");
        mockMvc.perform(get("/api/proctor/research/scenarios")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(8)));
    }

    @Test
    void conditionsEndpointReturnsAllAllowedControlledValues() throws Exception {
        String token = login("rr-admin@example.com", "admin123");
        mockMvc.perform(get("/api/proctor/research/conditions")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[0].key").value("lighting"))
                .andExpect(jsonPath("$.data[0].values", hasSize(2)))
                .andExpect(jsonPath("$.data[0].options", hasSize(2)))
                .andExpect(jsonPath("$.data[1].key").value("cameraQuality"))
                .andExpect(jsonPath("$.data[2].key").value("network"))
                .andExpect(jsonPath("$.data[3].key").value("cameraAngle"));
    }

    @Test
    void runDoesNotModifyEnforcementState() throws Exception {
        String experimentId = createExperiment("Isolation study", EXAM);
        String attemptId = insertAttempt("iso-at-1", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        insertEvent("iso-ev-1", attemptId, "TAB_SWITCH", "BROWSER", null, BASE.plusSeconds(3));
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "iso-run", token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        String sampleId = createPlannedSample(runId, attemptId, "TAB_SWITCH",
                Map.of("lighting", "GOOD"), token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples/" + sampleId + "/observe")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        capture(runId, sampleId, token, "");
        reviewRunSample(sampleId, "TAB_SWITCH", token);
        Integer warningsBefore = warningCount(attemptId);
        String statusBefore = attemptStatus(attemptId);
        String accessBefore = accessStatus("rr-student", EXAM);
        Instant terminatedBefore = terminatedAt(attemptId);
        String terminatedReasonBefore = terminatedReason(attemptId);

        mockMvc.perform(get("/api/proctor/research/runs/" + runId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        assertThat(warningCount(attemptId)).isEqualTo(warningsBefore);
        assertThat(attemptStatus(attemptId)).isEqualTo(statusBefore);
        assertThat(accessStatus("rr-student", EXAM)).isEqualTo(accessBefore);
        assertThat(terminatedAt(attemptId)).isEqualTo(terminatedBefore);
        assertThat(terminatedReason(attemptId)).isEqualTo(terminatedReasonBefore);
        assertThat(accessBefore).isEqualTo(ExamAccessStatus.ELIGIBLE.name());
    }

    @Test
    void noRawMediaIsPersistedInRunSampleTables() throws Exception {
        Integer mediaColumns = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_name in ('research_runs', 'research_run_samples') "
                        + "and lower(column_name) in ('video_blob', 'audio_blob', 'raw_video', 'raw_audio')",
                Integer.class);
        assertThat(mediaColumns).isZero();
    }

    @Test
    void duplicateRunCodeWithinExperimentIsRejectedWith409() throws Exception {
        String experimentId = createExperiment("Dup code study", EXAM);
        String token = login("rr-admin@example.com", "admin123");
        createRun(experimentId, "same-code", token);

        mockMvc.perform(post("/api/proctor/research/experiments/" + experimentId + "/runs")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runCode\":\"same-code\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void runDataQualityReflectsMissingSignalsAndScenarioMismatch() throws Exception {
        String experimentId = createExperiment("DQ study", EXAM);
        String attemptId = insertAttempt("dq-at-1", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String token = login("rr-admin@example.com", "admin123");
        String runId = createRun(experimentId, "dq-run", token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        String s1 = createPlannedSample(runId, attemptId, "TAB_SWITCH",
                Map.of("lighting", "GOOD"), token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/samples/" + s1 + "/observe")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        capture(runId, s1, token, "");
        reviewRunSample(s1, "NORMAL", token);

        mockMvc.perform(get("/api/proctor/research/runs/" + runId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataQuality.missingSignals").value(1))
                .andExpect(jsonPath("$.data.dataQuality.scenarioGroundTruthDisagreement").value(1));
    }

    private String createExperiment(String name, String examId) throws Exception {
        String token = login("rr-admin@example.com", "admin123");
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
        return insertAttempt(id, start, expires, EXAM);
    }

    private String insertAttempt(String id, Instant start, Instant expires, String examId) {
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version, warning_count) "
                        + "values (?, ?, ?, 1, 'STARTED', ?, ?, 0, 0)",
                id, examId, "rr-student", start, expires);
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