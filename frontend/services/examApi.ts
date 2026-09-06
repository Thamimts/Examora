import api from './api'; import type { ApiResponse, Exam, Result } from '@/types'

type CreateExamPayload = { title: string; subject: string; date: string; duration: number }
type SubmittedAnswer = { questionId: string; optionId?: string; value?: string }
type StartExamResponse = { examId: string; studentId: string; status: string; exam: Exam; attemptId: string; startedAt: string; expiresAt: string; endAt?: string }
type ExamSubmissionResponse = { result: Result; score: number; total: number; percentage: number }

export const examApi = {
  list: () => api.get<ApiResponse<Exam[]>>('/exams'),
  get: (id: string) => api.get<ApiResponse<Exam>>(`/exams/${id}`),
  create: (payload: CreateExamPayload) => api.post<ApiResponse<Exam>>('/exams', payload),
  update: (id: string, payload: Partial<Exam>) => api.put<ApiResponse<Exam>>(`/exams/${id}`, payload),
  remove: (id: string) => api.delete(`/exams/${id}`),
  publish: (id: string) => api.post<ApiResponse<Exam>>(`/exams/${id}/publish`),
  start: (id: string) => api.post<ApiResponse<StartExamResponse>>(`/exams/${id}/start`),
  submit: (id: string, answers: SubmittedAnswer[]) => api.post<ApiResponse<ExamSubmissionResponse>>(`/exams/${id}/submit`, { answers }),
}
