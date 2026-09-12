import api from './api'
import type { ApiResponse } from '@/types'
import type { AIPracticeAnswerResponse, AIPracticeReview, AIPracticeSession, AIPracticeSessionSummary, AIPracticeSubmitResponse } from '@/types/ai'

const PREFIX = '/student/ai-practice'

type ApiData = { data?: unknown } & Record<string, unknown>

export const aiPracticeApi = {
  generate: async (topic: string, difficulty: string, questionCount: number, token: string, minimumQuestions?: number, minimumAccuracy?: number): Promise<AIPracticeSession> => {
    const response = await fetch('/api/student/ai-practice/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        topic,
        difficulty,
        questionCount,
        minimumQuestions: minimumQuestions ?? undefined,
        minimumAccuracy: minimumAccuracy ?? undefined,
      }),
      cache: 'no-store',
    })
    const body = (await response.json().catch(() => null)) as ApiData | null
    if (!response.ok) {
      const error = new Error((body as { error?: string } | null)?.error ?? 'Unable to start your AI practice session.') as Error & { status?: number }
      error.status = response.status
      throw error
    }
    return body?.data as AIPracticeSession
  },
  listSessions: () => api.get<ApiResponse<AIPracticeSessionSummary[]>>(`${PREFIX}/sessions`),
  getSession: (sessionId: string) => api.get<ApiResponse<AIPracticeSession>>(`${PREFIX}/sessions/${sessionId}`),
  answer: (sessionId: string, questionId: string, optionId: string) =>
    api.post<ApiResponse<AIPracticeAnswerResponse>>(`${PREFIX}/sessions/${sessionId}/answer`, { questionId, optionId }),
  submit: (sessionId: string) => api.post<ApiResponse<AIPracticeSubmitResponse>>(`${PREFIX}/sessions/${sessionId}/submit`),
  getReview: (sessionId: string) => api.get<ApiResponse<AIPracticeReview>>(`${PREFIX}/sessions/${sessionId}/review`),
  generateReview: async (sessionId: string, token: string): Promise<AIPracticeReview> => {
    const response = await fetch('/api/student/ai-practice/review', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ sessionId }),
      cache: 'no-store',
    })
    const body = (await response.json().catch(() => null)) as ApiData | null
    if (!response.ok) {
      const error = new Error((body as { error?: string } | null)?.error ?? 'The AI review is temporarily unavailable.') as Error & { status?: number }
      error.status = response.status
      throw error
    }
    return body?.data as AIPracticeReview
  },
}