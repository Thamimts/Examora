import api from './api'
import type { ApiResponse } from '@/types'
import type { PracticeAnswerResponse, PracticeSession } from '@/types/adaptive'

export const adaptiveApi = {
  resumeOrCreate: (examId: string) =>
    api.get<ApiResponse<PracticeSession>>(`/student/adaptive/exams/${examId}/session`),
  startNew: (examId: string, targetQuestionCount?: number) =>
    api.post<ApiResponse<PracticeSession>>(`/student/adaptive/exams/${examId}/sessions`, targetQuestionCount ? { targetQuestionCount } : {}),
  get: (sessionId: string) => api.get<ApiResponse<PracticeSession>>(`/student/adaptive/sessions/${sessionId}`),
  answer: (sessionId: string, questionId: string, optionId: string) =>
    api.post<ApiResponse<PracticeAnswerResponse>>(`/student/adaptive/sessions/${sessionId}/answer`, { questionId, optionId }),
}