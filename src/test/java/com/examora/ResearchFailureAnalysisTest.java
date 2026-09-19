package com.examora;

import com.examora.service.ProctorFusionService.FusionSignal;
import com.examora.service.ResearchFailureAnalysis;
import com.examora.service.ResearchFailureAnalysis.ConfidenceBand;
import com.examora.service.ResearchFailureAnalysis.Counted;
import com.examora.service.ResearchFailureAnalysis.DescriptiveStats;
import com.examora.service.ResearchFailureAnalysis.DisagreementAnalysis;
import com.examora.service.ResearchFailureAnalysis.Distribution;
import com.examora.service.ResearchFailureAnalysis.EnvironmentalFailure;
import com.examora.service.ResearchFailureAnalysis.EvaluatorCategories;
import com.examora.service.ResearchFailureAnalysis.FailureCase;
import com.examora.service.ResearchFailureAnalysis.FailurePattern;
import com.examora.service.ResearchFailureAnalysis.FailureReport;
import com.examora.service.ResearchFailureAnalysis.ScenarioFailure;
import com.examora.service.ResearchFailureAnalysis.SignalAssociation;
import com.examora.service.ResearchFailureAnalysis.SignalProfile;
import com.examora.service.ResearchFailureAnalysis.SignalProfileRow;
import com.examora.service.ResearchFailureAnalysis.TransitionCategories;
import com.examora.service.ResearchFailureAnalysis.TransitionSummary;
import com.examora.service.ResearchStatisticalAnalysis.AnalysisSample;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchFailureAnalysisTest {

    private static final String DATASET_VERSION = "dataset-v1";
    private static final String BASELINE_VERSION = "baseline-v1";
    private static final String FUSION_VERSION = "fusion-v1";
    private static final Instant BASE = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void emptyDatasetProducesEmptyFailureReport() {
        FailureReport report = analyze(List.of());

        assertThat(report.evaluableSampleCount()).isZero();
        assertThat(report.failureCaseCount()).isZero();
        assertThat(report.disagreementCount()).isZero();
        assertThat(report.changedPredictionCount()).isZero();
        assertThat(report.categories().categories()).isEmpty();
        assertThat(report.transitionCategories().categories()).isEmpty();
        assertThat(report.disagreement().categories()).isEmpty();
        assertThat(report.signalProfile().rows()).isEmpty();
        assertThat(report.confidenceBands()).isEmpty();
        assertThat(report.missingConfidenceSignalCount()).isZero();
        assertThat(report.environmentalFailures()).isEmpty();
        assertThat(report.missingConditionCount()).isZero();
        assertThat(report.scenarioFailures()).isEmpty();
        assertThat(report.signalAssociation().errorSampleCount()).isZero();
        assertThat(report.signalAssociation().errorSamplesWithoutSignals()).isZero();
        assertThat(report.failureCases()).isEmpty();
        assertThat(report.patterns()).containsExactly(new FailurePattern("No evaluable samples in scope."));
        assertThat(report.transitions().changedPredictionCount()).isZero();
        assertThat(report.transitions().baselineCorrectToFusionWrongFraction()).isNull();
        assertThat(report.transitions().baselineWrongToFusionCorrectFraction()).isNull();
    }

    @Test
    void allTruePositivesProduceNoFailureCases() {
        FailureReport report = analyze(List.of(
                sample("s1", "TAB_SWITCH", "TAB_SWITCH", true, true),
                sample("s2", "CAMERA_OFF", "CAMERA_OFF", true, true),
                sample("s3", "AUDIO_DETECTED", "AUDIO_DETECTED", true, true)));

        assertThat(report.evaluableSampleCount()).isEqualTo(3);
        assertThat(report.failureCaseCount()).isZero();
        assertThat(report.disagreementCount()).isZero();
        assertThat(report.categories().categories()).isEmpty();
        assertThat(report.transitionCategories().categories())
                .containsExactly(new Counted("CORRECT_TO_CORRECT", 3));
        assertThat(report.patterns()).doesNotContain(
                new FailurePattern("3 false-positive observations were recorded for " + BASELINE_VERSION + "."));
    }

    @Test
    void allTrueNegativesProduceNoFailureCases() {
        FailureReport report = analyze(List.of(
                sample("s1", "NORMAL", "NORMAL", false, false),
                sample("s2", "NORMAL", "NORMAL", false, false)));

        assertThat(report.evaluableSampleCount()).isEqualTo(2);
        assertThat(report.failureCaseCount()).isZero();
        assertThat(report.transitionCategories().categories())
                .containsExactly(new Counted("CORRECT_TO_CORRECT", 2));
        assertThat(report.patterns()).isEmpty();
    }

    @Test
    void allFalsePositivesYieldExpectedCategories() {
        FailureReport report = analyze(List.of(
                sample("s1", "NORMAL", "NORMAL", true, true),
                sample("s2", "NORMAL", "NORMAL", true, true),
                sample("s3", "NORMAL", "NORMAL", true, true)));

        assertThat(report.failureCaseCount()).isEqualTo(3);
        assertThat(report.disagreementCount()).isZero();
        assertThat(report.categories().categories()).containsExactly(
                new Counted("BASELINE_FALSE_POSITIVE", 3),
                new Counted("FUSION_FALSE_POSITIVE", 3));
        assertThat(report.transitionCategories().categories())
                .containsExactly(new Counted("WRONG_TO_WRONG", 3));
        assertThat(report.patterns()).contains(
                new FailurePattern("3 false-positive observations were recorded for " + BASELINE_VERSION + "."),
                new FailurePattern("3 false-positive observations were recorded for " + FUSION_VERSION + "."),
                new FailurePattern("3 failure samples were recorded for scenario NORMAL."));
    }

    @Test
    void allFalseNegativesYieldExpectedCategories() {
        FailureReport report = analyze(List.of(
                sample("s1", "TAB_SWITCH", "TAB_SWITCH", false, false),
                sample("s2", "CAMERA_OFF", "CAMERA_OFF", false, false),
                sample("s3", "TAB_SWITCH", "TAB_SWITCH", false, false)));

        assertThat(report.failureCaseCount()).isEqualTo(3);
        assertThat(report.categories().categories()).containsExactly(
                new Counted("BASELINE_FALSE_NEGATIVE", 3),
                new Counted("FUSION_FALSE_NEGATIVE", 3));
        assertThat(report.transitionCategories().categories())
                .containsExactly(new Counted("WRONG_TO_WRONG", 3));
        assertThat(report.failureCases()).allSatisfy(c -> assertThat(c.classification()).isEqualTo("WRONG_TO_WRONG"));
    }

    @Test
    void mixedErrorsSplitAcrossExpectedCategories() {
        List<AnalysisSample> samples = List.of(
                sample("s1", "TAB_SWITCH", "TAB_SWITCH", true, false),
                sample("s2", "NORMAL", "NORMAL", false, true),
                sample("s3", "NORMAL", "NORMAL", true, false),
                sample("s4", "TAB_SWITCH", "TAB_SWITCH", false, true),
                sample("s5", "NORMAL", "NORMAL", true, true),
                sample("s6", "TAB_SWITCH", "TAB_SWITCH", false, false),
                sample("s7", "NORMAL", "NORMAL", false, false),
                sample("s8", "TAB_SWITCH", "TAB_SWITCH", true, true));

        FailureReport report = analyze(samples);

        assertThat(report.evaluableSampleCount()).isEqualTo(8);
        assertThat(report.failureCaseCount()).isEqualTo(6);
        assertThat(report.disagreementCount()).isEqualTo(4);
        assertThat(report.changedPredictionCount()).isEqualTo(4);
        assertThat(report.categories().categories()).containsExactly(
                new Counted("BASELINE_FALSE_POSITIVE", 2),
                new Counted("BASELINE_FALSE_NEGATIVE", 2),
                new Counted("FUSION_FALSE_POSITIVE", 2),
                new Counted("FUSION_FALSE_NEGATIVE", 2),
                new Counted("BASELINE_ONLY_POSITIVE", 2),
                new Counted("FUSION_ONLY_POSITIVE", 2));
        assertThat(report.transitionCategories().categories()).containsExactly(
                new Counted("CORRECT_TO_CORRECT", 2),
                new Counted("CORRECT_TO_WRONG", 2),
                new Counted("WRONG_TO_CORRECT", 2),
                new Counted("WRONG_TO_WRONG", 2));
        assertThat(report.disagreement().categories()).containsExactly(
                new Counted("BASELINE_ONLY_POSITIVE", 2),
                new Counted("FUSION_ONLY_POSITIVE", 2));
        assertThat(report.patterns()).contains(
                new FailurePattern("4 baseline/fusion disagreements occurred."));
    }

    @Test
    void predictionTransitionsDescribeObservedChangesAndDistributions() {
        List<AnalysisSample> samples = List.of(
                sampleCond("s1", "TAB_SWITCH", "TAB_SWITCH", true, false, List.of(ai("a1", 0.5)),
                        Map.of("lighting", "GOOD")),
                sampleCond("s2", "TAB_SWITCH", "TAB_SWITCH", true, false, List.of(ai("a2", 0.9)),
                        Map.of("lighting", "GOOD")),
                sampleCond("s3", "CAMERA_OFF", "CAMERA_OFF", false, true, List.of(signal("a3", "SERVER_AI", "SERVER_AI", 0.3, null)),
                        Map.of("lighting", "LOW")),
                sampleCond("s4", "TAB_SWITCH", "TAB_SWITCH", false, true, List.of(signal("a4", "SERVER_AI", "SERVER_AI", 0.6, null)),
                        Map.of("lighting", "LOW")),
                sample("s5", "NORMAL", "NORMAL", false, false));

        FailureReport report = analyze(samples);

        TransitionSummary transitions = report.transitions();
        assertThat(transitions.changedPredictionCount()).isEqualTo(4);
        assertThat(transitions.baselineCorrectToFusionWrong()).isEqualTo(2);
        assertThat(transitions.baselineWrongToFusionCorrect()).isEqualTo(2);
        assertThat(transitions.baselineCorrectToFusionWrongFraction()).isCloseTo(0.5, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(transitions.baselineWrongToFusionCorrectFraction()).isCloseTo(0.5, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(transitions.byScenario()).containsExactly(
                new Distribution("TAB_SWITCH", 3), new Distribution("CAMERA_OFF", 1));
        assertThat(transitions.byCondition()).containsExactly(
                new Distribution("lighting=GOOD", 2), new Distribution("lighting=LOW", 2));
        assertThat(transitions.bySignalSource()).containsExactly(
                new Distribution("CLIENT_AI", 2), new Distribution("SERVER_AI", 2));
        assertThat(transitions.byConfidenceBand()).containsExactly(
                new Distribution("0.20-0.39", 1),
                new Distribution("0.40-0.59", 1),
                new Distribution("0.60-0.79", 1),
                new Distribution("0.80-1.00", 1));
    }

    @Test
    void baselineOnlyAndFusionOnlyPositiveCategoriesAreDistinguished() {
        FailureReport report = analyze(List.of(
                sample("s1", "NORMAL", "NORMAL", true, false),
                sample("s2", "TAB_SWITCH", "TAB_SWITCH", false, true),
                sample("s3", "NORMAL", "NORMAL", true, true)));

        assertThat(report.disagreement().categories()).containsExactly(
                new Counted("BASELINE_ONLY_POSITIVE", 1),
                new Counted("FUSION_ONLY_POSITIVE", 1));
        assertThat(report.categories().categories()).containsExactly(
                new Counted("BASELINE_FALSE_POSITIVE", 2),
                new Counted("BASELINE_FALSE_NEGATIVE", 1),
                new Counted("FUSION_FALSE_POSITIVE", 1),
                new Counted("BASELINE_ONLY_POSITIVE", 1),
                new Counted("FUSION_ONLY_POSITIVE", 1));
    }

    @Test
    void fusionOnlyPositiveErrorsAreReportedSeparately() {
        FailureReport report = analyze(List.of(
                sample("s1", "TAB_SWITCH", "TAB_SWITCH", false, true),
                sample("s2", "CAMERA_OFF", "CAMERA_OFF", false, true)));

        assertThat(report.categories().categories()).containsExactly(
                new Counted("BASELINE_FALSE_NEGATIVE", 2),
                new Counted("FUSION_ONLY_POSITIVE", 2));
        assertThat(report.failureCases()).allSatisfy(c -> assertThat(c.classification()).isEqualTo("WRONG_TO_CORRECT"));
    }

    @Test
    void signalProfileGroupsErrorCategoriesBySource() {
        List<AnalysisSample> samples = List.of(
                sample("s1", "NORMAL", "NORMAL", true, true,
                        List.of(ai("s1a", 0.7, 100L), ai("s1b", 0.8, 200L))),
                sample("s2", "TAB_SWITCH", "TAB_SWITCH", false, false,
                        List.of(signal("s2a", "SERVER_AI", "SERVER_AI", 0.9, 300L))),
                sample("s3", "TAB_SWITCH", "TAB_SWITCH", true, false, List.of()),
                sample("s4", "TAB_SWITCH", "TAB_SWITCH", true, false,
                        List.of(signal("s4a", "BROWSER", "BROWSER", 0.2, 10L))));

        FailureReport report = analyze(samples);
        SignalProfile profile = report.signalProfile();
        List<SignalProfileRow> rows = profile.rows();

        assertThat(rows).hasSize(6);
        assertThat(rows.get(0).category()).isEqualTo("BASELINE_FALSE_POSITIVE");
        assertThat(rows.get(0).source()).isEqualTo("CLIENT_AI");
        assertThat(rows.get(0).signalCount()).isEqualTo(2);
        assertThat(rows.get(0).sampleCount()).isEqualTo(1);
        assertThat(rows.get(0).confidence()).isEqualTo(new DescriptiveStats(2, 0.75, 0.75, 0.7, 0.8));
        assertThat(rows.get(0).duration()).isEqualTo(new DescriptiveStats(2, 150.0, 150.0, 100.0, 200.0));

        assertThat(rows.get(1).category()).isEqualTo("BASELINE_FALSE_NEGATIVE");
        assertThat(rows.get(1).source()).isEqualTo("SERVER_AI");
        assertThat(rows.get(1).signalCount()).isEqualTo(1);
        assertThat(rows.get(1).confidence()).isEqualTo(new DescriptiveStats(1, 0.9, 0.9, 0.9, 0.9));

        assertThat(rows.get(2).category()).isEqualTo("FUSION_FALSE_POSITIVE");
        assertThat(rows.get(2).source()).isEqualTo("CLIENT_AI");
        assertThat(rows.get(2).signalCount()).isEqualTo(2);

        assertThat(rows.get(3).category()).isEqualTo("FUSION_FALSE_NEGATIVE");
        assertThat(rows.get(3).source()).isEqualTo("BROWSER");
        assertThat(rows.get(3).signalCount()).isEqualTo(1);
        assertThat(rows.get(4).category()).isEqualTo("FUSION_FALSE_NEGATIVE");
        assertThat(rows.get(4).source()).isEqualTo("SERVER_AI");
        assertThat(rows.get(4).confidence().mean()).isEqualTo(0.9);

        assertThat(rows.get(5).category()).isEqualTo("BASELINE_ONLY_POSITIVE");
        assertThat(rows.get(5).source()).isEqualTo("BROWSER");
        assertThat(rows.get(5).confidence().mean()).isEqualTo(0.2);
        assertThat(rows.get(5).duration().mean()).isEqualTo(10.0);
    }

    @Test
    void confidenceBandsUseMaximumAiConfidencePerSample() {
        List<AnalysisSample> samples = List.of(
                sample("s1", "NORMAL", "NORMAL", false, true, List.of(ai("a1", 0.1))),
                sample("s2", "NORMAL", "NORMAL", false, true, List.of(signal("a2", "SERVER_AI", "SERVER_AI", 0.25, null))),
                sample("s3", "NORMAL", "NORMAL", false, false, List.of(ai("a3", 0.45))),
                sample("s4", "TAB_SWITCH", "TAB_SWITCH", true, true, List.of(ai("a4", 0.65))),
                sample("s5", "TAB_SWITCH", "TAB_SWITCH", true, false, List.of(ai("a5", 0.95))),
                sample("s6", "NORMAL", "NORMAL", false, true, List.of(ai("a6a", 0.15), ai("a6b", 0.55))));

        FailureReport report = analyze(samples);
        List<ConfidenceBand> bands = report.confidenceBands();

        assertThat(bands).hasSize(5);
        assertThat(bands.get(0).band()).isEqualTo("0.00-0.19");
        assertThat(bands.get(0).distinctSampleCount()).isEqualTo(1);
        assertThat(bands.get(0).signalCount()).isEqualTo(1);
        assertThat(bands.get(0).precision()).isEqualTo(0.0);
        assertThat(bands.get(0).falsePositiveRate()).isEqualTo(1.0);
        assertThat(bands.get(0).evaluator()).isEqualTo(FUSION_VERSION);

        assertThat(bands.get(1).band()).isEqualTo("0.20-0.39");
        assertThat(bands.get(1).distinctSampleCount()).isEqualTo(1);

        assertThat(bands.get(2).band()).isEqualTo("0.40-0.59");
        assertThat(bands.get(2).distinctSampleCount()).isEqualTo(2);
        assertThat(bands.get(2).signalCount()).isEqualTo(3);
        assertThat(bands.get(2).falsePositiveRate()).isEqualTo(0.5);

        assertThat(bands.get(3).band()).isEqualTo("0.60-0.79");
        assertThat(bands.get(3).distinctSampleCount()).isEqualTo(1);
        assertThat(bands.get(3).precision()).isEqualTo(1.0);
        assertThat(bands.get(3).recall()).isEqualTo(1.0);

        assertThat(bands.get(4).band()).isEqualTo("0.80-1.00");
        assertThat(bands.get(4).distinctSampleCount()).isEqualTo(1);
        assertThat(bands.get(4).recall()).isEqualTo(0.0);
        assertThat(bands.get(4).falseNegativeRate()).isEqualTo(1.0);
    }

    @Test
    void missingAiConfidenceSignalsAreCountedAndExcludedFromBands() {
        List<AnalysisSample> samples = List.of(
                sample("s1", "NORMAL", "NORMAL", false, true,
                        List.of(new FusionSignal("m1", "CLIENT_AI", "CLIENT_AI", BASE.toString(), null, null))),
                sample("s2", "NORMAL", "NORMAL", false, true, List.of(ai("a1", 0.5))),
                sample("s3", "NORMAL", "NORMAL", false, true,
                        List.of(signal("b1", "BROWSER", "BROWSER", null, null))));

        FailureReport report = analyze(samples);

        assertThat(report.missingConfidenceSignalCount()).isEqualTo(1);
        assertThat(report.confidenceBands()).hasSize(1);
        assertThat(report.confidenceBands().get(0).band()).isEqualTo("0.40-0.59");
        assertThat(report.confidenceBands().get(0).distinctSampleCount()).isEqualTo(1);
        assertThat(report.confidenceBands().get(0).signalCount()).isEqualTo(1);
    }

    @Test
    void environmentalFailuresFollowControlledConditionOrder() {
        List<AnalysisSample> samples = List.of(
                sampleCond("s1", "NORMAL", "NORMAL", true, true, List.of(),
                        Map.of("lighting", "GOOD", "cameraQuality", "HD", "network", "STABLE", "cameraAngle", "FRONT")),
                sampleCond("s2", "NORMAL", "NORMAL", true, true, List.of(),
                        Map.of("lighting", "LOW", "network", "STABLE")),
                sampleCond("s3", "TAB_SWITCH", "TAB_SWITCH", true, true, List.of(),
                        Map.of("lighting", "GOOD", "cameraQuality", "HD")),
                sampleCond("s4", "TAB_SWITCH", "TAB_SWITCH", false, false, List.of(),
                        Map.of("lighting", "LOW")));

        FailureReport report = analyze(samples);
        List<EnvironmentalFailure> groups = report.environmentalFailures();

        assertThat(groups).hasSize(5);
        assertThat(groups.get(0).condition()).isEqualTo("lighting=GOOD");
        assertThat(groups.get(0).sampleCount()).isEqualTo(2);
        assertThat(groups.get(0).baselineFalsePositiveRate()).isEqualTo(1.0);
        assertThat(groups.get(1).condition()).isEqualTo("lighting=LOW");
        assertThat(groups.get(1).sampleCount()).isEqualTo(2);
        assertThat(groups.get(2).condition()).isEqualTo("cameraQuality=HD");
        assertThat(groups.get(2).sampleCount()).isEqualTo(2);
        assertThat(groups.get(3).condition()).isEqualTo("network=STABLE");
        assertThat(groups.get(3).sampleCount()).isEqualTo(2);
        assertThat(groups.get(4).condition()).isEqualTo("cameraAngle=FRONT");
        assertThat(groups.get(4).sampleCount()).isEqualTo(1);
        assertThat(report.missingConditionCount()).isEqualTo(3);
    }

    @Test
    void scenarioFailuresFollowScenarioOrderAndOnlyPresentScenarios() {
        List<AnalysisSample> samples = List.of(
                sample("s1", "NORMAL", "NORMAL", true, true),
                sample("s2", "NORMAL", "NORMAL", true, true),
                sample("s3", "TAB_SWITCH", "TAB_SWITCH", false, false),
                sample("s4", "CAMERA_OFF", "CAMERA_OFF", true, true));

        FailureReport report = analyze(samples);
        List<ScenarioFailure> groups = report.scenarioFailures();

        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).scenario()).isEqualTo("NORMAL");
        assertThat(groups.get(0).sampleCount()).isEqualTo(2);
        assertThat(groups.get(0).fusionFalsePositives()).isEqualTo(2);
        assertThat(groups.get(0).fusionFalseNegativeRate()).isNull();
        assertThat(groups.get(1).scenario()).isEqualTo("TAB_SWITCH");
        assertThat(groups.get(1).sampleCount()).isEqualTo(1);
        assertThat(groups.get(1).fusionFalseNegatives()).isEqualTo(1);
        assertThat(groups.get(1).fusionFalseNegativeRate()).isEqualTo(1.0);
        assertThat(groups.get(2).scenario()).isEqualTo("CAMERA_OFF");
        assertThat(groups.get(2).sampleCount()).isEqualTo(1);
    }

    @Test
    void smallSampleDisclosureFlagsGroupsBelowThreshold() {
        List<AnalysisSample> samples = List.of(
                sampleCond("s1", "NORMAL", "NORMAL", true, true, List.of(),
                        Map.of("lighting", "GOOD", "cameraQuality", "HD", "network", "STABLE", "cameraAngle", "FRONT")),
                sampleCond("s2", "NORMAL", "NORMAL", true, true, List.of(),
                        Map.of("lighting", "GOOD", "cameraQuality", "HD", "network", "STABLE", "cameraAngle", "FRONT")),
                sampleCond("s3", "NORMAL", "NORMAL", true, true, List.of(),
                        Map.of("lighting", "GOOD", "cameraQuality", "HD", "network", "STABLE", "cameraAngle", "FRONT")),
                sampleCond("s4", "NORMAL", "NORMAL", true, true, List.of(),
                        Map.of("lighting", "GOOD", "cameraQuality", "HD", "network", "STABLE", "cameraAngle", "FRONT")),
                sampleCond("s5", "NORMAL", "NORMAL", true, true, List.of(),
                        Map.of("lighting", "GOOD", "cameraQuality", "HD", "network", "STABLE", "cameraAngle", "FRONT")),
                sampleCond("s6", "TAB_SWITCH", "TAB_SWITCH", false, false, List.of(),
                        Map.of("lighting", "GOOD", "cameraQuality", "HD", "network", "STABLE", "cameraAngle", "FRONT")));

        FailureReport report = analyze(samples);

        assertThat(ResearchFailureAnalysis.SMALL_SAMPLE_THRESHOLD).isEqualTo(5);
        assertThat(ResearchFailureAnalysis.SMALL_SAMPLE_DISCLOSURE).isEqualTo("Small sample; interpret descriptively.");
        assertThat(report.missingConditionCount()).isZero();

        ScenarioFailure normal = scenario(report, "NORMAL");
        assertThat(normal.sampleCount()).isEqualTo(5);
        assertThat(normal.smallSample()).isFalse();

        ScenarioFailure tab = scenario(report, "TAB_SWITCH");
        assertThat(tab.sampleCount()).isEqualTo(1);
        assertThat(tab.smallSample()).isTrue();

        EnvironmentalFailure lighting = environmental(report, "lighting=GOOD");
        assertThat(lighting.sampleCount()).isEqualTo(6);
        assertThat(lighting.smallSample()).isFalse();
    }

    @Test
    void signalAssociationReportsErrorSamplesWithoutSignalsAndSources() {
        List<AnalysisSample> samples = List.of(
                sample("f1", "NORMAL", "NORMAL", true, true, List.of(ai("x1", 0.5))),
                sample("f2", "NORMAL", "NORMAL", true, true,
                        List.of(ai("x2", 0.5), signal("x3", "SERVER_AI", "SERVER_AI", 0.6, null))),
                sample("f3", "NORMAL", "NORMAL", false, true, List.of()),
                sample("f4", "TAB_SWITCH", "TAB_SWITCH", false, false,
                        List.of(signal("x5", "SYSTEM", "SYSTEM", 0.4, null))));

        FailureReport report = analyze(samples);
        SignalAssociation association = report.signalAssociation();

        assertThat(association.errorSampleCount()).isEqualTo(4);
        assertThat(association.errorSamplesWithoutSignals()).isEqualTo(1);
        assertThat(association.sources()).containsExactly(
                new Counted("CLIENT_AI", 2), new Counted("SERVER_AI", 1), new Counted("SYSTEM", 1));
    }

    @Test
    void versionStampingIsExact() {
        FailureReport report = analyze(List.of(sample("s1", "NORMAL", "NORMAL", true, false)));

        assertThat(report.datasetVersion()).isEqualTo(DATASET_VERSION);
        assertThat(report.baselineVersion()).isEqualTo(BASELINE_VERSION);
        assertThat(report.fusionVersion()).isEqualTo(FUSION_VERSION);
        assertThat(report.analysisVersion()).isEqualTo("analysis-v1");
        assertThat(report.failureAnalysisVersion()).isEqualTo("failure-analysis-v1");
        assertThat(report.generatedAt()).isNotNull();
    }

    @Test
    void allGroupingsHaveDeterministicOrdering() {
        List<AnalysisSample> outOfOrder = List.of(
                sample("s8", "TAB_SWITCH", "TAB_SWITCH", true, true),
                sample("s2", "NORMAL", "NORMAL", false, true),
                sample("s5", "NORMAL", "NORMAL", true, true),
                sample("s1", "TAB_SWITCH", "TAB_SWITCH", true, false),
                sample("s7", "NORMAL", "NORMAL", false, false),
                sample("s4", "TAB_SWITCH", "TAB_SWITCH", false, true),
                sample("s6", "TAB_SWITCH", "TAB_SWITCH", false, false),
                sample("s3", "NORMAL", "NORMAL", true, false));

        FailureReport report = analyze(outOfOrder);

        assertThat(report.categories().categories()).containsExactly(
                new Counted("BASELINE_FALSE_POSITIVE", 2),
                new Counted("BASELINE_FALSE_NEGATIVE", 2),
                new Counted("FUSION_FALSE_POSITIVE", 2),
                new Counted("FUSION_FALSE_NEGATIVE", 2),
                new Counted("BASELINE_ONLY_POSITIVE", 2),
                new Counted("FUSION_ONLY_POSITIVE", 2));
        assertThat(report.disagreement().categories()).containsExactly(
                new Counted("BASELINE_ONLY_POSITIVE", 2),
                new Counted("FUSION_ONLY_POSITIVE", 2));

        List<String> failureIds = report.failureCases().stream().map(FailureCase::sampleId).toList();
        assertThat(failureIds).containsExactly("s1", "s2", "s3", "s4", "s5", "s6");

        assertThat(report.scenarioFailures().stream().map(ScenarioFailure::scenario).toList())
                .containsExactly("NORMAL", "TAB_SWITCH");
        assertThat(report.transitions().byScenario().stream().map(Distribution::value).toList())
                .containsExactly("NORMAL", "TAB_SWITCH");
    }

    @Test
    void deterministicRepeatedAnalysisProducesEqualReportsExceptGeneratedAt() {
        List<AnalysisSample> samples = List.of(
                sample("s1", "NORMAL", "NORMAL", true, true, List.of(ai("a1", 0.5))),
                sample("s2", "TAB_SWITCH", "TAB_SWITCH", true, false, List.of(ai("a2", 0.8))));

        FailureReport first = analyze(samples);
        FailureReport second = analyze(samples);

        assertThat(first.generatedAt()).isNotNull();
        assertThat(second.generatedAt()).isNotNull();
        assertThat(stripGeneratedAt(first)).isEqualTo(stripGeneratedAt(second));
    }

    @Test
    void zeroDenominatorsAreNullNeverNaNOrInfinity() {
        List<AnalysisSample> samples = List.of(
                sampleCond("s1", "NORMAL", "NORMAL", true, true, List.of(ai("a1", 0.5)),
                        Map.of("lighting", "GOOD")),
                sampleCond("s2", "NORMAL", "NORMAL", true, true, List.of(ai("a2", 0.7)),
                        Map.of("lighting", "GOOD")));

        FailureReport report = analyze(samples);

        ScenarioFailure normal = scenario(report, "NORMAL");
        assertThat(normal.fusionFalsePositiveRate()).isEqualTo(1.0);
        assertThat(normal.fusionFalseNegativeRate()).isNull();
        assertThat(normal.baselineFalseNegativeRate()).isNull();

        ConfidenceBand band = report.confidenceBands().stream()
                .filter(b -> b.band().equals("0.40-0.59")).findFirst().orElseThrow();
        assertThat(band.precision()).isEqualTo(0.0);
        assertThat(band.recall()).isNull();
        assertThat(band.falsePositiveRate()).isEqualTo(1.0);
        assertThat(band.falseDiscoveryRate()).isEqualTo(1.0);

        assertThat(report.transitions().baselineCorrectToFusionWrongFraction()).isNull();
        assertThat(report.patterns()).noneMatch(p -> p.statement().contains("NaN") || p.statement().contains("Infinity"));
    }

    private static FailureReport analyze(List<AnalysisSample> samples) {
        return ResearchFailureAnalysis.analyze(samples, DATASET_VERSION, BASELINE_VERSION, FUSION_VERSION);
    }

    private static FailureReport stripGeneratedAt(FailureReport report) {
        return new FailureReport(report.datasetVersion(), report.baselineVersion(), report.fusionVersion(),
                report.analysisVersion(), report.failureAnalysisVersion(), null,
                report.evaluableSampleCount(), report.failureCaseCount(), report.disagreementCount(),
                report.changedPredictionCount(), report.categories(), report.transitionCategories(),
                report.disagreement(), report.signalProfile(), report.confidenceBands(),
                report.missingConfidenceSignalCount(), report.environmentalFailures(),
                report.missingConditionCount(), report.scenarioFailures(), report.signalAssociation(),
                report.transitions(), report.patterns(), report.failureCases());
    }

    private static ScenarioFailure scenario(FailureReport report, String scenario) {
        return report.scenarioFailures().stream()
                .filter(s -> s.scenario().equals(scenario)).findFirst()
                .orElseThrow(() -> new AssertionError("missing scenario group " + scenario));
    }

    private static EnvironmentalFailure environmental(FailureReport report, String condition) {
        return report.environmentalFailures().stream()
                .filter(s -> s.condition().equals(condition)).findFirst()
                .orElseThrow(() -> new AssertionError("missing condition group " + condition));
    }

    private static AnalysisSample sample(String id, String scenario, String label,
                                         boolean baselinePositive, boolean fusionPositive) {
        return sample(id, scenario, label, baselinePositive, fusionPositive, List.of());
    }

    private static AnalysisSample sample(String id, String scenario, String label,
                                         boolean baselinePositive, boolean fusionPositive, List<FusionSignal> signals) {
        return new AnalysisSample(id, scenario, Map.of(), label, baselinePositive, fusionPositive,
                null, null, signals, null, null, null, BASE);
    }

    private static AnalysisSample sampleCond(String id, String scenario, String label,
                                             boolean baselinePositive, boolean fusionPositive,
                                             List<FusionSignal> signals, Map<String, String> conditions) {
        return new AnalysisSample(id, scenario, conditions, label, baselinePositive, fusionPositive,
                null, null, signals, null, null, null, BASE);
    }

    private static FusionSignal ai(String id, double confidence) {
        return new FusionSignal(id, "CLIENT_AI", "CLIENT_AI", BASE.toString(), confidence, null);
    }

    private static FusionSignal ai(String id, double confidence, Long durationMs) {
        return new FusionSignal(id, "CLIENT_AI", "CLIENT_AI", BASE.toString(), confidence, durationMs);
    }

    private static FusionSignal signal(String id, String type, String source, Double confidence, Long durationMs) {
        return new FusionSignal(id, type, source, BASE.toString(), confidence, durationMs);
    }
}