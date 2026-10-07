import api from './api'
import type { ApiResponse, Role, User, UserProfile } from '@/types'
export interface CreateUserPayload {
  name: string
  email: string
  password?: string
  role: Role
  rollNumber?: string
  dateOfBirth?: string
  department?: string
  batch?: string
}
export interface UpdateProfilePayload {
  name?: string
  department?: string
  batch?: string
  avatar?: string
}
export const userApi = {
  me: () => api.get<ApiResponse<UserProfile>>('/users/me'),
  list: () => api.get<ApiResponse<User[]>>('/users'),
  create: (body: CreateUserPayload) => api.post<ApiResponse<User>>('/users', body),
  updateProfile: (body: UpdateProfilePayload) => api.put<ApiResponse<UserProfile>>('/users/me', body),
  resetCredentials: (id: string) => api.post<ApiResponse<null>>(`/users/${id}/reset-credentials`),
}