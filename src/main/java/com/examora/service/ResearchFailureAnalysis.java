package com.examora.service;

import com.examora.service.ProctorFusionService.FusionSignal;
import com.examora.service.ResearchStatisticalAnalysis.AnalysisSample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure, deterministic, read-only failure-case analysis over the same analysed samples used by
 * {@link ResearchStatisticalAnalysis}. The class describes observed errors of the baseline and
 * fusion predictors (false positives, false negatives), prediction transitions, disagreements,
 * signal associations, confidence bands and per-environment/per-scenario groupings.
 *
 * <p>Everything here is descriptive. It derives counts and descriptive statistics exclusively from
 * persisted, human-reviewed, evaluable samples. It never fabricates samples, never writes anything,
 * never calls an AI or LLM, never exposes student identity, raw media, biometrics or demographic
 * attributes, and never ranks one predictor as superior to another.
 *
 * <p>Out of scope by construction: causality, statistical significance, p-values and effect sizes.
 * Any association reported between a controlled condition, a signal source or a confidence band and
 * an observed error must not be interpreted as a causal relationship.
 */
public final class ResearchFailureAnalysis {

    public static final String FAILURE_ANALYSIS_VERSION = "failure-analysis-v1";
    public static final int SMALL_SAMPLE_THRESHOLD = 5;
    public static final String SMALL_SAMPLE_DISCLOSURE = "Small sample; interpret descriptively.";
    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 100;

    /** Deterministic order of error categories reported by {@code EvaluatorCategories}. */
    public static final List<String> CATEGORY_ORDER = List.of(
            "BASELINE_FALSE_POSITIVE", "BASELINE_FALSE_NEGATIVE",
            "FUSION_FALSE_POSITIVE", "FUSION_FALSE_NEGATIVE",
            "BASELINE_ONLY_POSITIVE", "FUSION_ONLY_POSITIVE");

    /** Deterministic order of per-sample prediction transition classifications. */
    public static final List<String> TRANSITION_ORDER = List.of(
            "CORRECT_TO_CORRECT", "CORRECT_TO_WRONG", "WRONG_TO_CORRECT", "WRONG_TO_WRONG");

    private static final List<String> BAND_LABELS = List.of(
            "0.00-0.19", "0.20-0.39", "0.40-0.59", "0.60-0.79", "0.80-1.00");

    /** Only AI signal sources participate in confidence bands. */
    private static final Set<String> AI_SOURCES = Set.of("CLIENT_AI", "SERVER_AI");

    private ResearchFailureAnalysis() {
    }

