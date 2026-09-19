export type ResearchExperimentStatus = 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED'

export type ResearchRunStatus = 'PLANNED' | 'RUNNING' | 'COMPLETED' | 'CANCELLED'

export type ResearchRunSampleStatus = 'PLANNED' | 'CAPTURING' | 'CAPTURED'

export type ControlledConditionKey = 'lighting' | 'cameraQuality' | 'network' | 'cameraAngle'

export type ControlledConditions = {
  lighting: 'GOOD' | 'LOW'
  cameraQuality: 'HD' | 'LOW'
  network: 'STABLE' | 'INTERRUPTED'
  cameraAngle: 'FRONT' | 'OFF_ANGLE'
}

export type ResearchScenario =
  | 'NORMAL'
  | 'WINDOW_BLUR'
  | 'TAB_SWITCH'
  | 'CAMERA_OFF'
  | 'MULTIPLE_FACES'
  | 'AUDIO_DETECTED'
  | 'FULLSCREEN_EXIT'
  | 'NETWORK_INTERRUPTION'

export const RESEARCH_SCENARIOS: ResearchScenario[] = [
  'NORMAL',
  'WINDOW_BLUR',
  'TAB_SWITCH',
  'CAMERA_OFF',
  'MULTIPLE_FACES',
  'AUDIO_DETECTED',
  'FULLSCREEN_EXIT',
  'NETWORK_INTERRUPTION',
]

export const RESEARCH_SCENARIO_LABELS: Record<ResearchScenario, string> = {
  NORMAL: 'Normal behavior',
  WINDOW_BLUR: 'Incidental window blur',
  TAB_SWITCH: 'Tab switch',
  CAMERA_OFF: 'Camera turned off',
  MULTIPLE_FACES: 'Multiple faces visible',
  AUDIO_DETECTED: 'Unexpected audio detected',
  FULLSCREEN_EXIT: 'Exited fullscreen',
  NETWORK_INTERRUPTION: 'Network interruption',
}

export type ResearchCondition = {
  lighting?: 'NORMAL' | 'LOW' | 'BRIGHT'
  cameraQuality?: 'LOW' | 'MEDIUM' | 'HIGH'
  network?: 'GOOD' | 'DEGRADED'
  cameraAngle?: 'NORMAL' | 'OBSTRUCTED'
}

export interface ResearchExperiment {
  id: string
  name: string
  description: string | null
  algorithmVersion: string
  baselineVersion: string
  datasetVersion: string
  status: ResearchExperimentStatus
  createdAt: string
  createdBy: string | null
  examId: string | null
}

export interface ResearchRun {
  id: string
  experimentId: string
  runCode: string
  startedAt: string | null
  endedAt: string | null
  operatorId: string | null
  operatorName: string | null
  status: ResearchRunStatus
  datasetVersion: string
  notes: string | null
  createdAt: string
}

export interface ResearchRunMatrixRow {
  scenario: ResearchScenario
  expectedLabel: string
  planned: number
  captured: number
  reviewed: number
  evaluable: number
  conditionsKey: string[] | null
}

export interface ResearchRunDataQuality {
  planned: number
  capturing: number
  captured: number
  reviewed: number
  evaluable: number
  unreviewed: number
  tied: number
  invalid: number
  missingSignals: number
  unexpectedSignals: number
  scenarioGroundTruthAgreement: number
  scenarioGroundTruthDisagreement: number
  missingMeasuredLatency: number
  missingBandwidth: number
}

export interface ResearchRunSample {
  id: string
  runId: string
  attemptId: string
  scenario: ResearchScenario
  condition: Partial<ControlledConditions>
  status: ResearchRunSampleStatus
  startedAt: string | null
  endedAt: string | null
  measuredLatencyMs: number | null
  rawMediaBytes: number | null
  signalBytes: number | null
  researchSampleId: string | null
  signalCount: number
  reviewed: boolean
  evaluated: boolean
  tied: boolean
  createdAt: string
}

export interface ResearchRunDetail {
  run: ResearchRun
  dataQuality: ResearchRunDataQuality
  samples: ResearchRunSample[]
  matrix: ResearchRunMatrixRow[]
  scenarios: ResearchScenarioOutcome[]
}

export interface ScenarioInstruction {
  scenario: ResearchScenario
  label: string
  description: string
  expectedAction: string
  durationGuidance: string
  reviewerObservation: string
  expectedSignals: string[]
}

export interface ConditionOption {
  value: string
  description: string
}

export interface ConditionCatalogEntry {
  key: ControlledConditionKey
  label: string
  values: string[]
  options: ConditionOption[]
}

export interface SignalTypeCount {
  type: string
  count: number
}

