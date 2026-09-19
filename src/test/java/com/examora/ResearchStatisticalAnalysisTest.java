package com.examora;

import com.examora.service.ProctorFusionService.FusionSignal;
import com.examora.service.ResearchMetrics;
import com.examora.service.ResearchStatisticalAnalysis;
import com.examora.service.ResearchStatisticalAnalysis.AnalysisSample;
import com.examora.service.ResearchStatisticalAnalysis.Confidence;
import com.examora.service.ResearchStatisticalAnalysis.ConfidenceInterval;
import com.examora.service.ResearchStatisticalAnalysis.DataQualityCounts;
import com.examora.service.ResearchStatisticalAnalysis.MetricDelta;
import com.examora.service.ResearchStatisticalAnalysis.Report;
import com.examora.service.ResearchStatisticalAnalysis.ReviewObservation;
import com.examora.service.ResearchStatisticalAnalysis.DisagreementSummary;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ResearchStatisticalAnalysisTest {

    private static final String DATASET_VERSION = "dataset-v1";
    private static final String BASELINE_VERSION = "baseline-v1";
    private static final String FUSION_VERSION = "fusion-v1";
    private static final Instant BASE = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void emptyDatasetProducesEmptyReportWithStableVersionsAndDeterminism() {
        DataQualityCounts empty = new DataQualityCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        Report first = ResearchStatisticalAnalysis.analyze(List.of(), List.of(), empty,
                DATASET_VERSION, BASELINE_VERSION, FUSION_VERSION);
        Report second = ResearchStatisticalAnalysis.analyze(List.of(), List.of(), empty,
                DATASET_VERSION, BASELINE_VERSION, FUSION_VERSION);

        assertThat(first.analysisVersion()).isEqualTo("analysis-v1");
        assertThat(first.datasetVersion()).isEqualTo(DATASET_VERSION);
        assertThat(first.baselineVersion()).isEqualTo(BASELINE_VERSION);
        assertThat(first.fusionVersion()).isEqualTo(FUSION_VERSION);
        assertThat(first.baseline().confusion().total()).isZero();
        assertThat(first.baseline().metrics().precision()).isNull();
        assertThat(first.baseline().metrics().accuracy()).isNull();
        assertThat(first.fusion().metrics().precision()).isNull();
        assertThat(first.metricDeltas()).allMatch(d -> d.delta() == null);
        assertThat(first.confidence()).isEmpty();
        assertThat(first.latency().measured().measuredCount()).isZero();
        assertThat(first.latency().storedWindow().measuredCount()).isZero();
        assertThat(first.bandwidth().rawMedia().measuredCount()).isZero();
        assertThat(first.bandwidth().signalBytes().measuredCount()).isZero();
        assertThat(first.reviewAgreement().reviewedSamples()).isZero();
        assertThat(first.reviewAgreement().agreementRate()).isNull();
        assertThat(first.confidenceIntervals()).isEmpty();
        assertThat(first.disagreementSamples()).isEmpty();

        Report strippedFirst = stripGeneratedAt(first);
        Report strippedSecond = stripGeneratedAt(second);
        assertThat(strippedFirst).isEqualTo(strippedSecond);
    }

    @Test
    void perfectClassifierConfusionAndMetricsArePerfect() {
        AnalysisSample tp1 = sample("s1", "TAB_SWITCH", true, true, List.of(), 1000L, 100L, BASE);
        AnalysisSample tp2 = sample("s2", "CAMERA_OFF", true, true, List.of(), 1000L, 100L, BASE);
        AnalysisSample tn1 = sample("s3", "NORMAL", false, false, List.of(), 1000L, 100L, BASE);
        AnalysisSample tn2 = sample("s4", "NORMAL", false, false, List.of(), 1000L, 100L, BASE);

        Report report = analyze(List.of(tp1, tp2, tn1, tn2));

        assertThat(report.baseline().confusion()).isEqualTo(new ResearchMetrics.Confusion(2, 2, 0, 0));
        assertThat(report.baseline().confusion().total()).isEqualTo(4);
        assertThat(report.baseline().metrics().precision()).isEqualTo(1.0);
        assertThat(report.baseline().metrics().recall()).isEqualTo(1.0);
        assertThat(report.baseline().metrics().specificity()).isEqualTo(1.0);
        assertThat(report.baseline().metrics().accuracy()).isEqualTo(1.0);
        assertThat(report.baseline().metrics().falsePositiveRate()).isEqualTo(0.0);
        assertThat(report.baseline().metrics().falseNegativeRate()).isEqualTo(0.0);
        assertThat(report.baseline().metrics().f1()).isEqualTo(1.0);
        assertThat(report.baseline().metrics().wrongfulWarningRate()).isEqualTo(0.0);
    }

    @Test
    void allPositivePredictionsYieldExpectedConfusion() {
        AnalysisSample a = sample("s1", "TAB_SWITCH", true, true, List.of(), 0L, 0L, BASE);
        AnalysisSample b = sample("s2", "NORMAL", false, true, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(a, b));

        assertThat(report.fusion().confusion().truePositives()).isEqualTo(1);
        assertThat(report.fusion().confusion().falsePositives()).isEqualTo(1);
        assertThat(report.fusion().confusion().trueNegatives()).isZero();
        assertThat(report.fusion().metrics().precision()).isEqualTo(0.5);
        assertThat(report.fusion().metrics().recall()).isEqualTo(1.0);
        assertThat(report.fusion().metrics().specificity()).isEqualTo(0.0);
        assertThat(report.fusion().metrics().falsePositiveRate()).isEqualTo(1.0);
    }

    @Test
    void allNegativePredictionsYieldExpectedConfusion() {
        AnalysisSample a = sample("s1", "TAB_SWITCH", true, false, List.of(), 0L, 0L, BASE);
        AnalysisSample b = sample("s2", "CAMERA_OFF", true, false, List.of(), 0L, 0L, BASE);
        AnalysisSample c = sample("s3", "NORMAL", false, false, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(a, b, c));

        assertThat(report.baseline().confusion().trueNegatives()).isEqualTo(1);
        assertThat(report.baseline().confusion().falseNegatives()).isEqualTo(2);
        assertThat(report.baseline().metrics().recall()).isEqualTo(0.0);
        assertThat(report.baseline().metrics().specificity()).isEqualTo(1.0);
        assertThat(report.baseline().metrics().falsePositiveRate()).isEqualTo(0.0);
    }

    @Test
    void allFalsePositiveConfusionCountsWarningsOnNormalSamples() {
        AnalysisSample a = sample("s1", "NORMAL", false, true, List.of(), 0L, 0L, BASE);
        AnalysisSample b = sample("s2", "NORMAL", false, true, List.of(), 0L, 0L, BASE);
        AnalysisSample c = sample("s3", "NORMAL", false, true, List.of(), 0L, 0L, BASE);
        AnalysisSample d = sample("s4", "NORMAL", false, true, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(a, b, c, d));

        assertThat(report.baseline().confusion().falsePositives()).isEqualTo(4);
        assertThat(report.baseline().confusion().truePositives()).isZero();
        assertThat(report.baseline().confusion().trueNegatives()).isZero();
        assertThat(report.baseline().metrics().precision()).isEqualTo(0.0);
        assertThat(report.baseline().metrics().wrongfulWarningRate()).isEqualTo(1.0);
    }

    @Test
    void allFalseNegativeConfusionCountsMissedAnomalies() {
        AnalysisSample a = sample("s1", "TAB_SWITCH", true, false, List.of(), 0L, 0L, BASE);
        AnalysisSample b = sample("s2", "CAMERA_OFF", true, false, List.of(), 0L, 0L, BASE);
        AnalysisSample c = sample("s3", "AUDIO_DETECTED", true, false, List.of(), 0L, 0L, BASE);
        AnalysisSample d = sample("s4", "FULLSCREEN_EXIT", true, false, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(a, b, c, d));

        assertThat(report.baseline().confusion().falseNegatives()).isEqualTo(4);
        assertThat(report.baseline().confusion().truePositives()).isZero();
        assertThat(report.baseline().metrics().recall()).isEqualTo(0.0);
        assertThat(report.baseline().metrics().falseNegativeRate()).isEqualTo(1.0);
    }

    @Test
    void mixedConfusionMatchesManualCounts() {
        AnalysisSample tp = sample("s1", "TAB_SWITCH", true, true, List.of(), 0L, 0L, BASE);
        AnalysisSample fn = sample("s2", "TAB_SWITCH", true, false, List.of(), 0L, 0L, BASE);
        AnalysisSample fp = sample("s3", "NORMAL", false, true, List.of(), 0L, 0L, BASE);
        AnalysisSample tn = sample("s4", "NORMAL", false, false, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(tp, fn, fp, tn));

        assertThat(report.baseline().confusion()).isEqualTo(new ResearchMetrics.Confusion(1, 1, 1, 1));
        assertThat(report.baseline().confusion().total()).isEqualTo(4);
        assertThat(report.baseline().metrics().accuracy()).isEqualTo(0.5);
        assertThat(report.fusion().confusion()).isEqualTo(new ResearchMetrics.Confusion(1, 1, 1, 1));
    }

    @Test
    void zeroDenominatorsAreNullNeverNaNOrInfinity() {
        AnalysisSample n1 = sample("s1", "NORMAL", false, false, List.of(), 0L, 0L, BASE);
        AnalysisSample n2 = sample("s2", "NO_ANOMALY", false, false, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(n1, n2));

        assertThat(report.baseline().metrics().precision()).isNull();
        assertThat(report.baseline().metrics().recall()).isNull();
        assertThat(report.baseline().metrics().falseNegativeRate()).isNull();
        assertThat(report.baseline().metrics().f1()).isNull();
        assertThat(report.baseline().metrics().wrongfulWarningRate()).isNull();
        assertThat(report.baseline().metrics().specificity()).isEqualTo(1.0);
        assertThat(report.baseline().metrics().falsePositiveRate()).isEqualTo(0.0);
    }

    @Test
    void metricDeltasReportFusionMinusBaseline() {
        AnalysisSample s1 = sampleDiff("s1", "TAB_SWITCH", true, true, true, List.of(), 0L, 0L, BASE);
        AnalysisSample s2 = sampleDiff("s2", "NORMAL", false, false, false, List.of(), 0L, 0L, BASE);
        AnalysisSample s3 = sampleDiff("s3", "TAB_SWITCH", true, true, false, List.of(), 0L, 0L, BASE);
        AnalysisSample s4 = sampleDiff("s4", "NORMAL", false, false, true, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(s1, s2, s3, s4));

        Map<String, Double> deltas = deltasOf(report);
        assertThat(deltas.get("precision")).isCloseTo(-0.5, within(1e-9));
        assertThat(deltas.get("recall")).isCloseTo(-0.5, within(1e-9));
        assertThat(deltas.get("specificity")).isCloseTo(-0.5, within(1e-9));
        assertThat(deltas.get("accuracy")).isCloseTo(-0.5, within(1e-9));
        assertThat(deltas.get("falsePositiveRate")).isCloseTo(0.5, within(1e-9));
        assertThat(deltas.get("falseNegativeRate")).isCloseTo(0.5, within(1e-9));
        assertThat(deltas.get("f1")).isCloseTo(-0.5, within(1e-9));
        assertThat(deltas.get("fdr")).isCloseTo(0.5, within(1e-9));
        assertThat(report.metricDeltas()).extracting(MetricDelta::metric)
                .containsExactly("precision", "recall", "specificity", "accuracy",
                        "falsePositiveRate", "falseNegativeRate", "f1", "fdr");
    }

    @Test
    void metricDeltasAreNullWhenEitherSideIsNull() {
        AnalysisSample n1 = sample("s1", "NORMAL", false, false, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(n1));

        Map<String, Double> deltas = deltasOf(report);
        assertThat(deltas.get("precision")).isNull();
        assertThat(deltas.get("recall")).isNull();
        assertThat(deltas.get("falseNegativeRate")).isNull();
        assertThat(deltas.get("f1")).isNull();
        assertThat(deltas.get("fdr")).isNull();
        assertThat(deltas.get("accuracy")).isEqualTo(0.0);
    }

    @Test
    void disagreementCategoriesAreCountedWithPercentages() {
        AnalysisSample agreePos1 = sampleDiff("s1", "TAB_SWITCH", true, true, true, List.of(), 0L, 0L, BASE);
        AnalysisSample agreePos2 = sampleDiff("s2", "CAMERA_OFF", true, true, true, List.of(), 0L, 0L, BASE);
        AnalysisSample baselineOnly = sampleDiff("s3", "WINDOW_BLUR", true, true, false, List.of(), 0L, 0L, BASE);
        AnalysisSample fusionOnly = sampleDiff("s4", "AUDIO_DETECTED", true, false, true, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(agreePos1, agreePos2, baselineOnly, fusionOnly));

        assertThat(report.disagreementSummary()).hasSize(4);
        category(report, "AGREE_POSITIVE").count(2).percentage(0.5);
        category(report, "AGREE_NEGATIVE").count(0).percentage(0.0);
        category(report, "BASELINE_ONLY_POSITIVE").count(1).percentage(0.25);
        category(report, "FUSION_ONLY_POSITIVE").count(1).percentage(0.25);
    }

    @Test
    void disagreementSamplesListOnlyDifferingPredictions() {
        AnalysisSample agree = sampleDiff("s1", "TAB_SWITCH", true, true, true, List.of(), 0L, 0L, BASE);
        AnalysisSample baselineOnly = sampleLabeled("s2", "CAMERA_OFF", "CAMERA_OFF", true, false,
                List.of(signal("ev1", "CAMERA_OFF", "BROWSER", null, BASE.plusSeconds(1))), 0L, 0L, BASE);
        AnalysisSample fusionOnly = sampleLabeled("s3", "AUDIO_DETECTED", "AUDIO_DETECTED", false, true,
                List.of(), 0L, 0L, BASE);
        AnalysisSample agreeNeg = sampleDiff("s4", "NORMAL", false, false, false, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(agree, baselineOnly, fusionOnly, agreeNeg));

        assertThat(report.disagreementSamples()).hasSize(2);
        assertThat(report.disagreementSamples()).extracting(s -> s.sampleId()).containsExactly("s2", "s3");
        assertThat(report.disagreementSamples().get(0).groundTruthLabel()).isEqualTo("CAMERA_OFF");
        assertThat(report.disagreementSamples().get(0).baselinePositive()).isTrue();
        assertThat(report.disagreementSamples().get(0).fusionPositive()).isFalse();
        assertThat(report.disagreementSamples().get(0).signalSummary()).isEqualTo("CAMERA_OFF x1");
        assertThat(report.disagreementSamples().get(1).signalSummary()).isEqualTo("no signals");
    }

    @Test
    void scenarioStratificationOnlyCoversPresentScenariosInFixedOrder() {
        AnalysisSample normal = sample("s1", "NORMAL", false, false, List.of(), 0L, 0L, BASE);
        AnalysisSample tab = sample("s2", "TAB_SWITCH", true, true, List.of(), 0L, 0L, BASE);
        AnalysisSample tab2 = sample("s3", "TAB_SWITCH", true, true, List.of(), 0L, 0L, BASE);
        AnalysisSample cameraOff = sample("s4", "CAMERA_OFF", true, true, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(normal, tab, tab2, cameraOff));

        assertThat(report.scenarios()).hasSize(3);
        assertThat(report.scenarios()).extracting(s -> s.value())
                .containsExactly("NORMAL", "TAB_SWITCH", "CAMERA_OFF");
        assertThat(report.scenarios().get(0).sampleCount()).isEqualTo(1);
        assertThat(report.scenarios().get(1).sampleCount()).isEqualTo(2);
        assertThat(report.scenarios().get(2).sampleCount()).isEqualTo(1);
    }

    @Test
    void conditionStratificationCoversControlledDimensionsOnly() {
        AnalysisSample a = sampleCond("s1", "NORMAL", false, false, List.of(), 0L, 0L,
                Map.of("lighting", "GOOD", "cameraQuality", "HD"), BASE);
        AnalysisSample b = sampleCond("s2", "TAB_SWITCH", true, true, List.of(), 0L, 0L,
                Map.of("lighting", "LOW"), BASE);
        AnalysisSample c = sampleCond("s3", "TAB_SWITCH", true, true, List.of(), 0L, 0L, Map.of(), BASE);

        Report report = analyze(List.of(a, b, c));

        assertThat(report.conditions()).extracting(s -> s.value())
                .containsExactly("lighting=GOOD", "lighting=LOW", "cameraQuality=HD");
        assertThat(report.conditions()).extracting(s -> s.sampleCount()).containsExactly(1, 1, 1);
        assertThat(report.conditions()).noneMatch(s -> s.value().contains("gender")
                || s.value().contains("age") || s.value().contains("ethnicity"));
    }

    @Test
    void confidenceDistributionIsDescriptivePerSource() {
        AnalysisSample a = sample("s1", "TAB_SWITCH", true, true,
                List.of(signal("e1", "TAB_SWITCH", "CLIENT_AI", 0.8, BASE),
                        signal("e2", "TAB_SWITCH", "CLIENT_AI", 0.6, BASE.plusSeconds(1))), 0L, 0L, BASE);
        AnalysisSample b = sample("s2", "CAMERA_OFF", true, true,
                List.of(signal("e3", "CAMERA_OFF", "CLIENT_AI", 0.9, BASE),
                        signal("e4", "CAMERA_OFF", "SERVER_AI", 0.5, BASE)), 0L, 0L, BASE);

        Report report = analyze(List.of(a, b));

        assertThat(report.confidence()).hasSize(2);
        assertThat(report.confidence()).extracting(Confidence::source)
                .containsExactly("CLIENT_AI", "SERVER_AI");
        Confidence client = report.confidence().get(0);
        assertThat(client.source()).isEqualTo("CLIENT_AI");
        assertThat(client.sampleCount()).isEqualTo(2);
        assertThat(client.measuredCount()).isEqualTo(3);
        assertThat(client.min()).isEqualTo(0.6);
        assertThat(client.max()).isEqualTo(0.9);
        assertThat(client.mean()).isCloseTo((0.8 + 0.6 + 0.9) / 3.0, within(1e-9));
        assertThat(client.median()).isEqualTo(0.8);
        assertThat(report.confidence().get(1).median()).isEqualTo(0.5);
    }

    @Test
    void measuredLatencyDescriptiveStatsAreComputed() {
        AnalysisSample a = sample("s1", "TAB_SWITCH", true, true, List.of(), 100L, 10L, BASE);
        AnalysisSample b = sample("s2", "TAB_SWITCH", true, true, List.of(), 300L, 10L, BASE);
        AnalysisSample c = sample("s3", "CAMERA_OFF", true, true, List.of(), 200L, 10L, BASE);

        Report report = analyze(List.of(a, b, c));

        assertThat(report.latency().measured().measuredCount()).isEqualTo(3);
        assertThat(report.latency().measured().mean()).isEqualTo(200.0);
        assertThat(report.latency().measured().median()).isEqualTo(200.0);
        assertThat(report.latency().measured().min()).isEqualTo(100.0);
        assertThat(report.latency().measured().max()).isEqualTo(300.0);
    }

    @Test
    void storedWindowLatencyUsesEarliestInWindowSignal() {
        AnalysisSample a = sample("s1", "TAB_SWITCH", true, true,
                List.of(signal("e1", "TAB_SWITCH", "BROWSER", null, BASE.plusSeconds(5)),
                        signal("e2", "TAB_SWITCH", "BROWSER", null, BASE.plusSeconds(2))),
                999L, 10L, BASE);
        AnalysisSample b = sample("s2", "CAMERA_OFF", true, true,
                List.of(signal("e3", "CAMERA_OFF", "BROWSER", null, BASE.plusSeconds(8))),
                999L, 10L, BASE);

        Report report = analyze(List.of(a, b));

        assertThat(report.latency().measured().measuredCount()).isEqualTo(2);
        assertThat(report.latency().storedWindow().measuredCount()).isEqualTo(2);
        assertThat(report.latency().storedWindow().min()).isEqualTo(2000.0);
        assertThat(report.latency().storedWindow().max()).isEqualTo(8000.0);
        assertThat(report.latency().storedWindow().median()).isEqualTo(5000.0);
    }

    @Test
    void latencyStratifiedByScenarioAndCondition() {
        AnalysisSample a = sampleCond("s1", "TAB_SWITCH", true, true,
                List.of(signal("e1", "TAB_SWITCH", "BROWSER", null, BASE.plusSeconds(2))),
                100L, 10L, Map.of("lighting", "LOW"), BASE);
        AnalysisSample b = sampleCond("s2", "TAB_SWITCH", true, true,
                List.of(signal("e2", "TAB_SWITCH", "BROWSER", null, BASE.plusSeconds(4))),
                null, 10L, Map.of("lighting", "LOW"), BASE);

        Report report = analyze(List.of(a, b));

        assertThat(report.latencyByScenario()).extracting(s -> s.value()).containsExactly("TAB_SWITCH");
        assertThat(report.latencyByScenario().get(0).sampleCount()).isEqualTo(2);
        assertThat(report.latencyByScenario().get(0).latency().measured().measuredCount()).isEqualTo(1);
        assertThat(report.latencyByScenario().get(0).latency().storedWindow().measuredCount()).isEqualTo(2);
        assertThat(report.latencyByCondition()).extracting(s -> s.value()).containsExactly("lighting=LOW");
        assertThat(report.latencyByCondition().get(0).latency().storedWindow().median()).isEqualTo(3000.0);
    }

    @Test
    void bandwidthSeparatesRawMediaAndSignalBytesIncludingMeasuredZeros() {
        AnalysisSample a = bandwidthSample("s1", "TAB_SWITCH", true, true, 1000L, 10L);
        AnalysisSample b = bandwidthSample("s2", "TAB_SWITCH", true, true, 2000L, 20L);
        AnalysisSample c = bandwidthSample("s3", "NORMAL", false, false, 0L, 5L);

        Report report = analyze(List.of(a, b, c));

        assertThat(report.bandwidth().rawMedia().measuredCount()).isEqualTo(3);
        assertThat(report.bandwidth().rawMedia().mean()).isEqualTo(1000.0);
        assertThat(report.bandwidth().rawMedia().min()).isEqualTo(0.0);
        assertThat(report.bandwidth().signalBytes().measuredCount()).isEqualTo(3);
        assertThat(report.bandwidth().signalBytes().mean()).isCloseTo(35.0 / 3.0, within(1e-9));
    }

    @Test
    void bandwidthIsUnavailableWhenNotMeasured() {
        AnalysisSample a = sample("s1", "NORMAL", false, false, List.of(), null, null, BASE);

        Report report = analyze(List.of(a));

        assertThat(report.bandwidth().rawMedia().measuredCount()).isZero();
        assertThat(report.bandwidth().rawMedia().mean()).isNull();
        assertThat(report.bandwidth().signalBytes().measuredCount()).isZero();
        assertThat(report.bandwidth().signalBytes().mean()).isNull();
    }

    @Test
    void evaluableCoverageIsNullWhenCapturedIsZero() {
        DataQualityCounts zero = new DataQualityCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        Report report = ResearchStatisticalAnalysis.analyze(List.of(), List.of(), zero,
                DATASET_VERSION, BASELINE_VERSION, FUSION_VERSION);

        assertThat(report.evaluableCoverage()).isNull();

        DataQualityCounts partial = new DataQualityCounts(4, 4, 2, 2, 2, 0, 0, 0, 0, 0, 0);
        Report covered = ResearchStatisticalAnalysis.analyze(List.of(), List.of(), partial,
                DATASET_VERSION, BASELINE_VERSION, FUSION_VERSION);

        assertThat(covered.evaluableCoverage()).isEqualTo(0.5);
    }

    @Test
    void singleReviewerDatasetStatementIsReported() {
        List<ReviewObservation> reviews = List.of(
                new ReviewObservation("s1", "r1", "NORMAL"),
                new ReviewObservation("s2", "r2", "TAB_SWITCH"));

        Report report = analyze(List.of(), reviews);

        assertThat(report.reviewAgreement().reviewedSamples()).isEqualTo(2);
        assertThat(report.reviewAgreement().resolvedSamples()).isEqualTo(2);
        assertThat(report.reviewAgreement().tiedSamples()).isZero();
        assertThat(report.reviewAgreement().agreementRate()).isEqualTo(1.0);
        assertThat(report.reviewAgreement().singleReviewerDataset()).isTrue();
        assertThat(report.reviewAgreement().pairwiseAgreementRate()).isNull();
    }

    @Test
    void pairwiseAgreementRateRequiresTwoReviewersPerSample() {
        List<ReviewObservation> reviews = List.of(
                new ReviewObservation("s1", "r1", "NORMAL"),
                new ReviewObservation("s1", "r2", "NORMAL"),
                new ReviewObservation("s2", "r1", "TAB_SWITCH"),
                new ReviewObservation("s2", "r2", "NORMAL"));

        Report report = analyze(List.of(), reviews);

        assertThat(report.reviewAgreement().reviewedSamples()).isEqualTo(2);
        assertThat(report.reviewAgreement().resolvedSamples()).isEqualTo(1);
        assertThat(report.reviewAgreement().tiedSamples()).isEqualTo(1);
        assertThat(report.reviewAgreement().singleReviewerDataset()).isFalse();
        assertThat(report.reviewAgreement().pairwiseAgreementRate()).isEqualTo(0.5);
    }

    @Test
    void tiedReviewsYieldZeroAgreementRateAndResolvedCounts() {
        List<ReviewObservation> reviews = List.of(
                new ReviewObservation("s1", "r1", "NORMAL"),
                new ReviewObservation("s1", "r2", "TAB_SWITCH"));

        Report report = analyze(List.of(), reviews);

        assertThat(report.reviewAgreement().reviewedSamples()).isEqualTo(1);
        assertThat(report.reviewAgreement().resolvedSamples()).isZero();
        assertThat(report.reviewAgreement().tiedSamples()).isEqualTo(1);
        assertThat(report.reviewAgreement().agreementRate()).isEqualTo(0.0);
    }

    @Test
    void wilsonIntervalBoundariesAndMetricCIs() {
        AnalysisSample tp1 = sample("s1", "TAB_SWITCH", true, true, List.of(), 0L, 0L, BASE);
        AnalysisSample tp2 = sample("s2", "CAMERA_OFF", true, true, List.of(), 0L, 0L, BASE);
        AnalysisSample tn1 = sample("s3", "NORMAL", false, false, List.of(), 0L, 0L, BASE);
        AnalysisSample tn2 = sample("s4", "NORMAL", false, false, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(tp1, tp2, tn1, tn2));

        assertThat(report.confidenceIntervals()).extracting(i -> i.metric())
                .contains("precision", "recall", "specificity", "accuracy",
                        "falsePositiveRate", "falseNegativeRate", "fdr")
                .doesNotContain("f1");
        ConfidenceInterval interval = interval(report, "precision");
        assertThat(interval.confidenceLevel()).isEqualTo(0.95);
        assertThat(interval.method()).isEqualTo("wilson");
        assertThat(interval.estimate()).isEqualTo(1.0);
        assertThat(interval.lower()).isGreaterThan(0.0);
        assertThat(interval.upper()).isLessThanOrEqualTo(1.0);

        ConfidenceInterval full = ResearchStatisticalAnalysis.wilson(1.0, 4);
        assertThat(full.lower()).isGreaterThan(0.0);
        assertThat(full.upper()).isLessThanOrEqualTo(1.0);
        ConfidenceInterval none = ResearchStatisticalAnalysis.wilson(0.0, 4);
        assertThat(none.lower()).isEqualTo(0.0);
        assertThat(none.upper()).isLessThan(0.5);
        ConfidenceInterval noSample = ResearchStatisticalAnalysis.wilson(0.5, 0);
        assertThat(noSample.lower()).isNull();
        assertThat(noSample.upper()).isNull();
        assertThat(noSample.estimate()).isEqualTo(0.5);
    }

    @Test
    void predictionTransitionsDescribeObservedChanges() {
        AnalysisSample stableCorrect = sampleDiff("s1", "TAB_SWITCH", true, true, true, List.of(), 0L, 0L, BASE);
        AnalysisSample regression = sampleDiff("s2", "TAB_SWITCH", true, true, false, List.of(), 0L, 0L, BASE);
        AnalysisSample repaired = sampleDiff("s3", "CAMERA_OFF", true, false, true, List.of(), 0L, 0L, BASE);
        AnalysisSample stableNeg = sampleDiff("s4", "NORMAL", false, false, false, List.of(), 0L, 0L, BASE);

        Report report = analyze(List.of(stableCorrect, regression, repaired, stableNeg));

        assertThat(report.transitions().baselineCorrectCount()).isEqualTo(3);
        assertThat(report.transitions().fusionCorrectCount()).isEqualTo(3);
        assertThat(report.transitions().baselineErrorCount()).isEqualTo(1);
        assertThat(report.transitions().fusionErrorCount()).isEqualTo(1);
        assertThat(report.transitions().changedPredictionCount()).isEqualTo(2);
        assertThat(report.transitions().baselineCorrectToFusionWrong()).isEqualTo(1);
        assertThat(report.transitions().baselineWrongToFusionCorrect()).isEqualTo(1);
    }

    private Report analyze(List<AnalysisSample> samples) {
        return analyze(samples, List.of());
    }

    private Report analyze(List<AnalysisSample> samples, List<ReviewObservation> reviews) {
        int n = samples.size();
        DataQualityCounts dq = new DataQualityCounts(n, n, n, n, 0, 0, 0, 0, 0, 0, 0);
        return ResearchStatisticalAnalysis.analyze(samples, reviews, dq,
                DATASET_VERSION, BASELINE_VERSION, FUSION_VERSION);
    }

    private AnalysisSample sample(String id, String scenario, boolean actualPositive,
                                  boolean predictedPositive, List<FusionSignal> signals,
                                  Long measuredLatencyMs, Long rawMediaBytes, Instant windowStart) {
        return sampleDiff(id, scenario, actualPositive, predictedPositive, predictedPositive,
                signals, measuredLatencyMs, rawMediaBytes, windowStart);
    }

    private AnalysisSample sampleCond(String id, String scenario, boolean actualPositive,
                                      boolean predictedPositive, List<FusionSignal> signals,
                                      Long measuredLatencyMs, Long rawMediaBytes, Map<String, String> conditions,
                                      Instant windowStart) {
        return sampleDiffCond(id, scenario, actualPositive, predictedPositive, predictedPositive,
                signals, measuredLatencyMs, rawMediaBytes, conditions, windowStart);
    }

    private AnalysisSample sampleDiff(String id, String scenario, boolean actualPositive,
                                      boolean baselinePositive, boolean fusionPositive, List<FusionSignal> signals,
                                      Long measuredLatencyMs, Long rawMediaBytes, Instant windowStart) {
        return sampleDiffWithBytes(id, scenario, resolvedLabel(actualPositive),
                baselinePositive, fusionPositive, signals, measuredLatencyMs,
                rawMediaBytes, rawMediaBytes == null ? null : 5L, windowStart);
    }

    private AnalysisSample sampleLabeled(String id, String scenario, String label,
                                         boolean baselinePositive, boolean fusionPositive, List<FusionSignal> signals,
                                         Long measuredLatencyMs, Long rawMediaBytes, Instant windowStart) {
        return sampleDiffWithBytes(id, scenario, label, baselinePositive, fusionPositive,
                signals, measuredLatencyMs, rawMediaBytes, rawMediaBytes == null ? null : 5L, windowStart);
    }

    private AnalysisSample bandwidthSample(String id, String scenario, boolean actualPositive,
                                           boolean predictedPositive, Long rawMediaBytes, Long signalBytes) {
        return new AnalysisSample(id, scenario, Map.of(), resolvedLabel(actualPositive),
                predictedPositive, predictedPositive, null, null, List.of(),
                null, rawMediaBytes, signalBytes, BASE);
    }

    private AnalysisSample sampleDiffWithBytes(String id, String scenario, String label,
                                               boolean baselinePositive, boolean fusionPositive, List<FusionSignal> signals,
                                               Long measuredLatencyMs, Long rawMediaBytes, Long signalBytes,
                                               Instant windowStart) {
        return new AnalysisSample(id, scenario, Map.of(), label,
                baselinePositive, fusionPositive, null, null, signals,
                measuredLatencyMs, rawMediaBytes, signalBytes, windowStart);
    }

    private AnalysisSample sampleDiffCond(String id, String scenario, boolean actualPositive,
                                          boolean baselinePositive, boolean fusionPositive, List<FusionSignal> signals,
                                          Long measuredLatencyMs, Long rawMediaBytes, Map<String, String> conditions,
                                          Instant windowStart) {
        return new AnalysisSample(id, scenario, conditions, resolvedLabel(actualPositive),
                baselinePositive, fusionPositive, null, null, signals,
                measuredLatencyMs, rawMediaBytes, rawMediaBytes == null ? null : 5L, windowStart);
    }

    private String resolvedLabel(boolean actualPositive) {
        return actualPositive ? "TAB_SWITCH" : "NORMAL";
    }

    private static FusionSignal signal(String id, String type, String source, Double confidence, Instant at) {
        return new FusionSignal(id, type, source, at.toString(), confidence, null);
    }

    private static Map<String, Double> deltasOf(Report report) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (MetricDelta delta : report.metricDeltas()) {
            map.put(delta.metric(), delta.delta());
        }
        return map;
    }

    private static CategoryAssert category(Report report, String category) {
        for (DisagreementSummary summary : report.disagreementSummary()) {
            if (summary.category().equals(category)) {
                return new CategoryAssert(summary.count(), summary.percentage());
            }
        }
        return new CategoryAssert(-1, null);
    }

    private static ConfidenceInterval interval(Report report, String metric) {
        for (ConfidenceInterval interval : report.confidenceIntervals()) {
            if (interval.metric().equals(metric)) {
                return interval;
            }
        }
        throw new AssertionError("missing interval for " + metric);
    }

    private static Report stripGeneratedAt(Report report) {
        return new Report(report.datasetVersion(), report.baselineVersion(), report.fusionVersion(),
                report.analysisVersion(), null, report.sampleRecords(), report.dataQuality(),
                report.evaluableCoverage(), report.baseline(), report.fusion(), report.metricDeltas(),
                report.disagreementSummary(), report.disagreementSamples(), report.scenarios(),
                report.conditions(), report.confidence(), report.latency(), report.latencyByScenario(),
                report.latencyByCondition(), report.bandwidth(), report.reviewAgreement(),
                report.confidenceIntervals(), report.transitions());
    }

    private static final class CategoryAssert {
        private final Integer count;
        private final Double percentage;

        private CategoryAssert(Integer count, Double percentage) {
            this.count = count;
            this.percentage = percentage;
        }

        private CategoryAssert count(int expected) {
            assertThat(count).isEqualTo(expected);
            return this;
        }

        private void percentage(double expected) {
            assertThat(percentage).isCloseTo(expected, within(1e-9));
        }
    }
}