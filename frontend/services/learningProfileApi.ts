import api from './api'
import type { ApiResponse } from '@/types'
import type { LearningProfile } from '@/types/learning-profile'

export const learningProfileApi = {
  get: () => api.get<ApiResponse<LearningProfile>>('/student/learning-profile'),
}