import api from './api'; import type { ApiResponse, Result } from '@/types'
export const resultApi = { mine: () => api.get<ApiResponse<Result[]>>('/results/me'), list: () => api.get<ApiResponse<Result[]>>('/results') }
