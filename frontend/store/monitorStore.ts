import { create } from 'zustand'
import type { ProctorAttempt, ProctorMonitorData, ProctorUpdate } from '@/types/proctor'

interface ExamMonitorState {
  examId: string | null
  examTitle: string
  attempts: ProctorAttempt[]
  setMonitorData: (data: ProctorMonitorData) => void
  mergeUpdate: (update: ProctorUpdate) => void
  clear: () => void
}

export const useMonitorStore = create<ExamMonitorState>((set) => ({
  examId: null,
  examTitle: '',
  attempts: [],

  setMonitorData: (data) =>
    set({
      examId: data.examId,
      examTitle: data.examTitle,
      attempts: data.attempts,
    }),

  mergeUpdate: (update) =>
    set((state) => {
      if (state.examId !== update.examId) return state

      const existing = state.attempts.find((a) => a.attemptId === update.attemptId)
      if (existing) {
        return {
          attempts: state.attempts.map((a) =>
            a.attemptId === update.attemptId
              ? {
                  ...a,
                  status: update.status,
                  riskLevel: update.riskLevel,
                  riskScore: update.riskScore,
                  eventCount: update.eventCount,
                  latestProctorEvent: update.latestEvent ?? a.latestProctorEvent,
                  lastActivityAt: update.occurredAt,
                  activeNow: update.status === 'STARTED',
                }
              : a,
          ),
        }
      }

      const newAttempt: ProctorAttempt = {
        attemptId: update.attemptId,
        attemptNumber: 1,
        status: update.status,
        startedAt: update.occurredAt,
        expiresAt: '',
        student: update.student,
        activeNow: update.status === 'STARTED',
        riskLevel: update.riskLevel,
        riskScore: update.riskScore,
        eventCount: update.eventCount,
        latestProctorEvent: update.latestEvent,
        lastActivityAt: update.occurredAt,
      }
      return { attempts: [newAttempt, ...state.attempts] }
    }),

  clear: () => set({ examId: null, examTitle: '', attempts: [] }),
}))
