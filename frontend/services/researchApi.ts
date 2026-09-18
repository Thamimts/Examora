import { api } from './api'
import type { ApiResponse } from '@/types'
import type { ResearchCondition, ResearchConditionKey, ResearchEvaluation, ResearchExperiment, ResearchScenario, ResearchSample } from '@/types/research'

export const researchApi = {
  listExperiments: () =>
    api.get<ApiResponse<ResearchExperiment[]>>('/proctor/research/experiments'),
  createExperiment: (body: { name: string; description?: string }) =>
    api.post<ApiResponse<ResearchExperiment>>('/proctor/research/experiments', body),
  createSample: (experimentId: string, body: {
    attemptId?: string
    windowStart: string
    windowEnd: string
    startedAt?: string
    endedAt?: string
    scenario?: ResearchScenario
    label?: string
    conditions?: ResearchCondition
    rawMediaBytes?: number
    signalBytes?: number
    measuredLatencyMs?: number
  }) =>
    api.post<ApiResponse<ResearchSample>>(`/proctor/research/experiments/${experimentId}/samples`, body),
  addReview: (sampleId: string, body: { label: string; confidence?: number; notes?: string }) =>
    api.post<ApiResponse<ResearchSample>>(`/proctor/research/samples/${sampleId}/review`, body),
  evaluate: (experimentId: string, condition?: ResearchConditionKey) =>
    api.get<ApiResponse<ResearchEvaluation>>(`/proctor/research/experiments/${experimentId}/evaluation`, {
      params: condition ? { condition } : undefined,
    }),
  runEvaluation: (experimentId: string) =>
    api.post<ApiResponse<ResearchEvaluation>>(`/proctor/research/experiments/${experimentId}/evaluate`),
}