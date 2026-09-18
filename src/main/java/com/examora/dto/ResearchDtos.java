package com.examora.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ResearchDtos {

    private ResearchDtos() {}

    public record ExperimentCreateRequest(String name, String description,
                                          String algorithmVersion, String baselineVersion,
                                          String datasetVersion, String examId) {
    }

    public record ExperimentStatusRequest(String status) {
    }

    public record SampleCreateRequest(String attemptId, String windowStart, String windowEnd,
                                      String startedAt, String endedAt,
                                      String scenario, String label,
                                      Map<String, String> conditions,
                                      Long rawMediaBytes, Long signalBytes,
                                      Long measuredLatencyMs) {
    }

    public record RunCreateRequest(String runCode, String datasetVersion, String notes) {
    }

    public record RunSampleCreateRequest(String attemptId, String scenario,
                                         Map<String, String> conditions) {
    }

    public record SampleCaptureRequest(String startedAt, String endedAt, Long measuredLatencyMs,
                                       Long rawMediaBytes, Long signalBytes) {
    }

    public record ReviewRequest(String label, Double confidence, String notes) {
    }

    public record ResearchExperimentDto(String id, String name, String description,
                                        String algorithmVersion, String baselineVersion,
                                        String datasetVersion, String status,
                                        String examId, Instant createdAt, String createdBy) {
    }

    public record ResearchSampleDto(String id, String experimentId, String attemptId,
                                    String windowStart, String windowEnd,
                                    String startedAt, String endedAt,
                                    String scenario, String label,
                                    Map<String, String> conditions, Long rawMediaBytes,
                                    Long signalBytes, Long measuredLatencyMs,
                                    long reviewCount, Instant createdAt) {
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

    public record ScenarioOutcomeDto(String scenario, String label, String expectedLabel,
                                     int sampleCount, int resolvedCount, int agreementCount) {
    }

    public record DataQualityDto(int registered, int evaluable, int unreviewed, int tied,
                                 int invalid, int scenarioGroundTruthAgreement,
                                 int missingSignals, int missingCondition,
                                 int missingMeasuredLatency, int missingBandwidth,
                                 List<ScenarioOutcomeDto> scenarios) {
    }

    public record StudyEvaluationDto(String experimentId, int totalSamples, int evaluatedSamples,
                                     int reviewedSamples, int unevaluatedSamples,
                                     EvaluatorResultDto baseline, EvaluatorResultDto fusion,
                                     LatencyStatsDto latency, BandwidthStatsDto bandwidth,
                                     List<ConditionEvaluationDto> conditions,
                                     String datasetVersion, String evaluatedAt,
                                     DataQualityDto dataQuality, List<ScenarioOutcomeDto> scenarios) {
    }

    public record ResearchRunDto(String id, String experimentId, String runCode, String startedAt,
                                 String endedAt, String operatorId, String operatorName, String status,
                                 String datasetVersion, String notes, Instant createdAt) {
    }

    public record RunMatrixSummaryDto(String scenario, String expectedLabel, int planned, int captured,
                                      int reviewed, int evaluable, List<String> conditionsKey) {
    }

    public record RunDataQualityDto(int planned, int capturing, int captured, int reviewed,
                                    int evaluable, int unreviewed, int tied, int invalid,
                                    int missingSignals, int unexpectedSignals,
                                    int scenarioGroundTruthAgreement, int scenarioGroundTruthDisagreement,
                                    int missingMeasuredLatency, int missingBandwidth) {
    }

    public record RunSampleDto(String id, String runId, String attemptId, String scenario,
                               Map<String, String> condition, String status,
                               String startedAt, String endedAt, Long measuredLatencyMs,
                               Long rawMediaBytes, Long signalBytes, String researchSampleId,
                               long signalCount, boolean reviewed, boolean evaluated,
                               boolean tied, Instant createdAt) {
    }

    public record RunDetailDto(ResearchRunDto run, RunDataQualityDto dataQuality,
                               List<RunSampleDto> samples, List<RunMatrixSummaryDto> matrix,
                               List<ScenarioOutcomeDto> scenarios) {
    }

    public record ScenarioInstructionDto(String scenario, String label, String description,
                                         String expectedAction, String durationGuidance,
                                         String reviewerObservation, List<String> expectedSignals) {
    }

    public record ConditionOptionDto(String value, String description) {
    }

    public record ConditionCatalogDto(String key, String label, List<String> values,
                                      List<ConditionOptionDto> options) {
    }

    public record SignalTypeCountDto(String type, int count) {
    }

    public record SignalSourceCountDto(String source, int count) {
    }

    public record RunSampleDetailDto(String id, String runId, String attemptId, String scenario,
                                     Map<String, String> condition, String status,
                                     String startedAt, String endedAt, Long measuredLatencyMs,
                                     Long rawMediaBytes, Long signalBytes,
                                     long signalCount, List<SignalTypeCountDto> signalTypes,
                                     List<SignalSourceCountDto> signalSources,
                                     Double minConfidence, Double maxConfidence, Double meanConfidence,
                                     Long maxDurationMs, String groundTruthLabel,
                                     Boolean baselinePositive, Boolean fusionPositive,
                                     boolean scenarioAgreement) {
    }
}