package com.examora;

import com.examora.service.ProctorFusionService;
import com.examora.service.ResearchConfig;
import com.examora.service.ResearchEvaluationEngine;
import com.examora.service.ResearchEvaluationEngine.ConditionGroup;
import com.examora.service.ResearchEvaluationEngine.SampleEvaluation;
import com.examora.service.ResearchEvaluationEngine.StudyEvaluation;
import com.examora.service.ResearchMetrics;
import com.examora.service.ResearchMetrics.Confusion;
import com.examora.service.ResearchValidation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the first controlled experiment evaluation surfaces: the informational
 * pilot target, human-review agreement states, FDR/false-discovery metrics, zero-denominator
 * null semantics (never NaN/Infinity), determinism, latency/bandwidth aggregation, and
 * condition-grouped breakdowns. No Spring context, no fabricated research data.
 */
class ResearchRunEvaluationTest {

    private static final Instant BASE = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant WINDOW_START = BASE;
    private static final Instant WINDOW_END = BASE.plusSeconds(10);

    private ResearchEvaluationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ResearchEvaluationEngine(new ProctorFusionService(), ResearchConfig.FUSION_THRESHOLD);
    }

    @Test
    void pilotTargetIsInformationalAt80AcrossEightScenarios() {
        assertThat(ResearchConfig.SCENARIO_ORDER).hasSize(8);
        assertThat(ResearchConfig.TARGET_SAMPLES_PER_SCENARIO).isEqualTo(10);
        assertThat(ResearchConfig.TARGET_EXPERIMENT_SAMPLES).isEqualTo(80);
        assertThat(ResearchConfig.CONTROLLED_CONDITION_ORDER)
                .containsExactly("lighting", "cameraQuality", "network", "cameraAngle");
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            assertThat(ResearchConfig.CONTROLLED_CONDITIONS.get(key)).isNotNull();
        }
    }

    @Test
    void agreementStateReflectsUnreviewedAgreedTiedAndResolved() {
        assertThat(ResearchValidation.agreementState(0, null, false)).isEqualTo("UNREVIEWED");
        assertThat(ResearchValidation.agreementState(1, "NORMAL", true)).isEqualTo("AGREED");
        assertThat(ResearchValidation.agreementState(2, "NORMAL", true)).isEqualTo("AGREED");
        assertThat(ResearchValidation.agreementState(2, null, false)).isEqualTo("TIED");
        assertThat(ResearchValidation.agreementState(3, "ANOMALY", false)).isEqualTo("RESOLVED");
    }

    @Test
    void fdrEqualsWrongfulWarningRateOnMixedConfusion() {
        ResearchMetrics.EvaluatorMetrics metrics = ResearchMetrics.compute(
                new Confusion(8, 10, 2, 0));
        assertThat(metrics.fdr()).isEqualByComparingTo(2.0 / 10.0);
        assertThat(metrics.wrongfulWarningRate()).isEqualByComparingTo(metrics.fdr());
        assertThat(metrics.precision()).isEqualByComparingTo(0.8);
    }

    @Test
    void fdrIsNullWhenNothingPredictedPositive() {
        ResearchMetrics.EvaluatorMetrics metrics = ResearchMetrics.compute(
                new Confusion(0, 5, 0, 0));
        assertThat(metrics.fdr()).isNull();
        assertThat(metrics.precision()).isNull();
        assertThat(metrics.falsePositiveRate()).isEqualByComparingTo(0.0);
        assertThat(metrics.specificity()).isEqualByComparingTo(1.0);
    }

    @Test
    void extremeConfusionReportsFiniteMetricsIncludingFdrOne() {
        Confusion c = new Confusion(0, 0, 3, 2);
        ResearchMetrics.EvaluatorMetrics metrics = ResearchMetrics.compute(c);
        assertThat(metrics.fdr()).isEqualByComparingTo(1.0);
        assertThat(metrics.precision()).isEqualByComparingTo(0.0);
        assertThat(metrics.recall()).isEqualByComparingTo(0.0);
        assertThat(metrics.specificity()).isEqualByComparingTo(0.0);
        assertThat(metrics.f1()).isEqualByComparingTo(0.0);
        assertThat(metrics.falsePositiveRate()).isEqualByComparingTo(1.0);
        assertThat(metrics.falseNegativeRate()).isEqualByComparingTo(1.0);
        assertThat(allFinite(metrics)).isTrue();
    }

    @Test
    void evaluatedMetricsNeverContainNaNOrInfinity() {
        List<Confusion> confusions = List.of(
                new Confusion(0, 0, 0, 0),
                new Confusion(3, 4, 1, 2),
                new Confusion(0, 10, 2, 0),
                new Confusion(5, 0, 0, 7));
        for (Confusion c : confusions) {
            assertThat(allFinite(ResearchMetrics.compute(c))).isTrue();
        }
    }

    @Test
    void evaluationIsDeterministicAcrossIdenticalInputs() {
        List<SampleEvaluation> inputs = List.of(
                sample("a", true, List.of(signal("ev-a", "TAB_SWITCH", "BROWSER", null, WINDOW_START.plusSeconds(2))), 1000L, 100L),
                sample("b", false, List.of(), 1000L, 100L));

        StudyEvaluation first = engine.evaluate(inputs, ResearchConfig.BASELINE_VERSION, ResearchConfig.ALGORITHM_VERSION);
        StudyEvaluation second = engine.evaluate(inputs, ResearchConfig.BASELINE_VERSION, ResearchConfig.ALGORITHM_VERSION);

        assertThat(first).isEqualTo(second);
        assertThat(allFiniteMetrics(first)).isTrue();
    }

    @Test
    void conditionGroupsOnlyContainValuesActuallyPresent() {
        List<SampleEvaluation> inputs = List.of(
                sample("a", true, List.of(signal("ev-a", "TAB_SWITCH", "BROWSER", null, WINDOW_START.plusSeconds(1))), 1000L, 100L),
                sample("b", false, List.of(), 1000L, 100L));

        List<ConditionGroup> groups = engine.evaluateByCondition(
                withCondition(inputs, "GOOD"),
                ResearchConfig.BASELINE_VERSION, ResearchConfig.ALGORITHM_VERSION);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).conditionValue()).isEqualTo("GOOD");
        assertThat(groups.get(0).sampleCount()).isEqualTo(2);
        assertThat(groups.get(0).baseline().metrics().fdr()).isEqualByComparingTo(0.0);
        assertThat(groups.get(0).fusion().metrics().fdr()).isNull();
        assertThat(allFinite(groups.get(0).fusion().metrics())).isTrue();

        assertThat(engine.evaluateByCondition(inputs, ResearchConfig.BASELINE_VERSION,
                ResearchConfig.ALGORITHM_VERSION)).isEmpty();
    }

    @Test
    void latencyStatsExposeMeanMedianMinMax() {
        ResearchMetrics.LatencyStats stats = ResearchMetrics.latency(List.of(50L, 10L, 30L));
        assertThat(stats.measuredCount()).isEqualTo(3);
        assertThat(stats.meanMs()).isEqualByComparingTo(30.0);
        assertThat(stats.medianMs()).isEqualByComparingTo(30.0);
        assertThat(stats.minMs()).isEqualByComparingTo(10.0);
        assertThat(stats.maxMs()).isEqualByComparingTo(50.0);
    }

    @Test
    void latencyStatsEmptyWhenNoMeasurements() {
        ResearchMetrics.LatencyStats stats = ResearchMetrics.latency(List.of());
        assertThat(stats.measuredCount()).isZero();
        assertThat(stats.meanMs()).isNull();
        assertThat(stats.minMs()).isNull();
        assertThat(stats.maxMs()).isNull();
    }

    @Test
    void bandwidthStatsEmptyWhenNothingMeasured() {
        ResearchMetrics.BandwidthStats stats = ResearchMetrics.bandwidth(List.of());
        assertThat(stats.measuredCount()).isZero();
        assertThat(stats.meanSignalBytes()).isNull();
        assertThat(stats.meanRawMediaBytes()).isNull();
        assertThat(stats.meanDataMinimizationRatio()).isNull();
    }

    @Test
    void bandwidthStatsAggregateAcrossSamples() {
        ResearchMetrics.BandwidthStats stats = ResearchMetrics.bandwidth(
                List.of(new long[]{1000L, 100L}, new long[]{500L, 50L}));
        assertThat(stats.measuredCount()).isEqualTo(2);
        assertThat(stats.meanRawMediaBytes()).isEqualByComparingTo(750.0);
        assertThat(stats.meanSignalBytes()).isEqualByComparingTo(75.0);
        assertThat(stats.meanDataMinimizationRatio()).isEqualByComparingTo(0.9);
    }

    @Test
    void zeroDenominatorCellsReportNullNotNaN() {
        SampleEvaluation negativeOnly = sample("n1", false, List.of(), null, null);
        SampleEvaluation unreviewed = new SampleEvaluation("n2", WINDOW_START, WINDOW_END, null,
                false, List.of(), null, null, null);

        StudyEvaluation study = engine.evaluate(List.of(negativeOnly, unreviewed),
                ResearchConfig.BASELINE_VERSION, ResearchConfig.ALGORITHM_VERSION);

        assertThat(study.evaluatedSamples()).isEqualTo(1);
        assertThat(study.baseline().confusion().trueNegatives()).isEqualTo(1);
        assertThat(study.baseline().metrics().precision()).isNull();
        assertThat(study.baseline().metrics().recall()).isNull();
        assertThat(study.baseline().metrics().f1()).isNull();
        assertThat(study.baseline().metrics().fdr()).isNull();
        assertThat(study.latency().measuredCount()).isZero();
        assertThat(study.bandwidth().measuredCount()).isZero();
    }

    private List<SampleEvaluation> withCondition(List<SampleEvaluation> inputs, String value) {
        return inputs.stream()
                .map(s -> new SampleEvaluation(s.sampleId(), s.windowStart(), s.windowEnd(),
                        s.actualPositive(), s.reviewed(), s.signals(), s.rawMediaBytes(),
                        s.signalBytes(), value))
                .toList();
    }

    private SampleEvaluation sample(String id, boolean actualPositive,
                                    List<ProctorFusionService.FusionSignal> signals,
                                    Long rawMediaBytes, Long signalBytes) {
        return new SampleEvaluation(id, WINDOW_START, WINDOW_END, actualPositive, true,
                signals, rawMediaBytes, signalBytes, null);
    }

    private static ProctorFusionService.FusionSignal signal(String id, String type, String source,
                                                            Double confidence, Instant at) {
        return new ProctorFusionService.FusionSignal(id, type, source, at.toString(), confidence, 500L);
    }

    private static boolean allFinite(ResearchMetrics.EvaluatorMetrics metrics) {
        return finiteOrNull(metrics.precision()) && finiteOrNull(metrics.recall())
                && finiteOrNull(metrics.specificity()) && finiteOrNull(metrics.accuracy())
                && finiteOrNull(metrics.falsePositiveRate()) && finiteOrNull(metrics.falseNegativeRate())
                && finiteOrNull(metrics.f1()) && finiteOrNull(metrics.wrongfulWarningRate())
                && finiteOrNull(metrics.fdr());
    }

    private static boolean allFiniteMetrics(StudyEvaluation study) {
        return allFinite(study.baseline().metrics()) && allFinite(study.fusion().metrics());
    }

    private static boolean finiteOrNull(Double value) {
        return value == null || Double.isFinite(value);
    }
}