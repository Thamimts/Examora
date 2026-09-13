import { api } from './api'
import type { ApiResponse } from '@/types'
import type { ProctorEvent as ProctorEventDto, ProctorMonitorData, ProctorSummary, StudentProctorEvent } from '@/types/proctor'

export const proctorApi = {
  events: (events: StudentProctorEvent[]) =>
    api.post<ApiResponse<{ saved: number }>>('/proctor/events/batch', { events }),
  start: (attemptId: string) =>
    api.post(`/proctor/attempts/${attemptId}/start`),
  stop: (attemptId: string) =>
    api.post(`/proctor/attempts/${attemptId}/stop`),
  monitor: (examId: string) =>
    api.get<ApiResponse<ProctorMonitorData>>(`/proctor/exams/${examId}/monitor`),
  summary: (examId: string) =>
    api.get<ApiResponse<ProctorSummary>>(`/proctor/exams/${examId}/summary`),
  attemptEvents: (attemptId: string, limit = 50) =>
    api.get<ApiResponse<ProctorEventDto[]>>(`/proctor/attempts/${attemptId}/events`, {
      params: { limit },
    }),
}
