export type CoachAction =
  | 'EXPLAIN_PERFORMANCE'
  | 'EXPLAIN_MISTAKES'
  | 'EXPLAIN_QUESTION'
  | 'REVIEW_WEAK_AREAS'
  | 'SUGGEST_REVISION'
  | 'GENERATE_SIMILAR_QUESTION'
  | 'CHAT'

export type CoachDifficultyName = 'EASY' | 'MEDIUM' | 'HARD'

export interface CoachExamSummary {
  examId: string
  title: string
  subject: string
  score: number
  percentage: number
  completedAt: string
  attemptNumber: number
}

export interface CoachStats {
  correct: number
  incorrect: number
  unanswered: number
  accuracy: number
  easyCorrect: number
  easyTotal: number
  mediumCorrect: number
  mediumTotal: number
  hardCorrect: number
  hardTotal: number
}

export interface CoachQuestion {
  number: number
  questionId: string
  text: string
  options: string[]
  difficultyName: CoachDifficultyName
  correct: boolean
  selectedOptionText: string | null
}

/** Server-verified, per-action pruned context for ONE selected exam (P5D.5). */
export interface ExamCoachContext {
  examId: string
  examTitle: string
  subject: string
  completedAt: string
  attemptNumber: number
  score: number
  total: number
  percentage: number
  action: CoachAction
  stats: CoachStats | null
  mistakes: CoachQuestion[] | null
  questions: CoachQuestion[] | null
  question: CoachQuestion | null
  correctAnswer: string | null
}

export interface CoachChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface CoachChatRequest {
  examId: string
  action: CoachAction
  questionId?: string
  message?: string
  history?: CoachChatMessage[]
}

export interface CoachChatResponse {
  answer: string
}