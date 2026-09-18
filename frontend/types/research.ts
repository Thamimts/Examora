export type ResearchExperimentStatus = 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED'

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

export type ResearchConditionKey = keyof ResearchCondition