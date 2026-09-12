import { generateObject } from 'ai'
import { z } from 'zod'
import { createStudentCacheKey } from '@/lib/ai-analysis-cache'
import { createRateLimiter } from '@/lib/ai-rate-limit'
import { aiErrorResponse, backendUrl, createAiProvider, hasAiKey } from '@/lib/ai-config'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const tutorRequestSchema = z.object({
  sessionId: z.string().min(1).max(100),
  questionId: z.string().min(1).max(100),
  action: z.enum(['EXPLAIN_WRONG', 'EXPLAIN_SIMPLE', 'GIVE_EXAMPLE', 'EXPLAIN_CONCEPT', 'SIMILAR_QUESTION', 'HARDER_QUESTION']),
})

const tutorContextSchema = z.object({
  questionId: z.string(),
  question: z.string(),
  topic: z.string(),
  difficulty: z.string(),
  options: z.array(z.object({ id: z.string(), text: z.string() })),
  yourAnswer: z.string().nullish(),
  correctAnswer: z.string(),
  correct: z.boolean(),
  answered: z.boolean(),
})

const tutorExplanationSchema = z.object({
  title: z.string().min(1).max(200),
  explanation: z.string().min(5).max(2000),
  keyPoint: z.string().min(1).max(500),
  misconception: z.string().max(500).nullable(),
})

const tutorQuestionSchema = z
  .object({
    question: z.string().min(5).max(1000),
    options: z.array(z.object({ text: z.string().min(1).max(300) })).length(4),
    difficulty: z.number().int().min(1).max(5),
    hint: z.string().min(1).max(500),
    correctAnswer: z.string().min(1).max(300),
  })
  .superRefine((data, ctx) => {
    const texts = data.options.map(option => option.text)
    if (new Set(texts).size !== 4) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'The options must be distinct.', path: ['options'] })
    }
    if (!texts.includes(data.correctAnswer)) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'The correct answer must match one of the options.', path: ['correctAnswer'] })
    }
  })

const rateLimiter = createRateLimiter({ maxRequests: 15, windowMs: 10 * 60 * 1000 })
const model = createAiProvider()

function promptData(context: z.infer<typeof tutorContextSchema>) {
  return {
    question: context.question,
    topic: context.topic,
    difficulty: context.difficulty,
    options: context.options.map(option => option.text),
    yourAnswer: context.yourAnswer,
    correctAnswer: context.correctAnswer,
    correct: context.correct,
    answered: context.answered,
  }
}

function buildExplanationPrompt(context: z.infer<typeof tutorContextSchema>, action: string): string {
  const data = JSON.stringify(promptData(context))
  switch (action) {
    case 'EXPLAIN_WRONG':
      return `You are a patient AI tutor helping a student learn from their mistake. The student answered this practice question INCORRECTLY. Using ONLY this server-verified data: ${data}.
Explain why their answer ("yourAnswer") is wrong, why "correctAnswer" is correct, and the most likely misconception that led them astray. Be encouraging, specific and concise (2-4 sentences of explanation). Do not invent facts not in the data.
Return ONLY JSON: {"title":"Short, specific title","explanation":"...","keyPoint":"One memorable key takeaway","misconception":"... | null"}.`
    case 'EXPLAIN_SIMPLE':
      return `You are a patient AI tutor. Explain the concept behind this practice question in the SIMPLEST possible terms, as if to a complete beginner. Using ONLY this server-verified data: ${data}.
Keep it short, friendly and beginner-level. Do not invent facts not in the data.
Return ONLY JSON: {"title":"Short, specific title","explanation":"...","keyPoint":"The single most important fact to remember","misconception":null}.`
    case 'GIVE_EXAMPLE':
      return `You are a patient AI tutor. Help a student understand the concept behind this practice question through one concrete, easy, everyday example. Using ONLY this server-verified data: ${data}.
Work through the example briefly so the student sees how the concept connects to the question. Do not invent facts that contradict the data.
Return ONLY JSON: {"title":"Short, specific title","explanation":"The example, explained","keyPoint":"What the example teaches","misconception":null}.`
    case 'EXPLAIN_CONCEPT':
    default:
      return `You are a patient AI tutor. Teach the underlying concept tested by this practice question, independently of the specific question text. Using ONLY this server-verified data: ${data}.
Introduce the concept, explain how it works and why it matters. 2-4 sentences. Do not invent facts not in the data.
Return ONLY JSON: {"title":"Short, specific title","explanation":"...","keyPoint":"One memorable key takeaway","misconception":"A common misconception students have about this concept, or null"}.`
  }
}

