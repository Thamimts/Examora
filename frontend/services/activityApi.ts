import api from './api'; import type { ActivityEvent, ApiResponse } from '@/types'
export const activityApi = { recent: () => api.get<ApiResponse<ActivityEvent[]>>('/activity') }
