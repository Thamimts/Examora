'use client'
import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Check, RotateCcw, X } from 'lucide-react'
import { Card, Header } from '@/components/shared'
import { useToast } from '@/components/feedback'
import { usePracticeStore } from '@/store/practiceStore'
import { adaptiveApi } from '@/services/adaptiveApi'
import type { PracticeAnswerResponse, PracticeSummary, PracticeSession } from '@/types/adaptive'

function isForeignPracticeSessionError(error: unknown) {
  if (!error || typeof error !== 'object') return false
  const response = (error as { response?: { status?: number; data?: { message?: unknown } } }).response
  return response?.status === 403 && response.data?.message === 'This session belongs to another student.'
}

export function PracticeSession() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const queryClient = useQueryClient()
  const session = usePracticeStore((state) => state.sessions[id])
  const submitting = usePracticeStore((state) => state.submitting)
  const setSession = usePracticeStore((state) => state.setSession)
  const applyAnswered = usePracticeStore((state) => state.applyAnswered)
  const setSubmitting = usePracticeStore((state) => state.setSubmitting)
  const resetSession = usePracticeStore((state) => state.reset)

  const [selected, setSelected] = useState('')
  const [feedback, setFeedback] = useState<PracticeAnswerResponse | null>(null)

  const sessionQuery = useQuery({
    queryKey: ['practice-session', id],
    queryFn: async () => (await adaptiveApi.resumeOrCreate(id)).data.data,
    enabled: Boolean(id),
    retry: 1,
  })

  const working = sessionQuery.data ?? session

  useEffect(() => {
    if (sessionQuery.data) setSession(id, sessionQuery.data)
  }, [sessionQuery.data, id, setSession])

  const submitAnswer = useMutation({
    mutationFn: ({ questionId, optionId }: { questionId: string; optionId: string }) =>
      adaptiveApi.answer(working!.id, questionId, optionId),
    onMutate: () => setSubmitting(true),
    onSuccess: (response) => {
      const result = response.data.data
      applyAnswered(id, result)
      setFeedback(result)
      setSelected('')
      const refreshed = usePracticeStore.getState().sessions[id]
      if (refreshed) queryClient.setQueryData<PracticeSession>(['practice-session', id], refreshed)
    },
    onError: (error: unknown) => {
      if (isForeignPracticeSessionError(error)) {
        setSelected('')
        setFeedback(null)
        resetSession(id)
        sessionQuery.refetch()
        toast.error('Your session no longer matches your account. Select an answer and submit again.')
      } else {
        toast.error('Unable to submit this answer.')
      }
    },
    onSettled: () => setSubmitting(false),
  })

  const restart = useMutation({
    mutationFn: () => adaptiveApi.startNew(id),
    onMutate: () => setSubmitting(true),
    onSuccess: (response) => {
      setFeedback(null)
      setSelected('')
      setSession(id, response.data.data)
      queryClient.setQueryData<PracticeSession>(['practice-session', id], response.data.data)
      toast.success('A new practice session has started.')
    },
    onError: () => toast.error('Unable to start a new session.'),
    onSettled: () => setSubmitting(false),
  })

  if (sessionQuery.isPending) {
    return (
      <>
        <Header title="Adaptive practice" description="Questions adjust to your skill as you answer." />
        <Card className="mx-auto max-w-3xl" >
          <div className="h-4 w-1/3 animate-pulse rounded-full bg-muted" />
          <div className="h-64 animate-pulse rounded-xl bg-muted" />
        </Card>
      </>
    )
  }

  if (sessionQuery.isError || !working) {
    return (
      <>
        <Header title="Adaptive practice" description="Questions adjust to your skill as you answer." />
        <Card className="mx-auto max-w-3xl" >
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-destructive">Unable to load this practice session.</p>
            <button className="rounded-lg border border-border px-3 py-2 text-sm" onClick={() => sessionQuery.refetch()}>Retry</button>
          </div>
        </Card>
      </>
    )
  }

  if (working.status === 'COMPLETED') {
    return <SummaryLayout session={working} onPracticeAgain={() => restart.mutate()} onBack={() => navigate('/student/exams')} busy={restart.isPending} />
  }

  if (working.status === 'EXPIRED' || !working.currentQuestion) {
    return (
      <>
        <Header title="Adaptive practice" description="Questions adjust to your skill as you answer." />
        <Card className="mx-auto max-w-3xl">
          <p className="text-sm text-muted-foreground">Your previous session is no longer active. Start a new practice session to continue.</p>
          <button disabled={restart.isPending || submitting} className="mt-6 flex items-center gap-2 rounded-xl bg-primary px-4 py-2.5 text-sm text-primary-foreground disabled:opacity-60" onClick={() => restart.mutate()}>
            <RotateCcw size={16} /> {restart.isPending ? 'Starting…' : 'Start new session'}
          </button>
        </Card>
      </>
    )
  }

  const question = working.currentQuestion
  const canSubmit = Boolean(selected) && !submitting && !feedback

  return (
    <>
      <Header title="Adaptive practice" description="Practice from the selected exam; difficulty adjusts as you answer." />
      <Card className="mx-auto max-w-3xl">
        <div className="flex items-center justify-between gap-3 text-sm text-muted-foreground">
          <span>Question {working.answeredCount + 1} of {working.targetQuestionCount}</span>
          <span className="rounded-full bg-primary/10 px-3 py-1 font-medium text-primary">Level {working.level}</span>
        </div>
        <div className="mt-3 h-2 rounded-full bg-muted">
          <div className="h-2 rounded-full bg-primary transition-[width] duration-300" style={{ width: `${Math.min((working.answeredCount / working.targetQuestionCount) * 100, 100)}%` }} />
        </div>
        <p className="mt-6 text-xs font-semibold uppercase tracking-widest text-primary">Difficulty {question.difficulty} / 5</p>
        <h2 className="mt-3 text-xl font-semibold">{question.text}</h2>
        <div className="mt-6 grid gap-3">
          {question.options.map((option) => (
            <button
              key={option.id}
              type="button"
              aria-pressed={selected === option.id}
              disabled={Boolean(feedback) || submitting}
              onClick={() => setSelected(option.id)}
              className={`rounded-xl border p-4 text-left text-sm transition-[background-color,border-color,box-shadow,transform] duration-150 active:scale-[0.99] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60 ${selected === option.id ? 'border-primary bg-primary/10 shadow-sm' : 'border-border'}`}
            >
              {option.text}
            </button>
          ))}
        </div>
        {feedback && <AnswerFeedback response={feedback} />}
        <div className="mt-8 flex flex-wrap items-center justify-between gap-3">
          <button className="flex items-center gap-2 rounded-xl border border-border px-4 py-2 text-sm" onClick={() => navigate('/student/exams')}>
            <ArrowLeft size={16} /> Back to exams
          </button>
          {feedback ? (
            <button className="rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground" onClick={() => setFeedback(null)}>
              {feedback.completed ? 'View results' : 'Next question'}
            </button>
          ) : (
            <button disabled={!canSubmit} className="rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-60" onClick={() => submitAnswer.mutate({ questionId: question.id, optionId: selected })}>
              {submitting ? 'Checking…' : 'Submit answer'}
            </button>
          )}
        </div>
      </Card>
    </>
  )
}