    public static FailureReport analyze(List<AnalysisSample> samples,
                                        String datasetVersion, String baselineVersion, String fusionVersion) {
        List<AnalysisSample> evaluable = new ArrayList<>();
        if (samples != null) {
            for (AnalysisSample sample : samples) {
                if (sample != null && sample.groundTruthLabel() != null) {
                    evaluable.add(sample);
                }
            }
        }

        List<AnalysisSample> failures = new ArrayList<>();
        int disagreementCount = 0;
        int changedPredictionCount = 0;
        for (AnalysisSample sample : evaluable) {
            if (!transitionIsCorrect(sample)) {
                failures.add(sample);
            }
            if (sample.baselinePositive() != sample.fusionPositive()) {
                disagreementCount++;
                changedPredictionCount++;
            }
        }

        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (String category : CATEGORY_ORDER) {
            categoryCounts.put(category, 0);
        }
        for (AnalysisSample sample : failures) {
            for (String category : categoriesOf(sample)) {
                categoryCounts.merge(category, 1, Integer::sum);
            }
        }

        Map<String, Integer> transitionCounts = new LinkedHashMap<>();
        for (String transition : TRANSITION_ORDER) {
            transitionCounts.put(transition, 0);
        }
        for (AnalysisSample sample : evaluable) {
            transitionCounts.merge(transitionOf(sample), 1, Integer::sum);
        }

        Map<String, Integer> disagreementCategoryCounts = new LinkedHashMap<>();
        disagreementCategoryCounts.put("BASELINE_ONLY_POSITIVE", 0);
        disagreementCategoryCounts.put("FUSION_ONLY_POSITIVE", 0);
        for (AnalysisSample sample : evaluable) {
            if (sample.baselinePositive() != sample.fusionPositive()) {
                String category = sample.baselinePositive() ? "BASELINE_ONLY_POSITIVE" : "FUSION_ONLY_POSITIVE";
                disagreementCategoryCounts.merge(category, 1, Integer::sum);
            }
        }

        SignalProfile signalProfile = buildSignalProfile(failures, categoryCounts);
        int missingConfidenceSignalCount = countMissingConfidenceSignals(evaluable);
        List<ConfidenceBand> bands = buildConfidenceBands(evaluable, fusionVersion);
        List<EnvironmentalFailure> environmentalFailures = buildEnvironmentalFailures(evaluable);
        int missingConditionCount = countMissingConditions(evaluable);
        List<ScenarioFailure> scenarioFailures = buildScenarioFailures(evaluable);
        SignalAssociation signalAssociation = buildSignalAssociation(failures);
        TransitionSummary transitions = buildTransitions(evaluable);
        List<FailurePattern> patterns = buildPatterns(evaluable, failures, categoryCounts, disagreementCount,
                environmentalFailures, baselineVersion, fusionVersion);

        List<FailureCase> failureCases = new ArrayList<>();
        for (AnalysisSample failure : failures) {
            failureCases.add(toFailureCase(failure));
        }
        failureCases.sort(java.util.Comparator.comparing(FailureCase::sampleId));

        return new FailureReport(datasetVersion, baselineVersion, fusionVersion,
                ResearchStatisticalAnalysis.ANALYSIS_VERSION, FAILURE_ANALYSIS_VERSION, Instant.now(),
                evaluable.size(), failures.size(), disagreementCount, changedPredictionCount,
                new EvaluatorCategories(toCounted(categoryCounts)),
                new TransitionCategories(toCounted(transitionCounts)),
                new DisagreementAnalysis(toCounted(disagreementCategoryCounts)),
                signalProfile, bands, missingConfidenceSignalCount,
                environmentalFailures, missingConditionCount, scenarioFailures,
                signalAssociation, transitions, patterns, failureCases);
    }

    private static boolean transitionIsCorrect(AnalysisSample sample) {
        boolean groundPositive = groundPositive(sample.groundTruthLabel());
        return sample.baselinePositive() == groundPositive && sample.fusionPositive() == groundPositive;
    }

    private static SignalProfile buildSignalProfile(List<AnalysisSample> failures,
                                                    Map<String, Integer> categoryCounts) {
        List<SignalProfileRow> rows = new ArrayList<>();
        for (String category : CATEGORY_ORDER) {
            if (categoryCounts.get(category) == null || categoryCounts.get(category) == 0) {
                continue;
            }
            List<AnalysisSample> categorySamples = new ArrayList<>();
            for (AnalysisSample sample : failures) {
                if (categoriesOf(sample).contains(category)) {
                    categorySamples.add(sample);
                }
            }
            for (String source : ResearchStatisticalAnalysis.CONFIDENCE_SOURCE_ORDER) {
                List<FusionSignal> signals = new ArrayList<>();
                Set<String> presentSampleIds = new java.util.HashSet<>();
                for (AnalysisSample sample : categorySamples) {
                    for (FusionSignal signal : sample.signals()) {
                        if (source.equals(signal.source())) {
                            signals.add(signal);
                            presentSampleIds.add(sample.sampleId());
                        }
                    }
                }
                if (signals.isEmpty()) {
                    continue;
                }
                List<Double> confidences = new ArrayList<>();
                List<Long> durations = new ArrayList<>();
                for (FusionSignal signal : signals) {
                    confidences.add(signal.confidence());
                    durations.add(signal.durationMs());
                }
                rows.add(new SignalProfileRow(category, source, signals.size(), presentSampleIds.size(),
                        descriptive(confidences), descriptiveLong(durations)));
            }
        }
        return new SignalProfile(rows);
    }

