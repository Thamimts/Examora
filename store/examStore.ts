'use client'
import { create } from 'zustand'
import type { AnswerValue, AttemptState } from '@/types/exam'

type Store = {
  attempts: Record<string, AttemptState>
  start: (id: string, startAt: string, endAt: string) => void
  answer: (questionId: string, value: AnswerValue) => void
  toggleReview: (questionId: string) => void
  move: (index: number) => void
  clear: (questionId: string) => void
  submit: (id: string) => void
  get: (id: string) => AttemptState | undefined
}

const questionKey = (examId: string, questionId: string) => `${examId}:${questionId}`

export const useExamStore = create<Store>()((set, get) => ({
  attempts: {},
  start: (id, startAt, endAt) => set((state) => ({ attempts: { ...state.attempts, [id]: state.attempts[id] ?? { examId: id, questionIndex: 0, answers: {}, review: {}, startAt, endAt, submitted: false, autosaving: false } } })),
  answer: (questionId, value) => set((state) => {
    const current = Object.values(state.attempts)[0]
    if (!current || current.submitted) return state
    return { attempts: { ...state.attempts, [current.examId]: { ...current, answers: { ...current.answers, [questionKey(current.examId, questionId)]: value }, autosaving: true } } }
  }),
  toggleReview: (questionId) => set((state) => {
    const current = Object.values(state.attempts)[0]
    if (!current || current.submitted) return state
    const key = questionKey(current.examId, questionId)
    return { attempts: { ...state.attempts, [current.examId]: { ...current, review: { ...current.review, [key]: !current.review[key] } } } }
  }),
  move: (index) => set((state) => { const current = Object.values(state.attempts)[0]; return current && !current.submitted ? { attempts: { ...state.attempts, [current.examId]: { ...current, questionIndex: Math.max(0, index) } } } : state }),
  clear: (questionId) => set((state) => {
    const current = Object.values(state.attempts)[0]
    if (!current || current.submitted) return state
    const key = questionKey(current.examId, questionId)
    const answers = { ...current.answers }; delete answers[key]
    return { attempts: { ...state.attempts, [current.examId]: { ...current, answers, autosaving: true } } }
  }),
  submit: (id) => set((state) => { const current = state.attempts[id]; return current && !current.submitted ? { attempts: { ...state.attempts, [id]: { ...current, submitted: true, autosaving: false } } } : state }),
  get: (id) => get().attempts[id],
}))

export { questionKey }
