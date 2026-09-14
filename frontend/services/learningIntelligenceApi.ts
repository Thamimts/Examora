import api from './api'
import type { ApiResponse } from '@/types'
import type { LearningIntelligence } from '@/types/learning-intelligence'

export const learningIntelligenceApi = {
  get: () => api.get<ApiResponse<LearningIntelligence>>('/student/learning-intelligence'),
}