import api from './api'; import type { ApiResponse, Exam, Result } from '@/types'
import type { ActiveAttemptInfo, AttemptProgress, ExamResultReview, StudentAttemptStatus } from '@/types/exam'

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
  activeAttempts: () => api.get<ApiResponse<ActiveAttemptInfo[]>>('/exams/attempts/active'),
  start: (id: string) => api.post<ApiResponse<StartExamResponse>>(`/exams/${id}/start`),
  submit: (id: string, answers: SubmittedAnswer[]) => api.post<ApiResponse<ExamSubmissionResponse>>(`/exams/${id}/submit`, { answers }),
  resultReview: (id: string) => api.get<ApiResponse<ExamResultReview>>(`/exams/${id}/result`),
  attemptProgress: (id: string) => api.get<ApiResponse<AttemptProgress>>(`/exams/${id}/attempt/progress`),
  attemptStatus: (id: string) => api.get<ApiResponse<StudentAttemptStatus>>(`/exams/${id}/attempt/status`),
  saveAnswer: (id: string, questionId: string, value: string) => api.put<ApiResponse<null>>(`/exams/${id}/attempt/answers/${questionId}`, { value }),
}
