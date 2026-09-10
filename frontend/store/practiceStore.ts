'use client'
import { create } from 'zustand'
import type { PracticeAnswerResponse, PracticeSession } from '@/types/adaptive'

type PracticeState = {
  sessions: Record<string, PracticeSession>
  submitting: boolean
  setSession: (examId: string, session: PracticeSession) => void
  applyAnswered: (examId: string, response: PracticeAnswerResponse) => void
  setSubmitting: (value: boolean) => void
  reset: (examId: string) => void
}

export const usePracticeStore = create<PracticeState>()((set) => ({
  sessions: {},
  submitting: false,
  setSession: (examId, session) => set((state) => ({ sessions: { ...state.sessions, [examId]: session } })),
  applyAnswered: (examId, response) => set((state) => {
    const current = state.sessions[examId]
    if (!current) return state
    return {
      sessions: {
        ...state.sessions,
        [examId]: {
          ...current,
          status: response.completed ? 'COMPLETED' : current.status,
          answeredCount: response.answeredCount,
          correctCount: response.correctCount,
          workingDifficulty: response.workingDifficulty,
          level: response.level,
          currentQuestion: response.next,
          summary: response.summary ?? current.summary,
        },
      },
    }
  }),
  setSubmitting: (value) => set({ submitting: value }),
  reset: (examId) => set((state) => {
    const next = { ...state.sessions }
    delete next[examId]
    return { sessions: next }
  }),
}))