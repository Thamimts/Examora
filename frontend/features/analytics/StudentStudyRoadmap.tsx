'use client'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AlarmClock, AlertCircle, Check, ChevronRight, Flag, ListChecks, Lock, Map as MapIcon, Medal, NotebookPen, Play, RefreshCw, Sparkles, Target } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/store/authStore'
import { Card } from '@/components/shared'
import { aiPracticeApi } from '@/services/aiPracticeApi'
import type { AITopicProgress, AIPracticeSessionSummary, RoadmapDifficulty, RoadmapPriority, RoadmapTopic, StudyRoadmap } from '@/types/ai'

type RoadmapError = Error & { status?: number }

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message ? error.message : 'Unable to load your study roadmap.'
}

function errorDetail(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return 'Unable to start this practice. Please try again.'
}

const priorityStyles: Record<RoadmapPriority, string> = {
  HIGH: 'bg-red-500/10 text-red-700',
  MEDIUM: 'bg-amber-500/10 text-amber-700',
  LOW: 'bg-emerald-500/10 text-emerald-700',
}

const statusStyles: Record<string, string> = {
  NOT_STARTED: 'bg-muted text-muted-foreground',
  IN_PROGRESS: 'bg-primary/10 text-primary',
  READY_FOR_NEXT: 'bg-amber-500/10 text-amber-700',
  MASTERED: 'bg-emerald-500/10 text-emerald-700',
}

function ProgressStatusBadge({ progress }: { progress: AITopicProgress | null }) {
  const status = progress?.status ?? 'NOT_STARTED'
  return <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${statusStyles[status] ?? statusStyles.NOT_STARTED}`}>{status.replace(/_/g, ' ')}</span>
}

function PriorityBadge({ priority }: { priority: RoadmapPriority }) {
  return <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${priorityStyles[priority]}`}>{priority}</span>
}

const ladderOrder: RoadmapDifficulty[] = ['EASY', 'MEDIUM', 'HARD']

type LadderState = 'completed' | 'available' | 'locked'

function ladderState(progress: AITopicProgress | null, level: RoadmapDifficulty): LadderState {
  if (!progress) return level === 'EASY' ? 'available' : 'locked'
  const levelProgress = progress[level.toLowerCase() as 'easy' | 'medium' | 'hard']
  if (progress.status === 'MASTERED' || (levelProgress?.completed ?? 0) > 0) return 'completed'
  if (progress.unlockedDifficulty === level) return 'available'
  return 'locked'
}

function TopicLadder({ progress }: { progress: AITopicProgress | null }) {
  return (
    <div className="grid gap-1.5">
      {ladderOrder.map((level, index) => {
        const state = ladderState(progress, level)
        const levelProgress = progress?.[level.toLowerCase() as 'easy' | 'medium' | 'hard'] ?? null
        const accuracy = levelProgress?.bestAccuracy
        if (state === 'completed') {
          return (
            <p key={level} className="flex flex-wrap items-center gap-2 text-sm">
              <Check size={15} className="shrink-0 text-emerald-600" />
              <span className="font-medium">{level} completed</span>
              {accuracy !== null && accuracy !== undefined && (
                <span className="text-xs text-muted-foreground">best {accuracy}%</span>
              )}
            </p>
          )
        }
        if (state === 'available') {
          return (
            <p key={level} className="flex flex-wrap items-center gap-2 text-sm font-medium text-primary">
              <Play size={15} className="shrink-0 fill-current" />
              <span>{level} available</span>
              <span className="text-xs font-normal text-muted-foreground">{index === 0 ? 'start here' : 'practice now'}</span>
            </p>
          )
        }
        return (
          <p key={level} className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <Lock size={15} className="shrink-0" />
            <span>{level} locked</span>
            <span className="text-xs">Complete {ladderOrder[index - 1]} first.</span>
          </p>
        )
      })}
    </div>
  )
}

