package com.examora;

import com.examora.exception.ApiException;
import com.examora.service.ProctorFusionService;
import com.examora.service.ResearchConfig;
import com.examora.service.ResearchEvaluationEngine;
import com.examora.service.ResearchEvaluationEngine.ConditionGroup;
import com.examora.service.ResearchEvaluationEngine.SampleEvaluation;
import com.examora.service.ResearchEvaluationEngine.StudyEvaluation;
import com.examora.service.ResearchMetrics;
import com.examora.service.ResearchValidation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ResearchEvaluationTest {

    private static final Instant BASE = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant WINDOW_START = BASE;
    private static final Instant WINDOW_END = BASE.plusSeconds(10);

    private ResearchEvaluationEngine engine;
    private ProctorFusionService fusionService;

    @BeforeEach
    void setUp() {
        fusionService = new ProctorFusionService();
        engine = new ResearchEvaluationEngine(fusionService, ResearchConfig.FUSION_THRESHOLD);
    }

    @Test
    void emptyDatasetYieldsNoMetric() {
        StudyEvaluation study = engine.evaluate(List.of(), ResearchConfig.BASELINE_VERSION, ResearchConfig.ALGORITHM_VERSION);

        assertThat(study.totalSamples()).isZero();
        assertThat(study.evaluatedSamples()).isZero();
        assertThat(study.unevaluatedSamples()).isZero();
        assertThat(study.baseline().metrics().precision()).isNull();
        assertThat(study.baseline().metrics().recall()).isNull();
        assertThat(study.baseline().metrics().f1()).isNull();
        assertThat(study.fusion().metrics().precision()).isNull();
        assertThat(study.latency().measuredCount()).isZero();
        assertThat(study.bandwidth().measuredCount()).isZero();
    }

    @Test
    void allNormalSamplesAreTrueNegatives() {
        SampleEvaluation a = sample("s1", false, false, List.of(), 1000L, 100L, "NORMAL");
        SampleEvaluation b = sample("s2", false, false, List.of(), 1000L, 100L, "NORMAL");

        StudyEvaluation study = evaluate(a, b);

        assertThat(study.evaluatedSamples()).isEqualTo(2);
        assertThat(study.baseline().confusion().trueNegatives()).isEqualTo(2);
        assertThat(study.baseline().confusion().falsePositives()).isZero();
        assertThat(study.fusion().confusion().trueNegatives()).isEqualTo(2);
        assertThat(study.fusion().metrics().specificity()).isEqualTo(1.0);
    }

    @Test
    void allAnomalySamplesWithEvidenceAreTruePositives() {
        SampleEvaluation a = sample("s1", true, false, List.of(strongSignal("ev1", BASE.plusSeconds(1))), 100L, 90L, "ANOMALY");
        SampleEvaluation b = sample("s2", true, false, List.of(strongSignal("ev2", BASE.plusSeconds(2))), 100L, 90L, "ANOMALY");

        StudyEvaluation study = evaluate(a, b);

        assertThat(study.baseline().confusion().truePositives()).isEqualTo(2);
        assertThat(study.fusion().confusion().truePositives()).isEqualTo(2);
        assertThat(study.fusion().confusion().falseNegatives()).isZero();
        assertThat(study.fusion().metrics().recall()).isEqualTo(1.0);
    }

    @Test
    void perfectPredictionsScoreOne() {
        SampleEvaluation tp = sample("s1", true, false, List.of(strongSignal("ev1", BASE.plusSeconds(1))), 100L, 90L, "ANOMALY");
        SampleEvaluation tn = sample("s2", false, false, List.of(), 100L, 0L, "NORMAL");

        StudyEvaluation study = evaluate(tp, tn);

        assertThat(study.baseline().metrics().precision()).isEqualTo(1.0);
        assertThat(study.baseline().metrics().recall()).isEqualTo(1.0);
        assertThat(study.baseline().metrics().f1()).isEqualTo(1.0);
        assertThat(study.fusion().metrics().precision()).isEqualTo(1.0);
        assertThat(study.fusion().metrics().accuracy()).isEqualTo(1.0);
        assertThat(study.fusion().metrics().wrongfulWarningRate()).isZero();
    }

    @Test
    void allNormalSamplesWithSignalsAreFalsePositives() {
        SampleEvaluation a = sample("s1", false, false, List.of(strongSignal("ev1", BASE.plusSeconds(1))), 100L, 90L, "NORMAL");
        SampleEvaluation b = sample("s2", false, false, List.of(strongSignal("ev2", BASE.plusSeconds(2))), 100L, 90L, "NORMAL");

        StudyEvaluation study = evaluate(a, b);

        assertThat(study.baseline().confusion().falsePositives()).isEqualTo(2);
        assertThat(study.baseline().metrics().precision()).isZero();
        assertThat(study.fusion().confusion().falsePositives()).isEqualTo(2);
        assertThat(study.fusion().metrics().wrongfulWarningRate()).isEqualTo(1.0);
    }

    @Test
    void anomalySamplesWithoutSignalsAreFalseNegatives() {
        SampleEvaluation a = sample("s1", true, false, List.of(), 100L, 0L, "ANOMALY");
        SampleEvaluation b = sample("s2", true, false, List.of(), 100L, 0L, "ANOMALY");

        StudyEvaluation study = evaluate(a, b);

        assertThat(study.baseline().confusion().falseNegatives()).isEqualTo(2);
        assertThat(study.baseline().metrics().recall()).isZero();
        assertThat(study.fusion().confusion().truePositives()).isZero();
        assertThat(study.fusion().metrics().falseNegativeRate()).isEqualTo(1.0);
    }

    @Test
    void mixedDatasetProducesExpectedConfusion() {
        SampleEvaluation tp = sample("s1", true, false, List.of(strongSignal("ev1", BASE.plusSeconds(1))), 100L, 90L, "ANOMALY");
        SampleEvaluation fn = sample("s2", true, false, List.of(), 100L, 0L, "ANOMALY");
        SampleEvaluation fp = sample("s3", false, false, List.of(strongSignal("ev3", BASE.plusSeconds(3))), 100L, 90L, "NORMAL");
        SampleEvaluation tn = sample("s4", false, false, List.of(), 100L, 0L, "NORMAL");

        StudyEvaluation study = evaluate(tp, fn, fp, tn);

        assertThat(study.baseline().confusion()).isEqualTo(new ResearchMetrics.Confusion(1, 1, 1, 1));
        assertThat(study.baseline().metrics().precision()).isEqualTo(0.5);
        assertThat(study.baseline().metrics().recall()).isEqualTo(0.5);
        assertThat(study.baseline().metrics().f1()).isEqualTo(0.5);
        assertThat(study.baseline().metrics().accuracy()).isEqualTo(0.5);
        assertThat(study.baseline().metrics().falsePositiveRate()).isEqualTo(0.5);
        assertThat(study.baseline().metrics().falseNegativeRate()).isEqualTo(0.5);
        assertThat(study.baseline().metrics().specificity()).isEqualTo(0.5);
        assertThat(study.baseline().metrics().wrongfulWarningRate()).isEqualTo(0.5);
    }

    @Test
    void fusionBeatsBaselineOnFalsePositiveRichData() {
        SampleEvaluation tp = sample("s1", true, false, List.of(strongSignal("ev1", BASE.plusSeconds(1))), 100L, 90L, "ANOMALY");
        SampleEvaluation weakFp = sample("s2", false, false, List.of(weakSignal("ev2", BASE.plusSeconds(2))), 100L, 90L, "NORMAL");

        StudyEvaluation study = evaluate(tp, weakFp);

        assertThat(study.baseline().confusion().falsePositives()).isEqualTo(1);
        assertThat(study.fusion().confusion().falsePositives()).isZero();
        assertThat(study.baseline().metrics().precision()).isEqualTo(0.5);
        assertThat(study.fusion().metrics().precision()).isEqualTo(1.0);
    }

    @Test
    void metricsWithZeroDenominatorsAreNullNotNan() {
        StudyEvaluation study = engine.evaluate(List.of(), ResearchConfig.BASELINE_VERSION, ResearchConfig.ALGORITHM_VERSION);

        assertThat(study.baseline().metrics().precision()).isNull();
        assertThat(study.baseline().metrics().recall()).isNull();
        assertThat(study.baseline().metrics().wrongfulWarningRate()).isNull();
        assertThat(study.baseline().confusion().total()).isZero();
    }

    @Test
    void evaluationIsDeterministic() {
        List<SampleEvaluation> inputs = List.of(
                sample("s1", true, false, List.of(strongSignal("ev1", BASE.plusSeconds(1))), 100L, 90L, "ANOMALY"),
                sample("s2", false, false, List.of(), 100L, 0L, "NORMAL"));

        StudyEvaluation first = engine.evaluate(inputs, ResearchConfig.BASELINE_VERSION, ResearchConfig.ALGORITHM_VERSION);
        StudyEvaluation second = engine.evaluate(inputs, ResearchConfig.BASELINE_VERSION, ResearchConfig.ALGORITHM_VERSION);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void latencyUsesEarliestInWindowSignal() {
        SampleEvaluation a = sample("s1", true, false,
                List.of(strongSignal("ev-a1", BASE.plusSeconds(1)), strongSignal("ev-a2", BASE.plusSeconds(4))),
                100L, 90L, "ANOMALY");
        SampleEvaluation b = sample("s2", false, false, List.of(), 100L, 0L, "NORMAL");

        StudyEvaluation study = evaluate(a, b);

        assertThat(study.latency().measuredCount()).isEqualTo(1);
        assertThat(study.latency().meanMs()).isCloseTo(1000.0, within(1e-9));
        assertThat(study.latency().minMs()).isCloseTo(1000.0, within(1e-9));
        assertThat(study.latency().maxMs()).isCloseTo(1000.0, within(1e-9));
    }

    @Test
    void medianLatencyAveragesMiddleTwoOnEvenCount() {
        SampleEvaluation a = sample("s1", true, false, List.of(strongSignal("ev1", BASE.plusSeconds(1))), 100L, 90L, "ANOMALY");
        SampleEvaluation b = sample("s2", true, false, List.of(strongSignal("ev2", BASE.plusSeconds(2))), 100L, 90L, "ANOMALY");
        SampleEvaluation c = sample("s3", true, false, List.of(strongSignal("ev3", BASE.plusSeconds(3))), 100L, 90L, "ANOMALY");
        SampleEvaluation d = sample("s4", true, false, List.of(strongSignal("ev4", BASE.plusSeconds(4))), 100L, 90L, "ANOMALY");

        StudyEvaluation study = evaluate(a, b, c, d);

        assertThat(study.latency().measuredCount()).isEqualTo(4);
        assertThat(study.latency().medianMs()).isCloseTo(2500.0, within(1e-9));
    }

    @Test
    void bandwidthRatioIsReportedOnlyWhenRawMediaMeasured() {
        SampleEvaluation a = sample("s1", true, false, List.of(strongSignal("ev1", BASE.plusSeconds(1))), 1000L, 200L, "ANOMALY");
        SampleEvaluation b = sample("s2", false, false, List.of(), 800L, 160L, "NORMAL");

        StudyEvaluation study = evaluate(a, b);

        assertThat(study.bandwidth().measuredCount()).isEqualTo(2);
        assertThat(study.bandwidth().meanDataMinimizationRatio()).isCloseTo(0.8, within(1e-9));
        assertThat(study.bandwidth().meanRawMediaBytes()).isCloseTo(900.0, within(1e-9));
        assertThat(study.bandwidth().meanSignalBytes()).isCloseTo(180.0, within(1e-9));
    }

    @Test
    void bandwidthIsEmptyWhenRawMediaBytesMissing() {
        SampleEvaluation a = sampleWithoutBandwidth("s1", false, List.of(), "NORMAL");

        StudyEvaluation study = evaluate(a);

        assertThat(study.bandwidth().measuredCount()).isZero();
        assertThat(study.bandwidth().meanDataMinimizationRatio()).isNull();
    }

    @Test
    void conditionStratificationGroupsByConditionValue() {
        SampleEvaluation a = sample("s1", true, false, List.of(strongSignal("ev1", BASE.plusSeconds(1))), 100L, 90L, "NORMAL");
        SampleEvaluation b = sample("s2", false, false, List.of(), 100L, 0L, "LOW");
        SampleEvaluation c = sample("s3", false, false, List.of(), 100L, 0L, "LOW");

        List<ConditionGroup> groups = engine.evaluateByCondition(List.of(a, b, c),
                ResearchConfig.BASELINE_VERSION, ResearchConfig.ALGORITHM_VERSION);

        assertThat(groups).hasSize(2);
        ConditionGroup normal = groups.stream().filter(g -> g.conditionValue().equals("NORMAL")).findFirst().orElseThrow();
        ConditionGroup low = groups.stream().filter(g -> g.conditionValue().equals("LOW")).findFirst().orElseThrow();
        assertThat(normal.sampleCount()).isEqualTo(1);
        assertThat(low.sampleCount()).isEqualTo(2);
    }

    @Test
    void duplicateSampleIdsAreRejected() {
        SampleEvaluation a = sample("dup", true, false, List.of(), 100L, 0L, "ANOMALY");
        SampleEvaluation b = sample("dup", false, false, List.of(), 100L, 0L, "NORMAL");

        assertThatThrownBy(() -> engine.evaluate(List.of(a, b),
                ResearchConfig.BASELINE_VERSION, ResearchConfig.ALGORITHM_VERSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate sample id");
    }

    @Test
    void windowMustBePositiveBoundedAndRecent() {
        Instant now = BASE;

        assertThatThrownBy(() -> ResearchValidation.validateWindow(WINDOW_END, WINDOW_START, now))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ResearchValidation.validateWindow(BASE, BASE.plusMillis(500), now))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("duration");
        assertThatThrownBy(() -> ResearchValidation.validateWindow(BASE.plusSeconds(60), BASE.plusSeconds(90), now))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("future");
        assertThatThrownBy(() -> ResearchValidation.validateWindow(null, WINDOW_END, now))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void validityConstraintsAreEnforced() {
        assertThatThrownBy(() -> ResearchValidation.validateConfidence(1.5))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("confidence");
        assertThatThrownBy(() -> ResearchValidation.validateConfidence(Double.NaN))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ResearchValidation.validateLabel("CHEATER"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ResearchValidation.validateConditionMetadata(Map.of("facialExpression", "HAPPY")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("condition");
        assertThatThrownBy(() -> ResearchValidation.validateByteCounts(null, 10L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("together");
        assertThatThrownBy(() -> ResearchValidation.validateByteCounts(10L, -5L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void labelSemanticsAreExactlyNeutral() {
        assertThat(ResearchValidation.isNegativeLabel("NORMAL")).isTrue();
        assertThat(ResearchValidation.isNegativeLabel("NO_ANOMALY")).isTrue();
        assertThat(ResearchValidation.isNegativeLabel("ANOMALY")).isFalse();
        assertThat(ResearchValidation.isNegativeLabel("SUM")).isFalse();
        assertThat(ResearchConfig.POSITIVE_LABELS)
                .noneMatch(label -> label.contains("CHEAT"))
                .noneMatch(label -> label.contains("PROBABIL"))
                .noneMatch(label -> label.contains("TRUST"));
    }

    @Test
    void evaluationCarriesBothVersions() {
        SampleEvaluation a = sample("s1", true, false, List.of(strongSignal("ev1", BASE.plusSeconds(1))), 100L, 90L, "ANOMALY");

        StudyEvaluation study = evaluate(a);

        assertThat(study.baseline().version()).isEqualTo("baseline-v1");
        assertThat(study.fusion().version()).isEqualTo("fusion-v1");
    }

    private StudyEvaluation evaluate(SampleEvaluation... samples) {
        return engine.evaluate(List.of(samples), ResearchConfig.BASELINE_VERSION, ResearchConfig.ALGORITHM_VERSION);
    }

    private SampleEvaluation sample(String id, boolean actualPositive, boolean reviewed,
                                    List<ProctorFusionService.FusionSignal> signals,
                                    Long rawMediaBytes, Long signalBytes, String conditionValue) {
        return new SampleEvaluation(id, WINDOW_START, WINDOW_END, actualPositive, reviewed,
                signals, rawMediaBytes, signalBytes, conditionValue);
    }

    private SampleEvaluation sampleWithoutBandwidth(String id, boolean actualPositive,
                                                    List<ProctorFusionService.FusionSignal> signals,
                                                    String conditionValue) {
        return new SampleEvaluation(id, WINDOW_START, WINDOW_END, actualPositive, false,
                signals, null, null, conditionValue);
    }

    private static ProctorFusionService.FusionSignal strongSignal(String id, Instant at) {
        return new ProctorFusionService.FusionSignal(id, "MULTIPLE_FACES", "CLIENT_AI", at.toString(), 1.0, null);
    }

    private static ProctorFusionService.FusionSignal weakSignal(String id, Instant at) {
        return new ProctorFusionService.FusionSignal(id, "NETWORK_INTERRUPTION", "SYSTEM", at.toString(), null, null);
    }
}