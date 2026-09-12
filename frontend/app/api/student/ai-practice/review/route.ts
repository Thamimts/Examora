import { generateObject } from 'ai'
import { z } from 'zod'
import { createStudentCacheKey } from '@/lib/ai-analysis-cache'
import { createRateLimiter } from '@/lib/ai-rate-limit'
import { aiErrorResponse, backendUrl, createAiProvider, hasAiKey } from '@/lib/ai-config'
import type { AIPracticeReview } from '@/types/ai'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const reviewRequestSchema = z.object({
  sessionId: z.string().min(1).max(100),
})

const reviewQuestionInputSchema = z.object({
  id: z.string(),
  question: z.string(),
  yourAnswer: z.string().nullable(),
  correctAnswer: z.string(),
  correct: z.boolean(),
  answered: z.boolean(),
})

const reviewInputSchema = z.object({
  sessionId: z.string(),
  topic: z.string(),
  difficulty: z.enum(['EASY', 'MEDIUM', 'HARD']),
  questions: z.array(reviewQuestionInputSchema).min(1).max(20),
})

const aiExplainationSchema = z.object({
  questionId: z.string(),
  explanation: z.string().min(5).max(2000),
})

const aiReviewSchema = z.object({
  explanations: z.array(aiExplainationSchema).min(1).max(20),
})

const rateLimiter = createRateLimiter({ maxRequests: 5, windowMs: 10 * 60 * 1000 })
const model = createAiProvider()

function buildReviewPrompt(review: z.infer<typeof reviewInputSchema>): string {
  return `You are a personalized study coach. Below is a completed AI practice featuring the student's answer, the correct answer and whether they answered correctly. For EVERY one of the ${review.questions.length} questions, write a short teaching explanation (2-4 sentences) for the question "question". Explain why the correct answer is correct and, when the student answered, why their answer ("yourAnswer") was right or wrong. Never change the supplied correctness or answer data, and never invent metrics.

Question data: ${JSON.stringify(review.questions)}

Return ONLY JSON: {"explanations":[{"questionId":"<id>","explanation":"..."}]} using the exact id values from the supplied data.`
}

export async function POST(request: Request) {
  const authorization = request.headers.get('authorization')
  if (!authorization) {
    return Response.json({ error: 'Authentication is required.' }, { status: 401 })
  }

  let payload: z.infer<typeof reviewRequestSchema>
  try {
    const parsed = reviewRequestSchema.safeParse(await request.json())
    if (!parsed.success) {
      return Response.json({ error: 'A session id is required.' }, { status: 400 })
    }
    payload = parsed.data
  } catch {
    return Response.json({ error: 'A JSON body is required.' }, { status: 400 })
  }

  const reviewResponse = await fetch(`${backendUrl()}/student/ai-practice/sessions/${encodeURIComponent(payload.sessionId)}/review`, {
    headers: { Authorization: authorization, Accept: 'application/json' },
    cache: 'no-store',
  })
  const reviewBody = (await reviewResponse.json().catch(() => null)) as { error?: string; data?: unknown } | null
  if (!reviewResponse.ok) {
    return Response.json({ error: reviewBody?.error ?? 'This practice is not available for an AI review yet.' }, { status: reviewResponse.status >= 400 && reviewResponse.status <= 599 ? reviewResponse.status : 502 })
  }
  const parsedReview = reviewInputSchema.safeParse(reviewBody?.data ?? reviewBody)
  if (!parsedReview.success) {
    return Response.json({ error: 'The completed practice returned an invalid shape.' }, { status: 502 })
  }

  if (!hasAiKey()) {
    return Response.json({ error: 'AI review is not configured. Set the GROQ_API_KEY environment variable to enable it.' }, { status: 503 })
  }

  const key = createStudentCacheKey(authorization)
  const limited = rateLimiter.allow(key)
  if (!limited.ok) {
    return Response.json({ error: 'AI review generation is rate limited. Try again in a few minutes.' }, { status: 429, headers: { 'Retry-After': String(limited.retryAfterSeconds) } })
  }

  try {
    const { object } = await generateObject({
      model,
      schema: aiReviewSchema,
      prompt: buildReviewPrompt({ sessionId: parsedReview.data.sessionId, topic: parsedReview.data.topic, difficulty: parsedReview.data.difficulty, questions: parsedReview.data.questions }),
    })

    const explanationsResponse = await fetch(`${backendUrl()}/student/ai-practice/sessions/${encodeURIComponent(payload.sessionId)}/explanations`, {
      method: 'POST',
      headers: { Authorization: authorization, 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ explanations: object.explanations }),
      cache: 'no-store',
    })
    if (!explanationsResponse.ok) {
      return Response.json({ error: 'Your practice was graded, but the AI explanations could not be saved.' }, { status: 502 })
    }

    const finalResponse = await fetch(`${backendUrl()}/student/ai-practice/sessions/${encodeURIComponent(payload.sessionId)}/review`, {
      headers: { Authorization: authorization, Accept: 'application/json' },
      cache: 'no-store',
    })
    const finalBody = (await finalResponse.json().catch(() => null)) as { data?: AIPracticeReview } | null
    if (!finalResponse.ok || !finalBody?.data) {
      return Response.json({ error: 'Your practice was graded, but the full review could not be loaded.' }, { status: 502 })
    }
    return Response.json({ success: true, data: finalBody.data })
  } catch (error) {
    return aiErrorResponse(error, 'The AI review')
  }
}