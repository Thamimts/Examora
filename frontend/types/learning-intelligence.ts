export type Dimension =
  | 'EASY_DIFFICULTY'
  | 'MEDIUM_DIFFICULTY'
  | 'HARD_DIFFICULTY'
  | 'EXAM_PERFORMANCE'
  | 'PRACTICE_PERFORMANCE'
  | 'RECENT_PERFORMANCE'
  | 'CONSISTENCY'

export type Severity = 'LOW' | 'MEDIUM' | 'HIGH'
export type Confidence = 'INSUFFICIENT_DATA' | 'LOW_CONFIDENCE' | 'MEDIUM_CONFIDENCE' | 'HIGH_CONFIDENCE'
export type PriorityLevel = 'LOW' | 'MEDIUM' | 'HIGH'

export type RecommendationCode =
  | 'REINFORCE_BASICS'
  | 'PRACTICE_MEDIUM_QUESTIONS'
  | 'PRACTICE_HARD_QUESTIONS'
  | 'REVIEW_RECENT_EXAMS'
  | 'INCREASE_PRACTICE'
  | 'COMPLETE_REGULAR_PRACTICE'

export interface Strength {
  dimension: Dimension
  accuracy: number
  observations: number
  confidence: Confidence
  signal: string
}

export interface Weakness {
  dimension: Dimension
  accuracy: number
  observations: number
  severity: Severity
  confidence: Confidence
  signal: string
}

export interface Priority {
  dimension: Dimension
  priorityLevel: PriorityLevel
  reason: string
  recommendedAction: RecommendationCode
}

export interface Recommendation {
  code: RecommendationCode
}

export interface DataQuality {
  overallAccuracy: number | null
  observationCount: number
  strengthCount: number
  weaknessCount: number
  sufficientData: boolean
}

export interface LearningIntelligence {
  strengths: Strength[]
  weaknesses: Weakness[]
  priorities: Priority[]
  recommendations: Recommendation[]
  dataQuality: DataQuality
}