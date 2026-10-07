export type Role = 'STUDENT' | 'TEACHER' | 'ADMIN'
export type LoginRole = 'STUDENT' | 'ADMIN'
export type UserProfile = {
  id: string
  name: string
  email: string
  role: Role
  avatar?: string
  rollNumber?: string | null
  dateOfBirth?: string | null
  department?: string | null
  batch?: string | null
  passwordChangeRequired?: boolean
}
export type User = { id: string; name: string; email: string; role: Role; avatar?: string }
export type Exam = { id: string; title: string; subject: string; date: string; duration: number; status: 'UPCOMING' | 'PUBLISHED' | 'ACTIVE' | 'ENDED' | 'COMPLETED' | 'DRAFT'; participants: number; averageScore?: number; startAt?: string; endAt?: string; centreId?: string | null }
export type Question = { id: string; examId?: string; text: string; options: string[]; answer?: string; difficulty?: number; type?: 'MCQ' | 'TRUE_FALSE' }
export type Result = { id: string; userId?: string; examId?: string; examTitle: string; subject: string; score: number; date: string; total: number }
export type ApiResponse<T> = { success: boolean; message?: string; data: T }
export type AuthResponse = { token: string; user: User }
export type LoginResult = { token: string | null; user: User | null; requiresTwoFactor: boolean; challengeToken?: string | null; requiresPasswordChange?: boolean; setupRequired?: boolean; setupToken?: string | null }
export type TwoFactorStatus = { enabled: boolean }
export type TwoFactorSetup = { secret: string; otpauthUri: string }
export type RecoveryCodes = { recoveryCodes: string[] }
export type OAuthProviderInfo = { provider: string; label: string; enabled: boolean }
export type ActivityEvent = { id: string; type: string; message: string; createdAt: string }
export type AnalyticsPoint = { month: string; score: number; exams: number }
export type DashboardStats = { label: string; value: string; change: string; tone: 'blue' | 'green' | 'amber' | 'slate' }
export type AuthState = { user: User | null; token: string | null; hydrated: boolean; setAuth: (auth: AuthResponse) => void; logout: () => void }
export const roleLabels: Record<Role, string> = { STUDENT: 'Student', TEACHER: 'Teacher', ADMIN: 'Administrator' }
export const homeForRole = (role: Role) => `/${role.toLowerCase()}/dashboard`
export const isRole = (user: User | null, roles?: Role[]) => Boolean(user && (!roles || roles.includes(user.role)))
