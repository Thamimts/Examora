import api from './api'; import type { ApiResponse, User } from '@/types'
export const userApi = { me: () => api.get<ApiResponse<User>>('/users/me'), list: () => api.get<ApiResponse<User[]>>('/users') }
