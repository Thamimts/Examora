export type Role = 'STUDENT' | 'TEACHER' | 'ADMIN'
export type User = { id: string; name: string; email: string; role: Role; avatar?: string }
export type Exam = { id: string; title: string; subject: string; date: string; duration: number; status: 'UPCOMING' | 'COMPLETED' | 'DRAFT'; participants: number; averageScore?: number }
export type Question = { id: string; examId?: string; text: string; options: string[]; answer?: string }
export type Result = { id: string; userId?: string; examId?: string; examTitle: string; subject: string; score: number; date: string; total: number }
export type ApiResponse<T> = { success: boolean; message?: string; data: T }
export type AuthResponse = { token: string; user: User }
export type AnalyticsPoint = { month: string; score: number; exams: number }
export type DashboardStats = { label: string; value: string; change: string; tone: 'blue' | 'green' | 'amber' | 'slate' }
export type AuthState = { user: User | null; token: string | null; hydrated: boolean; setAuth: (auth: AuthResponse) => void; logout: () => void }
export const roleLabels: Record<Role, string> = { STUDENT: 'Student', TEACHER: 'Teacher', ADMIN: 'Administrator' }
export const homeForRole = (role: Role) => `/${role.toLowerCase()}/dashboard`
export const isRole = (user: User | null, roles?: Role[]) => Boolean(user && (!roles || roles.includes(user.role)))
