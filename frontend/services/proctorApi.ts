import { api } from './api'
import type { ApiResponse } from '@/types'
import type { CommandCenterData, ProctorEvent as ProctorEventDto, ProctorMonitorData, ProctorSignalInput, ProctorSummary, StudentProctorEvent, SubmitSignalResponse } from '@/types/proctor'

export const proctorApi = {
  events: (events: StudentProctorEvent[]) =>
    api.post<ApiResponse<{ saved: number }>>('/proctor/events/batch', { events }),
  submitSignal: (attemptId: string, signal: ProctorSignalInput) =>
    api.post<ApiResponse<SubmitSignalResponse>>(`/proctor/attempts/${attemptId}/signals`, signal),
  start: (attemptId: string) =>
    api.post(`/proctor/attempts/${attemptId}/start`),
  stop: (attemptId: string) =>
    api.post(`/proctor/attempts/${attemptId}/stop`),
  monitor: (examId: string) =>
    api.get<ApiResponse<ProctorMonitorData>>(`/proctor/exams/${examId}/monitor`),
  summary: (examId: string) =>
    api.get<ApiResponse<ProctorSummary>>(`/proctor/exams/${examId}/summary`),
  commandCenter: (examId: string) =>
    api.get<ApiResponse<CommandCenterData>>(`/proctor/exams/${examId}/command-center`),
  attemptEvents: (attemptId: string, limit = 50) =>
    api.get<ApiResponse<ProctorEventDto[]>>(`/proctor/attempts/${attemptId}/events`, {
      params: { limit },
    }),
}
