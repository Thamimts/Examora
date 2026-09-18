package com.examora;

import com.examora.exception.ApiException;
import com.examora.service.ResearchConfig;
import com.examora.service.ResearchDataQuality;
import com.examora.service.ResearchDataQuality.Entry;
import com.examora.service.ResearchDataQuality.Report;
import com.examora.service.ResearchDataQuality.ScenarioOutcome;
import com.examora.service.ResearchValidation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchScenarioTest {

    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2030-01-01T00:00:01Z");
    private static final Instant T2 = Instant.parse("2030-01-01T00:00:02Z");
    private static final Instant T3 = Instant.parse("2030-01-01T00:00:03Z");

    @Test
    void everyAllowedScenarioPassesAllowList() {
        for (String scenario : ResearchConfig.SCENARIOS) {
            ResearchValidation.validateScenario(scenario);
        }
        ResearchValidation.validateScenario(null);
    }

    @Test
    void inventedScenarioIsRejected() {
        assertThatThrownBy(() -> ResearchValidation.validateScenario("INVENTED"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void scenarioLabelIsHumanReadableAndMissingScenarioHasNoLabel() {
        assertThat(ResearchValidation.scenarioLabel("NORMAL")).isEqualTo("Normal behavior");
        assertThat(ResearchValidation.scenarioLabel("NETWORK_INTERRUPTION")).isEqualTo("Network interruption");
        assertThat(ResearchValidation.scenarioLabel(null)).isNull();
    }

    @Test
    void expectedLabelEqualsTheScenarioItself() {
        assertThat(ResearchValidation.scenarioExpectedLabel("TAB_SWITCH")).isEqualTo("TAB_SWITCH");
        assertThat(ResearchValidation.scenarioExpectedLabel(null)).isNull();
    }

    @Test
    void scenarioAgreementRequiresExactResolvedMatch() {
        assertThat(ResearchValidation.scenarioAgrees("NORMAL", "NORMAL")).isTrue();
        assertThat(ResearchValidation.scenarioAgrees("NORMAL", "ANOMALY")).isFalse();
        assertThat(ResearchValidation.scenarioAgrees(null, "NORMAL")).isFalse();
        assertThat(ResearchValidation.scenarioAgrees("NORMAL", null)).isFalse();
    }

    @Test
    void measuredLatencyIsOptionalAndNonNegative() {
        ResearchValidation.validateMeasuredLatency(null);
        ResearchValidation.validateMeasuredLatency(0L);
        ResearchValidation.validateMeasuredLatency(59999L);
        assertThatThrownBy(() -> ResearchValidation.validateMeasuredLatency(-1L))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void datasetVersionIsOptionalButMustNotBeBlank() {
        ResearchValidation.validateDatasetVersion(null);
        ResearchValidation.validateDatasetVersion("dataset-v1");
        assertThatThrownBy(() -> ResearchValidation.validateDatasetVersion("  "))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void overlapDetectionMatchesRepositorySemantics() {
        assertThat(ResearchValidation.hasOverlap(T0, T1, T0, T1)).isTrue();
        assertThat(ResearchValidation.hasOverlap(T0, T2, T1, T3)).isTrue();
        assertThat(ResearchValidation.hasOverlap(T1, T3, T0, T2)).isTrue();
        assertThat(ResearchValidation.hasOverlap(T0, T1, T1, T2)).isFalse();
        assertThat(ResearchValidation.hasOverlap(T2, T3, T0, T1)).isFalse();
        assertThat(ResearchValidation.hasOverlap(null, T1, T0, T1)).isFalse();
    }

    @Test
    void majorityLabelResolvesStrictMajorityOnly() {
        assertThat(ResearchValidation.majorityLabel(List.of("ANOMALY"))).isEqualTo("ANOMALY");
        assertThat(ResearchValidation.majorityLabel(List.of("ANOMALY", "ANOMALY", "NORMAL")))
                .isEqualTo("ANOMALY");
        assertThat(ResearchValidation.majorityLabel(List.of("ANOMALY", "NORMAL"))).isNull();
        assertThat(ResearchValidation.majorityLabel(List.of("ANOMALY", "NORMAL", "GAZE_ANOMALY"))).isNull();
        assertThat(ResearchValidation.majorityLabel(List.of())).isNull();
        assertThat(ResearchValidation.majorityLabel(null)).isNull();
    }

    @Test
    void dataQualityCountsEveryFlagWithoutDiscarding() {
        Entry complete = entry("s1", "NORMAL", "NORMAL", true, false, true, true, true, true, true);
        Entry unlinked = entry("s2", "TAB_SWITCH", "TAB_SWITCH", true, false, false, false, true, false, true);
        Entry tied = entry("s3", "WINDOW_BLUR", null, true, false, true, true, true, true, true);
        Entry invalid = entry("s4", "CAMERA_OFF", "CAMERA_OFF", true, true, true, true, true, true, true);
        Entry mismatch = entry("s5", "NORMAL", "ANOMALY", true, false, true, false, false, false, false);
        Entry unscenario = entry("s6", null, "NORMAL", true, false, true, true, true, true, true);
        Entry unreviewed = entry("s7", "AUDIO_DETECTED", null, false, false, true, true, true, true, true);

        Report report = ResearchDataQuality.compute(
                List.of(complete, unlinked, tied, invalid, mismatch, unscenario, unreviewed));

        assertThat(report.registered()).isEqualTo(7);
        assertThat(report.evaluable()).isEqualTo(4);
        assertThat(report.unreviewed()).isEqualTo(1);
        assertThat(report.tied()).isEqualTo(1);
        assertThat(report.invalid()).isEqualTo(1);
        assertThat(report.scenarioGroundTruthAgreement()).isEqualTo(2);
        assertThat(report.missingSignals()).isEqualTo(1);
        assertThat(report.missingCondition()).isEqualTo(1);
        assertThat(report.missingMeasuredLatency()).isEqualTo(2);
        assertThat(report.missingBandwidth()).isEqualTo(1);
    }

    @Test
    void dataQualityScenarioOutcomesCarryExpectedLabels() {
        Report report = ResearchDataQuality.compute(List.of(
                entry("s1", "NORMAL", "NORMAL", true, false, true, true, true, true, true),
                entry("s2", "NORMAL", "ANOMALY", true, false, true, true, true, true, true),
                entry("s3", "TAB_SWITCH", "TAB_SWITCH", true, false, false, false, true, false, true)));

        ScenarioOutcome normal = report.scenarios().stream()
                .filter(outcome -> outcome.scenario().equals("NORMAL")).findFirst().orElseThrow();
        assertThat(normal.label()).isEqualTo("Normal behavior");
        assertThat(normal.expectedLabel()).isEqualTo("NORMAL");
        assertThat(normal.sampleCount()).isEqualTo(2);
        assertThat(normal.resolvedCount()).isEqualTo(2);
        assertThat(normal.agreementCount()).isEqualTo(1);

        ScenarioOutcome tabSwitch = report.scenarios().stream()
                .filter(outcome -> outcome.scenario().equals("TAB_SWITCH")).findFirst().orElseThrow();
        assertThat(tabSwitch.expectedLabel()).isEqualTo("TAB_SWITCH");
        assertThat(tabSwitch.agreementCount()).isEqualTo(1);
    }

    @Test
    void emptyDataQualityReportIsZeroed() {
        Report report = ResearchDataQuality.compute(List.of());
        assertThat(report.registered()).isZero();
        assertThat(report.evaluable()).isZero();
        assertThat(report.scenarios()).isEmpty();
    }

    private static Entry entry(String id, String scenario, String label, boolean reviewed, boolean invalid,
                               boolean linked, boolean hasSignals, boolean hasCondition,
                               boolean hasLatency, boolean hasBandwidth) {
        return new Entry(id, scenario, label, reviewed, invalid, linked, hasSignals, hasCondition,
                hasLatency, hasBandwidth);
    }
}