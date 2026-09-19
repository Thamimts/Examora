package com.examora.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure, deterministic, read-only statistical analysis of the controlled research dataset.
 * It has no database access, no random sampling, no AI calls and no side effects: repeated
 * analysis of the same inputs produces the identical {@code Report} except for the
 * {@code generatedAt} stamp.
 *
 * <p>Everything here is descriptive. The report never labels an evaluator "better" or
 * "worse", never ranks, and never claims generalization; it reports observed metric deltas
 * (fusion minus baseline) and lets the consumer interpret them.
 */
public final class ResearchStatisticalAnalysis {

    private ResearchStatisticalAnalysis() {}

    public static final String ANALYSIS_VERSION = "analysis-v1";
    public static final double CONFIDENCE_LEVEL = 0.95;
    public static final double WILSON_Z = 1.96;

    public static final List<String> METRIC_ORDER = List.of(
            "precision", "recall", "specificity", "accuracy",
            "falsePositiveRate", "falseNegativeRate", "f1", "fdr");

    public static final List<String> CONFIDENCE_SOURCE_ORDER = List.of(
            "BROWSER", "CLIENT_AI", "SERVER_AI", "HUMAN_INVIGILATOR", "SYSTEM");

    /**
     * A single captured sample in the analysis scope. Ground truth is the resolved human-review
     * majority of the sample; it is null for unreviewed, tied or invalid samples. No student
     * identifiers are ever carried here.
     */
    public record AnalysisSample(String sampleId, String scenario, Map<String, String> conditions,
                                 String groundTruthLabel, boolean baselinePositive, boolean fusionPositive,
                                 Double baselineScore, Double fusionScore,
                                 List<ProctorFusionService.FusionSignal> signals,
                                 Long measuredLatencyMs, Long rawMediaBytes, Long signalBytes,
                                 Instant windowStart) {
    }

    /** A human review observation (sample id, reviewer id, review label). Descriptive input only. */
    public record ReviewObservation(String sampleId, String reviewerId, String label) {
    }

    /**
     * Data-quality counts over the analysis scope, derived in the service layer from persisted
     * rows. The pure class uses these for the dataset overview and computes the evaluable coverage.
     */
    public record DataQualityCounts(int registeredSamples, int capturedSamples, int reviewedSamples,
                                    int evaluableSamples, int unreviewedSamples, int tiedSamples,
                                    int invalidSamples, int missingSignalSamples,
                                    int missingConditionSamples, int missingLatencySamples,
                                    int missingBandwidthSamples) {
    }

    public record EvaluatorResult(String version, ResearchMetrics.Confusion confusion,
                                  ResearchMetrics.EvaluatorMetrics metrics) {
    }

    /** Observed difference of one metric, fusion minus baseline; null when either side is null. */
    public record MetricDelta(String metric, Double delta) {
    }

    public record DisagreementSummary(String category, int count, Double percentage) {
    }

    /** A single sample where baseline and fusion disagree (a "prediction change"). */
    public record DisagreementSample(String sampleId, String scenario, Map<String, String> conditions,
                                     String groundTruthLabel, boolean baselinePositive, boolean fusionPositive,
                                     String signalSummary) {
    }

    /** Per-scenario or per-condition metrics and deltas over evaluable samples. */
    public record Stratum(String value, int sampleCount, List<AnalysisSample> samples,
                          EvaluatorResult baseline, EvaluatorResult fusion, List<MetricDelta> deltas) {
    }

    /** Descriptive confidence distribution for one signal source. */
    public record Confidence(String source, int sampleCount, int measuredCount,
                             Double min, Double max, Double mean, Double median) {
    }

    /** Descriptive statistics (count/min/max/mean/median) for a latency or byte series. */
    public record Descriptive(int measuredCount, Double mean, Double median, Double min, Double max) {

        public static final Descriptive EMPTY = new Descriptive(0, null, null, null, null);
    }

    /** Measured (researcher-recorded) and stored-window (derived) latency, both descriptive. */
    public record Latency(Descriptive measured, Descriptive storedWindow) {
    }

    public record StratumLatency(String value, int sampleCount, Latency latency) {
    }

    public record Bandwidth(Descriptive rawMedia, Descriptive signalBytes) {
    }

    public record ReviewAgreement(int reviewedSamples, int resolvedSamples, int tiedSamples,
                                  Double agreementRate, boolean singleReviewerDataset,
                                  Double pairwiseAgreementRate) {
    }