export interface SignalSourceCount {
  source: string
  count: number
}

export interface ResearchRunSampleDetail {
  id: string
  runId: string
  attemptId: string
  scenario: ResearchScenario
  condition: Partial<ControlledConditions>
  status: ResearchRunSampleStatus
  startedAt: string | null
  endedAt: string | null
  measuredLatencyMs: number | null
  rawMediaBytes: number | null
  signalBytes: number | null
  signalCount: number
  signalTypes: SignalTypeCount[]
  signalSources: SignalSourceCount[]
  minConfidence: number | null
  maxConfidence: number | null
  meanConfidence: number | null
  maxDurationMs: number | null
  groundTruthLabel: string | null
  baselinePositive: boolean | null
  fusionPositive: boolean | null
  scenarioAgreement: boolean
}

export interface ResearchSample {
  id: string
  experimentId: string
  attemptId: string | null
  windowStart: string
  windowEnd: string
  startedAt: string
  endedAt: string
  scenario: ResearchScenario | null
  label: string | null
  conditions: ResearchCondition
  rawMediaBytes: number | null
  signalBytes: number | null
  measuredLatencyMs: number | null
  reviewCount: number
  createdAt: string
}

export interface ResearchConfusion {
  truePositives: number
  trueNegatives: number
  falsePositives: number
  falseNegatives: number
}

export interface ResearchMetrics {
  precision: number | null
  recall: number | null
  specificity: number | null
  accuracy: number | null
  falsePositiveRate: number | null
  falseNegativeRate: number | null
  f1: number | null
  wrongfulWarningRate: number | null
  fdr: number | null
}

export interface ResearchEvaluatorResult {
  version: string
  confusion: ResearchConfusion
  metrics: ResearchMetrics
}

export interface ResearchLatencyStats {
  measuredCount: number
  meanMs: number | null
  medianMs: number | null
  minMs: number | null
  maxMs: number | null
}

export interface ResearchBandwidthStats {
  measuredCount: number
  meanSignalBytes: number | null
  meanRawMediaBytes: number | null
  meanDataMinimizationRatio: number | null
}

export interface ResearchConditionEvaluation {
  conditionValue: string
  sampleCount: number
  baseline: ResearchEvaluatorResult
  fusion: ResearchEvaluatorResult
}

export interface ResearchScenarioOutcome {
  scenario: ResearchScenario
  label: string | null
  expectedLabel: string
  sampleCount: number
  resolvedCount: number
  agreementCount: number
}

export interface ResearchDataQuality {
  registered: number
  evaluable: number
  unreviewed: number
  tied: number
  invalid: number
  scenarioGroundTruthAgreement: number
  missingSignals: number
  missingCondition: number
  missingMeasuredLatency: number
  missingBandwidth: number
  scenarios: ResearchScenarioOutcome[]
}

export interface ResearchEvaluation {
  experimentId: string
  totalSamples: number
  evaluatedSamples: number
  reviewedSamples: number
  unevaluatedSamples: number
  baseline: ResearchEvaluatorResult
  fusion: ResearchEvaluatorResult
  latency: ResearchLatencyStats
  bandwidth: ResearchBandwidthStats
  conditions: ResearchConditionEvaluation[] | null
  datasetVersion: string
  evaluatedAt: string
  dataQuality: ResearchDataQuality
  scenarios: ResearchScenarioOutcome[]
}

export type ResearchAgreementState = 'UNREVIEWED' | 'TIED' | 'AGREED' | 'RESOLVED'

export interface ResearchRunProgress {
  target: number
  planned: number
  capturing: number
  captured: number
  reviewed: number
  evaluable: number
}

export interface ResearchScenarioProgress {
  scenario: ResearchScenario
  label: string | null
  target: number
  planned: number
  captured: number
  reviewed: number
  evaluable: number
}

export interface ResearchConditionValueCount {
  value: string
  count: number
}

export interface ResearchConditionCount {
  key: ResearchConditionKey
  label: string
  values: ResearchConditionValueCount[]
}

export interface ResearchReviewStatus {
  runSampleId: string
  scenario: ResearchScenario
  reviewCount: number
  resolvedLabel: string | null
  agreementState: ResearchAgreementState
}

export interface ResearchDisagreement {
  sampleId: string
  runSampleId: string
  scenario: ResearchScenario
  conditionsKey: string
  groundTruthLabel: string
  baselinePositive: boolean
  fusionPositive: boolean
  signalTypes: SignalTypeCount[]
  signalSources: SignalSourceCount[]
  minConfidence: number | null
  maxConfidence: number | null
  meanConfidence: number | null
  maxDurationMs: number | null
}

