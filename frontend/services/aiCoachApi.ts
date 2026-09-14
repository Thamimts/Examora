import api from './api'
import type { ApiResponse } from '@/types'
import type { CoachAction, CoachExamSummary, ExamCoachContext } from '@/types/ai-coach'

export const aiCoachApi = {
  listExams: () => api.get<ApiResponse<CoachExamSummary[]>>('/student/ai-coach/exams'),
  context: (examId: string, action: CoachAction, questionId?: string) =>
    api.post<ApiResponse<ExamCoachContext>>(`/student/ai-coach/exams/${examId}`, {
      action,
      ...(questionId ? { questionId } : {}),
    }),
}