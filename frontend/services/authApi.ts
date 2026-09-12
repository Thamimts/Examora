import api from './api'
import type { ApiResponse, AuthResponse, LoginResult, OAuthProviderInfo, RecoveryCodes, TwoFactorSetup, TwoFactorStatus } from '@/types'
export const authApi = {
  login: (payload: { email: string; password: string }) => api.post<ApiResponse<LoginResult>>('/auth/login', payload),
  register: (payload: { name: string; email: string; password: string }) => api.post<ApiResponse<AuthResponse>>('/auth/register', payload),
  logout: () => api.post('/auth/logout'),
  verifyTwoFactor: (payload: { challengeToken: string; code: string }) => api.post<ApiResponse<AuthResponse>>('/auth/2fa/verify', payload),
  recoverTwoFactor: (payload: { challengeToken: string; recoveryCode: string }) => api.post<ApiResponse<AuthResponse>>('/auth/2fa/recover', payload),
  twoFactorStatus: () => api.get<ApiResponse<TwoFactorStatus>>('/auth/2fa/status'),
  twoFactorSetup: () => api.post<ApiResponse<TwoFactorSetup>>('/auth/2fa/setup'),
  twoFactorConfirm: (code: string) => api.post<ApiResponse<RecoveryCodes>>('/auth/2fa/confirm', { code }),
  twoFactorDisable: (payload: { code?: string; password?: string }) => api.post<ApiResponse<null>>('/auth/2fa/disable', payload),
  changePassword: (payload: { currentPassword: string; newPassword: string }) => api.post<ApiResponse<null>>('/auth/change-password', payload),
  oauthProviders: () => api.get<ApiResponse<OAuthProviderInfo[]>>('/auth/oauth/providers'),
}