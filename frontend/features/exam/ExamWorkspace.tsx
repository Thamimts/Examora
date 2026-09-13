'use client'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery, useMutation } from '@tanstack/react-query'
import { AlertCircle, Check, ChevronLeft, ChevronRight, Flag, LayoutGrid, X } from 'lucide-react'
import { examApi } from '@/services/examApi'
import { questionApi } from '@/services/questionApi'
import { useExamStore } from '@/store/examStore'
import { useProctorSession } from '@/hooks/useProctorSession'
import { ExamStatusBar, type CountdownTier } from '@/features/exam/ExamStatusBar'
import { QuestionPalette } from '@/features/exam/QuestionPalette'
import { SubmissionReviewModal } from '@/features/exam/SubmissionReviewModal'

export function ExamWorkspace({ id }: { id: string }) {
  const navigate = useNavigate()
  const [index, setIndex] = useState(0)
  const [resumeState, setResumeState] = useState<'loading' | 'ready' | 'expired' | 'unavailable'>('loading')
  const [confirmSubmit, setConfirmSubmit] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [syncKey, setSyncKey] = useState(0)
  const [announcement, setAnnouncement] = useState('')
  const lastTierRef = useRef<CountdownTier>('normal')

  const answers = useExamStore((state) => state.answers)
  const review = useExamStore((state) => state.review)
  const saveState = useExamStore((state) => state.saveState)
  const offline = useExamStore((state) => state.offline)
  const expired = useExamStore((state) => state.expired)
  const expiresAt = useExamStore((state) => state.expiresAt)
  const attemptId = useExamStore((state) => state.attemptId)
  const remainingSeconds = useExamStore((state) => state.remainingSeconds)
  const resume = useExamStore((state) => state.resume)
  const setAnswer = useExamStore((state) => state.setAnswer)
  const clearAnswer = useExamStore((state) => state.clearAnswer)
  const toggleReview = useExamStore((state) => state.toggleReview)
  const flush = useExamStore((state) => state.flush)
  const markExpired = useExamStore((state) => state.markExpired)
  const resync = useExamStore((state) => state.resync)

  const examQuery = useQuery({ queryKey: ['exam', id], queryFn: async () => (await examApi.get(id)).data.data, enabled: Boolean(id), retry: 1 })
  const questionsQuery = useQuery({ queryKey: ['exam-questions', id], queryFn: async () => (await questionApi.list(id)).data.data, enabled: Boolean(id), retry: 1 })

  const submitMutation = useMutation({
    mutationFn: async () => {
      await flush()
      const currentAnswers = useExamStore.getState().answers
      const questions = questionsQuery.data || []
      return examApi.submit(id, questions.map(question => ({ questionId: question.id, value: currentAnswers[question.id] || '' })))
    },
    onSuccess: response => navigate(`/student/exams/${id}/result`, { replace: true, state: { submission: response.data.data } }),
    onError: async (error) => {
      const statusCode = (error as { response?: { status?: number } })?.response?.status
      if (statusCode === 409) {
        try {
          await examApi.resultReview(id)
          navigate(`/student/exams/${id}/result`, { replace: true })
        } catch {
          markExpired()
          setResumeState('expired')
          setSubmitError('This attempt is no longer open for submission. Your answers are safe; if a result exists you can view it from the expired screen.')
        }
        return
      }
      if (statusCode === 401 || statusCode === 403) {
        setSubmitError('Your session is no longer valid. Please sign in again.')
        return
      }
      if (statusCode === undefined || statusCode >= 500) {
        setSubmitError('Cannot reach the exam service right now. Your answers are saved and will not be lost. Please try submitting again.')
        return
      }
      setSubmitError('Unable to submit this exam. Please try again.')
    },
  })

  useEffect(() => {
    let cancelled = false
    resume(id)
      .then(() => { if (!cancelled) setResumeState('ready') })
      .catch((error) => {
        if (cancelled) return
        const statusCode = (error as { response?: { status?: number } })?.response?.status
        setResumeState(statusCode === 409 ? 'expired' : 'unavailable')
      })
    return () => { cancelled = true }
  }, [id, resume])

  useEffect(() => {
    if (expired) setResumeState('expired')
  }, [expired])

  const handleCountdownZero = useCallback(() => {
    let cancelled = false
    void examApi.attemptProgress(id).then((response) => {
      if (cancelled) return
      const progress = response.data.data
      resync(progress)
      setSyncKey(key => key + 1)
      setAnnouncement('Your remaining time was verified with the exam server.')
    }).catch(() => {
      if (cancelled) return
      markExpired()
    })
    return () => { cancelled = true }
  }, [id, resync, markExpired])

  const handleTierChange = useCallback((tier: CountdownTier) => {
    const previous = lastTierRef.current
    if (tier === previous) return
    lastTierRef.current = tier
    if (tier === 'critical') setAnnouncement('About one minute remaining.')
    else if (tier === 'low' && previous === 'normal') setAnnouncement('Approximately five minutes remaining.')
  }, [])

  const questions = questionsQuery.data || []
  const q = questions[Math.min(index, Math.max(questions.length - 1, 0))]
  const answeredCount = questions.filter(question => answers[question.id]).length
  const stateValues = Object.values(saveState)
  const anySaving = stateValues.includes('saving')
  const anyFailed = stateValues.includes('failed')
  const saveStatusLabel = expired ? null
    : offline ? 'Offline — retrying when the connection returns'
    : anySaving ? 'Saving…'
    : anyFailed ? 'Some answers could not be saved'
    : answeredCount > 0 ? 'All answers saved'
    : null

  const goTo = useCallback((next: number) => {
    if (next < 0 || next >= questions.length) return
    setIndex(next)
    setDrawerOpen(false)
    const wasAnswered = Boolean(answers[questions[next].id])
    setAnnouncement(`Question ${next + 1} of ${questions.length}. ${wasAnswered ? 'Already answered.' : 'Not yet answered.'}`)
  }, [questions.length, questions, answers])

  useEffect(() => {
    if (resumeState !== 'ready' || expired) return
    const handler = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)) return
      if (event.altKey && event.key === 'ArrowLeft') {
        event.preventDefault()
        if (index > 0) goTo(index - 1)
      } else if (event.altKey && event.key === 'ArrowRight') {
        event.preventDefault()
        if (index < questions.length - 1) goTo(index + 1)
      } else if ((event.key === 'm' || event.key === 'M') && !event.ctrlKey && !event.metaKey && !event.altKey) {
        event.preventDefault()
        if (q) {
          toggleReview(q.id)
          const isMarked = !review[q.id]
          setAnnouncement(isMarked ? `Question ${index + 1} marked for review.` : `Question ${index + 1} no longer marked.`)
        }
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [resumeState, expired, index, questions.length, q, review, toggleReview, goTo])

  const { status: proctoringStatus } = useProctorSession({ attemptId, active: resumeState === 'ready' && !expired })

  const currentMarked = Boolean(q && review[q.id])

  if (resumeState === 'loading' || examQuery.isPending || questionsQuery.isPending) return <div className="mx-auto max-w-5xl"><div className="h-80 animate-pulse rounded-2xl bg-muted" /></div>
  if (resumeState === 'expired') return (
    <div className="mx-auto max-w-5xl">
      <Card>
        <div role="alert" className="text-center">
          <div className="mx-auto grid size-12 place-items-center rounded-full bg-amber-500/10 text-amber-600"><AlertCircle size={22} /></div>
          <h2 className="mt-4 font-semibold">Exam time expired</h2>
          <p className="mt-2 text-sm text-muted-foreground">{submitError ?? 'This attempt is no longer accepting answers.'}</p>
          <div className="mt-6 flex flex-wrap justify-center gap-3">
            <button className="rounded-xl border border-border px-4 py-2.5 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => navigate('/student/exams')}>Back to exams</button>
            <button className="rounded-xl bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => navigate(`/student/exams/${id}/result`)}>View result</button>
          </div>
        </div>
      </Card>
    </div>
  )
  if (resumeState === 'unavailable' || examQuery.isError || questionsQuery.isError || !examQuery.data) return (
    <div className="mx-auto max-w-5xl">
      <Card><p className="text-sm text-destructive">Unable to load this exam attempt.</p></Card>
    </div>
  )
  if (!q) return (
    <div className="mx-auto max-w-5xl">
      <Card><p className="py-8 text-center text-sm text-muted-foreground">No questions are available for this exam.</p></Card>
    </div>
  )

  const submit = () => submitMutation.mutate()
  const numberOfQuestions = questions.length

  return (
    <div className="mx-auto max-w-5xl pb-28 lg:pb-20">
      <span className="sr-only" role="status" aria-live="polite">{announcement}</span>
      <ExamStatusBar
        title={examQuery.data.title}
        index={index}
        total={numberOfQuestions}
        expiresAt={expiresAt}
        initialSeconds={remainingSeconds}
        syncKey={syncKey}
        saveLabel={saveStatusLabel}
        proctoringStatus={proctoringStatus}
        onCountdownZero={handleCountdownZero}
        onTierChange={handleTierChange}
      />
      <div className="grid gap-6 lg:grid-cols-[1fr_280px]">
        <div>
          <Card>
            <div className="flex items-center justify-between text-sm text-muted-foreground">
              <span>Question {index + 1} of {numberOfQuestions}</span>
              <span>{answeredCount} answered · {Object.keys(review).length} marked</span>
            </div>
            <div className="mt-3 h-2 rounded-full bg-muted"><div className="h-2 rounded-full bg-primary transition-all" style={{ width: `${((index + 1) / numberOfQuestions) * 100}%` }} /></div>
            <div aria-keyshortcuts="Alt+ArrowLeft Alt+ArrowRight M" className="mt-10">
              <h2 className="text-xl font-semibold leading-8">{q.text}</h2>
              <div className="mt-7 space-y-3">{q.options.map(option => {
                const selected = answers[q.id] === option
                return (
                  <button key={option} onClick={() => setAnswer(q.id, option)} className={`flex w-full items-center gap-3 rounded-xl border p-4 text-left text-sm transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${selected ? 'border-primary bg-primary/10' : 'border-border hover:bg-muted'}`}>
                    <span className={`grid size-6 place-items-center rounded-full border text-xs ${selected ? 'border-primary bg-primary text-primary-foreground' : 'border-muted-foreground'}`}>{selected && <Check size={14} />}</span>
                    {option}
                  </button>
                )
              })}</div>
            </div>
          </Card>

          <div className="mt-4 flex flex-wrap items-center justify-between gap-3 lg:hidden" aria-label="Question list">
            <button type="button" onClick={() => setDrawerOpen(true)} className="flex items-center gap-2 rounded-xl border border-border px-4 py-2.5 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]">
              <LayoutGrid size={16} /> Palette
            </button>
            <button type="button" onClick={() => { toggleReview(q.id); setAnnouncement(review[q.id] ? `Question ${index + 1} no longer marked.` : `Question ${index + 1} marked for review.`) }} className={`flex items-center gap-2 rounded-xl border px-4 py-2.5 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] ${currentMarked ? 'border-amber-400/60 bg-amber-500/10 text-amber-700' : 'border-border'}`}>
              <Flag size={16} /> {currentMarked ? 'Marked' : 'Mark'}
            </button>
          </div>
        </div>

        <Card className="hidden lg:block">
          <h2 className="font-semibold">Question navigator</h2>
          <div className="mt-4">
            <QuestionPalette questions={questions} currentIndex={index} answers={answers} review={review} onNavigate={goTo} />
          </div>
          <button type="button" onClick={() => { toggleReview(q.id); setAnnouncement(review[q.id] ? `Question ${index + 1} no longer marked.` : `Question ${index + 1} marked for review.`) }} className={`mt-6 flex w-full items-center justify-center gap-2 rounded-xl border px-4 py-2.5 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] ${currentMarked ? 'border-amber-400/60 bg-amber-500/10 text-amber-700' : 'border-border'}`}>
            <Flag size={16} /> {currentMarked ? 'Remove mark' : 'Mark question'}
          </button>
        </Card>
      </div>

      {submitError && <p className="mt-5 text-sm text-destructive" role="alert">{submitError}</p>}

      <div className="fixed inset-x-0 bottom-[5.5rem] z-30 mx-auto max-w-5xl px-3 sm:px-4 lg:bottom-4">
        <div className="flex items-center justify-between gap-2 rounded-2xl border border-border bg-card p-2 shadow-lg">
          <button type="button" disabled={index === 0} onClick={() => goTo(index - 1)} className="flex min-h-11 items-center gap-1.5 rounded-xl border border-border px-3 py-2 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-50">
            <ChevronLeft size={16} /> <span className="hidden sm:inline">Previous</span>
          </button>
          <div className="flex items-center gap-2">
            <button type="button" onClick={() => clearAnswer(q.id)} className="flex min-h-11 items-center gap-1.5 rounded-xl border border-border px-3 py-2 text-sm transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]">
              <X size={15} /> <span className="hidden sm:inline">Clear</span>
            </button>
            <button type="button" disabled={index === numberOfQuestions - 1} onClick={() => goTo(index + 1)} className="flex min-h-11 items-center gap-1.5 rounded-xl border border-border px-3 py-2 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-50">
              <span className="hidden sm:inline">Next</span> <ChevronRight size={16} />
            </button>
            <button type="button" disabled={submitMutation.isPending} onClick={() => { setSubmitError(null); setConfirmSubmit(true) }} className="min-h-11 rounded-xl bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-60">
              Submit
            </button>
          </div>
        </div>
      </div>

      {drawerOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div className="absolute inset-0 bg-background/75 backdrop-blur-sm" onClick={() => setDrawerOpen(false)} aria-hidden="true" />
          <div role="dialog" aria-modal="true" aria-label="Question navigator" className="absolute inset-y-0 right-0 flex w-[min(320px,86vw)] flex-col border-l border-border bg-card p-5 shadow-2xl">
            <div className="flex items-center justify-between">
              <h2 className="font-semibold">Question navigator</h2>
              <button type="button" onClick={() => setDrawerOpen(false)} aria-label="Close question palette" className="rounded-lg border border-border p-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><X size={16} /></button>
            </div>
            <div className="mt-5 overflow-y-auto">
              <QuestionPalette questions={questions} currentIndex={index} answers={answers} review={review} onNavigate={goTo} />
            </div>
          </div>
        </div>
      )}

      <SubmissionReviewModal
        open={confirmSubmit}
        questions={questions}
        currentIndex={index}
        answers={answers}
        review={review}
        expiresAt={expiresAt}
        busy={submitMutation.isPending}
        error={submitError}
        onGoTo={(next) => { setConfirmSubmit(false); setSubmitError(null); goTo(next) }}
        onSubmit={() => { setSubmitError(null); submit() }}
        onCancel={() => setConfirmSubmit(false)}
      />
    </div>
  )
}

function Card({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <section className={`rounded-2xl border border-border bg-card p-5 ${className}`}>{children}</section>
}