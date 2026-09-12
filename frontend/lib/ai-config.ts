import { createGroq } from '@ai-sdk/groq'
import { APICallError, AISDKError, JSONParseError, NoObjectGeneratedError, NoSuchModelError, TypeValidationError } from 'ai'
import { z } from 'zod'

export const AI_MODEL = 'openai/gpt-oss-20b'
export const AI_CACHE_MAX_ENTRIES = 200
export const AI_CACHE_TTL_MS = 30 * 60 * 1000

export const analyticsSchema = z.object({
  completedExamCount: z.number().int().nonnegative(),
  averageScore: z.number().min(0).max(100),
  highestScore: z.number().min(0).max(100).nullable(),
  lowestScore: z.number().min(0).max(100).nullable(),
  accuracy: z.number().min(0).max(100),
  recentScoreTrend: z.array(z.object({ resultId: z.string(), examTitle: z.string(), date: z.string(), score: z.number().min(0).max(100) })),
  subjectPerformance: z.array(z.object({ subject: z.string(), completedExamCount: z.number().int().nonnegative(), averageScore: z.number().min(0).max(100), accuracy: z.number().min(0).max(100) })),
  topicPerformance: z.array(z.object({ topic: z.string(), completedExamCount: z.number().int().nonnegative(), averageScore: z.number().min(0).max(100), accuracy: z.number().min(0).max(100) })),
})

export function hasAiKey(): boolean {
  return Boolean(process.env.GROQ_API_KEY || process.env.OPENAI_API_KEY)
}

export function backendUrl(): string {
  return process.env.EXAMORA_API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080/api'
}

export function createAiProvider() {
  return createGroq({ apiKey: process.env.GROQ_API_KEY ?? process.env.OPENAI_API_KEY })(AI_MODEL)
}

export function aiErrorResponse(error: unknown, feature: string): Response {
  if (!hasAiKey()) {
    return Response.json({ error: `${feature} is not configured. Set the GROQ_API_KEY environment variable to enable it.` }, { status: 503 })
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
  return Response.json({ error: `${feature} is temporarily unavailable. Please try again.` }, { status: 503 })
}