    /** Wilson score interval around a proportion estimate. F1 has no justified CI and is omitted. */
    public record ConfidenceInterval(String metric, double estimate, Double lower, Double upper,
                                     double confidenceLevel, String method) {
    }

    public record PredictionTransitions(int baselineErrorCount, int fusionErrorCount,
                                        int baselineCorrectCount, int fusionCorrectCount,
                                        int changedPredictionCount,
                                        int baselineCorrectToFusionWrong,
                                        int baselineWrongToFusionCorrect) {
    }

    /**
     * Complete descriptive analysis report. All lists are deterministically ordered; numeric
     * values are never NaN or infinite (unavailable values are null). Only {@code generatedAt}
     * varies between repeated analyses of identical inputs.
     */
    public record Report(String datasetVersion, String baselineVersion, String fusionVersion,
                         String analysisVersion, Instant generatedAt,
                         List<AnalysisSample> sampleRecords,
                         DataQualityCounts dataQuality, Double evaluableCoverage,
                         EvaluatorResult baseline, EvaluatorResult fusion,
                         List<MetricDelta> metricDeltas,
                         List<DisagreementSummary> disagreementSummary,
                         List<DisagreementSample> disagreementSamples,
                         List<Stratum> scenarios,
                         List<Stratum> conditions,
                         List<Confidence> confidence,
                         Latency latency,
                         List<StratumLatency> latencyByScenario,
                         List<StratumLatency> latencyByCondition,
                         Bandwidth bandwidth,
                         ReviewAgreement reviewAgreement,
                         List<ConfidenceInterval> confidenceIntervals,
                         PredictionTransitions transitions) {
    }

    public static Report analyze(List<AnalysisSample> samples,
                                 List<ReviewObservation> reviews,
                                 DataQualityCounts dataQuality,
                                 String datasetVersion, String baselineVersion, String fusionVersion) {
        Instant generatedAt = Instant.now();
        List<AnalysisSample> captured = new ArrayList<>();
        if (samples != null) {
            for (AnalysisSample sample : samples) {
                if (sample != null) {
                    captured.add(sample);
                }
            }
        }
        List<AnalysisSample> evaluable = captured.stream().filter(s -> s.groundTruthLabel() != null).toList();

        ResearchMetrics.Confusion baselineConfusion = ResearchMetrics.Confusion.EMPTY;
        ResearchMetrics.Confusion fusionConfusion = ResearchMetrics.Confusion.EMPTY;
        for (AnalysisSample sample : evaluable) {
            boolean actualPositive = !ResearchValidation.isNegativeLabel(sample.groundTruthLabel());
            baselineConfusion = baselineConfusion.accumulate(sample.baselinePositive(), actualPositive);
            fusionConfusion = fusionConfusion.accumulate(sample.fusionPositive(), actualPositive);
        }
        EvaluatorResult baselineResult = new EvaluatorResult(baselineVersion,
                baselineConfusion, ResearchMetrics.compute(baselineConfusion));
        EvaluatorResult fusionResult = new EvaluatorResult(fusionVersion,
                fusionConfusion, ResearchMetrics.compute(fusionConfusion));

        List<MetricDelta> deltas = metricDeltas(baselineResult.metrics(), fusionResult.metrics());
        List<DisagreementSummary> disagreements = disagreementSummary(evaluable);
        List<DisagreementSample> disagreementSamples = disagreementSamples(evaluable);
        List<Stratum> scenarios = scenarioStrata(evaluable, baselineVersion, fusionVersion);
        List<Stratum> conditions = conditionStrata(evaluable, baselineVersion, fusionVersion);
        List<Confidence> confidence = confidenceDistribution(captured);
        Latency latency = latency(captured);
        List<StratumLatency> latencyByScenario = latencyByScenario(captured);
        List<StratumLatency> latencyByCondition = latencyByCondition(captured);
        Bandwidth bandwidth = bandwidth(captured);
        ReviewAgreement reviewAgreement = reviewAgreement(reviews);
        List<ConfidenceInterval> intervals = confidenceIntervals(baselineConfusion, fusionConfusion);
        PredictionTransitions transitions = transitions(evaluable);

        Double evaluableCoverage = dataQuality.capturedSamples() <= 0 ? null
                : (double) dataQuality.evaluableSamples() / (double) dataQuality.capturedSamples();

        return new Report(datasetVersion, baselineVersion, fusionVersion, ANALYSIS_VERSION, generatedAt,
                captured, dataQuality, evaluableCoverage,
                baselineResult, fusionResult, deltas, disagreements, disagreementSamples,
                scenarios, conditions, confidence, latency,
                latencyByScenario, latencyByCondition, bandwidth,
                reviewAgreement, intervals, transitions);
    }

