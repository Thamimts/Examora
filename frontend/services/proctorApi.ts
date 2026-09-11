import { api } from './api'
import type { ApiResponse } from '@/types'
import type { ProctorEvent as ProctorEventDto, ProctorMonitorData } from '@/types/proctor'

export const proctorApi = {
  events: (events: import('@/types/ai').ProctorEvent[]) =>
    api.post('/proctor/events/batch', { events }),
  start: (attemptId: string) =>
    api.post(`/proctor/attempts/${attemptId}/start`),
  stop: (attemptId: string) =>
    api.post(`/proctor/attempts/${attemptId}/stop`),
  monitor: (examId: string) =>
    api.get<ApiResponse<ProctorMonitorData>>(`/proctor/exams/${examId}/monitor`),
  attemptEvents: (attemptId: string, limit = 50) =>
    api.get<ApiResponse<ProctorEventDto[]>>(`/proctor/attempts/${attemptId}/events`, {
      params: { limit },
    }),
}
