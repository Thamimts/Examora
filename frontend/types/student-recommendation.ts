import type { Dimension, Confidence, PriorityLevel } from '@/types/learning-intelligence'

export type RecommendationCode =
  | 'PRACTICE_EASY_QUESTIONS'
  | 'PRACTICE_MEDIUM_QUESTIONS'
  | 'PRACTICE_HARD_QUESTIONS'
  | 'REVIEW_RECENT_EXAMS'
  | 'INCREASE_PRACTICE'
  | 'MAINTAIN_STRENGTH'
  | 'BUILD_BASELINE'

export type RecommendationAction = 'PRACTICE' | 'REVIEW' | 'MAINTENANCE' | 'BASELINE'

export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'

export interface SupportingMetrics {
  accuracy: number | null
  observations: number | null
  recentAccuracy: number | null
  previousAccuracy: number | null
  recentlyPracticed: boolean | null
}

export interface Recommendation {
  code: RecommendationCode
  priority: PriorityLevel
  action: RecommendationAction
  dimension: Dimension | null
  difficulty: Difficulty | null
  reasonCode: string
  confidence: Confidence
  targetQuestionCount: number
  supportingMetrics: SupportingMetrics
}

export interface RecommendationSummary {
  primaryRecommendation: string
  recommendationCount: number
  dataSufficient: boolean
}

export interface StudentRecommendations {
  recommendations: Recommendation[]
  summary: RecommendationSummary
  generatedAt: string
}