function buildQuestionPrompt(context: z.infer<typeof tutorContextSchema>, action: string): string {
  const data = JSON.stringify(promptData(context))
  const sameConcept = action === 'HARDER_QUESTION'
    ? 'Noticeably HARDER than the original, but on the SAME core concept'
    : 'Very SIMILAR to the original and on the SAME core concept'
  return `You are an AI tutor creating a follow-up question for a student. Generate one multiple-choice question that tests the same core concept as this practice question, and that is ${sameConcept}. Source (server-verified, answer key included so you target the right concept): ${data}.
Requirements:
- "question" must be self-contained and answerable purely from general knowledge.
- exactly 4 distinct options in "options".
- "correctAnswer" must be the EXACT text of one of the options (do not mark it in the output, just list it there).
- "difficulty" is 1-5 (5 = hardest).
- "hint" is a short, non-giving hint.
Return ONLY JSON: {"question":"...","options":[{"text":"..."},{"text":"..."},{"text":"..."},{"text":"..."}],"difficulty":1-5,"hint":"...","correctAnswer":"..."}`
}

function backendMessage(body: { message?: string } | null, fallback: string): string {
  return body?.message || fallback
}

export async function POST(request: Request) {
  const authorization = request.headers.get('authorization')
  if (!authorization) {
    return Response.json({ error: 'Authentication is required.' }, { status: 401 })
  }

  let payload: z.infer<typeof tutorRequestSchema>
  try {
    const parsed = tutorRequestSchema.safeParse(await request.json())
    if (!parsed.success || parsed.data.action === undefined) {
      return Response.json({ error: 'A session id, a question id and a valid tutor action are required.' }, { status: 400 })
    }
    payload = parsed.data
  } catch {
    return Response.json({ error: 'A JSON body is required.' }, { status: 400 })
  }

  if (!hasAiKey()) {
    return Response.json({ error: 'The AI tutor is not configured. Set the GROQ_API_KEY environment variable to enable it.' }, { status: 503 })
  }

  const key = createStudentCacheKey(authorization)
  const limited = rateLimiter.allow(key)
  if (!limited.ok) {
    return Response.json({ error: 'The AI tutor is rate limited. Try again in a few minutes.' }, { status: 429, headers: { 'Retry-After': String(limited.retryAfterSeconds) } })
  }

  const contextResponse = await fetch(`${backendUrl()}/student/ai-tutor`, {
    method: 'POST',
    headers: { Authorization: authorization, 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ sessionId: payload.sessionId, questionId: payload.questionId, action: payload.action }),
    cache: 'no-store',
  })
  const contextBody = (await contextResponse.json().catch(() => null)) as { message?: string; data?: unknown } | null
  if (!contextResponse.ok) {
    const status = contextResponse.status >= 400 && contextResponse.status <= 599 ? contextResponse.status : 502
    return Response.json({ error: backendMessage(contextBody, 'This practice is not available for the AI tutor yet.') }, { status })
  }
  const parsedContext = tutorContextSchema.safeParse(contextBody?.data ?? contextBody)
  if (!parsedContext.success) {
    return Response.json({ error: 'The practice returned data the AI tutor could not use.' }, { status: 502 })
  }

  try {
    if (payload.action === 'SIMILAR_QUESTION' || payload.action === 'HARDER_QUESTION') {
      const { object } = await generateObject({
        model,
        schema: tutorQuestionSchema,
        prompt: buildQuestionPrompt(parsedContext.data, payload.action),
      })

      const questionResponse = await fetch(`${backendUrl()}/student/ai-tutor/questions`, {
        method: 'POST',
        headers: { Authorization: authorization, 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({
          sessionId: payload.sessionId,
          sourceQuestionId: parsedContext.data.questionId,
          kind: payload.action,
          question: object.question,
          options: object.options.map(option => option.text),
          difficulty: object.difficulty,
          hint: object.hint,
          correctAnswer: object.correctAnswer,
        }),
        cache: 'no-store',
      })
      const questionBody = (await questionResponse.json().catch(() => null)) as { message?: string; data?: unknown } | null
      if (!questionResponse.ok) {
        const status = questionResponse.status >= 400 && questionResponse.status <= 599 ? questionResponse.status : 502
        return Response.json({ error: backendMessage(questionBody, 'Your tutor question could not be saved.') }, { status })
      }
      return Response.json({ success: true, data: questionBody?.data })
    }

    const { object } = await generateObject({
      model,
      schema: tutorExplanationSchema,
      prompt: buildExplanationPrompt(parsedContext.data, payload.action),
    })
    return Response.json({
      success: true,
      data: { title: object.title, explanation: object.explanation, keyPoint: object.keyPoint, misconception: object.misconception ?? null },
    })
  } catch (error) {
    return aiErrorResponse(error, 'The AI tutor')
  }
}