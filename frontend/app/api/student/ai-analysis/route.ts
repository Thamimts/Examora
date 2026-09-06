import { generateObject } from 'ai'
import { z } from 'zod'

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

type CachedAnalysis = { fingerprint: string; analysis: StudentAiAnalysis }
const cache = new Map<string, CachedAnalysis>()

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

export async function GET(request: Request) {
  const source = await getAnalytics(request)
  if ('response' in source) return source.response
  if (source.analytics.completedExamCount === 0) return Response.json({ error: 'Complete an exam to generate analysis.' }, { status: 422 })
  const fingerprint = JSON.stringify(source.analytics)
  const cached = cache.get(source.authorization)
  if (cached?.fingerprint === fingerprint) return Response.json(cached.analysis)
  try {
    const { object } = await generateObject({
      model: 'openai/o4-mini',
      schema: studentAiAnalysisSchema,
      prompt: `Use only the supplied analytics. Never invent scores, counts, subjects, topics, or statistics. Do not repeat numeric claims unless they are present in the input. Produce concise, actionable guidance. If topicPerformance is empty, say that topic-level data is unavailable. Analytics: ${fingerprint}`,
    })
    cache.set(source.authorization, { fingerprint, analysis: object })
    return Response.json(object)
  } catch {
    return Response.json({ error: 'AI analysis is temporarily unavailable. Please retry.' }, { status: 503 })
  }
}

export async function POST(request: Request) {
  cache.delete(request.headers.get('authorization') ?? '')
  return GET(request)
}

export const dynamic = 'force-dynamic'