function AnswerFeedback({ response }: { response: PracticeAnswerResponse }) {
  return (
    <div className={`mt-6 rounded-xl border p-4 text-sm ${response.correct ? 'border-emerald-500/30 bg-emerald-500/5' : 'border-destructive/30 bg-destructive/5'}`}>
      <p className={`flex items-center gap-2 font-semibold ${response.correct ? 'text-emerald-600' : 'text-destructive'}`}>
        {response.correct ? <Check size={18} /> : <X size={18} />}
        {response.correct ? 'Correct!' : 'Not quite.'}
      </p>
      {!response.correct && response.correctOptionText && (
        <p className="mt-2 text-muted-foreground">The correct answer was <b>{response.correctOptionText}</b>.</p>
      )}
      <p className="mt-2 text-muted-foreground">
        {response.answeredCount} of {response.completed ? 'target reached' : 'answered so far, level ' + response.level}
      </p>
    </div>
  )
}

function SummaryLayout({ session, onPracticeAgain, onBack, busy }: { session: PracticeSession; onPracticeAgain: () => void; onBack: () => void; busy: boolean }) {
  const summary: PracticeSummary | null = session.summary
  const accuracy = summary?.accuracy ?? (session.answeredCount ? Math.round((session.correctCount / session.answeredCount) * 100) : 0)
  return (
    <>
      <Header title="Practice session complete" description="Here is how you performed in this adaptive session." />
      <Card className="mx-auto max-w-3xl">
        <div className="text-center">
          <div className="mx-auto grid size-16 place-items-center rounded-full bg-emerald-500/10 text-emerald-600"><Check size={30} /></div>
          <p className="mt-5 text-5xl font-semibold">{accuracy}%</p>
          <p className="mt-2 text-sm text-muted-foreground">{session.examTitle} · {session.correctCount} of {session.answeredCount} correct</p>
        </div>
        <div className="mt-8 grid grid-cols-3 gap-3 text-center">
          <div className="rounded-xl bg-muted p-3"><b>{session.correctCount}</b><p className="text-xs text-muted-foreground">Correct</p></div>
          <div className="rounded-xl bg-muted p-3"><b>{Math.max(session.answeredCount - session.correctCount, 0)}</b><p className="text-xs text-muted-foreground">Incorrect</p></div>
          <div className="rounded-xl bg-muted p-3"><b>{session.level}</b><p className="text-xs text-muted-foreground">Final level</p></div>
        </div>
        {summary?.perDifficulty.length ? (
          <div className="mt-8">
            <h2 className="font-semibold">Accuracy by difficulty</h2>
            <div className="mt-4 space-y-4">
              {summary.perDifficulty.map((stat) => (
                <div key={stat.difficulty}>
                  <div className="mb-1 flex justify-between text-sm"><span>Level {stat.difficulty}</span><span>{stat.accuracy}% · {stat.correct}/{stat.answered}</span></div>
                  <div className="h-2 rounded-full bg-muted"><div className="h-2 rounded-full bg-primary" style={{ width: `${Math.min(stat.accuracy, 100)}%` }} /></div>
                </div>
              ))}
            </div>
          </div>
        ) : null}
        {(summary?.weakAreas.length || summary?.strongAreas.length) ? (
          <div className="mt-8 grid gap-4 sm:grid-cols-2">
            {summary!.strongAreas.length ? (
              <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/5 p-4">
                <p className="text-xs font-semibold uppercase tracking-wide text-emerald-600">Strong areas</p>
                <div className="mt-2 flex flex-wrap gap-2">{summary!.strongAreas.map(area => <span key={area} className="rounded-full bg-emerald-500/10 px-3 py-1 text-xs text-emerald-700">{area}</span>)}</div>
              </div>
            ) : null}
            {summary!.weakAreas.length ? (
              <div className="rounded-xl border border-amber-500/30 bg-amber-500/5 p-4">
                <p className="text-xs font-semibold uppercase tracking-wide text-amber-600">Weak areas</p>
                <div className="mt-2 flex flex-wrap gap-2">{summary!.weakAreas.map(area => <span key={area} className="rounded-full bg-amber-500/10 px-3 py-1 text-xs text-amber-700">{area}</span>)}</div>
              </div>
            ) : null}
          </div>
        ) : null}
        {summary?.subjectFocus ? <p className="mt-8 rounded-xl bg-muted p-4 text-sm text-muted-foreground">{summary.subjectFocus}</p> : null}
        <div className="mt-8 flex flex-wrap items-center justify-between gap-3">
          <button className="flex items-center gap-2 rounded-xl border border-border px-4 py-2 text-sm" onClick={onBack}><ArrowLeft size={16} /> Back to exams</button>
          <button disabled={busy} className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-60" onClick={onPracticeAgain}><RotateCcw size={16} /> {busy ? 'Starting…' : 'Practice again'}</button>
        </div>
      </Card>
    </>
  )
}