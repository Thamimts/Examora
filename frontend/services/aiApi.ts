import { api } from './api'
import type { AIAnalysis, AnalyticsOverview, ExamGenerationRequest, GeneratedExam, StudentAnalytics } from '@/types/ai'
export const aiApi = { analysis: (studentId: string, examId: string) => api.get<AIAnalysis>(`/ai/analysis/${studentId}/${examId}`), studentAnalytics: (studentId: string) => api.get<StudentAnalytics>(`/ai/analytics/students/${studentId}`), generateExam: (payload: ExamGenerationRequest) => api.post<GeneratedExam>('/ai/exams/generate', payload), analytics: () => api.get<AnalyticsOverview>('/analytics/overview') }
