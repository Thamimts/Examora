import { generateObject } from 'ai'
import { z } from 'zod'
import { createBoundedCache, createStudentCacheKey } from '@/lib/ai-analysis-cache'
import { AI_CACHE_MAX_ENTRIES, AI_CACHE_TTL_MS, aiErrorResponse, analyticsSchema, backendUrl, createAiProvider, hasAiKey } from '@/lib/ai-config'
import type { StudentAiAnalysis } from '@/types/ai'
import type { StudentPerformanceAnalytics } from '@/types/analytics'

const studentAiAnalysisSchema = z.object({
  summary: z.string(),
  strengths: z.array(z.string()).max(5),
  weaknesses: z.array(z.string()).max(5),
  recommendations: z.array(z.string()).max(5),
  priorityTopics: z.array(z.string()).max(5),
  studyPriorities: z.array(z.object({ topic: z.string(), reason: z.string(), priority: z.enum(['HIGH', 'MEDIUM', 'LOW']) })).max(5),
})

const cache = createBoundedCache<StudentAiAnalysis>({ maxEntries: AI_CACHE_MAX_ENTRIES, ttlMs: AI_CACHE_TTL_MS })
const model = createAiProvider()

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

export async function GET(request: Request) {
  const source = await getAnalytics(request)
  if ('response' in source) return source.response
  if (source.analytics.completedExamCount === 0) {
    return Response.json({ error: 'Complete at least one exam to generate an analysis.' }, { status: 422 })
  }
  if (!hasAiKey()) {
    return Response.json({ error: 'AI analysis is not configured. Set the GROQ_API_KEY environment variable to enable it.' }, { status: 503 })
  }
  const authorization = request.headers.get('authorization') ?? ''
  const key = createStudentCacheKey(authorization)
  const fingerprint = JSON.stringify(source.analytics)
  const cached = cache.get(key, fingerprint)
  if (cached) return Response.json(cached)
  try {
    const { object } = await generateObject({
      model,
      schema: studentAiAnalysisSchema,
      prompt: buildPrompt(source.analytics),
    })
    cache.set(key, fingerprint, object)
    return Response.json(object)
  } catch (error) {
    return aiErrorResponse(error, 'AI analysis')
  }
}

export async function POST(request: Request) {
  cache.delete(createStudentCacheKey(request.headers.get('authorization') ?? ''))
  return GET(request)
}

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'