    private static int countMissingConfidenceSignals(List<AnalysisSample> evaluable) {
        int count = 0;
        for (AnalysisSample sample : evaluable) {
            for (FusionSignal signal : sample.signals()) {
                if (AI_SOURCES.contains(signal.source()) && signal.confidence() == null) {
                    count++;
                }
            }
        }
        return count;
    }

    private static List<ConfidenceBand> buildConfidenceBands(List<AnalysisSample> evaluable, String fusionVersion) {
        Map<Integer, List<AnalysisSample>> bandSamples = new LinkedHashMap<>();
        int[] bandSignalCounts = new int[BAND_LABELS.size()];
        for (AnalysisSample sample : evaluable) {
            Double maxConfidence = null;
            int aiSignalsWithConfidence = 0;
            for (FusionSignal signal : sample.signals()) {
                if (AI_SOURCES.contains(signal.source()) && signal.confidence() != null) {
                    aiSignalsWithConfidence++;
                    maxConfidence = maxConfidence == null || signal.confidence() > maxConfidence
                            ? signal.confidence() : maxConfidence;
                }
            }
            if (maxConfidence != null) {
                int index = bandIndex(maxConfidence);
                bandSamples.computeIfAbsent(index, ignored -> new ArrayList<>()).add(sample);
                bandSignalCounts[index] += aiSignalsWithConfidence;
            }
        }

        List<ConfidenceBand> bands = new ArrayList<>();
        for (int index = 0; index < BAND_LABELS.size(); index++) {
            List<AnalysisSample> inBand = bandSamples.get(index);
            if (inBand == null || inBand.isEmpty()) {
                continue;
            }
            long[] confusion = confusion(inBand, false);
            bands.add(new ConfidenceBand(BAND_LABELS.get(index), bandSignalCounts[index], inBand.size(),
                    confusion[0], confusion[1], confusion[2], confusion[3],
                    rate(confusion[0], confusion[0] + confusion[2]),
                    rate(confusion[0], confusion[0] + confusion[3]),
                    rate(confusion[2], confusion[2] + confusion[1]),
                    rate(confusion[3], confusion[0] + confusion[3]),
                    rate(confusion[2], confusion[2] + confusion[0]),
                    fusionVersion));
        }
        return bands;
    }

