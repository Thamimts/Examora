import type { Dimension } from '@/types/learning-intelligence'

export type ProgressDirection =
  | 'IMPROVING'
  | 'DECLINING'
  | 'STABLE'
  | 'INSUFFICIENT_DATA'

export type IntelligenceChangeType =
  | 'NEW_WEAKNESS'
  | 'WEAKNESS_IMPROVED'
  | 'WEAKNESS_PERSISTED'
  | 'STRENGTH_MAINTAINED'
  | 'RECENT_DECLINE'

export type MilestoneCode =
  | 'FIRST_COMPLETED_EXAM'
  | 'IMPROVED_EXAM_PERFORMANCE'
  | 'FIRST_PRACTICE_SESSION'
  | 'FIRST_HARD_QUESTION_SUCCESS'
  | 'IMPROVED_HARD_DIFFICULTY'

export interface ProgressTrend {
  direction: ProgressDirection
  recentAverage: number | null
  previousAverage: number | null
  delta: number | null
}

export interface ExamHistoryPoint {
  examId: string
  examTitle: string
  subject: string
  date: string
  score: number
  total: number
  percentage: number | null
  accuracy: number | null
  attemptNumber: number | null
}

export interface ExamProgress {
  direction: ProgressDirection
  recentAverage: number | null
  previousAverage: number | null
  delta: number | null
  history: ExamHistoryPoint[]
}

export interface PracticeProgress {
  direction: ProgressDirection
  recentAccuracy: number | null
  previousAccuracy: number | null
  delta: number | null
  recentQuestions: number
  previousQuestions: number
}

export interface DifficultyProgression {
  direction: ProgressDirection
  recentAccuracy: number | null
  previousAccuracy: number | null
  delta: number | null
  recentObservations: number
  previousObservations: number
}

export interface DifficultyProgress {
  easy: DifficultyProgression
  medium: DifficultyProgression
  hard: DifficultyProgression
}

export interface IntelligenceChange {
  type: IntelligenceChangeType
  dimension: Dimension
  previousAccuracy: number | null
  currentAccuracy: number | null
  detail: string
}

export interface Milestone {
  code: MilestoneCode
  occurredAt: string
  metric: number | null
}

export interface ProgressDataQuality {
  sufficientForTrend: boolean
  completedExamCount: number
  practiceObservationCount: number
}

export interface StudentProgress {
  overall: ProgressTrend
  examProgress: ExamProgress
  practiceProgress: PracticeProgress
  difficultyProgress: DifficultyProgress
  intelligenceChanges: IntelligenceChange[]
  milestones: Milestone[]
  dataQuality: ProgressDataQuality
}