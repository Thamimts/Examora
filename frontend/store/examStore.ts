'use client'
import { create } from 'zustand'
import { examApi } from '@/services/examApi'
import type { AttemptProgress } from '@/types/exam'

export type SaveState = 'saving' | 'saved' | 'failed'

type ExamAutosaveStore = {
  examId: string | null
  attemptId: string | null
  startedAt: string | null
  expiresAt: string | null
  remainingSeconds: number
  answers: Record<string, string>
  saved: Record<string, string>
  saveState: Record<string, SaveState>
  offline: boolean
  loaded: boolean
  expired: boolean
  review: Record<string, boolean>
  resume: (examId: string) => Promise<AttemptProgress>
  setAnswer: (questionId: string, value: string) => void
  clearAnswer: (questionId: string) => void
  toggleReview: (questionId: string) => void
  resync: (progress: AttemptProgress) => void
  flush: () => Promise<void>
  markExpired: () => void
  reset: () => void
}

const omitKey = (record: Record<string, string>, key: string): Record<string, string> => {
  const next = { ...record }
  delete next[key]
  return next
}

const DEBOUNCE_MS = 800
const MAX_RETRIES = 5

export const useExamStore = create<ExamAutosaveStore>((set, get) => {
  let debounceTimer: number | null = null
  let inFlight = new Set<string>()
  let retries: Record<string, number> = {}

  const statusOf = (error: unknown): number | null => {
    const status = (error as { response?: { status?: number } })?.response?.status
    return typeof status === 'number' ? status : null
  }

  const saveOne = async (questionId: string, value: string): Promise<void> => {
    const { examId, expired } = get()
    if (!examId || expired || inFlight.has(questionId)) return
    inFlight.add(questionId)
    set((state) => ({ saveState: { ...state.saveState, [questionId]: 'saving' } }))
    try {
      await examApi.saveAnswer(examId, questionId, value)
      set((state) => ({
        saved: value === '' ? omitKey(state.saved, questionId) : { ...state.saved, [questionId]: value },
        saveState: { ...state.saveState, [questionId]: 'saved' },
        offline: false,
      }))
      delete retries[questionId]
    } catch (error) {
      const status = statusOf(error)
      if (status === 409) {
        get().markExpired()
        return
      }
      if (status === 401 || status === 403 || status === 400) {
        set((state) => ({ saveState: { ...state.saveState, [questionId]: 'failed' }, offline: false }))
        return
      }
      set((state) => ({ saveState: { ...state.saveState, [questionId]: 'failed' }, offline: true }))
      const attempt = (retries[questionId] ?? 0) + 1
      retries[questionId] = attempt
      if (attempt <= MAX_RETRIES) {
        window.setTimeout(() => {
          if (!get().expired) void get().flush()
        }, Math.min(30000, 1500 * attempt))
      }
    } finally {
      inFlight.delete(questionId)
    }
  }

  return {
    examId: null,
    attemptId: null,
    startedAt: null,
    expiresAt: null,
    remainingSeconds: 0,
    answers: {},
    saved: {},
    saveState: {},
    offline: false,
    loaded: false,
    expired: false,
    review: {},

    resume: async (examId) => {
      const response = await examApi.attemptProgress(examId)
      const progress = response.data.data
      set({
        examId: progress.examId,
        attemptId: progress.attemptId,
        startedAt: progress.startedAt,
        expiresAt: progress.expiresAt,
        remainingSeconds: progress.remainingSeconds,
        answers: { ...progress.answers },
        saved: { ...progress.answers },
        saveState: {},
        offline: false,
        loaded: true,
        expired: false,
      })
      return progress
    },

    setAnswer: (questionId, value) => {
      const { expired } = get()
      if (expired) return
      const retryKeys: Record<string, number> = {}
      retries = retryKeys
      if (debounceTimer !== null) window.clearTimeout(debounceTimer)
      set((state) => ({
        answers: { ...state.answers, [questionId]: value },
        saveState: { ...state.saveState, [questionId]: 'saving' },
        offline: false,
      }))
      debounceTimer = window.setTimeout(() => {
        debounceTimer = null
        void get().flush()
      }, DEBOUNCE_MS)
    },

    clearAnswer: (questionId) => {
      const { expired } = get()
      if (expired) return
      retries = {}
      if (debounceTimer !== null) window.clearTimeout(debounceTimer)
      set((state) => {
        const answers = { ...state.answers }
        delete answers[questionId]
        return { answers, saveState: { ...state.saveState, [questionId]: 'saving' } }
      })
      debounceTimer = window.setTimeout(() => {
        debounceTimer = null
        void get().flush()
      }, DEBOUNCE_MS)
    },

    toggleReview: (questionId) => {
      const { expired } = get()
      if (expired) return
      set((state) => ({
        review: {
          ...state.review,
          [questionId]: !state.review[questionId],
        },
      }))
    },

    flush: async () => {
      if (debounceTimer !== null) {
        window.clearTimeout(debounceTimer)
        debounceTimer = null
      }
      const { examId, expired, answers, saved } = get()
      if (!examId || expired) return
      const qids = new Set<string>()
      for (const qid of Object.keys(answers)) if (answers[qid] !== saved[qid]) qids.add(qid)
      for (const qid of Object.keys(saved)) if (!(qid in answers)) qids.add(qid)
      for (const qid of qids) await saveOne(qid, answers[qid] ?? '')
    },

    markExpired: () => set({ expired: true }),

    resync: (progress) => {
      set({
        expiresAt: progress.expiresAt,
        remainingSeconds: progress.remainingSeconds,
        offline: false,
      })
    },

    reset: () =>
      set({
        examId: null,
        attemptId: null,
        startedAt: null,
        expiresAt: null,
        remainingSeconds: 0,
        answers: {},
        saved: {},
        saveState: {},
        offline: false,
        loaded: false,
        expired: false,
        review: {},
      }),
  }
})