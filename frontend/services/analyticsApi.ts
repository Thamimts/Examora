import api from './api'
import type { ApiResponse } from '@/types'
import type { StudentPerformanceAnalytics } from '@/types/analytics'

export const analyticsApi = {
  performance: () => api.get<ApiResponse<StudentPerformanceAnalytics>>('/student/performance'),
}