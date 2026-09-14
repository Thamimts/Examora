import api from './api'
import type { ApiResponse } from '@/types'
import type { StudentRecommendations } from '@/types/student-recommendation'

export const studentRecommendationApi = {
  get: () => api.get<ApiResponse<StudentRecommendations>>('/student/recommendations'),
}