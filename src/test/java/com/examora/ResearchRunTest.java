package com.examora;

import com.examora.exception.ApiException;
import com.examora.service.ResearchConfig;
import com.examora.service.ResearchConfig.ScenarioInstruction;
import com.examora.service.ResearchRunDataQuality;
import com.examora.service.ResearchRunDataQuality.Entry;
import com.examora.service.ResearchRunDataQuality.MatrixRow;
import com.examora.service.ResearchRunDataQuality.Report;
import com.examora.service.ResearchRunStateMachine;
import com.examora.service.ResearchValidation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchRunTest {

    @Test
    void scenarioInstructionAllowListCoversEveryScenario() {
        Set<String> ordered = Set.copyOf(ResearchConfig.SCENARIO_ORDER);
        assertThat(ResearchConfig.SCENARIO_INSTRUCTIONS.keySet()).isEqualTo(ResearchConfig.SCENARIOS);
        assertThat(ResearchConfig.SCENARIO_EXPECTED_SIGNALS.keySet()).isEqualTo(ResearchConfig.SCENARIOS);
        assertThat(ordered).hasSize(8);
        for (String scenario : ResearchConfig.SCENARIOS) {
            ScenarioInstruction instruction = ResearchConfig.SCENARIO_INSTRUCTIONS.get(scenario);
            assertThat(instruction.description()).isNotBlank();
            assertThat(instruction.expectedAction()).isNotBlank();
            assertThat(instruction.durationGuidance()).isNotBlank();
            assertThat(instruction.reviewerObservation()).isNotBlank();
            assertThat(ResearchConfig.SCENARIO_EXPECTED_SIGNALS.get(scenario)).isNotNull();
        }
    }

    @Test
    void expectedSignalsForNormalAreEmptySoAnySignalIsUnexpected() {
        assertThat(ResearchConfig.SCENARIO_EXPECTED_SIGNALS.get("NORMAL")).isEmpty();
        assertThat(ResearchConfig.SCENARIO_EXPECTED_SIGNALS.get("TAB_SWITCH"))
                .containsExactly("TAB_SWITCH");
        assertThat(ResearchConfig.SCENARIO_EXPECTED_SIGNALS.get("MULTIPLE_FACES"))
                .contains("MULTIPLE_FACES", "FACE_COUNT_ANOMALY", "MULTIPLE_PERSONS");
    }

    @Test
    void controlledConditionAllowListAcceptsEveryLegalCombination() {
        for (String lighting : ResearchConfig.CONTROLLED_CONDITIONS.get("lighting")) {
            for (String quality : ResearchConfig.CONTROLLED_CONDITIONS.get("cameraQuality")) {
                for (String network : ResearchConfig.CONTROLLED_CONDITIONS.get("network")) {
                    for (String angle : ResearchConfig.CONTROLLED_CONDITIONS.get("cameraAngle")) {
                        ResearchValidation.validateControlledConditions(Map.of(
                                "lighting", lighting,
                                "cameraQuality", quality,
                                "network", network,
                                "cameraAngle", angle));
                    }
                }
            }
        }
    }

    @Test
    void inventedControlledConditionValueIsRejected() {
        assertThatThrownBy(() -> ResearchValidation.validateControlledConditions(
                Map.of("lighting", "BRIGHT")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ResearchValidation.validateControlledConditions(
                Map.of("network", "GOOD")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void unknownConditionKeyIsRejected() {
        assertThatThrownBy(() -> ResearchValidation.validateControlledConditions(
                Map.of("cameraPosition", "FRONT")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ResearchValidation.validateControlledConditions(Map.of()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ResearchValidation.validateControlledConditions(null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void runStateTransitionsAreValidForPlainFlow() {
        assertThat(ResearchRunStateMachine.canStart("PLANNED")).isTrue();
        assertThat(ResearchRunStateMachine.canStart("RUNNING")).isTrue();
        assertThat(ResearchRunStateMachine.canComplete("RUNNING")).isTrue();
        assertThat(ResearchRunStateMachine.canComplete("COMPLETED")).isTrue();
        assertThat(ResearchRunStateMachine.canCancel("PLANNED")).isTrue();
        assertThat(ResearchRunStateMachine.canCancel("RUNNING")).isTrue();
        assertThat(ResearchRunStateMachine.canCancel("CANCELLED")).isTrue();
    }

    @Test
    void invalidRunTransitionsAreRejected() {
        assertThat(ResearchRunStateMachine.canStart("COMPLETED")).isFalse();
        assertThat(ResearchRunStateMachine.canStart("CANCELLED")).isFalse();
        assertThat(ResearchRunStateMachine.canComplete("PLANNED")).isFalse();
        assertThat(ResearchRunStateMachine.canComplete("CANCELLED")).isFalse();
        assertThat(ResearchRunStateMachine.canCancel("COMPLETED")).isFalse();
        assertThat(ResearchRunStateMachine.canCreatePlannedSample("COMPLETED")).isFalse();
        assertThat(ResearchRunStateMachine.canCreatePlannedSample("CANCELLED")).isFalse();
        assertThat(ResearchRunStateMachine.canCapture("COMPLETED")).isFalse();
        assertThat(ResearchRunStateMachine.canCapture("CANCELLED")).isFalse();
    }

    @Test
    void repeatedCompletionIsIdempotentAndDeterministic() {
        assertThat(ResearchRunStateMachine.isIdempotentComplete("COMPLETED")).isTrue();
        assertThat(ResearchRunStateMachine.isIdempotentComplete("RUNNING")).isFalse();
        assertThat(ResearchRunStateMachine.isIdempotentCancel("CANCELLED")).isTrue();
        assertThat(ResearchRunStateMachine.isIdempotentCancel("RUNNING")).isFalse();
        assertThat(ResearchRunStateMachine.isIdempotentStart("RUNNING")).isTrue();
    }

    @Test
    void sampleLifecycleAllowsPlannedToCapturingToCapturedOnly() {
        assertThat(ResearchRunStateMachine.canCaptureSample("PLANNED")).isTrue();
        assertThat(ResearchRunStateMachine.canCaptureSample("CAPTURING")).isTrue();
        assertThat(ResearchRunStateMachine.canCaptureSample("CAPTURED")).isFalse();
        assertThat(ResearchRunStateMachine.hasInFlightSample(true)).isTrue();
        assertThat(ResearchRunStateMachine.hasInFlightSample(false)).isFalse();
    }

    @Test
    void completionIsGuardedWhileAnySampleIsMidCapture() {
        assertThat(ResearchRunStateMachine.hasInFlightSample(true)).isTrue();
        assertThat(ResearchRunStateMachine.canComplete("RUNNING")).isTrue();
    }

    @Test
    void runCodeValidationAcceptsLegalCodes() {
        ResearchValidation.normalizeRunCode("run-1");
        ResearchValidation.normalizeRunCode("RUN_2.0");
        assertThat(ResearchValidation.normalizeRunCode("  mixed.code-1_ ")).isEqualTo("mixed.code-1_");
    }

    @Test
    void runCodeValidationRejectsBlankAndInvalidCharacters() {
        assertThatThrownBy(() -> ResearchValidation.normalizeRunCode("  ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ResearchValidation.normalizeRunCode(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ResearchValidation.normalizeRunCode("run code!"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ResearchValidation.normalizeRunCode(new String(new char[41]).replace('\0', 'a')))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void conditionsKeyIsCanonicalAndOrderIndependent() {
        String ab = ResearchValidation.conditionsKey(Map.of(
                "lighting", "GOOD", "network", "STABLE"));
        String ba = ResearchValidation.conditionsKey(Map.of(
                "network", "STABLE", "lighting", "GOOD"));
        assertThat(ab).isEqualTo(ba);
        assertThat(ab).isEqualTo("lighting=GOOD;network=STABLE");
    }

    @Test
    void attemptBelongsToExperimentOnlyWhenExamsMatch() {
        assertThat(ResearchValidation.attemptBelongsToExperiment("exam-a", "exam-a")).isTrue();
        assertThat(ResearchValidation.attemptBelongsToExperiment("exam-a", null)).isTrue();
        assertThat(ResearchValidation.attemptBelongsToExperiment("exam-a", "exam-b")).isFalse();
        assertThat(ResearchValidation.attemptBelongsToExperiment(null, "exam-b")).isFalse();
    }

    @Test
    void privacySafeMetadataRejectsDemographicKeys() {
        assertThatThrownBy(() -> ResearchValidation.validateControlledConditions(
                Map.of("lighting", "GOOD", "gender", "FEMALE")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ResearchValidation.validateControlledConditions(
                Map.of("ethnicity", "GROUP_A", "lighting", "LOW")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void runDataQualityCountsEveryBucketAndTie() {
        Entry planned = entry("s1", "PLANNED", true, false, false, "NORMAL", false, false, false, true, true);
        Entry capturing = entry("s2", "CAPTURING", true, false, false, "TAB_SWITCH", false, false, false, true, true);
        Entry capturedReviewed = entry("s3", "CAPTURED", true, true, true, "NORMAL", false, true, false, true, true);
        Entry capturedTied = entry("s4", "CAPTURED", true, true, false, null, false, false, false, true, true);
        Entry capturedUnreviewed = entry("s5", "CAPTURED", true, false, false, "AUDIO_DETECTED", false, false, true, false, false);
        Entry capturedInvalid = entry("s6", "CAPTURED", true, true, true, "CAMERA_OFF", true, true, false, false, false);

        Report report = ResearchRunDataQuality.compute(
                List.of(planned, capturing, capturedReviewed, capturedTied, capturedUnreviewed, capturedInvalid));

        assertThat(report.planned()).isEqualTo(1);
        assertThat(report.capturing()).isEqualTo(1);
        assertThat(report.captured()).isEqualTo(4);
        assertThat(report.reviewed()).isEqualTo(3);
        assertThat(report.evaluable()).isEqualTo(1);
        assertThat(report.unreviewed()).isEqualTo(1);
        assertThat(report.tied()).isEqualTo(1);
        assertThat(report.invalid()).isEqualTo(1);
        assertThat(report.missingSignals()).isEqualTo(2);
        assertThat(report.missingMeasuredLatency()).isEqualTo(2);
        assertThat(report.missingBandwidth()).isEqualTo(2);
    }

    @Test
    void scenarioGroupTruthDisagreementIsCountedSeparately() {
        Entry agree = entryCaptured("r1", "NORMAL", "NORMAL");
        Entry disagree = entryCaptured("r2", "NORMAL", "ANOMALY");
        Report report = ResearchRunDataQuality.compute(List.of(agree, disagree));
        assertThat(report.scenarioGroundTruthAgreement()).isEqualTo(1);
        assertThat(report.scenarioGroundTruthDisagreement()).isEqualTo(1);
    }

    @Test
    void unexpectedSignalsAreCountedForCapturedSamples() {
        Entry unexpected = new Entry("r1", "CAPTURED", true, true, true, "NORMAL", "NORMAL",
                false, true, Set.of("TAB_SWITCH"), true, true, "lighting=GOOD");
        Entry clean = new Entry("r2", "CAPTURED", true, true, true, "TAB_SWITCH", "TAB_SWITCH",
                false, true, Set.of(), true, true, "network=STABLE");
        Report report = ResearchRunDataQuality.compute(List.of(unexpected, clean));
        assertThat(report.unexpectedSignals()).isEqualTo(1);
        assertThat(report.missingSignals()).isZero();
    }

    @Test
    void matrixAggregationIsDeterministicInScenarioOrder() {
        List<Entry> entries = List.of(
                new Entry("a", "PLANNED", false, false, false, null, "NORMAL",
                        false, false, Set.of(), false, false, "lighting=GOOD"),
                new Entry("b", "CAPTURED", true, true, true, "TAB_SWITCH", "TAB_SWITCH",
                        false, true, Set.of(), true, true, "network=STABLE"),
                new Entry("c", "CAPTURED", true, true, true, "MULTIPLE_FACES", "MULTIPLE_FACES",
                        false, true, Set.of(), true, true, "cameraAngle=FRONT"));

        List<MatrixRow> rows = ResearchRunDataQuality.matrix(entries);
        assertThat(rows).extracting(MatrixRow::scenario)
                .containsExactlyElementsOf(ResearchConfig.SCENARIO_ORDER);
        assertThat(rows.get(0).planned()).isEqualTo(1);
        assertThat(rows.get(2).captured()).isEqualTo(1);
        assertThat(rows.get(2).evaluable()).isEqualTo(1);
        MatrixRow tabSwitch = rows.stream().filter(row -> row.scenario().equals("TAB_SWITCH")).findFirst().orElseThrow();
        assertThat(tabSwitch.conditionsKey()).containsExactly("network=STABLE");
    }

    @Test
    void emptyRunDataQualityReportIsZeroed() {
        Report report = ResearchRunDataQuality.compute(List.of());
        assertThat(report.planned()).isZero();
        assertThat(report.captured()).isZero();
        assertThat(report.evaluable()).isZero();
        assertThat(report.invalid()).isZero();
    }

    private static Entry entry(String id, String status, boolean linked, boolean reviewed, boolean evaluable,
                               String scenario, boolean invalid, boolean hasSignals, boolean unexpected,
                               boolean hasLatency, boolean hasBandwidth) {
        return new Entry(id, status, linked, reviewed, evaluable,
                evaluable ? scenario : null, scenario, invalid, hasSignals,
                unexpected ? Set.of("TAB_SWITCH") : Set.of(), hasLatency, hasBandwidth, "k=v");
    }

    private static Entry entryCaptured(String id, String scenario, String resolvedLabel) {
        return new Entry(id, "CAPTURED", true, true, true, resolvedLabel, scenario,
                false, false, Set.of(), true, true, "k=v");
    }
}