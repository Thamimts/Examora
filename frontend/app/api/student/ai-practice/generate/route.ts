import { generateObject } from 'ai'
import { z } from 'zod'
import { createStudentCacheKey } from '@/lib/ai-analysis-cache'
import { createRateLimiter } from '@/lib/ai-rate-limit'
import { aiErrorResponse, backendUrl, createAiProvider, hasAiKey } from '@/lib/ai-config'
import { DEFAULT_MIN_ACCURACY, fetchRoadmapProgress, startDifficultyFor } from '@/lib/ai-roadmap'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const generateRequestSchema = z.object({
  topic: z.string().min(1).max(100),
  difficulty: z.enum(['EASY', 'MEDIUM', 'HARD']),
  questionCount: z.number().int().min(1).max(20),
  minimumQuestions: z.number().int().min(1).max(20).optional(),
  minimumAccuracy: z.number().min(1).max(100).optional(),
})

const aiPracticeQuestionSchema = z.object({
  question: z.string().min(5).max(1000),
  options: z.array(z.string().min(1).max(300)).length(4),
  correctOption: z.string().min(1).max(300),
  explanation: z.string().min(10).max(2000),
}).superRefine((data, ctx) => {
  if (new Set(data.options).size !== 4) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'Options must be unique.', path: ['options'] })
  }
  if (!data.options.includes(data.correctOption)) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'The correct option must match one of the options.', path: ['correctOption'] })
  }
})

const practiceGenerationSchema = z.object({
  questions: z.array(aiPracticeQuestionSchema).min(1).max(20),
})

const rateLimiter = createRateLimiter({ maxRequests: 5, windowMs: 10 * 60 * 1000 })
const model = createAiProvider()

function buildGeneratePrompt(topic: string, difficulty: string, questionCount: number): string {
  return `You generate short multiple-choice practice questions for an online exam platform. Generate exactly ${questionCount} self-contained multiple-choice questions about the topic "${topic}" at ${difficulty} difficulty.

Requirements for every question:
- Question text must be clear, standalone and answerable from general knowledge about the topic.
- Exactly 4 options, each a concise answer choice. All four options must be distinct.
- correctOption must be exactly one of the 4 options and MUST be the definitively correct answer.
- Provide a short factual explanation (2-4 sentences) that teaches why the correct option is correct.
- Questions must cover a spread of sub-areas within the topic; do not repeat a question or its answer.

Return ONLY the JSON object: {"questions":[{"question":"...","options":["A","B","C","D"],"correctOption":"...","explanation":"..."}]}`
}

function studentKey(authorization: string): string {
  return createStudentCacheKey(authorization)
}

async function generateQuestions(topic: string, difficulty: string, questionCount: number) {
  let lastError: unknown
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const { object } = await generateObject({
        model,
        schema: practiceGenerationSchema,
        prompt: buildGeneratePrompt(topic, difficulty, questionCount),
      })
      const questions = object.questions.slice(0, questionCount)
      if (questions.length === questionCount) return questions
      lastError = new Error(`Expected ${questionCount} questions but the model returned ${questions.length}.`)
    } catch (error) {
      lastError = error
    }
  }
  throw lastError
}

export async function POST(request: Request) {
  const authorization = request.headers.get('authorization')
  if (!authorization) {
    return Response.json({ error: 'Authentication is required.' }, { status: 401 })
  }

  let payload: z.infer<typeof generateRequestSchema>
  try {
    const parsed = generateRequestSchema.safeParse(await request.json())
    if (!parsed.success) {
      return Response.json({ error: 'Provide a topic, a difficulty (EASY, MEDIUM or HARD) and a question count between 1 and 20.' }, { status: 400 })
    }
    payload = parsed.data
  } catch {
    return Response.json({ error: 'A JSON body is required.' }, { status: 400 })
  }

  if (!hasAiKey()) {
    return Response.json({ error: 'AI practice is not configured. Set the GROQ_API_KEY environment variable to enable it.' }, { status: 503 })
  }

  const key = studentKey(authorization)
  const limited = rateLimiter.allow(key)
  if (!limited.ok) {
    return Response.json({ error: 'Practice generation is rate limited. Try again in a few minutes.' }, { status: 429, headers: { 'Retry-After': String(limited.retryAfterSeconds) } })
  }

  try {
    const questions = await generateQuestions(payload.topic, payload.difficulty, payload.questionCount)
    const sessionBody: Record<string, unknown> = { topic: payload.topic, difficulty: payload.difficulty, questions }
    if (payload.minimumQuestions !== undefined) sessionBody.minimumQuestions = payload.minimumQuestions
    if (payload.minimumAccuracy !== undefined) sessionBody.minimumAccuracy = payload.minimumAccuracy
    const createResponse = await fetch(`${backendUrl()}/student/ai-practice/sessions`, {
      method: 'POST',
      headers: { Authorization: authorization, 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(sessionBody),
      cache: 'no-store',
    })
    const body = await createResponse.json().catch(() => null)
    if (!createResponse.ok) {
      return Response.json({ error: body?.error ?? 'Unable to create the practice session.' }, { status: createResponse.status >= 400 && createResponse.status <= 599 ? createResponse.status : 502 })
    }
    return Response.json(body, { status: 201 })
  } catch (error) {
    return aiErrorResponse(error, 'AI practice generation')
  }
}