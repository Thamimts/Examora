import { generateObject } from 'ai'
import { z } from 'zod'

const requestSchema = z.object({
  studentId: z.string().min(1),
  results: z.array(z.object({
    examTitle: z.string(),
    subject: z.string(),
    score: z.number(),
    total: z.number().positive(),
    date: z.string(),
  })).max(100),
})

const analysisSchema = z.object({
  readiness: z.number().min(0).max(100),
  strongestArea: z.string(),
  nextFocus: z.string(),
  recommendations: z.array(z.string()).min(1).max(5),
  strengths: z.array(z.string()).max(5),
  weakAreas: z.array(z.string()).max(5),
  performanceTrend: z.array(z.object({ label: z.string(), value: z.number().min(0).max(100) })).max(12),
  topicPerformance: z.array(z.object({ topic: z.string(), score: z.number().min(0).max(100), questions: z.number().int().nonnegative() })).max(20),
  difficultyAnalysis: z.array(z.object({ level: z.string(), score: z.number().min(0).max(100), attempts: z.number().int().nonnegative() })).max(10),
  recentExams: z.array(z.object({ exam: z.string(), score: z.number().min(0).max(100), date: z.string() })).max(10),
})

export async function POST(request: Request) {
  const parsed = requestSchema.safeParse(await request.json().catch(() => null))
  if (!parsed.success) return Response.json({ error: 'Invalid analytics input.' }, { status: 400 })
  if (parsed.data.results.length === 0) return Response.json({ error: 'Complete an exam to generate analysis.' }, { status: 422 })

  const { object } = await generateObject({
    model: 'openai/gpt-4o-mini',
    schema: analysisSchema,
    prompt: `Analyze this student's completed exam results. Only infer patterns supported by the data; do not invent topics or scores. Return concise, actionable study guidance. Results: ${JSON.stringify(parsed.data.results)}`,
  })
  return Response.json(object)
}