export function StudentStudyRoadmap() {
  const token = useAuthStore((state) => state.token)
  const navigate = useNavigate()
  const [regenerating, setRegenerating] = useState(false)
  const [regenerateError, setRegenerateError] = useState<string | null>(null)
  const [startingTopic, setStartingTopic] = useState<string | null>(null)
  const [startError, setStartError] = useState<string | null>(null)

  const historyQuery = useQuery({
    queryKey: ['student-ai-practice-history'],
    enabled: Boolean(token),
    queryFn: async () => (await aiPracticeApi.listSessions()).data.data,
    retry: 1,
  })

  const startPractice = async (topic: RoadmapTopic) => {
    const difficulty = topic.startDifficulty
    if (!token || startingTopic || !difficulty) return
    setStartingTopic(topic.name)
    setStartError(null)
    try {
      const criteria = topic.completionCriteria?.[difficulty.toLowerCase() as 'easy' | 'medium' | 'hard']
      const session = await aiPracticeApi.generate(
        topic.name,
        difficulty,
        topic.recommendedPracticeCount,
        token,
        criteria?.minimumQuestions,
        criteria?.minimumAccuracy,
      )
      await historyQuery.refetch()
      navigate(`/student/ai-practice/${session.sessionId}`)
    } catch (error) {
      setStartError(errorDetail(error))
    } finally {
      setStartingTopic(null)
    }
  }

  const query = useQuery({
    queryKey: ['student-study-roadmap'],
    enabled: Boolean(token),
    queryFn: async () => {
      const response = await fetch('/api/student/study-roadmap', {
        headers: { Authorization: `Bearer ${token}` },
        cache: 'no-store',
      })
      const body = await response.json().catch(() => null)
      if (!response.ok) {
        const error: RoadmapError = new Error(body?.error ?? 'Unable to load your study roadmap')
        error.status = response.status
        throw error
      }
      return body as StudyRoadmap
    },
    retry: 1,
  })

  const status = (query.error as { status?: number } | undefined)?.status
  const hasNoExams = status === 422

  const regenerate = async () => {
    if (!token || regenerating) return
    setRegenerating(true)
    setRegenerateError(null)
    try {
      const response = await fetch('/api/student/study-roadmap', {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
        cache: 'no-store',
      })
      const body = await response.json().catch(() => null)
      if (!response.ok) {
        setRegenerateError(body?.error ?? 'Unable to regenerate the study roadmap.')
        return
      }
      await query.refetch()
    } catch {
      setRegenerateError('Unable to reach the study roadmap service. Please try again.')
    } finally {
      setRegenerating(false)
    }
  }

  return (
    <>
      {query.isPending && (
        <div className="grid gap-5" aria-busy="true" aria-live="polite">
          <Card>
            <div className="h-6 w-56 animate-pulse rounded-xl bg-muted" />
            <div className="mt-3 h-4 w-full animate-pulse rounded-xl bg-muted" />
            <div className="mt-2 h-4 w-2/3 animate-pulse rounded-xl bg-muted" />
          </Card>
          {[1, 2, 3].map(item => (
            <Card key={item}>
              <div className="flex items-center gap-3">
                <div className="grid size-10 animate-pulse place-items-center rounded-xl bg-muted" />
                <div className="flex-1">
                  <div className="h-4 w-48 animate-pulse rounded-xl bg-muted" />
                  <div className="mt-2 h-3 w-32 animate-pulse rounded-xl bg-muted" />
                </div>
              </div>
              <div className="mt-4 grid gap-3 sm:grid-cols-2">
                {[1, 2].map(child => (
                  <div key={child} className="h-24 animate-pulse rounded-xl bg-muted" />
                ))}
              </div>
            </Card>
          ))}
        </div>
      )}
      {query.isError && hasNoExams && (
        <Card className="max-w-2xl">
          <div className="flex items-center gap-3">
            <div className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary">
              <MapIcon size={20} />
            </div>
            <div>
              <p className="font-medium">No study roadmap yet</p>
              <p className="mt-1 text-sm text-muted-foreground">
                Complete at least one exam, then come back here for a personalized step-by-step study plan.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => navigate('/student/exams')}
            className="mt-5 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"
          >
            Browse exams
          </button>
        </Card>
      )}
      {query.isError && !hasNoExams && (
        <Card className="border-destructive/30">
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3 text-destructive">
              <AlertCircle size={20} />
              <div>
                <p className="font-medium">The study roadmap is temporarily unavailable</p>
                <p className="mt-1 text-sm text-muted-foreground">{errorMessage(query.error)}</p>
              </div>
            </div>
            <button
              type="button"
              onClick={() => query.refetch()}
              className="rounded-xl bg-primary px-4 py-2 text-sm text-primary-foreground"
            >
              Retry
            </button>
          </div>
        </Card>
      )}
      {query.data && (
        <>
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-muted-foreground">Your roadmap is an AI recommendation built from your submitted results.</p>
            <button
              type="button"
              disabled={regenerating}
              onClick={regenerate}
              className="flex items-center gap-2 rounded-xl border border-border px-4 py-2 text-sm hover:bg-muted disabled:opacity-60"
            >
              <RefreshCw size={15} className={regenerating ? 'animate-spin' : ''} />
              {regenerating ? 'Regenerating...' : 'Regenerate roadmap'}
            </button>
          </div>
          {regenerateError && (
            <p role="alert" className="mb-4 text-sm text-destructive">
              {regenerateError}
            </p>
          )}
          {startError && (
            <p role="alert" className="mb-4 text-sm text-destructive">
              {startError}
            </p>
          )}
          <Card className="mb-6">
            <div className="flex items-start gap-3">
              <div className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary">
                <Sparkles size={20} />
              </div>
              <div>
                <h2 className="text-lg font-semibold">{query.data.title}</h2>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">{query.data.summary}</p>
                <p className="mt-3 inline-flex items-center gap-2 rounded-full bg-muted px-3 py-1 text-xs font-medium text-muted-foreground">
                  <AlarmClock size={13} /> Estimated duration: {query.data.estimatedDuration}
                </p>
              </div>
            </div>
          </Card>
          <PracticeHistory sessions={historyQuery.data} />
          <div className="grid gap-6">
            {query.data.phases.map(phase => (
              <div key={phase.order}>
                <div className="mb-3 flex items-center gap-3">
                  <div className="grid size-9 shrink-0 place-items-center rounded-xl bg-primary/10 font-semibold text-primary">
                    {phase.order}
                  </div>
                  <div>
                    <h2 className="font-semibold">{phase.title}</h2>
                    <p className="text-sm text-muted-foreground">{phase.objective}</p>
                  </div>
                </div>
                <div className="grid gap-4 lg:grid-cols-2">
                  {phase.topics.map(topic => (
                    <Card key={topic.name} className="flex flex-col">
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <h3 className="font-semibold">{topic.name}</h3>
                        <div className="flex flex-wrap items-center gap-2">
                          <PriorityBadge priority={topic.priority} />
                          <ProgressStatusBadge progress={topic.progress} />
                        </div>
                      </div>
                      <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
                        <span className="inline-flex items-center gap-1">
                          <Target size={13} /> {topic.recommendedPracticeCount} practice questions
                        </span>
                        <span className="inline-flex items-center gap-1">
                          <AlarmClock size={13} /> {topic.estimatedStudyTime}
                        </span>
                      </div>
                      <div className="mt-4 grid gap-3 text-sm">
                        <div className="flex items-start gap-2">
                          <Flag size={15} className="mt-0.5 shrink-0 text-primary" />
                          <p className="leading-5 text-muted-foreground">{topic.reason}</p>
                        </div>
                        {topic.prerequisites.length > 0 && (
                          <div className="flex items-start gap-2">
                            <ListChecks size={15} className="mt-0.5 shrink-0 text-primary" />
                            <div className="flex flex-wrap items-center gap-1.5">
                              {topic.prerequisites.map(prerequisite => (
                                <span key={prerequisite} className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                                  {prerequisite}
                                </span>
                              ))}
                            </div>
                            <span className="text-xs text-muted-foreground">prerequisites</span>
                          </div>
                        )}
                        <div className="flex items-start gap-2">
                          <Target size={15} className="mt-0.5 shrink-0 text-primary" />
                          <div className="min-w-0 flex-1">
                            <TopicLadder progress={topic.progress} />
                          </div>
                        </div>
                      </div>
                      <div className="mt-4 flex flex-wrap items-center justify-between gap-2 border-t border-border pt-4">
                        <p className="text-xs text-muted-foreground">
                          {topic.progress?.status === 'MASTERED'
                            ? 'Every difficulty level completed.'
                            : `${topic.recommendedPracticeCount} questions at ${topic.startDifficulty ?? topic.recommendedDifficulty} difficulty`}
                        </p>
                        {topic.progress?.status === 'MASTERED' ? (
                          <span className="inline-flex items-center gap-2 rounded-xl bg-emerald-500/10 px-4 py-2 text-sm font-medium text-emerald-700">
                            <Medal size={16} /> Mastered
                          </span>
                        ) : (
                          <button
                            type="button"
                            disabled={!topic.startDifficulty || Boolean(startingTopic)}
                            onClick={() => startPractice(topic)}
                            className="flex shrink-0 items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-60"
                          >
                            <NotebookPen size={15} className={startingTopic === topic.name ? 'animate-pulse' : ''} />
                            {startingTopic === topic.name ? 'Creating…' : `Start ${topic.startDifficulty} practice`}
                          </button>
                        )}
                      </div>
                    </Card>
                  ))}
                </div>
              </div>
            ))}
          </div>
          <p className="mt-6 flex items-center gap-2 text-sm text-muted-foreground">
            <ChevronRight size={15} />
            Start practice to get AI-generated questions at your current unlocked difficulty, then return here to track your progression.
          </p>
        </>
      )}
    </>
  )
}

