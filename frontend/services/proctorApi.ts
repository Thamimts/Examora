import { api } from './api'
import type { ProctorEvent } from '@/types/ai'
export const proctorApi = { events: (events: ProctorEvent[]) => api.post('/proctor/events/batch', { events }), start: (attemptId: string) => api.post(`/proctor/attempts/${attemptId}/start`), stop: (attemptId: string) => api.post(`/proctor/attempts/${attemptId}/stop`) }
