import api from './api'
import type { ApiResponse } from '@/types'
import type { Centre, CentreRoom, InvigilatorRoomSummary, RoomSeating } from '@/types/enrolment'

export type CentrePayload = { name: string; code: string; address?: string; contactPhone?: string; contactEmail?: string }
export type CentreRoomPayload = { centreId: string; roomName: string; roomCode: string; capacity: number; invigilatorId?: string | null }

export const centreApi = {
  list: () => api.get<ApiResponse<Centre[]>>('/centres'),
  get: (id: string) => api.get<ApiResponse<Centre>>(`/centres/${id}`),
  rooms: (id: string) => api.get<ApiResponse<CentreRoom[]>>(`/centres/${id}/rooms`),
  create: (body: CentrePayload) => api.post<ApiResponse<Centre>>('/centres', body),
  update: (id: string, body: CentrePayload) => api.put<ApiResponse<Centre>>(`/centres/${id}`, body),
  remove: (id: string) => api.delete(`/centres/${id}`),
}

export const centreRoomApi = {
  mine: () => api.get<ApiResponse<CentreRoom[]>>('/centre-rooms/mine'),
  mineSummary: () => api.get<ApiResponse<InvigilatorRoomSummary[]>>('/centre-rooms/mine/summary'),
  seating: (roomId: string, examId?: string) =>
    api.get<ApiResponse<RoomSeating>>(`/centre-rooms/${roomId}/seating`, { params: examId ? { examId } : {} }),
  create: (body: CentreRoomPayload) => api.post<ApiResponse<CentreRoom>>('/centre-rooms', body),
  update: (id: string, body: CentreRoomPayload) => api.put<ApiResponse<CentreRoom>>(`/centre-rooms/${id}`, body),
  remove: (id: string) => api.delete(`/centre-rooms/${id}`),
}