function PracticeHistory({ sessions }: { sessions: AIPracticeSessionSummary[] | undefined }) {
  const navigate = useNavigate()
  const completed = (sessions ?? []).slice(0, 5)
  if (completed.length === 0) return null
  return (
    <Card className="mb-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <NotebookPen size={16} className="text-primary" />
          <h2 className="font-semibold">Recent AI practice</h2>
        </div>
        <button type="button" onClick={() => navigate('/student/ai-analysis?tab=roadmap')} className="text-sm text-primary hover:underline">
          Go to AI coach
        </button>
      </div>
      <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {completed.map(session => (
          <button
            key={session.sessionId}
            type="button"
            onClick={() => navigate(`/student/ai-practice/${session.sessionId}`)}
            className="rounded-xl border border-border p-4 text-left hover:bg-muted"
          >
            <div className="flex items-center justify-between gap-2">
              <p className="truncate text-sm font-medium">{session.topic}</p>
              <DifficultyBadgeSmall difficulty={session.difficulty} />
            </div>
            <p className="mt-2 text-xs text-muted-foreground">
              {session.status === 'COMPLETED'
                ? `Score: ${session.percentage ?? 0}%`
                : `${session.answeredCount} of ${session.questionCount} answered`}
            </p>
          </button>
        ))}
      </div>
    </Card>
  )
}

function DifficultyBadgeSmall({ difficulty }: { difficulty: RoadmapDifficulty }) {
  const styles: Record<RoadmapDifficulty, string> = {
    EASY: 'bg-emerald-500/10 text-emerald-700',
    MEDIUM: 'bg-amber-500/10 text-amber-700',
    HARD: 'bg-red-500/10 text-red-700',
  }
  return <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold ${styles[difficulty]}`}>{difficulty}</span>
}