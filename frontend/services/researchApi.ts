import { api } from './api'
import type { ApiResponse } from '@/types'
import type {
  AnalysisReport,
  ConditionCatalogEntry,
  ControlledConditions,
  FailureReport,
  FailureSamplesPage,
  ResearchCondition,
  ResearchConditionKey,
  ResearchEvaluation,
  ResearchExperiment,
  ResearchRun,
  ResearchRunDetail,
  ResearchRunSample,
  ResearchRunEvaluation,
  ResearchRunSampleDetail,
  ResearchSample,
  ResearchScenario,
  ScenarioInstruction,
  RunSampleReview,
  RunSampleReviews,
} from '@/types/research'

export const researchApi = {
  listExperiments: () =>
    api.get<ApiResponse<ResearchExperiment[]>>('/proctor/research/experiments'),
  createExperiment: (body: { name: string; description?: string; examId?: string }) =>
    api.post<ApiResponse<ResearchExperiment>>('/proctor/research/experiments', body),
  updateExperimentStatus: (experimentId: string, status: 'ACTIVE' | 'COMPLETED' | 'ARCHIVED') =>
    api.post<ApiResponse<ResearchExperiment>>(`/proctor/research/experiments/${experimentId}/status`, { status }),
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
  listRuns: (experimentId: string) =>
    api.get<ApiResponse<ResearchRun[]>>(`/proctor/research/experiments/${experimentId}/runs`),
  createRun: (experimentId: string, body: { runCode: string; datasetVersion?: string; notes?: string }) =>
    api.post<ApiResponse<ResearchRun>>(`/proctor/research/experiments/${experimentId}/runs`, body),
  getRun: (runId: string) =>
    api.get<ApiResponse<ResearchRunDetail>>(`/proctor/research/runs/${runId}`),
  evaluateRun: (runId: string) =>
    api.get<ApiResponse<ResearchRunEvaluation>>(`/proctor/research/runs/${runId}/evaluation`),
  startRun: (runId: string) =>
    api.post<ApiResponse<ResearchRun>>(`/proctor/research/runs/${runId}/start`),
  completeRun: (runId: string) =>
    api.post<ApiResponse<ResearchRun>>(`/proctor/research/runs/${runId}/complete`),
  cancelRun: (runId: string) =>
    api.post<ApiResponse<ResearchRun>>(`/proctor/research/runs/${runId}/cancel`),
  createRunSample: (runId: string, body: {
    attemptId: string
    scenario: ResearchScenario
    conditions: Partial<ControlledConditions>
  }) =>
    api.post<ApiResponse<ResearchRunSample>>(`/proctor/research/runs/${runId}/samples`, body),
  observeRunSample: (runId: string, sampleId: string) =>
    api.post<ApiResponse<ResearchRunSample>>(`/proctor/research/runs/${runId}/samples/${sampleId}/observe`),
  captureRunSample: (runId: string, sampleId: string, body: {
    startedAt?: string
    endedAt: string
    measuredLatencyMs?: number
    rawMediaBytes?: number
    signalBytes?: number
  }) =>
    api.post<ApiResponse<ResearchRunSample>>(`/proctor/research/runs/${runId}/samples/${sampleId}/capture`, body),
  getRunSampleDetail: (runSampleId: string) =>
    api.get<ApiResponse<ResearchRunSampleDetail>>(`/proctor/research/run-samples/${runSampleId}/detail`),
  getRunSampleReviews: (runSampleId: string) =>
    api.get<ApiResponse<RunSampleReviews>>(`/proctor/research/run-samples/${runSampleId}/reviews`),
  scenarioInstructions: () =>
    api.get<ApiResponse<ScenarioInstruction[]>>('/proctor/research/scenarios'),
  conditionCatalog: () =>
    api.get<ApiResponse<ConditionCatalogEntry[]>>('/proctor/research/conditions'),
  getExperimentAnalysis: (experimentId: string, runId?: string) =>
    api.get<ApiResponse<AnalysisReport>>(`/proctor/research/experiments/${experimentId}/analysis`, {
      params: runId ? { runId } : undefined,
    }),
  getFailureAnalysis: (experimentId: string, runId?: string) =>
    api.get<ApiResponse<FailureReport>>(`/proctor/research/experiments/${experimentId}/failure-analysis`, {
      params: runId ? { runId } : undefined,
    }),
  getFailureSamples: (experimentId: string, params: { runId?: string; page?: number; pageSize?: number }) =>
    api.get<ApiResponse<FailureSamplesPage>>(
      `/proctor/research/experiments/${experimentId}/failure-analysis/samples`,
      { params },
    ),
}