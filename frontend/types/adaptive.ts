export type PracticeOption = { id: string; text: string }

export type PracticeQuestion = { id: string; text: string; options: PracticeOption[]; difficulty: number }

export type PracticeAnsweredItem = { questionId: string; text: string; yourOption: string; correct: boolean; correctOptionText: string | null }

export type PracticeDifficultyStat = { difficulty: number; answered: number; correct: number; accuracy: number }

export type PracticeSummary = {
  correctCount: number
  totalAnswered: number
  accuracy: number
  perDifficulty: PracticeDifficultyStat[]
  weakAreas: string[]
  strongAreas: string[]
  subjectFocus: string | null
}

export type PracticeSessionStatus = 'STARTED' | 'COMPLETED' | 'EXPIRED'

export type PracticeSession = {
  id: string
  examId: string
  examTitle: string
  subject: string
  status: PracticeSessionStatus
  targetQuestionCount: number
  answeredCount: number
  correctCount: number
  workingDifficulty: number
  level: number
  currentQuestion: PracticeQuestion | null
  answeredHistory: PracticeAnsweredItem[]
  summary: PracticeSummary | null
}

export type PracticeAnswerResponse = {
  correct: boolean
  correctOptionText: string | null
  level: number
  workingDifficulty: number
  answeredCount: number
  correctCount: number
  next: PracticeQuestion | null
  completed: boolean
  summary: PracticeSummary | null
}