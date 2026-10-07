import api from './api'
import type { ApiResponse } from '@/types'
import type { EnrollmentDetail, ExamRosterEntry, RoomSeating } from '@/types/enrolment'

export type EnrollPayload = { examId: string }
export type ManualSeatPayload = { roomId: string; seatNumber: number }

export const enrolmentApi = {
  enroll: (body: EnrollPayload) => api.post<ApiResponse<EnrollmentDetail>>('/enrollments', body),
  my: () => api.get<ApiResponse<EnrollmentDetail[]>>('/enrollments/my'),
  roster: (examId: string) => api.get<ApiResponse<ExamRosterEntry[]>>(`/enrollments/exam/${examId}`),
  examRooms: (examId: string) => api.get<ApiResponse<RoomSeating[]>>(`/enrollments/exam/${examId}/rooms`),
  manualAssign: (examId: string, studentId: string, body: ManualSeatPayload) =>
    api.put<ApiResponse<ExamRosterEntry>>(`/enrollments/exam/${examId}/students/${studentId}`, body),
}