import type { Exam, Question, Result } from '@/types'
export type AnswerValue = string | string[]
export type ExamSession = { exam: Exam & { questions?: Question[] }; startAt: string; endAt: string; durationSeconds: number }
export type AttemptState = { examId: string; questionIndex: number; answers: Record<string, AnswerValue>; review: Record<string, boolean>; startAt: string; endAt: string; submitted: boolean; autosaving: boolean; lastSavedAt?: string }
export type ResultDetail = Result & { answers: Record<string, AnswerValue>; questions: Question[]; correct: number; totalQuestions: number }
export type ExamPayload = Pick<Exam, 'title' | 'subject' | 'duration'> & { description?: string; questions?: Question[] }
export type QuestionPayload = Omit<Question, 'id'>