export interface ResearchConditionGroupEvaluation {
  key: ResearchConditionKey
  conditionValue: string
  sampleCount: number
  baseline: ResearchEvaluatorResult
  fusion: ResearchEvaluatorResult
}

export interface ResearchCompletionSummary {
  targetObservations: number
  actualCaptured: number
  reviewed: number
  evaluable: number
  scenarioCoverage: number
  scenarioCells: number
  conditionCoverage: number
  conditionCells: number
  readyForEvaluation: boolean
}

export interface ResearchSnapshot {
  datasetVersion: string
  baselineVersion: string
  fusionVersion: string
  evaluatedAt: string | null
  evaluableSampleCount: number
}

export interface ResearchRunEvaluation {
  runId: string
  experimentId: string
  runCode: string
  progress: ResearchRunProgress
  scenarioProgress: ResearchScenarioProgress[]
  conditionDistribution: ResearchConditionCount[]
  dataQuality: ResearchRunDataQuality
  baseline: ResearchEvaluatorResult
  fusion: ResearchEvaluatorResult
  reviewAgreement: ResearchReviewStatus[]
  disagreements: ResearchDisagreement[]
  conditions: ResearchConditionGroupEvaluation[]
  latency: ResearchLatencyStats
  bandwidth: ResearchBandwidthStats
  completion: ResearchCompletionSummary
  snapshot: ResearchSnapshot
}

export type ResearchConditionKey = keyof ResearchCondition

export interface AnalysisConfusion {
  truePositives: number
  trueNegatives: number
  falsePositives: number
  falseNegatives: number
}

export interface AnalysisEvaluatorMetrics {
  precision: number | null
  recall: number | null
  specificity: number | null
  accuracy: number | null
  falsePositiveRate: number | null
  falseNegativeRate: number | null
  f1: number | null
  wrongfulWarningRate: number | null
  fdr: number | null
}

export interface AnalysisEvaluator {
  version: string
  confusion: AnalysisConfusion
  metrics: AnalysisEvaluatorMetrics
}

export interface AnalysisMetricDelta {
  metric: string
  delta: number | null
}

export interface AnalysisDisagreementCategory {
  category: 'AGREE_POSITIVE' | 'AGREE_NEGATIVE' | 'BASELINE_ONLY_POSITIVE' | 'FUSION_ONLY_POSITIVE'
  count: number
  percentage: number | null
}

export interface AnalysisDisagreementSample {
  sampleId: string
  scenario: ResearchScenario | null
  conditions: ResearchCondition | null
  groundTruthLabel: string | null
  baselinePositive: boolean
  fusionPositive: boolean
  signalSummary: string
}

export interface AnalysisStratum {
  value: string
  sampleCount: number
  baseline: AnalysisEvaluator
  fusion: AnalysisEvaluator
  deltas: AnalysisMetricDelta[]
}

export interface AnalysisConfidenceDistribution {
  source: string
  sampleCount: number
  measuredCount: number
  min: number | null
  max: number | null
  mean: number | null
  median: number | null
}

export interface AnalysisDescriptive {
  measuredCount: number
  mean: number | null
  median: number | null
  min: number | null
  max: number | null
}

export interface AnalysisLatency {
  measured: AnalysisDescriptive
  storedWindow: AnalysisDescriptive
}

export interface AnalysisStratumLatency {
  value: string
  sampleCount: number
  latency: AnalysisLatency
}

export interface AnalysisBandwidth {
  rawMedia: AnalysisDescriptive
  signalBytes: AnalysisDescriptive
}

export interface AnalysisReviewAgreement {
  reviewedSamples: number
  resolvedSamples: number
  tiedSamples: number
  agreementRate: number | null
  singleReviewerDataset: boolean
  pairwiseAgreementRate: number | null
}

export interface AnalysisMetricInterval {
  metric: string
  interval: {
    estimate: number
    lower: number | null
    upper: number | null
    confidenceLevel: number
    method: string
  }
}

export interface AnalysisTransitions {
  baselineErrorCount: number
  fusionErrorCount: number
  baselineCorrectCount: number
  fusionCorrectCount: number
  changedPredictionCount: number
  baselineCorrectToFusionWrong: number
  baselineWrongToFusionCorrect: number
}

