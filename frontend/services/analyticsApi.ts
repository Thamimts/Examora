import api from './api'
import type { ApiResponse } from '@/types'
import type {
  ExamAnalyticsDetail,
  ExamAnalyticsSummary,
  OptionLabel,
  StudentAnalyticsSummary,
  StudentDrilldownAnalytics,
  StudentExamAnalytics,
  StudentPerformanceAnalytics,
  StudentPerformanceRow,
} from '@/types/analytics'

export const analyticsApi = {
  performance: () => api.get<ApiResponse<StudentPerformanceAnalytics>>('/student/performance'),

  examSummaries: () => api.get<ApiResponse<ExamAnalyticsSummary[]>>('/analytics/exams'),
  examDetail: (examId: string) => api.get<ApiResponse<ExamAnalyticsDetail>>(`/analytics/exams/${examId}`),
  examStudents: (examId: string) =>
    api.get<ApiResponse<StudentPerformanceRow[]>>(`/analytics/exams/${examId}/students`),
  examOptionLabels: (examId: string) =>
    api.get<ApiResponse<OptionLabel[]>>(`/analytics/exams/${examId}/options`),
  studentDrilldown: (studentId: string) =>
    api.get<ApiResponse<StudentDrilldownAnalytics>>(`/analytics/students/${studentId}`),

  studentSummary: () => api.get<ApiResponse<StudentAnalyticsSummary>>('/student/analytics/summary'),
  studentExams: () => api.get<ApiResponse<StudentExamAnalytics[]>>('/student/analytics/exams'),
}