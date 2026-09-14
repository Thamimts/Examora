import api from './api'
import type { ApiResponse } from '@/types'
import type { StudentProgress } from '@/types/student-progress'

export const studentProgressApi = {
  get: () => api.get<ApiResponse<StudentProgress>>('/student/progress'),
}