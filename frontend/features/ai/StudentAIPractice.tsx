'use client'
import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { AlertCircle, ArrowLeft, Check, CheckCircle2, CircleX, Loader2, Medal, RefreshCw, Sparkles } from 'lucide-react'
import { Card, Header } from '@/components/shared'
import { useToast } from '@/components/feedback'
import { useAuthStore } from '@/store/authStore'
import { aiPracticeApi } from '@/services/aiPracticeApi'
import { AiTutorSection } from './AiTutorSection'
import type { AIPracticeReview, AIPracticeSession, RoadmapDifficulty } from '@/types/ai'

const difficultyStyles: Record<RoadmapDifficulty, string> = {
  EASY: 'bg-emerald-500/10 text-emerald-700',
  MEDIUM: 'bg-amber-500/10 text-amber-700',
  HARD: 'bg-red-500/10 text-red-700',
}

function DifficultyBadge({ difficulty }: { difficulty: RoadmapDifficulty }) {
  return <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${difficultyStyles[difficulty]}`}>{difficulty}</span>
}

function errorDetail(error: unknown): string {
  if (error && typeof error === 'object') {
    const maybe = error as { response?: { data?: { message?: string } }; message?: string }
    return (maybe.response?.data?.message ?? maybe.message) || 'Please try again.'
  }
  return 'Please try again.'
}

export function StudentAIPractice() {
  const { id = '' } = useParams()
  const token = useAuthStore((state) => state.token)
  const navigate = useNavigate()
  const toast = useToast()
  const queryClient = useQueryClient()

  const sessionQuery = useQuery({
    queryKey: ['ai-practice-session', id],
    queryFn: async () => (await aiPracticeApi.getSession(id)).data.data,
    enabled: Boolean(id),
    retry: 1,
  })

  const backToCoach = () => navigate('/student/ai-analysis?tab=roadmap')

  if (sessionQuery.isPending) {
    return (
      <>
        <Header title="AI practice" description="Answer AI-generated questions one at a time." />
        <Card className="mx-auto max-w-3xl" aria-busy="true">
          <div className="h-5 w-2/3 animate-pulse rounded-full bg-muted" />
          <div className="mt-6 h-4 w-1/4 animate-pulse rounded-full bg-muted" />
          <div className="mt-8 h-64 animate-pulse rounded-xl bg-muted" />
        </Card>
      </>
    )
  }

  if (sessionQuery.isError || !sessionQuery.data) {
    const error = sessionQuery.error
    return (
      <>
        <Header title="AI practice" description="Answer AI-generated questions one at a time." />
        <Card className="mx-auto max-w-3xl">
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3 text-destructive">
              <AlertCircle size={20} />
              <div>
                <p className="font-medium">Unable to load this practice session.</p>
                <p className="mt-1 text-sm text-muted-foreground">{errorDetail(error)}</p>
              </div>
            </div>
            <button type="button" onClick={() => sessionQuery.refetch()} className="rounded-xl bg-primary px-4 py-2 text-sm text-primary-foreground">
              Retry
            </button>
          </div>
        </Card>
      </>
    )
  }

  const session = sessionQuery.data

  if (session.status === 'COMPLETED') {
    return <CompletedView session={session} onBack={backToCoach} token={token ?? ''} />
  }

  return <ActivePractice session={session} onBack={backToCoach} />
}

function ActivePractice({ session, onBack }: { session: AIPracticeSession; onBack: () => void }) {
  const token = useAuthStore((state) => state.token) ?? ''
  const toast = useToast()
  const queryClient = useQueryClient()
  const [currentIndex, setCurrentIndex] = useState(0)
  const [selected, setSelected] = useState<Record<string, string>>({})
  const [showConfirm, setShowConfirm] = useState(false)

  useEffect(() => {
    const initial: Record<string, string> = {}
    for (const question of session.questions) {
      if (question.selectedOptionId) initial[question.id] = question.selectedOptionId
    }
    setSelected(initial)
  }, [session])

  const answeredCount = Object.keys(selected).length
  const total = session.questions.length
  const current = session.questions[currentIndex] ?? session.questions[0]

  const answerMutation = useMutation({
    mutationFn: (optionId: string) => aiPracticeApi.answer(session.sessionId, current!.id, optionId),
    onSuccess: (_data, optionId) => {
      const questionId = current!.id
      setSelected(previous => ({ ...previous, [questionId]: optionId }))
      toast.success('Answer selected.')
    },
    onError: (error: unknown) => {
      const detail = errorDetail(error)
      if (detail.includes('already been answered')) {
        toast.error('This question has already been answered.')
      } else {
        toast.error('Unable to save your answer.')
      }
    },
  })

  const submitMutation = useMutation({
    mutationFn: () => aiPracticeApi.submit(session.sessionId),
    onSuccess: () => {
      setShowConfirm(false)
      queryClient.invalidateQueries({ queryKey: ['ai-practice-session', session.sessionId] })
      queryClient.invalidateQueries({ queryKey: ['ai-practice-review', session.sessionId] })
    },
    onError: (error: unknown) => {
      setShowConfirm(false)
      toast.error(errorDetail(error))
    },
  })

  const selectOption = (optionId: string) => {
    if (selected[current?.id]) return
    answerMutation.mutate(optionId)
  }

  const requestSubmit = () => {
    if (submitMutation.isPending) return
    const unanswered = total - answeredCount
    if (unanswered > 0) setShowConfirm(true)
    else submitMutation.mutate()
  }

  const goToQuestion = (index: number) => setCurrentIndex(Math.min(Math.max(index, 0), total - 1))

  return (
    <>
      <Header title="AI practice" description="Answer each question, then submit for a personalized review." />
      {showConfirm && (
        <div className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4" role="dialog" aria-modal="true">
          <Card className="w-full max-w-md">
            <h2 className="text-lg font-semibold">Submit your practice?</h2>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">
              You have answered {answeredCount} of {total} questions. {total - answeredCount > 0
                ? `${total - answeredCount} will be counted as unanswered.`
                : 'All questions are answered.'}
              <span className="mt-1 block">Once submitted you cannot change your answers.</span>
            </p>
            <div className="mt-5 flex justify-end gap-3">
              <button type="button" onClick={() => setShowConfirm(false)} className="rounded-xl border border-border px-4 py-2 text-sm hover:bg-muted">
                Keep practicing
              </button>
              <button
                type="button"
                onClick={() => submitMutation.mutate()}
                disabled={submitMutation.isPending}
                className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-60"
              >
                {submitMutation.isPending && <Loader2 size={15} className="animate-spin" />}
                Submit practice
              </button>
            </div>
          </Card>
        </div>
      )}

      <Card className="mx-auto max-w-3xl">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium">{session.topic}</span>
            <DifficultyBadge difficulty={session.difficulty} />
          </div>
          <p className="text-sm text-muted-foreground">Answered {answeredCount} of {total}</p>
        </div>

        <div className="mt-4 flex flex-wrap gap-2">
          {session.questions.map((question, index) => {
            const isAnswered = Boolean(selected[question.id])
            const isCurrent = index === currentIndex
            return (
              <button
                key={question.id}
                type="button"
                onClick={() => goToQuestion(index)}
                aria-label={`Question ${index + 1}${isAnswered ? ' (answered)' : ''}`}
                className={`grid size-9 place-items-center rounded-xl text-sm font-medium border ${
                  isCurrent ? 'border-primary bg-primary/10 text-primary' : isAnswered ? 'border-primary/40 bg-primary/5' : 'border-border'
                }`}
              >
                {index + 1}
              </button>
            )
          })}
        </div>

        {current && (
          <div className="mt-6">
            <h2 className="text-lg font-semibold leading-7">{current.question}</h2>
            <div className="mt-5 grid gap-3" role="radiogroup" aria-label="Answer options">
              {current.options.map(option => {
                const isSelected = selected[current.id] === option.id
                return (
                  <button
                    key={option.id}
                    type="button"
                    role="radio"
                    aria-checked={isSelected}
                    disabled={Boolean(selected[current.id])}
                    onClick={() => selectOption(option.id)}
                    className={`flex items-center gap-3 rounded-xl border p-4 text-left text-sm transition-colors ${
                      isSelected
                        ? 'border-primary bg-primary/5 font-medium text-primary'
                        : 'border-border hover:bg-muted disabled:cursor-default disabled:opacity-70'
                    }`}
                  >
                    <span className={`grid size-5 shrink-0 place-items-center rounded-full border ${isSelected ? 'border-primary bg-primary text-primary-foreground' : 'border-muted-foreground/40'}`}>
                      {isSelected && <Check size={12} />}
                    </span>
                    <span>{option.text}</span>
                  </button>
                )
              })}
            </div>
            {selected[current.id] && (
              <p className="mt-3 inline-flex items-center gap-2 rounded-full bg-muted px-3 py-1 text-xs font-medium text-muted-foreground">
                <CheckCircle2 size={13} /> Answer selected
              </p>
            )}
          </div>
        )}

        <div className="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-5">
          <button type="button" onClick={onBack} className="flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground">
            <ArrowLeft size={15} /> Back to AI coach
          </button>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => goToQuestion(currentIndex - 1)}
              disabled={currentIndex === 0}
              className="rounded-xl border border-border px-4 py-2 text-sm disabled:opacity-40"
            >
              Previous
            </button>
            <button
              type="button"
              onClick={() => goToQuestion(currentIndex + 1)}
              disabled={currentIndex === total - 1}
              className="rounded-xl border border-border px-4 py-2 text-sm disabled:opacity-40"
            >
              Next
            </button>
            <button
              type="button"
              onClick={requestSubmit}
              disabled={submitMutation.isPending}
              className="rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-60"
            >
              {submitMutation.isPending ? 'Grading your practice…' : 'Submit practice'}
            </button>
          </div>
        </div>
      </Card>
    </>
  )
}

function CompletedView({ session, onBack, token }: { session: AIPracticeSession; onBack: () => void; token: string }) {
  const reviewQuery = useQuery({
    queryKey: ['ai-practice-review', session.sessionId],
    queryFn: async () => (await aiPracticeApi.getReview(session.sessionId)).data.data,
    enabled: Boolean(session.sessionId),
    retry: 1,
  })

  return (
    <>
      <Header title="AI practice" description="Your result and personalized AI review." />
      <Card className="mx-auto max-w-3xl">
        {reviewQuery.isPending && (
          <div className="flex items-center gap-3 text-muted-foreground" aria-busy="true">
            <Loader2 size={18} className="animate-spin" />
            <p className="text-sm">Grading your practice…</p>
          </div>
        )}
        {reviewQuery.isError && (
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-destructive">Unable to load your graded result.</p>
            <button type="button" onClick={() => reviewQuery.refetch()} className="rounded-xl bg-primary px-4 py-2 text-sm text-primary-foreground">
              Retry
            </button>
          </div>
        )}
        {reviewQuery.data && (
          <>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-lg font-semibold">{reviewQuery.data.topic}</h2>
                  <DifficultyBadge difficulty={reviewQuery.data.difficulty} />
                </div>
                <p className="mt-1 text-sm text-muted-foreground">Practice complete</p>
              </div>
              <div className="text-right">
                <p className="text-3xl font-semibold tracking-tight">{reviewQuery.data.percentage}%</p>
                <p className="text-xs uppercase tracking-widest text-muted-foreground">score</p>
              </div>
            </div>
            <div className="mt-4 grid grid-cols-3 gap-3 text-center text-sm">
              <div className="rounded-xl bg-emerald-500/10 p-3 text-emerald-700">
                <p className="text-xl font-semibold">{reviewQuery.data.correctCount}</p>
                <p className="text-xs">Correct</p>
              </div>
              <div className="rounded-xl bg-red-500/10 p-3 text-red-700">
                <p className="text-xl font-semibold">{reviewQuery.data.incorrectCount}</p>
                <p className="text-xs">Incorrect</p>
              </div>
              <div className="rounded-xl bg-muted p-3 text-muted-foreground">
                <p className="text-xl font-semibold">{reviewQuery.data.unansweredCount}</p>
                <p className="text-xs">Unanswered</p>
              </div>
            </div>
            <ProgressionPanel review={reviewQuery.data} onBack={onBack} token={token} />
            <button type="button" onClick={onBack} className="mt-4 inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground">
              <ArrowLeft size={15} /> Back to AI coach
            </button>
            <div className="mt-6 border-t border-border pt-5">
              <AiReviewBody review={reviewQuery.data} sessionId={session.sessionId} token={token} />
            </div>
          </>
        )}
      </Card>
    </>
  )
}

function ProgressionPanel({ review, onBack, token }: { review: AIPracticeReview; onBack: () => void; token: string }) {
  const navigate = useNavigate()
  const [busy, setBusy] = useState<RoadmapDifficulty | null>(null)
  const [error, setError] = useState<string | null>(null)

  const mastered = review.topicStatus === 'MASTERED' || review.unlockedDifficulty === 'MASTERED'
  const canContinue =
    review.unlockedDifficulty === 'EASY' || review.unlockedDifficulty === 'MEDIUM' || review.unlockedDifficulty === 'HARD'
  const nextDifficulty: RoadmapDifficulty | 'MASTERED' | null = canContinue ? review.unlockedDifficulty : null

  const practice = async (difficulty: RoadmapDifficulty) => {
    if (busy) return
    setBusy(difficulty)
    setError(null)
try {
        const count = review.totalCount
        const session = await aiPracticeApi.generate(
          review.topic,
          difficulty,
          count,
          token,
          review.minimumQuestions ?? count,
          review.minimumAccuracy ?? 80,
        )
        navigate(`/student/ai-practice/${session.sessionId}`)
      } catch (err) {
      setError(errorDetail(err))
      setBusy(null)
    }
  }

  const handleNextPractice = () => {
    if (nextDifficulty && nextDifficulty !== 'MASTERED') practice(nextDifficulty)
  }

  return (
    <div
      role="status"
      className={`mt-4 flex flex-wrap items-center justify-between gap-3 rounded-xl p-4 ${
        mastered ? 'bg-emerald-500/10' : review.completionMet ? 'bg-primary/10' : 'bg-amber-500/10'
      }`}
    >
      <div className="flex items-center gap-3">
        {mastered ? (
          <Medal size={28} className="shrink-0 text-emerald-600" />
        ) : review.completionMet ? (
          <CheckCircle2 size={28} className="shrink-0 text-primary" />
        ) : (
          <AlertCircle size={28} className="shrink-0 text-amber-600" />
        )}
        <div>
          <p className="font-semibold">
            {mastered ? `🎉 ${review.topic} mastered!` : review.completionMet ? `${review.difficulty} requirement completed` : `${review.difficulty} requirement not met yet`}
          </p>
          <p className="text-sm text-muted-foreground">
            {mastered
              ? 'You have completed every difficulty level for this topic.'
              : review.completionMet
              ? `${nextDifficulty} is now unlocked.`
              : `Answer ${review.minimumQuestions ?? review.totalCount}+ questions with at least ${review.minimumAccuracy ?? 80}% accuracy.`}
          </p>
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {error && <p className="text-xs text-destructive">{error}</p>}
        {mastered ? (
          <button type="button" onClick={onBack} className="rounded-xl bg-emerald-600 px-4 py-2 text-sm font-medium text-white">
            Continue to next topic
          </button>
        ) : review.completionMet ? (
          nextDifficulty && (
            <button type="button" disabled={Boolean(busy)} onClick={handleNextPractice} className="rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-60">
              {busy === nextDifficulty ? 'Creating…' : `Practice ${nextDifficulty} now`}
            </button>
          )
        ) : (
          <button type="button" disabled={Boolean(busy)} onClick={() => practice(review.difficulty)} className="rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-60">
            {busy === review.difficulty ? 'Creating…' : `Practice ${review.difficulty} again`}
          </button>
        )}
      </div>
    </div>
  )
}

function AiReviewBody({ review, sessionId, token }: { review: AIPracticeReview; sessionId: string; token: string }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [attempted, setAttempted] = useState(review.reviewCompleted)
  const [failed, setFailed] = useState(false)

  const generate = useMutation({
    mutationFn: () => aiPracticeApi.generateReview(sessionId, token),
    onSuccess: data => {
      setFailed(false)
      setAttempted(true)
      queryClient.setQueryData<AIPracticeReview>(['ai-practice-review', sessionId], data)
    },
    onError: (error: unknown) => {
      setFailed(true)
      setAttempted(false)
    },
  })

  const autoTriggered = useRef(!review.reviewCompleted)
  useEffect(() => {
    if (!review.reviewCompleted && !autoTriggered.current) {
      autoTriggered.current = true
      generate.mutate()
    }
  }, [review.reviewCompleted, generate])

  if (!review.reviewCompleted && generate.isPending) {
    return (
      <div className="flex items-center gap-3 text-muted-foreground" aria-busy="true">
        <Loader2 size={18} className="animate-spin" />
        <p className="text-sm">Generating your AI review…</p>
      </div>
    )
  }

  if (failed && !attempted && !generate.isPending) {
    return (
      <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <CircleX size={20} className="text-destructive" />
          <div>
            <p className="font-medium">Your practice has been graded.</p>
            <p className="mt-1 text-sm text-muted-foreground">The AI explanations are temporarily unavailable.</p>
          </div>
        </div>
        <button
          type="button"
          onClick={() => generate.mutate()}
          className="flex items-center gap-2 rounded-xl border border-border px-4 py-2 text-sm hover:bg-muted"
        >
          <RefreshCw size={15} /> Retry AI review
        </button>
      </div>
    )
  }

  const hasExplanations = review.reviewCompleted
  return (
    <div>
      <div className="mb-4 flex items-center gap-2">
        <Sparkles size={16} className="text-primary" />
        <h3 className="font-semibold">AI review</h3>
      </div>
      {!hasExplanations && (
        <p className="mb-4 text-sm text-muted-foreground">
          The AI explanations for this session are not available yet. {generate.isPending ? 'Working…' : 'Try regenerating them.'}
        </p>
      )}
      <div className="grid gap-4">
        {review.questions.map(question => (
          <div key={question.id} className="rounded-xl border border-border p-4">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <p className="text-sm font-medium leading-6">{question.question}</p>
              <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold ${question.answered ? (question.correct ? 'bg-emerald-500/10 text-emerald-700' : 'bg-red-500/10 text-red-700') : 'bg-muted text-muted-foreground'}`}>
                {question.answered ? (question.correct ? 'Correct' : 'Incorrect') : 'Unanswered'}
              </span>
            </div>
            <div className="mt-3 grid gap-2 text-sm sm:grid-cols-2">
              <p className="text-muted-foreground">
                Your answer: <span className="font-medium text-foreground">{question.yourAnswer ?? '—'}</span>
              </p>
              <p className="text-muted-foreground">
                Correct answer: <span className="font-medium text-foreground">{question.correctAnswer}</span>
              </p>
            </div>
            {question.explanation && (
              <p className="mt-3 rounded-xl bg-primary/5 p-3 text-sm leading-6 text-muted-foreground" aria-label="AI explanation">
                <Sparkles size={13} className="mr-1.5 inline text-primary" />
                {question.explanation}
              </p>
            )}
            <AiTutorSection question={question} sessionId={sessionId} token={token} />
          </div>
        ))}
      </div>
    </div>
  )
}