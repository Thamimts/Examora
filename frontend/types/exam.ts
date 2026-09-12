import type { Exam, Question, Result } from '@/types'
export type AnswerValue = string | string[]
export type ActiveAttemptInfo = { attemptId: string; examId: string; examTitle: string; subject: string; duration: number; status: string; startedAt: string; expiresAt: string; remainingSeconds: number }
export type ExamSession = { exam: Exam & { questions?: Question[] }; startAt: string; endAt: string; durationSeconds: number }
export type AttemptState = { examId: string; questionIndex: number; answers: Record<string, AnswerValue>; review: Record<string, boolean>; startAt: string; endAt: string; submitted: boolean; autosaving: boolean; lastSavedAt?: string }
export type ResultDetail = Result & { answers: Record<string, AnswerValue>; questions: Question[]; correct: number; totalQuestions: number }
export type QuestionReview = { number: number; questionId: string; questionText: string; options: string[]; selectedOptionText: string | null; correctOptionText: string | null; answered: boolean; correct: boolean }
export type ExamResultReview = { resultId: string; examId: string; examTitle: string; subject: string; date: string; score: number; total: number; percentage: number; correctCount: number; incorrectCount: number; unansweredCount: number; questions: QuestionReview[] }
export type ExamPayload = Pick<Exam, 'title' | 'subject' | 'duration'> & { description?: string; questions?: Question[] }
export type QuestionPayload = Omit<Question, 'id'>