    /** Fusion minus baseline for each metric; null when either side is null. Ordered deterministically. */
    public static List<MetricDelta> metricDeltas(ResearchMetrics.EvaluatorMetrics baseline,
                                                 ResearchMetrics.EvaluatorMetrics fusion) {
        List<MetricDelta> result = new ArrayList<>();
        Map<String, Double> values = metricValueMap(fusion);
        Map<String, Double> baseValues = metricValueMap(baseline);
        for (String metric : METRIC_ORDER) {
            Double base = baseValues.get(metric);
            Double value = values.get(metric);
            Double delta = base == null || value == null ? null : value - base;
            result.add(new MetricDelta(metric, delta));
        }
        return result;
    }

    private static Map<String, Double> metricValueMap(ResearchMetrics.EvaluatorMetrics m) {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("precision", m.precision());
        map.put("recall", m.recall());
        map.put("specificity", m.specificity());
        map.put("accuracy", m.accuracy());
        map.put("falsePositiveRate", m.falsePositiveRate());
        map.put("falseNegativeRate", m.falseNegativeRate());
        map.put("f1", m.f1());
        map.put("fdr", m.fdr());
        return map;
    }

    private static List<DisagreementSummary> disagreementSummary(List<AnalysisSample> evaluable) {
        int agreePositive = 0;
        int agreeNegative = 0;
        int baselineOnly = 0;
        int fusionOnly = 0;
        for (AnalysisSample sample : evaluable) {
            if (sample.baselinePositive() && sample.fusionPositive()) {
                agreePositive++;
            } else if (!sample.baselinePositive() && !sample.fusionPositive()) {
                agreeNegative++;
            } else if (sample.baselinePositive()) {
                baselineOnly++;
            } else {
                fusionOnly++;
            }
        }
        int total = evaluable.size();
        List<DisagreementSummary> result = new ArrayList<>();
        result.add(new DisagreementSummary("AGREE_POSITIVE", agreePositive, percent(agreePositive, total)));
        result.add(new DisagreementSummary("AGREE_NEGATIVE", agreeNegative, percent(agreeNegative, total)));
        result.add(new DisagreementSummary("BASELINE_ONLY_POSITIVE", baselineOnly, percent(baselineOnly, total)));
        result.add(new DisagreementSummary("FUSION_ONLY_POSITIVE", fusionOnly, percent(fusionOnly, total)));
        return result;
    }

    private static List<DisagreementSample> disagreementSamples(List<AnalysisSample> evaluable) {
        List<DisagreementSample> result = new ArrayList<>();
        for (AnalysisSample sample : evaluable) {
            if (sample.baselinePositive() == sample.fusionPositive()) {
                continue;
            }
            result.add(new DisagreementSample(sample.sampleId(), sample.scenario(), sample.conditions(),
                    sample.groundTruthLabel(), sample.baselinePositive(), sample.fusionPositive(),
                    signalSummary(sample.signals())));
        }
        return result;
    }

    private static List<Stratum> scenarioStrata(List<AnalysisSample> evaluable,
                                                String baselineVersion, String fusionVersion) {
        Map<String, List<AnalysisSample>> grouped = new LinkedHashMap<>();
        for (AnalysisSample sample : evaluable) {
            if (sample.scenario() != null) {
                grouped.computeIfAbsent(sample.scenario(), key -> new ArrayList<>()).add(sample);
            }
        }
        List<Stratum> result = new ArrayList<>();
        for (String scenario : ResearchConfig.SCENARIO_ORDER) {
            List<AnalysisSample> group = grouped.get(scenario);
            if (group != null) {
                result.add(stratum(scenario, group, baselineVersion, fusionVersion));
            }
        }
        return result;
    }

