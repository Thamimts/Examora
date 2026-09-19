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
                                      Double wrongfulWarningRate, Double fdr) {
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

    public record RunProgressDto(int target, int planned, int capturing, int captured,
                                 int reviewed, int evaluable) {
    }

    public record ScenarioProgressDto(String scenario, String label, int target, int planned,
                                      int captured, int reviewed, int evaluable) {
    }

    public record ConditionValueCountDto(String value, int count) {
    }

    public record ConditionDistributionDto(String key, String label,
                                           List<ConditionValueCountDto> values) {
    }

    public record ReviewStatusDto(String runSampleId, String scenario, int reviewCount,
                                  String resolvedLabel, String agreementState) {
    }

    public record DisagreementDto(String sampleId, String runSampleId, String scenario,
                                  String conditionsKey, String groundTruthLabel,
                                  boolean baselinePositive, boolean fusionPositive,
                                  List<SignalTypeCountDto> signalTypes,
                                  List<SignalSourceCountDto> signalSources,
                                  Double minConfidence, Double maxConfidence, Double meanConfidence,
                                  Long maxDurationMs) {
    }

    public record ConditionGroupEvaluationDto(String key, String conditionValue, int sampleCount,
                                              EvaluatorResultDto baseline, EvaluatorResultDto fusion) {
    }

    public record CompletionSummaryDto(int targetObservations, int actualCaptured, int reviewed,
                                       int evaluable, int scenarioCoverage, int scenarioCells,
                                       int conditionCoverage, int conditionCells,
                                       boolean readyForEvaluation) {
    }

    public record EvaluationSnapshotDto(String datasetVersion, String baselineVersion,
                                        String fusionVersion, String evaluatedAt,
                                        int evaluableSampleCount) {
    }

    public record RunEvaluationDto(String runId, String experimentId, String runCode,
                                   RunProgressDto progress,
                                   List<ScenarioProgressDto> scenarioProgress,
                                   List<ConditionDistributionDto> conditionDistribution,
                                   RunDataQualityDto dataQuality, EvaluatorResultDto baseline,
                                   EvaluatorResultDto fusion,
                                   List<ReviewStatusDto> reviewAgreement,
                                   List<DisagreementDto> disagreements,
                                   List<ConditionGroupEvaluationDto> conditions,
                                   LatencyStatsDto latency, BandwidthStatsDto bandwidth,
                                   CompletionSummaryDto completion, EvaluationSnapshotDto snapshot) {
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

    public record AnalysisConfusionDto(long truePositives, long trueNegatives,
                                       long falsePositives, long falseNegatives) {
    }

    public record AnalysisEvaluatorMetricsDto(Double precision, Double recall, Double specificity,
                                              Double accuracy, Double falsePositiveRate,
                                              Double falseNegativeRate, Double f1,
                                              Double wrongfulWarningRate, Double fdr) {
    }

    public record AnalysisEvaluatorDto(String version, AnalysisConfusionDto confusion,
                                       AnalysisEvaluatorMetricsDto metrics) {
    }

    public record AnalysisMetricDeltaDto(String metric, Double delta) {
    }

    public record AnalysisDisagreementCategoryDto(String category, int count, Double percentage) {
    }

    public record AnalysisDisagreementSampleDto(String sampleId, String scenario,
                                                Map<String, String> conditions, String groundTruthLabel,
                                                boolean baselinePositive, boolean fusionPositive,
                                                String signalSummary) {
    }

    public record AnalysisStratumDto(String value, int sampleCount, AnalysisEvaluatorDto baseline,
                                     AnalysisEvaluatorDto fusion, List<AnalysisMetricDeltaDto> deltas) {
    }

    public record AnalysisConfidenceDto(String source, int sampleCount, int measuredCount,
                                        Double min, Double max, Double mean, Double median) {
    }

    public record AnalysisDescriptiveDto(int measuredCount, Double mean, Double median,
                                         Double min, Double max) {
    }

    public record AnalysisLatencyDto(AnalysisDescriptiveDto measured, AnalysisDescriptiveDto storedWindow) {
    }

    public record AnalysisStratumLatencyDto(String value, int sampleCount, AnalysisLatencyDto latency) {
    }

    public record AnalysisBandwidthDto(AnalysisDescriptiveDto rawMedia, AnalysisDescriptiveDto signalBytes) {
    }

    public record AnalysisReviewAgreementDto(int reviewedSamples, int resolvedSamples, int tiedSamples,
                                             Double agreementRate, boolean singleReviewerDataset,
                                             Double pairwiseAgreementRate) {
    }

    public record AnalysisIntervalDto(double estimate, Double lower, Double upper,
                                      double confidenceLevel, String method) {
    }

    public record AnalysisMetricIntervalDto(String metric, AnalysisIntervalDto interval) {
    }

    public record AnalysisTransitionsDto(int baselineErrorCount, int fusionErrorCount,
                                         int baselineCorrectCount, int fusionCorrectCount,
                                         int changedPredictionCount,
                                         int baselineCorrectToFusionWrong,
                                         int baselineWrongToFusionCorrect) {
    }

    public record AnalysisReportDto(String experimentId, String runId,
                                    int registeredSamples, int capturedSamples,
                                    int reviewedSamples, int evaluableSamples,
                                    int unreviewedSamples, int tiedSamples, int invalidSamples,
                                    int missingSignalSamples, int missingConditionSamples,
                                    int missingLatencySamples, int missingBandwidthSamples,
                                    Double evaluableCoverage,
                                    AnalysisEvaluatorDto baseline, AnalysisEvaluatorDto fusion,
                                    List<AnalysisMetricDeltaDto> metricDeltas,
                                    List<AnalysisDisagreementCategoryDto> disagreementSummary,
                                    List<AnalysisDisagreementSampleDto> disagreementSamples,
                                    List<AnalysisStratumDto> scenarios,
                                    List<AnalysisStratumDto> conditions,
                                    List<AnalysisConfidenceDto> confidence,
                                    AnalysisLatencyDto latency,
                                    List<AnalysisStratumLatencyDto> latencyByScenario,
                                    List<AnalysisStratumLatencyDto> latencyByCondition,
                                    AnalysisBandwidthDto bandwidth,
                                    AnalysisReviewAgreementDto reviewAgreement,
                                    List<AnalysisMetricIntervalDto> confidenceIntervals,
                                    AnalysisTransitionsDto transitions,
                                    String datasetVersion, String baselineVersion, String fusionVersion,
                                    String analysisVersion, String generatedAt) {
    }

    public record FailureRangeDto(Double min, Double max) {
    }

    public record FailureCountedDto(String value, int count) {
    }

    public record FailureSignalProfileRowDto(String category, String source, int signalCount, int sampleCount,
                                             AnalysisDescriptiveDto confidence, AnalysisDescriptiveDto duration) {
    }

    public record FailureConfidenceBandDto(String band, int signalCount, int distinctSampleCount,
                                           long truePositives, long trueNegatives, long falsePositives, long falseNegatives,
                                           Double precision, Double recall, Double falsePositiveRate,
                                           Double falseNegativeRate, Double falseDiscoveryRate, String evaluator) {
    }

    /** Shared shape for per-environment (key=value) and per-scenario failure groupings. */
    public record FailureGroupDto(String value, int sampleCount, boolean smallSample,
                                  long baselineFalsePositives, long baselineFalseNegatives,
                                  long fusionFalsePositives, long fusionFalseNegatives,
                                  Double baselineFalsePositiveRate, Double baselineFalseNegativeRate,
                                  Double fusionFalsePositiveRate, Double fusionFalseNegativeRate,
                                  int disagreementCount) {
    }

    public record FailureDistributionDto(String value, int count) {
    }

    public record FailureTransitionDto(int changedPredictionCount, int baselineCorrectToFusionWrong,
                                       int baselineWrongToFusionCorrect,
                                       Double baselineCorrectToFusionWrongFraction,
                                       Double baselineWrongToFusionCorrectFraction,
                                       List<FailureDistributionDto> byScenario,
                                       List<FailureDistributionDto> byCondition,
                                       List<FailureDistributionDto> bySignalSource,
                                       List<FailureDistributionDto> byConfidenceBand) {
    }

    public record FailureSignalAssociationDto(int errorSampleCount, int errorSamplesWithoutSignals,
                                              List<FailureCountedDto> sources) {
    }

    public record FailureCaseDto(String sampleId, String scenario, String groundTruth,
                                 boolean baselinePrediction, boolean fusionPrediction,
                                 String classification, String condition,
                                 List<String> signalTypes, List<String> signalSources,
                                 FailureRangeDto confidenceRange, FailureRangeDto durationRange) {
    }

    public record FailurePatternDto(String text) {
    }

    public record FailureReportDto(String experimentId, String runId,
                                   int evaluableSampleCount, int failureCaseCount,
                                   int disagreementCount, int changedPredictionCount,
                                   List<FailureCountedDto> categories,
                                   List<FailureCountedDto> transitionCategories,
                                   List<FailureCountedDto> disagreementCategories,
                                   List<FailureSignalProfileRowDto> signalProfile,
                                   List<FailureConfidenceBandDto> confidenceBands,
                                   int missingConfidenceSignalCount,
                                   List<FailureGroupDto> environmentalFailures,
                                   int missingConditionCount,
                                   List<FailureGroupDto> scenarioFailures,
                                   FailureSignalAssociationDto signalAssociation,
                                   FailureTransitionDto transitions,
                                   List<FailurePatternDto> patterns,
                                   String datasetVersion, String baselineVersion, String fusionVersion,
                                   String analysisVersion, String failureAnalysisVersion, String generatedAt) {
    }

    public record FailureSamplesPageDto(String experimentId, String runId, int page, int pageSize,
                                        int totalCount, int totalPages, List<FailureCaseDto> samples) {
    }
}