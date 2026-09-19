package com.examora;

import com.examora.model.ExamAccessStatus;
import com.examora.model.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * End-to-end tests for the read-only research failure-case analysis endpoints. Real experiments,
 * runs, attempts and signals only, asserted against the same evaluable dataset as the statistical
 * analysis. Covers authorization, run vs experiment scope, pagination (default, cap, determinism),
 * real disagreement and false-positive/false-negative datasets, enforcement isolation,
 * non-persistence, version stamps, and the strict absence of student identity, raw media,
 * biometrics and demographic fields from payloads and schemas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_failure_analysis;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-research-failure-analysis-tests",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip=1000",
        "examora.login.max-per-ip-email=1000"
})
class ResearchFailureAnalysisIntegrationTest {

    private static final String EXAM = "rfa-exam-1";
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
        insertUser("rfa-admin", "Admin", "rfa-admin@example.com", "admin123", Role.ADMIN);
        insertUser("rfa-stud", "Student", "rfa-stud@example.com", "student123", Role.STUDENT);
        insertUser("rfa-teacher", "Teacher", "rfa-teacher@example.com", "teacher123", Role.TEACHER);
        insertExam(EXAM, "RFA Exam", "rfa-admin");
    }

    @Test
    void failureAnalysisRequiresAdmin() throws Exception {
        String experimentId = createExperiment("Auth failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        String runId = createRun(experimentId, "auth-failure-run", admin);
        String aggregate = "/api/proctor/research/experiments/" + experimentId + "/failure-analysis";
        String samples = "/api/proctor/research/experiments/" + experimentId + "/failure-analysis/samples";

        mockMvc.perform(get(aggregate)
                        .header("Authorization", bearer(login("rfa-stud@example.com", "student123"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(aggregate)
                        .header("Authorization", bearer(login("rfa-teacher@example.com", "teacher123"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(aggregate + "?runId=" + runId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(samples + "?runId=" + runId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(aggregate).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get(samples).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void failureAnalysisForUnknownExperimentIsNotFound() throws Exception {
        String admin = login("rfa-admin@example.com", "admin123");
        mockMvc.perform(get("/api/proctor/research/experiments/no-such-experiment/failure-analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void failureAnalysisRejectsRunFromAnotherExperiment() throws Exception {
        String admin = login("rfa-admin@example.com", "admin123");
        String experimentA = createExperiment("Scope failure A", EXAM);
        String experimentB = createExperiment("Scope failure B", EXAM);
        String runA = createRun(experimentA, "scope-failure-a", admin);
        createRun(experimentB, "scope-failure-b", admin);

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentB
                        + "/failure-analysis?runId=" + runA)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void failureAnalysisOverEmptyExperimentIsStableAndValid() throws Exception {
        String experimentId = createExperiment("Empty failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/failure-analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evaluableSampleCount").value(0))
                .andExpect(jsonPath("$.data.failureCaseCount").value(0))
                .andExpect(jsonPath("$.data.disagreementCount").value(0))
                .andExpect(jsonPath("$.data.changedPredictionCount").value(0))
                .andExpect(jsonPath("$.data.categories").isEmpty())
                .andExpect(jsonPath("$.data.transitionCategories").isEmpty())
                .andExpect(jsonPath("$.data.confidenceBands").isEmpty())
                .andExpect(jsonPath("$.data.environmentalFailures").isEmpty())
                .andExpect(jsonPath("$.data.scenarioFailures").isEmpty())
                .andExpect(jsonPath("$.data.patterns[0].text").value("No evaluable samples in scope."))
                .andExpect(jsonPath("$.data.failureAnalysisVersion").value("failure-analysis-v1"));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis/samples")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.pageSize").value(25));
    }

    @Test
    void failureAnalysisReportsRealExperimentWideFailureCounts() throws Exception {
        String experimentId = createExperiment("Pilot failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        String runId = createCompletedRun(experimentId, "pilot-failure-run", admin, List.of(
                spec("rfa-tw-", "TAB_SWITCH", label("TAB_SWITCH"),
                        event("TAB_SWITCH", "BROWSER", null)),
                spec("rfa-nc-", "NORMAL", label("NORMAL"), null)));

        String result = mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId", org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.evaluableSampleCount").value(2))
                .andExpect(jsonPath("$.data.failureCaseCount").value(1))
                .andExpect(jsonPath("$.data.disagreementCount").value(1))
                .andExpect(jsonPath("$.data.changedPredictionCount").value(1))
                .andExpect(jsonPath("$.data.categories.length()").value(2))
                .andExpect(jsonPath("$.data.transitions.baselineCorrectToFusionWrong").value(1))
                .andExpect(jsonPath("$.data.transitions.baselineWrongToFusionCorrect").value(0))
                .andExpect(jsonPath("$.data.transitions.byCondition.length()").value(2))
                .andExpect(jsonPath("$.data.transitions.byCondition[0].value").value("lighting=GOOD"))
                .andExpect(jsonPath("$.data.transitions.byCondition[0].count").value(1))
                .andExpect(jsonPath("$.data.transitions.byCondition[1].value").value("network=STABLE"))
                .andExpect(jsonPath("$.data.transitions.byCondition[1].count").value(1))
                .andExpect(jsonPath("$.data.failureAnalysisVersion").value("failure-analysis-v1"))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(result).at("/data");
        assertThat(data.at("/categories").size()).isEqualTo(2);
        assertThat(data.at("/categories/0/value").asText()).isEqualTo("FUSION_FALSE_NEGATIVE");
        assertThat(data.at("/categories/0/count").asInt()).isEqualTo(1);
        assertThat(data.at("/categories/1/value").asText()).isEqualTo("BASELINE_ONLY_POSITIVE");
        assertThat(data.has("failureCases")).isFalse();
        assertThat(data.at("/patterns").size()).isGreaterThan(0);
        assertThat(data.at("/scenarioFailures/0/value").asText()).isEqualTo("NORMAL");
        assertThat(data.at("/scenarioFailures/0/fusionFalseNegatives").asInt()).isZero();
        assertThat(data.at("/scenarioFailures/1/value").asText()).isEqualTo("TAB_SWITCH");
        assertThat(data.at("/scenarioFailures/1/fusionFalseNegatives").asInt()).isEqualTo(1);
        assertThat(data.at("/signalAssociation/errorSampleCount").asInt()).isEqualTo(1);
        assertThat(data.at("/signalAssociation/errorSamplesWithoutSignals").asInt()).isEqualTo(0);
        assertThat(data.has("signatureScore")).isFalse();
        assertNoNanNorInfinity(data);
    }

    @Test
    void failureAnalysisReportsRealFalsePositiveDataset() throws Exception {
        String experimentId = createExperiment("False positive study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        String runId = createCompletedRun(experimentId, "false-positive-run", admin, List.of(
                spec("rfa-fp-", "NORMAL", label("NORMAL"),
                        event("CAMERA_OFF", "CLIENT_AI", 0.9)),
                spec("rfa-fn-", "TAB_SWITCH", label("TAB_SWITCH"),
                        event("TAB_SWITCH", "BROWSER", null)),
                spec("rfa-tn-", "NORMAL", label("NORMAL"), null)));

        String result = mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis?runId=" + runId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(result).at("/data");

        assertThat(data.at("/evaluableSampleCount").asInt()).isEqualTo(3);
        assertThat(data.at("/failureCaseCount").asInt()).isEqualTo(2);
        assertThat(data.at("/categories").size()).isEqualTo(4);
        assertThat(data.at("/categories/0/value").asText()).isEqualTo("BASELINE_FALSE_POSITIVE");
        assertThat(data.at("/categories/0/count").asInt()).isEqualTo(1);
        assertThat(data.at("/categories/1/value").asText()).isEqualTo("FUSION_FALSE_POSITIVE");
        assertThat(data.at("/categories/1/count").asInt()).isEqualTo(1);
        assertThat(data.at("/categories/2/value").asText()).isEqualTo("FUSION_FALSE_NEGATIVE");
        assertThat(data.at("/categories/2/count").asInt()).isEqualTo(1);
        assertThat(data.at("/categories/3/value").asText()).isEqualTo("BASELINE_ONLY_POSITIVE");
        assertThat(data.at("/confidenceBands").size()).isEqualTo(1);
        assertThat(data.at("/confidenceBands/0/band").asText()).isEqualTo("0.80-1.00");
        assertThat(data.at("/confidenceBands/0/distinctSampleCount").asInt()).isEqualTo(1);
        assertThat(data.at("/confidenceBands/0/signalCount").asInt()).isEqualTo(1);
        assertThat(data.at("/confidenceBands/0/evaluator").asText()).isEqualTo("fusion-v1");
        assertThat(data.at("/signalAssociation/sources").size()).isEqualTo(2);
        assertThat(data.at("/scenarioFailures").size()).isEqualTo(2);
    }

    @Test
    void failureAnalysisReportsRealDisagreementDataset() throws Exception {
        String experimentId = createExperiment("Disagreement failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        String runId = createCompletedRun(experimentId, "disagreement-run", admin, List.of(
                spec("rfa-d1-", "TAB_SWITCH", label("TAB_SWITCH"),
                        event("TAB_SWITCH", "BROWSER", null)),
                spec("rfa-d2-", "NORMAL", label("NORMAL"),
                        event("NETWORK_INTERRUPTION", "BROWSER", null))));

        String result = mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failureCaseCount").value(2))
                .andExpect(jsonPath("$.data.disagreementCount").value(2))
                .andExpect(jsonPath("$.data.changedPredictionCount").value(2))
                .andExpect(jsonPath("$.data.disagreementCategories.length()").value(1))
                .andExpect(jsonPath("$.data.disagreementCategories[0].value").value("BASELINE_ONLY_POSITIVE"))
                .andExpect(jsonPath("$.data.disagreementCategories[0].count").value(2))
                .andReturn().getResponse().getContentAsString();

        String page = mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis/samples")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode pageData = objectMapper.readTree(page).at("/data");
        assertThat(pageData.at("/totalCount").asInt()).isEqualTo(2);
        assertThat(pageData.at("/samples").size()).isEqualTo(2);
        Set<String> classifications = new HashSet<>();
        for (JsonNode sample : pageData.at("/samples")) {
            assertThat(sample.at("/fusionPrediction").asBoolean()).isFalse();
            classifications.add(sample.at("/classification").asText());
        }
        assertThat(classifications).containsExactlyInAnyOrder("CORRECT_TO_WRONG", "WRONG_TO_CORRECT");
    }

    @Test
    void failureAnalysisScopesToASingleRunWhenRunIdGiven() throws Exception {
        String experimentId = createExperiment("Scope failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        String runA = createCompletedRun(experimentId, "scope-failure-a", admin,
                failureSpecs("rfa-sa-", 2));
        String runB = createCompletedRun(experimentId, "scope-failure-b", admin,
                failureSpecs("rfa-sb-", 1));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/failure-analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId", org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.evaluableSampleCount").value(3));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis?runId=" + runA)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(runA))
                .andExpect(jsonPath("$.data.evaluableSampleCount").value(2))
                .andExpect(jsonPath("$.data.failureCaseCount").value(2));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis?runId=" + runB)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failureCaseCount").value(1));
    }

    @Test
    void failureSamplesPaginationIsBoundedAndCapped() throws Exception {
        String experimentId = createExperiment("Pagination failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        String runId = createCompletedRun(experimentId, "page-failure-run", admin,
                failureSpecs("rfa-pg-", 5));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis/samples?pageSize=1000")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.totalCount").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.samples.length()").value(5));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis/samples?pageSize=2&page=1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.samples.length()").value(2));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis/samples?pageSize=2&page=10")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(10))
                .andExpect(jsonPath("$.data.samples").isEmpty());

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis/samples?page=-5")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0));
    }

    @Test
    void failureSamplesAreDeterministicallyOrderedBySampleId() throws Exception {
        String experimentId = createExperiment("Deterministic failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        String runId = createCompletedRun(experimentId, "deterministic-run", admin,
                failureSpecs("rfa-de-", 4));

        MvcResult first = getSamples(experimentId, null, 0, 2, admin);
        MvcResult second = getSamples(experimentId, null, 0, 2, admin);

        JsonNode firstData = objectMapper.readTree(first.getResponse().getContentAsString()).at("/data");
        JsonNode secondData = objectMapper.readTree(second.getResponse().getContentAsString()).at("/data");
        assertThat(firstData).isEqualTo(secondData);

        JsonNode pageZero = objectMapper.readTree(
                getSamples(experimentId, null, 0, 2, admin).getResponse().getContentAsString()).at("/data");
        JsonNode pageOne = objectMapper.readTree(
                getSamples(experimentId, null, 1, 2, admin).getResponse().getContentAsString()).at("/data");

        List<String> ids = new ArrayList<>();
        pageZero.at("/samples").forEach(node -> ids.add(node.at("/sampleId").asText()));
        pageOne.at("/samples").forEach(node -> ids.add(node.at("/sampleId").asText()));
        List<String> expected = new ArrayList<>(ids);
        expected.sort(String::compareTo);
        assertThat(ids).isEqualTo(expected);
        assertThat(ids).allSatisfy(id -> assertThat(id).isNotBlank());
    }

    @Test
    void failureSamplesDefaultPageSizeIsTwentyFive() throws Exception {
        String experimentId = createExperiment("Default size failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        createCompletedRun(experimentId, "default-size-run", admin, failureSpecs("rfa-ds-", 3));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis/samples")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.pageSize").value(25))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.samples.length()").value(3));
    }

    @Test
    void failureAnalysisDoesNotTouchEnforcementState() throws Exception {
        String experimentId = createExperiment("Isolation failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        String runId = createRun(experimentId, "iso-failure-run", admin);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        String attemptId = insertAttempt("rfa-iso-at", BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
        String sampleId = createPlannedSample(runId, attemptId, "TAB_SWITCH",
                Map.of("lighting", "GOOD", "network", "STABLE"), admin);
        observe(runId, sampleId, admin);
        insertEvent("rfa-iso-ev", attemptId, "TAB_SWITCH", "BROWSER", null, Instant.now());
        capture(runId, sampleId, admin, "");

        Integer warningsBefore = warningCount(attemptId);
        String statusBefore = attemptStatus(attemptId);
        String accessBefore = accessStatus("rfa-stud", EXAM);
        Instant terminatedBefore = terminatedAt(attemptId);
        String terminatedReasonBefore = terminatedReason(attemptId);
        Integer retestsBefore = count("retest_requests");

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/failure-analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis/samples")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        assertThat(warningCount(attemptId)).isEqualTo(warningsBefore);
        assertThat(attemptStatus(attemptId)).isEqualTo(statusBefore);
        assertThat(accessStatus("rfa-stud", EXAM)).isEqualTo(accessBefore);
        assertThat(terminatedAt(attemptId)).isEqualTo(terminatedBefore);
        assertThat(terminatedReason(attemptId)).isEqualTo(terminatedReasonBefore);
        assertThat(count("retest_requests")).isEqualTo(retestsBefore);
        assertThat(statusBefore).isEqualTo("STARTED");
        assertThat(accessBefore).isEqualTo(ExamAccessStatus.ELIGIBLE.name());
    }

    @Test
    void failureAnalysisPersistsNothingAndLeavesFusionAndBaselineUntouched() throws Exception {
        String experimentId = createExperiment("Persist failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        createCompletedRun(experimentId, "persist-failure-run", admin, failureSpecs("rfa-pe-", 2));

        Integer experimentsBefore = count("research_experiments");
        Integer samplesBefore = count("research_samples");
        Integer runSamplesBefore = count("research_run_samples");
        Integer reviewsBefore = count("research_reviews");
        Integer fusionResultsBefore = count("proctor_fusion_results");
        Integer eventsBefore = count("proctor_events");

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/failure-analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis/samples")
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
    void failurePayloadContainsNoStudentIdentityRawMediaOrDemographics() throws Exception {
        String experimentId = createExperiment("Privacy failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        createCompletedRun(experimentId, "privacy-failure-run", admin, failureSpecs("rfa-pr-", 2));

        String aggregate = mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String samples = mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId
                        + "/failure-analysis/samples")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertPayloadContainsNoStudentIdentifiersOrDemographics(aggregate);
        assertPayloadContainsNoStudentIdentifiersOrDemographics(samples);
    }

    @Test
    void researchFailureTablesPersistNoRawMediaNoBiometricsNoDemographics() throws Exception {
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

    @Test
    void failureAnalysisCarriesVersionStampsIncludingFailureVersion() throws Exception {
        String experimentId = createExperiment("Version failure study", EXAM);
        String admin = login("rfa-admin@example.com", "admin123");
        createCompletedRun(experimentId, "version-failure-run", admin, failureSpecs("rfa-ve-", 1));

        mockMvc.perform(get("/api/proctor/research/experiments/" + experimentId + "/failure-analysis")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.datasetVersion").value("dataset-v1"))
                .andExpect(jsonPath("$.data.baselineVersion").value("baseline-v1"))
                .andExpect(jsonPath("$.data.fusionVersion").value("fusion-v1"))
                .andExpect(jsonPath("$.data.analysisVersion").value("analysis-v1"))
                .andExpect(jsonPath("$.data.failureAnalysisVersion").value("failure-analysis-v1"))
                .andExpect(jsonPath("$.data.generatedAt").isNotEmpty());
    }

    private static Map<String, String> label(String scenario) {
        return Map.of("lighting", "GOOD", "network", "STABLE");
    }

    private List<SampleSpec> failureSpecs(String attemptPrefix, int count) {
        List<SampleSpec> specs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            specs.add(spec(attemptPrefix + i + "-", "TAB_SWITCH", label("TAB_SWITCH"),
                    event("TAB_SWITCH", "BROWSER", null)));
        }
        return specs;
    }

    private record SampleSpec(String attemptPrefix, String scenario, Map<String, String> conditions,
                              String reviewLabel, EventSpec event) {
    }

    private record EventSpec(String type, String source, Double confidence) {
    }

    @SafeVarargs
    private static SampleSpec spec(String attemptPrefix, String scenario, Map<String, String> conditions,
                                   EventSpec... event) {
        return new SampleSpec(attemptPrefix, scenario, conditions, scenario,
                event == null || event.length == 0 ? null : event[0]);
    }

    private static EventSpec event(String type, String source, Double confidence) {
        return new EventSpec(type, source, confidence);
    }

    private String createCompletedRun(String experimentId, String runCode, String token,
                                      List<SampleSpec> specs) throws Exception {
        String runId = createRun(experimentId, runCode, token);
        mockMvc.perform(post("/api/proctor/research/runs/" + runId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        for (SampleSpec spec : specs) {
            String attemptId = insertAttempt(spec.attemptPrefix + "at",
                    BASE.minusSeconds(30), Instant.now().plusSeconds(3600));
            String sampleId = createPlannedSample(runId, attemptId, spec.scenario, spec.conditions, token);
            observe(runId, sampleId, token);
            if (spec.event != null) {
                insertEvent(spec.attemptPrefix + "ev", attemptId, spec.event.type,
                        spec.event.source, spec.event.confidence, Instant.now());
            }
            capture(runId, sampleId, token, "");
            reviewRunSample(sampleId, spec.reviewLabel, token);
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
        String lower = payload.toLowerCase();
        assertThat(lower)
                .doesNotContain("\"studentid\"")
                .doesNotContain("\"student_id\"")
                .doesNotContain("\"email\"")
                .doesNotContain("\"gender\"")
                .doesNotContain("\"ethnicity\"")
                .doesNotContain("biometric")
                .doesNotContain("face_embedding")
                .doesNotContain("video_blob")
                .doesNotContain("audio_blob")
                .doesNotContain("raw_video")
                .doesNotContain("raw_audio")
                .doesNotContain("rfa-stud");
    }

    private MvcResult getSamples(String experimentId, String runId, int page, int pageSize,
                                 String token) throws Exception {
        String url = "/api/proctor/research/experiments/" + experimentId + "/failure-analysis/samples"
                + (runId == null ? "" : "?runId=" + runId)
                + "?page=" + page + "&pageSize=" + pageSize;
        return mockMvc.perform(get(url).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private int count(String table) {
        Integer value = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private String createExperiment(String name, String examId) throws Exception {
        String token = login("rfa-admin@example.com", "admin123");
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
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("capture -> " + result.getResponse().getStatus()
                    + " body=" + result.getResponse().getContentAsString());
        }
    }

    private void reviewRunSample(String runSampleId, String label, String token) throws Exception {
        String researchSampleId = jdbcTemplate.queryForObject(
                "select research_sample_id from research_run_samples where id = ?",
                String.class, runSampleId);
        assertThat(researchSampleId).isNotNull();
        mockMvc.perform(post("/api/proctor/research/samples/" + researchSampleId + "/review")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"" + label + "\"}"))
                .andExpect(status().isOk());
    }

    private String insertAttempt(String id, Instant start, Instant expires) {
        attemptSequence++;
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version, warning_count) "
                        + "values (?, ?, ?, ?, 'STARTED', ?, ?, 0, 0)",
                id, EXAM, "rfa-stud", attemptSequence, start, expires);
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