    private static List<Stratum> conditionStrata(List<AnalysisSample> evaluable,
                                                 String baselineVersion, String fusionVersion) {
        List<Stratum> result = new ArrayList<>();
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            Map<String, List<AnalysisSample>> grouped = new TreeMap<>();
            for (AnalysisSample sample : evaluable) {
                String value = sample.conditions() == null ? null : sample.conditions().get(key);
                if (value != null) {
                    grouped.computeIfAbsent(value, v -> new ArrayList<>()).add(sample);
                }
            }
            for (Map.Entry<String, List<AnalysisSample>> entry : grouped.entrySet()) {
                result.add(stratum(key + "=" + entry.getKey(), entry.getValue(), baselineVersion, fusionVersion));
            }
        }
        return result;
    }

    private static Stratum stratum(String value, List<AnalysisSample> group,
                                   String baselineVersion, String fusionVersion) {
        ResearchMetrics.Confusion baselineConfusion = ResearchMetrics.Confusion.EMPTY;
        ResearchMetrics.Confusion fusionConfusion = ResearchMetrics.Confusion.EMPTY;
        for (AnalysisSample sample : group) {
            boolean actualPositive = !ResearchValidation.isNegativeLabel(sample.groundTruthLabel());
            baselineConfusion = baselineConfusion.accumulate(sample.baselinePositive(), actualPositive);
            fusionConfusion = fusionConfusion.accumulate(sample.fusionPositive(), actualPositive);
        }
        EvaluatorResult baseline = new EvaluatorResult(baselineVersion,
                baselineConfusion, ResearchMetrics.compute(baselineConfusion));
        EvaluatorResult fusion = new EvaluatorResult(fusionVersion,
                fusionConfusion, ResearchMetrics.compute(fusionConfusion));
        return new Stratum(value, group.size(), group, baseline, fusion,
                metricDeltas(baseline.metrics(), fusion.metrics()));
    }

    private static List<Confidence> confidenceDistribution(List<AnalysisSample> samples) {
        Map<String, List<Double>> values = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> bySource = new LinkedHashMap<>();
        for (AnalysisSample sample : samples) {
            for (ProctorFusionService.FusionSignal signal : sample.signals()) {
                Double confidence = signal.confidence();
                if (confidence == null) {
                    continue;
                }
                values.computeIfAbsent(signal.source(), key -> new ArrayList<>()).add(confidence);
                bySource.computeIfAbsent(signal.source(), key -> new java.util.LinkedHashSet<>())
                        .add(sample.sampleId());
            }
        }
        List<Confidence> result = new ArrayList<>();
        for (String source : CONFIDENCE_SOURCE_ORDER) {
            List<Double> confidences = values.get(source);
            java.util.Set<String> sampleIds = bySource.get(source);
            if (confidences == null || confidences.isEmpty() || sampleIds == null || sampleIds.isEmpty()) {
                continue;
            }
            List<Double> sorted = new ArrayList<>(confidences);
            sorted.sort(Double::compareTo);
            double min = sorted.getFirst();
            double max = sorted.getLast();
            double mean = confidences.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double median = median(sorted);
            result.add(new Confidence(source, sampleIds.size(), sorted.size(),
                    min, max, mean, median));
        }
        return result;
    }

    private static Latency latency(List<AnalysisSample> samples) {
        List<Long> measured = new ArrayList<>();
        List<Long> storedWindow = new ArrayList<>();
        for (AnalysisSample sample : samples) {
            if (sample.measuredLatencyMs() != null) {
                measured.add(sample.measuredLatencyMs());
            }
            Long window = storedWindowLatency(sample);
            if (window != null) {
                storedWindow.add(window);
            }
        }
        return new Latency(descriptive(measured), descriptive(storedWindow));
    }

    private static List<StratumLatency> latencyByScenario(List<AnalysisSample> samples) {
        Map<String, List<AnalysisSample>> grouped = new LinkedHashMap<>();
        for (AnalysisSample sample : samples) {
            if (sample.scenario() != null && (sample.measuredLatencyMs() != null || hasSignals(sample))) {
                grouped.computeIfAbsent(sample.scenario(), key -> new ArrayList<>()).add(sample);
            }
        }
        List<StratumLatency> result = new ArrayList<>();
        for (String scenario : ResearchConfig.SCENARIO_ORDER) {
            List<AnalysisSample> group = grouped.get(scenario);
            if (group != null) {
                result.add(new StratumLatency(scenario, group.size(), latency(group)));
            }
        }
        return result;
    }

    private static List<StratumLatency> latencyByCondition(List<AnalysisSample> samples) {
        List<StratumLatency> result = new ArrayList<>();
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            Map<String, List<AnalysisSample>> grouped = new TreeMap<>();
            for (AnalysisSample sample : samples) {
                String value = sample.conditions() == null ? null : sample.conditions().get(key);
                if (value != null && (sample.measuredLatencyMs() != null || hasSignals(sample))) {
                    grouped.computeIfAbsent(value, v -> new ArrayList<>()).add(sample);
                }
            }
            for (Map.Entry<String, List<AnalysisSample>> entry : grouped.entrySet()) {
                result.add(new StratumLatency(key + "=" + entry.getKey(), entry.getValue().size(),
                        latency(entry.getValue())));
            }
        }
        return result;
    }

    private static boolean hasSignals(AnalysisSample sample) {
        return sample.signals() != null && !sample.signals().isEmpty();
    }

    private static Bandwidth bandwidth(List<AnalysisSample> samples) {
        List<Long> raw = new ArrayList<>();
        List<Long> signal = new ArrayList<>();
        for (AnalysisSample sample : samples) {
            if (sample.rawMediaBytes() != null) {
                raw.add(sample.rawMediaBytes());
            }
            if (sample.signalBytes() != null) {
                signal.add(sample.signalBytes());
            }
        }
        return new Bandwidth(descriptive(raw), descriptive(signal));
    }

    private static ReviewAgreement reviewAgreement(List<ReviewObservation> reviews) {
        Map<String, Map<String, String>> bySample = new LinkedHashMap<>();
        if (reviews != null) {
            for (ReviewObservation review : reviews) {
                if (review == null || review.reviewerId() == null) {
                    continue;
                }
                bySample.computeIfAbsent(review.sampleId(), key -> new LinkedHashMap<>())
                        .put(review.reviewerId(), review.label());
            }
        }
        int reviewedSamples = bySample.size();
        int resolvedSamples = 0;
        int tiedSamples = 0;
        int singleReviewerSamples = 0;
        int pairwisePairs = 0;
        int pairwiseAgree = 0;
        for (Map.Entry<String, Map<String, String>> entry : bySample.entrySet()) {
            List<String> labels = new ArrayList<>(entry.getValue().values());
            String majority = ResearchValidation.majorityLabel(labels);
            boolean resolved = majority != null;
            if (resolved) {
                resolvedSamples++;
            } else {
                tiedSamples++;
            }
            if (entry.getValue().size() == 1) {
                singleReviewerSamples++;
            }
            List<String> reviewerIds = new ArrayList<>(entry.getValue().keySet());
            if (reviewerIds.size() >= 2) {
                for (int i = 0; i < reviewerIds.size(); i++) {
                    for (int j = i + 1; j < reviewerIds.size(); j++) {
                        pairwisePairs++;
                        String labelA = entry.getValue().get(reviewerIds.get(i));
                        String labelB = entry.getValue().get(reviewerIds.get(j));
                        if (labelA != null && labelA.equals(labelB)) {
                            pairwiseAgree++;
                        }
                    }
                }
            }
        }
        boolean singleReviewerDataset = reviewedSamples > 0 && singleReviewerSamples == reviewedSamples;
        Double agreementRate = reviewedSamples == 0 ? null
                : (double) resolvedSamples / (double) reviewedSamples;
        Double pairwiseAgreementRate = pairwisePairs == 0 ? null
                : (double) pairwiseAgree / (double) pairwisePairs;
        return new ReviewAgreement(reviewedSamples, resolvedSamples, tiedSamples,
                agreementRate, singleReviewerDataset, pairwiseAgreementRate);
    }

    private static List<ConfidenceInterval> confidenceIntervals(
            ResearchMetrics.Confusion baseline, ResearchMetrics.Confusion fusion) {
        List<ConfidenceInterval> result = new ArrayList<>();
        addInterval(result, "precision",
                baseline.truePositives(), baseline.truePositives() + baseline.falsePositives());
        addInterval(result, "recall",
                baseline.truePositives(), baseline.truePositives() + baseline.falseNegatives());
        addInterval(result, "specificity",
                baseline.trueNegatives(), baseline.trueNegatives() + baseline.falsePositives());
        addInterval(result, "accuracy",
                baseline.truePositives() + baseline.trueNegatives(), baseline.total());
        addInterval(result, "falsePositiveRate",
                baseline.falsePositives(), baseline.falsePositives() + baseline.trueNegatives());
        addInterval(result, "falseNegativeRate",
                baseline.falseNegatives(), baseline.falseNegatives() + baseline.truePositives());
        addInterval(result, "fdr",
                baseline.falsePositives(), baseline.falsePositives() + baseline.truePositives());
        return result;
    }

    private static void addInterval(List<ConfidenceInterval> result, String metric, long successes, long total) {
        if (total <= 0) {
            return;
        }
        ConfidenceInterval interval = wilson((double) successes / (double) total, total);
        result.add(new ConfidenceInterval(metric, interval.estimate(), interval.lower(), interval.upper(),
                CONFIDENCE_LEVEL, "wilson"));
    }

    /** Wilson score interval for a proportion with n observations. Safe for n == 0. */
    public static ConfidenceInterval wilson(double p, long n) {
        double clamped = Math.max(0.0, Math.min(1.0, p));
        if (n <= 0) {
            return new ConfidenceInterval("", clamped, null, null, CONFIDENCE_LEVEL, "wilson");
        }
        double z = WILSON_Z;
        double denom = 1.0 + z * z / n;
        double center = (clamped + z * z / (2.0 * n)) / denom;
        double half = z * Math.sqrt(clamped * (1.0 - clamped) / n + z * z / (4.0 * n * n)) / denom;
        double lower = clamp(center - half);
        double upper = clamp(center + half);
        return new ConfidenceInterval("", clamped, lower, upper, CONFIDENCE_LEVEL, "wilson");
    }

    private static PredictionTransitions transitions(List<AnalysisSample> evaluable) {
        int baselineError = 0;
        int fusionError = 0;
        int baselineCorrect = 0;
        int fusionCorrect = 0;
        int changed = 0;
        int baselineCorrectToFusionWrong = 0;
        int baselineWrongToFusionCorrect = 0;
        for (AnalysisSample sample : evaluable) {
            boolean actualPositive = !ResearchValidation.isNegativeLabel(sample.groundTruthLabel());
            boolean bCorrect = sample.baselinePositive() == actualPositive;
            boolean fCorrect = sample.fusionPositive() == actualPositive;
            baselineCorrect += bCorrect ? 1 : 0;
            fusionCorrect += fCorrect ? 1 : 0;
            baselineError += bCorrect ? 0 : 1;
            fusionError += fCorrect ? 0 : 1;
            if (sample.baselinePositive() != sample.fusionPositive()) {
                changed++;
                if (bCorrect && !fCorrect) {
                    baselineCorrectToFusionWrong++;
                }
                if (!bCorrect && fCorrect) {
                    baselineWrongToFusionCorrect++;
                }
            }
        }
        return new PredictionTransitions(baselineError, fusionError, baselineCorrect, fusionCorrect,
                changed, baselineCorrectToFusionWrong, baselineWrongToFusionCorrect);
    }

    private static Long storedWindowLatency(AnalysisSample sample) {
        List<ProctorFusionService.FusionSignal> signals = sample.signals();
        if (signals == null || signals.isEmpty() || sample.windowStart() == null) {
            return null;
        }
        Instant earliest = null;
        for (ProctorFusionService.FusionSignal signal : signals) {
            Instant occurred = ProctorFusionService.parseInstant(signal.occurredAt());
            if (occurred == null) {
                continue;
            }
            if (earliest == null || occurred.isBefore(earliest)) {
                earliest = occurred;
            }
        }
        if (earliest == null || earliest.isBefore(sample.windowStart())) {
            return null;
        }
        return Duration.between(sample.windowStart(), earliest).toMillis();
    }

    private static String signalSummary(List<ProctorFusionService.FusionSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return "no signals";
        }
        Map<String, Integer> byType = new TreeMap<>();
        for (ProctorFusionService.FusionSignal signal : signals) {
            String type = signal.type() == null ? "" : signal.type();
            byType.merge(type, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : byType.entrySet()) {
            parts.add(entry.getKey() + " x" + entry.getValue());
        }
        return String.join("; ", parts);
    }

    private static Descriptive descriptive(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return Descriptive.EMPTY;
        }
        List<Double> sorted = values.stream().map(Long::doubleValue).sorted().toList();
        double mean = values.stream().mapToLong(Long::longValue).average().orElse(0.0);
        return new Descriptive(sorted.size(), mean, median(sorted), sorted.getFirst(), sorted.getLast());
    }

    private static double median(List<Double> sorted) {
        int n = sorted.size();
        if (n == 0) {
            return 0.0;
        }
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static Double percent(int count, int total) {
        if (total <= 0) {
            return null;
        }
        return (double) count / (double) total;
    }
}