export interface AnalysisReport {
  experimentId: string
  runId: string | null
  registeredSamples: number
  capturedSamples: number
  reviewedSamples: number
  evaluableSamples: number
  unreviewedSamples: number
  tiedSamples: number
  invalidSamples: number
  missingSignalSamples: number
  missingConditionSamples: number
  missingLatencySamples: number
  missingBandwidthSamples: number
  evaluableCoverage: number | null
  baseline: AnalysisEvaluator
  fusion: AnalysisEvaluator
  metricDeltas: AnalysisMetricDelta[]
  disagreementSummary: AnalysisDisagreementCategory[]
  disagreementSamples: AnalysisDisagreementSample[]
  scenarios: AnalysisStratum[]
  conditions: AnalysisStratum[]
  confidence: AnalysisConfidenceDistribution[]
  latency: AnalysisLatency
  latencyByScenario: AnalysisStratumLatency[]
  latencyByCondition: AnalysisStratumLatency[]
  bandwidth: AnalysisBandwidth
  reviewAgreement: AnalysisReviewAgreement
  confidenceIntervals: AnalysisMetricInterval[]
  transitions: AnalysisTransitions
  datasetVersion: string
  baselineVersion: string
  fusionVersion: string
  analysisVersion: string
  generatedAt: string
}

export type FailureCategory =
  | 'BASELINE_FALSE_POSITIVE'
  | 'BASELINE_FALSE_NEGATIVE'
  | 'FUSION_FALSE_POSITIVE'
  | 'FUSION_FALSE_NEGATIVE'
  | 'BASELINE_ONLY_POSITIVE'
  | 'FUSION_ONLY_POSITIVE'

export type FailureClassification = 'CORRECT_TO_CORRECT' | 'CORRECT_TO_WRONG' | 'WRONG_TO_CORRECT' | 'WRONG_TO_WRONG'

export interface FailureCounted {
  value: string
  count: number
}

export interface FailureRange {
  min: number | null
  max: number | null
}

export interface FailureSignalProfileRow {
  category: string
  source: string
  signalCount: number
  sampleCount: number
  confidence: AnalysisDescriptive
  duration: AnalysisDescriptive
}

export interface FailureConfidenceBand {
  band: string
  signalCount: number
  distinctSampleCount: number
  truePositives: number
  trueNegatives: number
  falsePositives: number
  falseNegatives: number
  precision: number | null
  recall: number | null
  falsePositiveRate: number | null
  falseNegativeRate: number | null
  falseDiscoveryRate: number | null
  evaluator: string
}

export interface FailureGroup {
  value: string
  sampleCount: number
  smallSample: boolean
  baselineFalsePositives: number
  baselineFalseNegatives: number
  fusionFalsePositives: number
  fusionFalseNegatives: number
  baselineFalsePositiveRate: number | null
  baselineFalseNegativeRate: number | null
  fusionFalsePositiveRate: number | null
  fusionFalseNegativeRate: number | null
  disagreementCount: number
}

export interface FailureDistribution {
  value: string
  count: number
}

export interface FailureTransitions {
  changedPredictionCount: number
  baselineCorrectToFusionWrong: number
  baselineWrongToFusionCorrect: number
  baselineCorrectToFusionWrongFraction: number | null
  baselineWrongToFusionCorrectFraction: number | null
  byScenario: FailureDistribution[]
  byCondition: FailureDistribution[]
  bySignalSource: FailureDistribution[]
  byConfidenceBand: FailureDistribution[]
}

export interface FailureSignalAssociation {
  errorSampleCount: number
  errorSamplesWithoutSignals: number
  sources: FailureCounted[]
}

export interface FailureCase {
  sampleId: string
  scenario: string | null
  groundTruth: string
  baselinePrediction: boolean
  fusionPrediction: boolean
  classification: FailureClassification
  condition: string
  signalTypes: string[]
  signalSources: string[]
  confidenceRange: FailureRange | null
  durationRange: FailureRange | null
}

export interface FailurePattern {
  text: string
}

export interface FailureReport {
  experimentId: string
  runId: string | null
  evaluableSampleCount: number
  failureCaseCount: number
  disagreementCount: number
  changedPredictionCount: number
  categories: FailureCounted[]
  transitionCategories: FailureCounted[]
  disagreementCategories: FailureCounted[]
  signalProfile: FailureSignalProfileRow[]
  confidenceBands: FailureConfidenceBand[]
  missingConfidenceSignalCount: number
  environmentalFailures: FailureGroup[]
  missingConditionCount: number
  scenarioFailures: FailureGroup[]
  signalAssociation: FailureSignalAssociation
  transitions: FailureTransitions
  patterns: FailurePattern[]
  datasetVersion: string
  baselineVersion: string
  fusionVersion: string
  analysisVersion: string
  failureAnalysisVersion: string
  generatedAt: string
}

export interface FailureSamplesPage {
  experimentId: string
  runId: string | null
  page: number
  pageSize: number
  totalCount: number
  totalPages: number
  samples: FailureCase[]
}