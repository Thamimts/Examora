import api from './api'
import type { ApiResponse, Question } from '@/types'

export const questionApi = {
  listAll: () => api.get<ApiResponse<Question[]>>('/questions'),
  list: (examId: string) => api.get<ApiResponse<Question[]>>(`/exams/${examId}/questions`),
  create: (examId: string, payload: Partial<Question>) => api.post<ApiResponse<Question>>(`/exams/${examId}/questions`, payload),
  update: (id: string, payload: Partial<Question>) => api.put<ApiResponse<Question>>(`/questions/${id}`, payload),
  remove: (id: string) => api.delete(`/questions/${id}`),
}
