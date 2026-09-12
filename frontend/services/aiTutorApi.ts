import api from './api'
import type { ApiResponse } from '@/types'
import type { AiTutorAction, AiTutorAnswerResult, AiTutorExplanation, AiTutorQuestion } from '@/types/ai'

const PREFIX = '/student/ai-tutor'

type ApiData = { data?: unknown } & Record<string, unknown>

export const aiTutorApi = {
  generate: async (sessionId: string, questionId: string, action: AiTutorAction, token: string): Promise<AiTutorExplanation | AiTutorQuestion> => {
    const response = await fetch('/api/student/ai-tutor', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ sessionId, questionId, action }),
      cache: 'no-store',
    })
    const body = (await response.json().catch(() => null)) as ApiData | null
    if (!response.ok) {
      const error = new Error((body as { error?: string } | null)?.error ?? 'The AI tutor is temporarily unavailable.') as Error & { status?: number }
      error.status = response.status
      throw error
    }
    return body?.data as AiTutorExplanation | AiTutorQuestion
  },
  answerQuestion: (questionId: string, optionId: string) =>
    api.post<ApiResponse<AiTutorAnswerResult>>(`${PREFIX}/questions/${questionId}/answer`, { optionId }),
}