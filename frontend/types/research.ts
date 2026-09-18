export type ResearchExperimentStatus = 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED'

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
  label: string | null
  conditions: ResearchCondition
  rawMediaBytes: number | null
  signalBytes: number | null
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
}

export type ResearchConditionKey = keyof ResearchCondition