package com.examora.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure research aggregation engine. It evaluates a list of reviewed samples
 * under both the binary baseline and fusion-v1, and derives confusion metrics,
 * detection latency, and data-minimization bandwidth stats. No enforcement or
 * persistence side effects.
 */
public final class ResearchEvaluationEngine {

    private final BaselineEvaluator baselineEvaluator = new BaselineEvaluator();
    private final FusionEvaluator fusionEvaluator;

    public ResearchEvaluationEngine(ProctorFusionService fusionService, double threshold) {
        this.fusionEvaluator = new FusionEvaluator(fusionService, threshold);
    }

    public ResearchEvaluationEngine(ProctorFusionService fusionService) {
        this(fusionService, ResearchConfig.FUSION_THRESHOLD);
    }

    public record SampleEvaluation(String sampleId, Instant windowStart, Instant windowEnd,
                                   Boolean actualPositive, boolean reviewed,
                                   List<ProctorFusionService.FusionSignal> signals,
                                   Long rawMediaBytes, Long signalBytes, String conditionValue) {
    }

    public record EvaluatorResult(String version, ResearchMetrics.Confusion confusion,
                                  ResearchMetrics.EvaluatorMetrics metrics) {
    }

    public record StudyEvaluation(int totalSamples, int evaluatedSamples, int reviewedSamples,
                                  int unevaluatedSamples, EvaluatorResult baseline, EvaluatorResult fusion,
                                  ResearchMetrics.LatencyStats latency,
                                  ResearchMetrics.BandwidthStats bandwidth) {
    }

    public record ConditionGroup(String conditionValue, int sampleCount,
                                 EvaluatorResult baseline, EvaluatorResult fusion) {
    }

    public StudyEvaluation evaluate(List<SampleEvaluation> samples, String baselineVersion, String algorithmVersion) {
        Bucket bucket = accumulate(samples);
        return new StudyEvaluation(bucket.total, bucket.evaluated, bucket.reviewed, bucket.unevaluated,
                new EvaluatorResult(baselineVersion, bucket.baseline, ResearchMetrics.compute(bucket.baseline)),
                new EvaluatorResult(algorithmVersion, bucket.fusion, ResearchMetrics.compute(bucket.fusion)),
                ResearchMetrics.latency(bucket.latencies),
                ResearchMetrics.bandwidth(bucket.bandwidth));
    }

    public List<ConditionGroup> evaluateByCondition(List<SampleEvaluation> samples,
                                                    String baselineVersion, String algorithmVersion) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        Map<String, List<SampleEvaluation>> grouped = new LinkedHashMap<>();
        for (SampleEvaluation sample : samples) {
            if (sample.conditionValue() == null) {
                continue;
            }
            grouped.computeIfAbsent(sample.conditionValue(), key -> new ArrayList<>()).add(sample);
        }
        List<ConditionGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<SampleEvaluation>> entry : grouped.entrySet()) {
            Bucket bucket = accumulate(entry.getValue());
            groups.add(new ConditionGroup(entry.getKey(), bucket.evaluated,
                    new EvaluatorResult(baselineVersion, bucket.baseline, ResearchMetrics.compute(bucket.baseline)),
                    new EvaluatorResult(algorithmVersion, bucket.fusion, ResearchMetrics.compute(bucket.fusion))));
        }
        return groups;
    }

    private Bucket accumulate(List<SampleEvaluation> samples) {
        Bucket bucket = new Bucket();
        if (samples == null || samples.isEmpty()) {
            return bucket;
        }
        Set<String> seen = new HashSet<>();
        for (SampleEvaluation sample : samples) {
            if (sample == null) {
                continue;
            }
            if (!seen.add(sample.sampleId())) {
                throw new IllegalArgumentException("duplicate sample id in evaluation: " + sample.sampleId());
            }
            bucket.total++;
            if (sample.actualPositive() == null) {
                bucket.unevaluated++;
                continue;
            }
            List<ProctorFusionService.FusionSignal> windowSignals = windowSignals(sample);
            boolean actualPositive = sample.actualPositive();
            boolean baselinePositive = baselineEvaluator.predict(windowSignals);
            boolean fusionPositive = fusionEvaluator.predict(windowSignals, sample.windowEnd(),
                    sample.windowEnd().toEpochMilli() - sample.windowStart().toEpochMilli());
            bucket.baseline = bucket.baseline.accumulate(baselinePositive, actualPositive);
            bucket.fusion = bucket.fusion.accumulate(fusionPositive, actualPositive);
            bucket.evaluated++;
            if (sample.reviewed()) {
                bucket.reviewed++;
            }
            if (!windowSignals.isEmpty()) {
                Instant detection = earliest(windowSignals);
                if (detection != null && !detection.isBefore(sample.windowStart())) {
                    bucket.latencies.add(Duration.between(sample.windowStart(), detection).toMillis());
                }
            }
            if (sample.rawMediaBytes() != null && sample.signalBytes() != null && sample.rawMediaBytes() > 0) {
                bucket.bandwidth.add(new long[]{sample.rawMediaBytes(), sample.signalBytes()});
            }
        }
        return bucket;
    }

    private List<ProctorFusionService.FusionSignal> windowSignals(SampleEvaluation sample) {
        List<ProctorFusionService.FusionSignal> result = new ArrayList<>();
        if (sample.signals() == null) {
            return result;
        }
        for (ProctorFusionService.FusionSignal signal : sample.signals()) {
            Instant occurred;
            try {
                occurred = ProctorFusionService.parseInstant(signal.occurredAt());
            } catch (RuntimeException ex) {
                continue;
            }
            if (occurred.isBefore(sample.windowStart()) || occurred.isAfter(sample.windowEnd())) {
                continue;
            }
            result.add(signal);
        }
        return result;
    }

    private Instant earliest(List<ProctorFusionService.FusionSignal> signals) {
        Instant earliest = null;
        for (ProctorFusionService.FusionSignal signal : signals) {
            Instant occurred;
            try {
                occurred = ProctorFusionService.parseInstant(signal.occurredAt());
            } catch (RuntimeException ex) {
                continue;
            }
            if (earliest == null || occurred.isBefore(earliest)) {
                earliest = occurred;
            }
        }
        return earliest;
    }

    private static final class Bucket {
        private int total;
        private int evaluated;
        private int reviewed;
        private int unevaluated;
        private ResearchMetrics.Confusion baseline = ResearchMetrics.Confusion.EMPTY;
        private ResearchMetrics.Confusion fusion = ResearchMetrics.Confusion.EMPTY;
        private final List<Long> latencies = new ArrayList<>();
        private final List<long[]> bandwidth = new ArrayList<>();
    }
}