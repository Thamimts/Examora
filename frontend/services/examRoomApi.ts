import api from './api'
import type { ApiResponse } from '@/types'
import type { ExamRoom, JoinRoomResponse, RoomDetail } from '@/types/examRoom'

export const examRoomApi = {
  create: (examId: string) => api.post<ApiResponse<ExamRoom>>('/exam-rooms', { examId }),
  mine: () => api.get<ApiResponse<ExamRoom[]>>('/exam-rooms/my'),
  get: (roomId: string) => api.get<ApiResponse<RoomDetail>>(`/exam-rooms/${roomId}`),
  start: (roomId: string) => api.post<ApiResponse<ExamRoom>>(`/exam-rooms/${roomId}/start`),
  end: (roomId: string) => api.post<ApiResponse<ExamRoom>>(`/exam-rooms/${roomId}/end`),
  join: (roomCode: string) => api.post<ApiResponse<JoinRoomResponse>>('/exam-rooms/join', { roomCode }),
}