import api from './api'
import type { Exam } from '@/types'

export const examApi = {
  list: () => api.get<Exam[]>('/exams/mine'),

  get: (id: string) =>
    api.get<Exam>(`/exams/${id}`),

  create: (payload: {
    title: string
    subject: string
    durationMinutes: number
  }) =>
    api.post<Exam>('/exams', payload),

  update: (id: string, payload: Partial<Exam>) =>
    api.put<Exam>(`/exams/${id}`, payload),

  remove: (id: string) =>
    api.delete(`/exams/${id}`),

  publish: (id: string) =>
    api.patch<Exam>(`/exams/${id}/status?status=PUBLISHED`),
}
