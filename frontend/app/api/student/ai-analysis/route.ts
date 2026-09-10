import { APICallError, AISDKError, JSONParseError, NoObjectGeneratedError, NoSuchModelError, TypeValidationError, generateObject } from 'ai'
import { z } from 'zod'
import { createBoundedCache, createStudentCacheKey } from '@/lib/ai-analysis-cache'
import type { StudentPerformanceAnalytics } from '@/types/analytics'

const analyticsSchema = z.object({
  completedExamCount: z.number().int().nonnegative(),
  averageScore: z.number().min(0).max(100),
  highestScore: z.number().min(0).max(100).nullable(),
  lowestScore: z.number().min(0).max(100).nullable(),
  accuracy: z.number().min(0).max(100),
  recentScoreTrend: z.array(z.object({ resultId: z.string(), examTitle: z.string(), date: z.string(), score: z.number().min(0).max(100) })),
  subjectPerformance: z.array(z.object({ subject: z.string(), completedExamCount: z.number().int().nonnegative(), averageScore: z.number().min(0).max(100), accuracy: z.number().min(0).max(100) })),
  topicPerformance: z.array(z.object({ topic: z.string(), completedExamCount: z.number().int().nonnegative(), averageScore: z.number().min(0).max(100), accuracy: z.number().min(0).max(100) })),
})

export const studentAiAnalysisSchema = z.object({
  summary: z.string(),
  strengths: z.array(z.string()).max(5),
  weaknesses: z.array(z.string()).max(5),
  recommendations: z.array(z.string()).max(5),
  priorityTopics: z.array(z.string()).max(5),
  studyPriorities: z.array(z.object({ topic: z.string(), reason: z.string(), priority: z.enum(['HIGH', 'MEDIUM', 'LOW']) })).max(5),
})

export type StudentAiAnalysis = z.infer<typeof studentAiAnalysisSchema>

const cache = createBoundedCache<StudentAiAnalysis>({ maxEntries: 200, ttlMs: 30 * 60 * 1000 })

function backendUrl() {
  return process.env.EXAMORA_API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080/api'
}

async function getAnalytics(request: Request) {
  const authorization = request.headers.get('authorization')
  if (!authorization) return { response: Response.json({ error: 'Authentication is required.' }, { status: 401 }) }
  const response = await fetch(`${backendUrl()}/student/performance`, { headers: { Authorization: authorization, Accept: 'application/json' }, cache: 'no-store' })
  if (!response.ok) return { response: Response.json({ error: response.status === 404 ? 'Performance analytics are not available.' : 'Unable to load performance analytics.' }, { status: response.status === 401 ? 401 : 502 }) }
  const body = await response.json()
  const parsed = analyticsSchema.safeParse(body?.data ?? body)
  if (!parsed.success) return { response: Response.json({ error: 'Performance analytics returned an invalid shape.' }, { status: 502 }) }
  return { analytics: parsed.data, authorization }
}

function buildPrompt(analytics: StudentPerformanceAnalytics): string {
  return 'You are generating a Student AI Performance Analysis for an online exam platform. Use ONLY the supplied analytics JSON. Never invent scores, counts, subjects, topics, exams, dates, or statistics that are not present in the input. Do not repeat a numeric claim unless it literally appears in the input. If topicPerformance is empty, state that topic-level data is not available. Produce concise, actionable guidance grounded in the data. Analytics: ' + JSON.stringify(analytics)
}

function aiErrorResponse(error: unknown): Response {
  if (!process.env.OPENAI_API_KEY) {
    return Response.json({ error: 'AI analysis is not configured. Set the OPENAI_API_KEY environment variable to enable it.' }, { status: 503 })
  }
  if (error instanceof APICallError) {
    const status = error.statusCode
    if (status === 401 || status === 403) {
      return Response.json({ error: 'The AI provider rejected the request. Check the configured API key.' }, { status: 503 })
    }
    if (typeof status === 'number' && status >= 429) {
      return Response.json({ error: 'The AI provider is rate limited or temporarily unavailable. Try again shortly.' }, { status: 503 })
    }
    return Response.json({ error: 'The AI provider could not complete the request.' }, { status: 503 })
  }
  if (error instanceof NoSuchModelError) {
    return Response.json({ error: 'The configured AI model is not available.' }, { status: 502 })
  }
  if (error instanceof NoObjectGeneratedError || error instanceof JSONParseError || error instanceof TypeValidationError || error instanceof AISDKError) {
    return Response.json({ error: 'The AI provider returned an unexpected response shape.' }, { status: 502 })
  }
  return Response.json({ error: 'AI analysis is temporarily unavailable. Please try again.' }, { status: 503 })
}

export async function GET(request: Request) {
  const source = await getAnalytics(request)
  if ('response' in source) return source.response
  if (source.analytics.completedExamCount === 0) {
    return Response.json({ error: 'Complete at least one exam to generate an analysis.' }, { status: 422 })
  }
  if (!process.env.OPENAI_API_KEY) {
    return Response.json({ error: 'AI analysis is not configured. Set the OPENAI_API_KEY environment variable to enable it.' }, { status: 503 })
  }
  const authorization = request.headers.get('authorization') ?? ''
  const key = createStudentCacheKey(authorization)
  const fingerprint = JSON.stringify(source.analytics)
  const cached = cache.get(key, fingerprint)
  if (cached) return Response.json(cached)
  try {
    const { object } = await generateObject({
      model: 'openai/o4-mini',
      schema: studentAiAnalysisSchema,
      prompt: buildPrompt(source.analytics),
    })
    cache.set(key, fingerprint, object)
    return Response.json(object)
  } catch (error) {
    return aiErrorResponse(error)
  }
}

export async function POST(request: Request) {
  cache.delete(createStudentCacheKey(request.headers.get('authorization') ?? ''))
  return GET(request)
}

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'