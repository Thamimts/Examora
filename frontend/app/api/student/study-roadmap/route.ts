import { generateObject } from 'ai'
import { z } from 'zod'
import { createBoundedCache, createStudentCacheKey } from '@/lib/ai-analysis-cache'
import { createRateLimiter } from '@/lib/ai-rate-limit'
import { AI_CACHE_MAX_ENTRIES, AI_CACHE_TTL_MS, aiErrorResponse, analyticsSchema, backendUrl, createAiProvider, hasAiKey } from '@/lib/ai-config'
import { mergeTopicProgress, normalizeCompletionCriteria, startDifficultyFor } from '@/lib/ai-roadmap'
import type { StudentPerformanceAnalytics } from '@/types/analytics'
import type { RoadmapPhase, RoadmapTopic, StudyRoadmap } from '@/types/ai'

const recentResultSchema = z.object({
  examId: z.string(),
  examTitle: z.string(),
  subject: z.string(),
  score: z.number(),
  total: z.number(),
  date: z.string(),
})

const questionReviewSchema = z.object({
  questionId: z.string(),
  questionText: z.string(),
  answered: z.boolean(),
  correct: z.boolean(),
})

const examReviewSchema = z.object({
  examTitle: z.string(),
  subject: z.string(),
  score: z.number(),
  total: z.number(),
  questions: z.array(questionReviewSchema),
})

const difficultyCompletionCriteriaSchema = z.object({
  minimumQuestions: z.number().int().min(1).max(20),
  minimumAccuracy: z.number().min(1).max(100),
})

const roadmapTopicSchema = z.object({
  name: z.string().min(1).max(60),
  reason: z.string().min(1).max(300),
  priority: z.enum(['HIGH', 'MEDIUM', 'LOW']),
  prerequisites: z.array(z.string().min(1).max(40)).max(5),
  recommendedDifficulty: z.enum(['EASY', 'MEDIUM', 'HARD']),
  estimatedStudyTime: z.string().regex(/^\d+\s*(minutes?|min|hours?|hrs?)$/i).max(20),
  recommendedPracticeCount: z.number().int().min(1).max(20),
  completionCriteria: z.object({
    easy: difficultyCompletionCriteriaSchema,
    medium: difficultyCompletionCriteriaSchema,
    hard: difficultyCompletionCriteriaSchema,
  }),
})

const roadmapPhaseSchema = z.object({
  order: z.number().int().min(1).max(6),
  title: z.string().min(1).max(80),
  objective: z.string().min(1).max(200),
  topics: z.array(roadmapTopicSchema).min(1).max(6),
})

const studyRoadmapSchema = z.object({
  title: z.string().min(1).max(80),
  summary: z.string().min(1).max(300),
  estimatedDuration: z.string().regex(/^\d+\s*(days?|weeks?|months?)$/i).max(20),
  phases: z.array(roadmapPhaseSchema).min(1).max(6),
}).refine((roadmap) => {
  const orders = roadmap.phases.map(phase => phase.order)
  return orders.every((order, index) => order === index + 1)
}, { message: 'Phase order must be sequential starting at 1.' })

const cache = createBoundedCache<StudyRoadmap>({ maxEntries: AI_CACHE_MAX_ENTRIES, ttlMs: AI_CACHE_TTL_MS })
const rateLimiter = createRateLimiter({ maxRequests: 5, windowMs: 10 * 60 * 1000 })
const model = createAiProvider()

const MAX_SNAPSHOT_EXAMS = 2
const MAX_SNAPSHOT_QUESTIONS = 8
const MAX_QUESTION_TEXT_LENGTH = 160

type RoadmapSnapshot = {
  examTitle: string
  subject: string
  score: number
  total: number
  questions: { questionText: string; answered: boolean; correct: boolean }[]
}

async function getContext(request: Request) {
  const authorization = request.headers.get('authorization')
  if (!authorization) return { response: Response.json({ error: 'Authentication is required.' }, { status: 401 }) }

  const performanceResponse = await fetch(`${backendUrl()}/student/performance`, { headers: { Authorization: authorization, Accept: 'application/json' }, cache: 'no-store' })
  if (!performanceResponse.ok) return { response: Response.json({ error: 'Unable to load performance analytics.' }, { status: performanceResponse.status === 401 ? 401 : 502 }) }
  const performanceBody = await performanceResponse.json()
  const parsedPerformance = analyticsSchema.safeParse(performanceBody?.data ?? performanceBody)
  if (!parsedPerformance.success) return { response: Response.json({ error: 'Performance analytics returned an invalid shape.' }, { status: 502 }) }
  const analytics = parsedPerformance.data

  const snapshotExams: RoadmapSnapshot[] = []
  if (analytics.completedExamCount > 0) {
    const resultsResponse = await fetch(`${backendUrl()}/results/me`, { headers: { Authorization: authorization, Accept: 'application/json' }, cache: 'no-store' })
    if (resultsResponse.ok) {
      const resultsBody = await resultsResponse.json()
      const parsedResults = z.array(recentResultSchema).safeParse(resultsBody?.data ?? resultsBody)
      if (parsedResults.success) {
        const recentResults = parsedResults.data
          .filter(result => result.examId && result.examTitle)
          .sort((a, b) => b.date.localeCompare(a.date))
          .slice(0, MAX_SNAPSHOT_EXAMS)
        for (const result of recentResults) {
          try {
            const reviewResponse = await fetch(`${backendUrl()}/exams/${encodeURIComponent(result.examId)}/result`, { headers: { Authorization: authorization, Accept: 'application/json' }, cache: 'no-store' })
            if (!reviewResponse.ok) continue
            const reviewBody = await reviewResponse.json()
            const parsedReview = examReviewSchema.safeParse(reviewBody?.data ?? reviewBody)
            if (!parsedReview.success) continue
            snapshotExams.push({
              examTitle: parsedReview.data.examTitle,
              subject: parsedReview.data.subject,
              score: parsedReview.data.score,
              total: parsedReview.data.total,
              questions: parsedReview.data.questions.slice(0, MAX_SNAPSHOT_QUESTIONS).map(question => ({
                questionText: question.questionText.length > MAX_QUESTION_TEXT_LENGTH ? question.questionText.slice(0, MAX_QUESTION_TEXT_LENGTH) + '…' : question.questionText,
                answered: question.answered,
                correct: question.correct,
              })),
            })
          } catch {
            // A single unfetchable review must not block the whole roadmap.
          }
        }
      }
    }
  }

  return {
    authorization,
    context: {
      analytics: stripAnalyticsForPrompt(analytics),
      recentQuestionSnapshots: snapshotExams,
    },
  }
}