    private static List<EnvironmentalFailure> buildEnvironmentalFailures(List<AnalysisSample> evaluable) {
        List<EnvironmentalFailure> groups = new ArrayList<>();
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            Map<String, List<AnalysisSample>> byValue = new TreeMap<>();
            for (AnalysisSample sample : evaluable) {
                String value = sample.conditions() == null ? null : sample.conditions().get(key);
                if (value != null) {
                    byValue.computeIfAbsent(value, ignored -> new ArrayList<>()).add(sample);
                }
            }
            for (Map.Entry<String, List<AnalysisSample>> entry : byValue.entrySet()) {
                String condition = key + "=" + entry.getKey();
                List<AnalysisSample> group = entry.getValue();
                int sampleCount = group.size();
                long[] baseline = confusion(group, true);
                long[] fusion = confusion(group, false);
                int disagreementCount = 0;
                for (AnalysisSample sample : group) {
                    if (sample.baselinePositive() != sample.fusionPositive()) {
                        disagreementCount++;
                    }
                }
                groups.add(new EnvironmentalFailure(condition, sampleCount, isSmallSample(sampleCount),
                        baseline[2], baseline[3], fusion[2], fusion[3],
                        rate(baseline[2], baseline[2] + baseline[1]),
                        rate(baseline[3], baseline[0] + baseline[3]),
                        rate(fusion[2], fusion[2] + fusion[1]),
                        rate(fusion[3], fusion[0] + fusion[3]),
                        disagreementCount));
            }
        }
        return groups;
    }

    private static List<ScenarioFailure> buildScenarioFailures(List<AnalysisSample> evaluable) {
        List<ScenarioFailure> groups = new ArrayList<>();
        for (String scenario : ResearchConfig.SCENARIO_ORDER) {
            List<AnalysisSample> group = new ArrayList<>();
            for (AnalysisSample sample : evaluable) {
                if (scenario.equals(sample.scenario())) {
                    group.add(sample);
                }
            }
            if (group.isEmpty()) {
                continue;
            }
            int sampleCount = group.size();
            long[] baseline = confusion(group, true);
            long[] fusion = confusion(group, false);
            int disagreementCount = 0;
            for (AnalysisSample sample : group) {
                if (sample.baselinePositive() != sample.fusionPositive()) {
                    disagreementCount++;
                }
            }
            groups.add(new ScenarioFailure(scenario, sampleCount, isSmallSample(sampleCount),
                    baseline[2], baseline[3], fusion[2], fusion[3],
                    rate(baseline[2], baseline[2] + baseline[1]),
                    rate(baseline[3], baseline[0] + baseline[3]),
                    rate(fusion[2], fusion[2] + fusion[1]),
                    rate(fusion[3], fusion[0] + fusion[3]),
                    disagreementCount));
        }
        return groups;
    }

    private static SignalAssociation buildSignalAssociation(List<AnalysisSample> failures) {
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        for (String source : ResearchStatisticalAnalysis.CONFIDENCE_SOURCE_ORDER) {
            int count = 0;
            for (AnalysisSample failure : failures) {
                boolean present = false;
                for (FusionSignal signal : failure.signals()) {
                    if (source.equals(signal.source())) {
                        present = true;
                        break;
                    }
                }
                if (present) {
                    count++;
                }
            }
            if (count > 0) {
                sourceCounts.put(source, count);
            }
        }
        return new SignalAssociation(failures.size(), distinctErrorSamplesWithoutSignals(failures),
                toCounted(sourceCounts));
    }

    private static int distinctErrorSamplesWithoutSignals(List<AnalysisSample> failures) {
        int count = 0;
        for (AnalysisSample failure : failures) {
            if (failure.signals().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static TransitionSummary buildTransitions(List<AnalysisSample> evaluable) {
        List<AnalysisSample> changed = new ArrayList<>();
        for (AnalysisSample sample : evaluable) {
            if (sample.baselinePositive() != sample.fusionPositive()) {
                changed.add(sample);
            }
        }
        int changedCount = changed.size();
        int baselineCorrectToFusionWrong = 0;
        int baselineWrongToFusionCorrect = 0;
        for (AnalysisSample sample : changed) {
            boolean groundPositive = groundPositive(sample.groundTruthLabel());
            if (sample.baselinePositive() == groundPositive && sample.fusionPositive() != groundPositive) {
                baselineCorrectToFusionWrong++;
            }
            if (sample.baselinePositive() != groundPositive && sample.fusionPositive() == groundPositive) {
                baselineWrongToFusionCorrect++;
            }
        }

        List<Distribution> byScenario = new ArrayList<>();
        for (String scenario : ResearchConfig.SCENARIO_ORDER) {
            int count = 0;
            for (AnalysisSample sample : changed) {
                if (scenario.equals(sample.scenario())) {
                    count++;
                }
            }
            if (count > 0) {
                byScenario.add(new Distribution(scenario, count));
            }
        }

        List<Distribution> byCondition = new ArrayList<>();
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            Map<String, Integer> perValue = new TreeMap<>();
            for (AnalysisSample sample : changed) {
                String value = sample.conditions() == null ? null : sample.conditions().get(key);
                if (value != null) {
                    perValue.merge(value, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> entry : perValue.entrySet()) {
                byCondition.add(new Distribution(key + "=" + entry.getKey(), entry.getValue()));
            }
        }

        List<Distribution> bySignalSource = new ArrayList<>();
        for (String source : ResearchStatisticalAnalysis.CONFIDENCE_SOURCE_ORDER) {
            int count = 0;
            for (AnalysisSample sample : changed) {
                for (FusionSignal signal : sample.signals()) {
                    if (source.equals(signal.source())) {
                        count++;
                        break;
                    }
                }
            }
            if (count > 0) {
                bySignalSource.add(new Distribution(source, count));
            }
        }

        List<Distribution> byConfidenceBand = new ArrayList<>();
        Map<Integer, Integer> bandCounts = new TreeMap<>();
        for (AnalysisSample sample : changed) {
            Double maxConfidence = null;
            for (FusionSignal signal : sample.signals()) {
                if (AI_SOURCES.contains(signal.source()) && signal.confidence() != null) {
                    maxConfidence = maxConfidence == null || signal.confidence() > maxConfidence
                            ? signal.confidence() : maxConfidence;
                }
            }
            if (maxConfidence != null) {
                bandCounts.merge(bandIndex(maxConfidence), 1, Integer::sum);
            }
        }
        for (Map.Entry<Integer, Integer> entry : bandCounts.entrySet()) {
            byConfidenceBand.add(new Distribution(BAND_LABELS.get(entry.getKey()), entry.getValue()));
        }

        Double correctToWrongFraction = changedCount == 0 ? null : (double) baselineCorrectToFusionWrong / changedCount;
        Double wrongToCorrectFraction = changedCount == 0 ? null : (double) baselineWrongToFusionCorrect / changedCount;
        return new TransitionSummary(changedCount, baselineCorrectToFusionWrong, baselineWrongToFusionCorrect,
                correctToWrongFraction, wrongToCorrectFraction,
                byScenario, byCondition, bySignalSource, byConfidenceBand);
    }

    private static List<FailurePattern> buildPatterns(List<AnalysisSample> evaluable, List<AnalysisSample> failures,
                                                      Map<String, Integer> categoryCounts, int disagreementCount,
                                                      List<EnvironmentalFailure> environmentalFailures,
                                                      String baselineVersion, String fusionVersion) {
        List<FailurePattern> patterns = new ArrayList<>();
        if (evaluable.isEmpty()) {
            patterns.add(new FailurePattern("No evaluable samples in scope."));
            return patterns;
        }

        int baselineFalsePositives = categoryCounts.getOrDefault("BASELINE_FALSE_POSITIVE", 0);
        int baselineFalseNegatives = categoryCounts.getOrDefault("BASELINE_FALSE_NEGATIVE", 0);
        int fusionFalsePositives = categoryCounts.getOrDefault("FUSION_FALSE_POSITIVE", 0);
        int fusionFalseNegatives = categoryCounts.getOrDefault("FUSION_FALSE_NEGATIVE", 0);
        if (baselineFalsePositives > 0) {
            patterns.add(new FailurePattern(baselineFalsePositives
                    + " false-positive observations were recorded for " + baselineVersion + "."));
        }
        if (baselineFalseNegatives > 0) {
            patterns.add(new FailurePattern(baselineFalseNegatives
                    + " false-negative observations were recorded for " + baselineVersion + "."));
        }
        if (fusionFalsePositives > 0) {
            patterns.add(new FailurePattern(fusionFalsePositives
                    + " false-positive observations were recorded for " + fusionVersion + "."));
        }
        if (fusionFalseNegatives > 0) {
            patterns.add(new FailurePattern(fusionFalseNegatives
                    + " false-negative observations were recorded for " + fusionVersion + "."));
        }
        if (disagreementCount > 0) {
            patterns.add(new FailurePattern(disagreementCount + " baseline/fusion disagreements occurred."));
        }

        for (String scenario : ResearchConfig.SCENARIO_ORDER) {
            int count = 0;
            for (AnalysisSample failure : failures) {
                if (scenario.equals(failure.scenario())) {
                    count++;
                }
            }
            if (count > 0) {
                patterns.add(new FailurePattern(count + " failure samples were recorded for scenario " + scenario + "."));
            }
        }

        for (EnvironmentalFailure group : environmentalFailures) {
            if (group.fusionFalsePositives() > 0) {
                String condition = group.condition();
                int separator = condition.indexOf('=');
                patterns.add(new FailurePattern(group.fusionFalsePositives()
                        + " false-positive samples were recorded under "
                        + condition.substring(separator + 1) + " " + condition.substring(0, separator) + "."));
            }
        }

        for (String source : ResearchStatisticalAnalysis.CONFIDENCE_SOURCE_ORDER) {
            int count = 0;
            for (AnalysisSample sample : evaluable) {
                for (FusionSignal signal : sample.signals()) {
                    if (source.equals(signal.source())) {
                        count++;
                        break;
                    }
                }
            }
            if (count > 0) {
                patterns.add(new FailurePattern(count + " of " + evaluable.size()
                        + " evaluated samples contained " + source + " signals."));
            }
        }
        return patterns;
    }

    private static FailureCase toFailureCase(AnalysisSample sample) {
        List<String> types = sample.signals().stream()
                .map(FusionSignal::type)
                .filter(type -> type != null && !type.isBlank())
                .distinct()
                .sorted()
                .toList();

        List<String> sources = new ArrayList<>();
        for (String source : ResearchStatisticalAnalysis.CONFIDENCE_SOURCE_ORDER) {
            boolean present = sample.signals().stream().anyMatch(signal -> source.equals(signal.source()));
            if (present) {
                sources.add(source);
            }
        }

        Double confidenceMin = null;
        Double confidenceMax = null;
        Long durationMin = null;
        Long durationMax = null;
        for (FusionSignal signal : sample.signals()) {
            if (AI_SOURCES.contains(signal.source()) && signal.confidence() != null) {
                confidenceMin = confidenceMin == null
                        ? signal.confidence() : Math.min(confidenceMin, signal.confidence());
                confidenceMax = confidenceMax == null
                        ? signal.confidence() : Math.max(confidenceMax, signal.confidence());
            }
            if (signal.durationMs() != null) {
                durationMin = durationMin == null
                        ? signal.durationMs() : Math.min(durationMin, signal.durationMs());
                durationMax = durationMax == null
                        ? signal.durationMs() : Math.max(durationMax, signal.durationMs());
            }
        }
        Range confidenceRange = confidenceMin == null ? null : new Range(confidenceMin, confidenceMax);
        Range durationRange = durationMin == null ? null : new Range((double) durationMin, (double) durationMax);

        return new FailureCase(sample.sampleId(), sample.scenario(), sample.groundTruthLabel(),
                sample.baselinePositive(), sample.fusionPositive(), transitionOf(sample),
                conditionsJoined(sample.conditions()), types, sources, confidenceRange, durationRange);
    }

    private static String conditionsJoined(Map<String, String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        Collections.sort(parts);
        return String.join(";", parts);
    }

    private static String transitionOf(AnalysisSample sample) {
        boolean groundPositive = groundPositive(sample.groundTruthLabel());
        boolean baselineCorrect = sample.baselinePositive() == groundPositive;
        boolean fusionCorrect = sample.fusionPositive() == groundPositive;
        if (baselineCorrect && fusionCorrect) {
            return "CORRECT_TO_CORRECT";
        }
        if (baselineCorrect) {
            return "CORRECT_TO_WRONG";
        }
        if (fusionCorrect) {
            return "WRONG_TO_CORRECT";
        }
        return "WRONG_TO_WRONG";
    }

    private static boolean groundPositive(String groundTruthLabel) {
        return !ResearchValidation.isNegativeLabel(groundTruthLabel);
    }

    private static List<String> categoriesOf(AnalysisSample sample) {
        boolean groundPositive = groundPositive(sample.groundTruthLabel());
        List<String> result = new ArrayList<>();
        if (sample.baselinePositive() != groundPositive) {
            result.add(sample.baselinePositive() ? "BASELINE_FALSE_POSITIVE" : "BASELINE_FALSE_NEGATIVE");
        }
        if (sample.fusionPositive() != groundPositive) {
            result.add(sample.fusionPositive() ? "FUSION_FALSE_POSITIVE" : "FUSION_FALSE_NEGATIVE");
        }
        if (sample.baselinePositive() != sample.fusionPositive()) {
            result.add(sample.baselinePositive() ? "BASELINE_ONLY_POSITIVE" : "FUSION_ONLY_POSITIVE");
        }
        return result;
    }

    private static long[] confusion(List<AnalysisSample> samples, boolean useBaseline) {
        long truePositives = 0;
        long trueNegatives = 0;
        long falsePositives = 0;
        long falseNegatives = 0;
        for (AnalysisSample sample : samples) {
            boolean groundPositive = groundPositive(sample.groundTruthLabel());
            boolean predicted = useBaseline ? sample.baselinePositive() : sample.fusionPositive();
            if (predicted) {
                if (groundPositive) {
                    truePositives++;
                } else {
                    falsePositives++;
                }
            } else {
                if (groundPositive) {
                    falseNegatives++;
                } else {
                    trueNegatives++;
                }
            }
        }
        return new long[]{truePositives, trueNegatives, falsePositives, falseNegatives};
    }

    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static boolean isSmallSample(int sampleCount) {
        return sampleCount > 0 && sampleCount < SMALL_SAMPLE_THRESHOLD;
    }

    private static int bandIndex(double confidence) {
        if (confidence < 0.2) {
            return 0;
        }
        if (confidence < 0.4) {
            return 1;
        }
        if (confidence < 0.6) {
            return 2;
        }
        if (confidence < 0.8) {
            return 3;
        }
        return 4;
    }

    private static List<Counted> toCounted(Map<String, Integer> counts) {
        List<Counted> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                result.add(new Counted(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    private static DescriptiveStats descriptive(List<Double> values) {
        List<Double> present = new ArrayList<>();
        for (Double value : values) {
            if (value != null) {
                present.add(value);
            }
        }
        if (present.isEmpty()) {
            return new DescriptiveStats(0, null, null, null, null);
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0.0;
        for (Double value : present) {
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
        }
        List<Double> sorted = new ArrayList<>(present);
        Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);
        if (sorted.size() % 2 == 0) {
            median = (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0;
        }
        return new DescriptiveStats(present.size(), sum / present.size(), median, min, max);
    }

    private static DescriptiveStats descriptiveLong(List<Long> values) {
        List<Double> asDoubles = new ArrayList<>();
        for (Long value : values) {
            asDoubles.add(value == null ? null : (double) value);
        }
        return descriptive(asDoubles);
    }

    private static int countMissingConditions(List<AnalysisSample> evaluable) {
        int count = 0;
        for (AnalysisSample sample : evaluable) {
            if (!hasAllControlledConditions(sample.conditions())) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasAllControlledConditions(Map<String, String> conditions) {
        if (conditions == null) {
            return false;
        }
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            if (conditions.get(key) == null) {
                return false;
            }
        }
        return true;
    }

    /** A deterministic copy of the report with a fresh timestamp; used only by clients that need to compare runs. */
    public record FailureReport(String datasetVersion, String baselineVersion, String fusionVersion,
                                String analysisVersion, String failureAnalysisVersion, Instant generatedAt,
                                int evaluableSampleCount, int failureCaseCount, int disagreementCount,
                                int changedPredictionCount,
                                EvaluatorCategories categories, TransitionCategories transitionCategories,
                                DisagreementAnalysis disagreement, SignalProfile signalProfile,
                                List<ConfidenceBand> confidenceBands, int missingConfidenceSignalCount,
                                List<EnvironmentalFailure> environmentalFailures, int missingConditionCount,
                                List<ScenarioFailure> scenarioFailures, SignalAssociation signalAssociation,
                                TransitionSummary transitions, List<FailurePattern> patterns,
                                List<FailureCase> failureCases) {
    }

    /** One counted value in a fixed-order summary (category, source, distribution). */
    public record Counted(String value, int count) {
    }

    /** Error category counts over the failure samples (false positives, false negatives, one-sided positives). */
    public record EvaluatorCategories(List<Counted> categories) {
    }

    /** Per-sample prediction transition classification counts over the evaluable samples. */
    public record TransitionCategories(List<Counted> categories) {
    }

    /** Counts of baseline/fusion disagreements split by direction. */
    public record DisagreementAnalysis(List<Counted> categories) {
    }

    /** Descriptive statistics (count/min/max/mean/median) over non-null values. */
    public record DescriptiveStats(int measuredCount, Double mean, Double median, Double min, Double max) {
    }

    /** Signal association per error category and signal source. */
    public record SignalProfileRow(String category, String source, int signalCount, int sampleCount,
                                   DescriptiveStats confidence, DescriptiveStats duration) {
    }

    public record SignalProfile(List<SignalProfileRow> rows) {
    }

    /** Confidence band of samples grouped by the maximum non-null AI signal confidence. */
    public record ConfidenceBand(String band, int signalCount, int distinctSampleCount,
                                 long truePositives, long trueNegatives, long falsePositives, long falseNegatives,
                                 Double precision, Double recall, Double falsePositiveRate,
                                 Double falseNegativeRate, Double falseDiscoveryRate, String evaluator) {
    }

    /** Per-controlled-condition failure summary. Confusion and rates are reported per evaluator. */
    public record EnvironmentalFailure(String condition, int sampleCount, boolean smallSample,
                                       long baselineFalsePositives, long baselineFalseNegatives,
                                       long fusionFalsePositives, long fusionFalseNegatives,
                                       Double baselineFalsePositiveRate, Double baselineFalseNegativeRate,
                                       Double fusionFalsePositiveRate, Double fusionFalseNegativeRate,
                                       int disagreementCount) {
    }

    /** Per-scenario failure summary. Confusion and rates are reported per evaluator. */
    public record ScenarioFailure(String scenario, int sampleCount, boolean smallSample,
                                  long baselineFalsePositives, long baselineFalseNegatives,
                                  long fusionFalsePositives, long fusionFalseNegatives,
                                  Double baselineFalsePositiveRate, Double baselineFalseNegativeRate,
                                  Double fusionFalsePositiveRate, Double fusionFalseNegativeRate,
                                  int disagreementCount) {
    }

    /** A count within a distribution over a fixed ordered axis. */
    public record Distribution(String value, int count) {
    }

    /** Prediction-transition summary over samples where baseline and fusion changed predictions. */
    public record TransitionSummary(int changedPredictionCount, int baselineCorrectToFusionWrong,
                                    int baselineWrongToFusionCorrect,
                                    Double baselineCorrectToFusionWrongFraction,
                                    Double baselineWrongToFusionCorrectFraction,
                                    List<Distribution> byScenario, List<Distribution> byCondition,
                                    List<Distribution> bySignalSource, List<Distribution> byConfidenceBand) {
    }

    /** Signal-source presence among the failure samples. */
    public record SignalAssociation(int errorSampleCount, int errorSamplesWithoutSignals,
                                    List<Counted> sources) {
    }

    /** A min/max range; null when nothing measurable was present. */
    public record Range(Double min, Double max) {
    }

    /** A single failure sample, identified only by its anonymised sample id. */
    public record FailureCase(String sampleId, String scenario, String groundTruth,
                              boolean baselinePrediction, boolean fusionPrediction,
                              String classification, String condition,
                              List<String> signalTypes, List<String> signalSources,
                              Range confidenceRange, Range durationRange) {
    }

    /** A deterministic neutral sentence summarising an observed pattern. */
    public record FailurePattern(String statement) {
    }
}