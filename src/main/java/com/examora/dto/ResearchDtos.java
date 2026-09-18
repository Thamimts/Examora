package com.examora.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ResearchDtos {

    private ResearchDtos() {}

    public record ExperimentCreateRequest(String name, String description,
                                          String algorithmVersion, String baselineVersion) {
    }

    public record ExperimentStatusRequest(String status) {
    }

    public record SampleCreateRequest(String attemptId, String windowStart, String windowEnd,
                                      String label, Map<String, String> conditions,
                                      Long rawMediaBytes, Long signalBytes) {
    }

    public record ReviewRequest(String label, Double confidence, String notes) {
    }

    public record ResearchExperimentDto(String id, String name, String description,
                                        String algorithmVersion, String baselineVersion,
                                        String status, Instant createdAt, String createdBy) {
    }

    public record ResearchSampleDto(String id, String experimentId, String attemptId,
                                    String windowStart, String windowEnd, String label,
                                    Map<String, String> conditions, Long rawMediaBytes,
                                    Long signalBytes, long reviewCount, Instant createdAt) {
    }

    public record ConfusionDto(long truePositives, long trueNegatives,
                               long falsePositives, long falseNegatives) {
    }

    public record EvaluatorMetricsDto(Double precision, Double recall, Double specificity,
                                      Double accuracy, Double falsePositiveRate,
                                      Double falseNegativeRate, Double f1,
                                      Double wrongfulWarningRate) {
    }

    public record EvaluatorResultDto(String version, ConfusionDto confusion,
                                     EvaluatorMetricsDto metrics) {
    }

    public record LatencyStatsDto(int measuredCount, Double meanMs, Double medianMs,
                                  Double minMs, Double maxMs) {
    }

    public record BandwidthStatsDto(int measuredCount, Double meanSignalBytes,
                                    Double meanRawMediaBytes, Double meanDataMinimizationRatio) {
    }

    public record ConditionEvaluationDto(String conditionValue, int sampleCount,
                                         EvaluatorResultDto baseline, EvaluatorResultDto fusion) {
    }

    public record StudyEvaluationDto(String experimentId, int totalSamples, int evaluatedSamples,
                                     int reviewedSamples, int unevaluatedSamples,
                                     EvaluatorResultDto baseline, EvaluatorResultDto fusion,
                                     LatencyStatsDto latency, BandwidthStatsDto bandwidth,
                                     List<ConditionEvaluationDto> conditions) {
    }
}