function stripAnalyticsForPrompt(analytics: StudentPerformanceAnalytics) {
  return {
    completedExamCount: analytics.completedExamCount,
    averageScore: analytics.averageScore,
    highestScore: analytics.highestScore,
    lowestScore: analytics.lowestScore,
    accuracy: analytics.accuracy,
    recentScoreTrend: analytics.recentScoreTrend.map(point => ({ examTitle: point.examTitle, date: point.date, score: point.score })),
    subjectPerformance: analytics.subjectPerformance.map(subject => ({ subject: subject.subject, completedExamCount: subject.completedExamCount, averageScore: subject.averageScore, accuracy: subject.accuracy })),
    topicPerformance: analytics.topicPerformance,
  }
}

function buildPrompt(context: { analytics: ReturnType<typeof stripAnalyticsForPrompt>; recentQuestionSnapshots: RoadmapSnapshot[] }): string {
  return 'You are a PERSONALIZED STUDY COACH for an online exam platform. Your only task is to produce a structured study roadmap from the supplied student data. Use ONLY the supplied JSON. Never invent scores, percentages, attempt counts, question counts, topics, exams, dates, or statistics that are not present in the input. Do not repeat a numeric claim unless it literally appears in the input. topicPerformance is a measured statistic and is empty when the platform does not track formal topics. When you name a topic such as "Binary Search" or "Arrays", it is an AI-inferred learning concept derived from question text or exam titles, NOT a measured statistic — never claim an accuracy percentage for an inferred topic. Prerequisites are also AI-inferred convenience groupings. Sequence phases from foundational to advanced so the student learns prerequisites before dependent topics. Pick a recommendedDifficulty consistent with the supplied scores and accuracy. estimatedStudyTime and estimatedDuration are AI estimates. The student must be able to act on the roadmap immediately. For every topic provide completionCriteria with three levels EASY, MEDIUM and HARD; set minimumQuestions equal to the recommendedPracticeCount for that topic and minimumAccuracy to 80 for every level. Data: ' + JSON.stringify(context)
}

type RawRoadmap=Omit<StudyRoadmap,'phases'> & { phases:Array<Omit<RoadmapPhase,'topics'> & { topics:Array<Omit<RoadmapTopic,'progress'|'startDifficulty'>> }> }
function normalizeRoadmap(roadmap: RawRoadmap): StudyRoadmap {
  return {
    ...roadmap,
    phases: roadmap.phases.map(phase => ({
              ...phase,
              topics: phase.topics.map(topic => ({ ...topic, progress: null, startDifficulty: startDifficultyFor(null), completionCriteria: normalizeCompletionCriteria(topic) })),
    })),
  }
}

export async function GET(request: Request) {
  const source = await getContext(request)
  if ('response' in source) return source.response
  if (source.context.analytics.completedExamCount === 0) {
    return Response.json({ error: 'Complete at least one exam to generate a study roadmap.' }, { status: 422 })
  }
  if (!hasAiKey()) {
    return Response.json({ error: 'The study roadmap is not configured. Set the GROQ_API_KEY environment variable to enable it.' }, { status: 503 })
  }
  const authorization = source.authorization
  const key = createStudentCacheKey(authorization)
  const fingerprint = JSON.stringify(source.context)
  const cached = cache.get(key, fingerprint)
  if (cached) {
    const merged = await mergeTopicProgress(authorization, cached)
    return Response.json(merged)
  }

  const limited = rateLimiter.allow(key)
  if (!limited.ok) {
    return Response.json({ error: 'Roadmap generation is rate limited. Try again in a few minutes.' }, { status: 429, headers: { 'Retry-After': String(limited.retryAfterSeconds) } })
  }

  try {
    const { object } = await generateObject({
      model,
      schema: studyRoadmapSchema,
      prompt: buildPrompt(source.context),
    })
    const normalized = normalizeRoadmap(object)
    cache.set(key, fingerprint, normalized)
    const merged = await mergeTopicProgress(authorization, normalized)
    return Response.json(merged)
  } catch (error) {
    return aiErrorResponse(error, 'The study roadmap')
  }
}

export async function POST(request: Request) {
  cache.delete(createStudentCacheKey(request.headers.get('authorization') ?? ''))
  return GET(request)
}

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'