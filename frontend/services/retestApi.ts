import api from './api'
import type { ApiResponse } from '@/types'

export type RetestRequest = { id: string; examId: string; studentId: string; studentName: string; examTitle: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; requestedAt: string; reviewedAt?: string; reviewedBy?: string; reason?: string }

export const retestApi = {
  mine: () => api.get<ApiResponse<RetestRequest[]>>('/retest-requests'),
  request: (examId: string) => api.post<ApiResponse<RetestRequest>>(`/retest-requests/${examId}`),
  admin: () => api.get<ApiResponse<RetestRequest[]>>('/retest-requests/admin'),
  review: (id: string, status: 'APPROVED' | 'REJECTED', reason?: string) => api.post<ApiResponse<RetestRequest>>(`/retest-requests/admin/${id}/review`, { status, reason }),
}
