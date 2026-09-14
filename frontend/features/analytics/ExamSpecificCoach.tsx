'use client'
import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  AlertCircle,
  BarChart3,
  Check,
  ChevronLeft,
  GraduationCap,
  RefreshCw,
  Search,
  Send,
  Sparkles,
  Target,
  X,
} from 'lucide-react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuthStore } from '@/store/authStore'
import { Card } from '@/components/shared'
import { aiCoachApi } from '@/services/aiCoachApi'
import type {
  CoachAction,
  CoachChatMessage,
  CoachExamSummary,
  CoachQuestion,
  ExamCoachContext,
} from '@/types/ai-coach'

const ACTION_LABELS: Record<string, string> = {
  EXPLAIN_PERFORMANCE: 'Explain my performance',
  EXPLAIN_MISTAKES: 'Explain my mistakes',
  REVIEW_WEAK_AREAS: 'Review my weak areas',
  SUGGEST_REVISION: 'Suggest a revision plan',
  EXPLAIN_QUESTION: 'Explain this question',
  GENERATE_SIMILAR_QUESTION: 'Make a similar question',
}

const SUGGESTED_ACTIONS: CoachAction[] = [
  'EXPLAIN_PERFORMANCE',
  'EXPLAIN_MISTAKES',
  'REVIEW_WEAK_AREAS',
  'SUGGEST_REVISION',
]

const difficultyStyles: Record<string, string> = {
  EASY: 'bg-emerald-500/10 text-emerald-700',
  MEDIUM: 'bg-amber-500/10 text-amber-700',
  HARD: 'bg-red-500/10 text-red-700',
}

function ErrorNotice({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <Card className="border-destructive/30">
      <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3 text-destructive">
          <AlertCircle size={20} />
          <p className="text-sm">{message}</p>
        </div>
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="flex items-center gap-2 rounded-xl border border-border px-3 py-1.5 text-sm hover:bg-muted"
          >
            <RefreshCw size={14} /> Retry
          </button>
        )}
      </div>
    </Card>
  )
}

export function ExamSpecificCoach() {
  const token = useAuthStore((state) => state.token)
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [selected, setSelected] = useState<CoachExamSummary | null>(null)
  const [search, setSearch] = useState('')
  const [deepLinkError, setDeepLinkError] = useState<string | null>(null)
  const [messages, setMessages] = useState<CoachChatMessage[]>([])
  const [input, setInput] = useState('')
  const [runningAction, setRunningAction] = useState<CoachAction | null>(null)
  const [chatError, setChatError] = useState<string | null>(null)

  const examsQuery = useQuery({
    queryKey: ['student-ai-coach-exams'],
    enabled: Boolean(token),
    queryFn: async () => (await aiCoachApi.listExams()).data.data,
    retry: 1,
  })

  useEffect(() => {
    if (selected) return
    if (!examsQuery.isSuccess || examsQuery.data.length === 0) return
    const requested = searchParams.get('exam')
    if (!requested) return
    const matched = examsQuery.data.find(exam => exam.examId === requested)
    if (matched) {
      setSelected(matched)
      setDeepLinkError(null)
    } else {
      setDeepLinkError(requested)
    }
  }, [selected, examsQuery.isSuccess, examsQuery.data, searchParams])

  const contextQuery = useQuery({
    queryKey: ['student-ai-coach-context', selected?.examId],
    enabled: Boolean(token && selected),
    queryFn: async () => (await aiCoachApi.context(selected!.examId, 'SUGGEST_REVISION')).data.data,
    retry: 1,
  })

  const context = contextQuery.data ?? null
  const mistakes = useMemo(
    () => (context?.questions ?? []).filter(q => !q.correct),
    [context],
  )

  const selectExam = (exam: CoachExamSummary) => {
    setSelected(exam)
    setMessages([])
    setChatError(null)
  }

  const backToPicker = () => {
    setSelected(null)
    setMessages([])
    setChatError(null)
  }

  const askCoach = async (action: CoachAction, questionId?: string, userLabel?: string) => {
    if (!token || !selected || runningAction) return
    const label = userLabel ?? ACTION_LABELS[action] ?? 'Tell me about my results'
    const history = messages
    setMessages(current => [...current, { role: 'user', content: label }])
    setRunningAction(action)
    setChatError(null)
    try {
      const response = await fetch('/api/student/ai-coach/chat', {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          examId: selected.examId,
          action,
          ...(questionId ? { questionId } : {}),
          message: label,
          history,
        }),
      })
      const body = (await response.json().catch(() => null)) as { answer?: string; error?: string } | null
      if (!response.ok) {
        setChatError(body?.error ?? 'The exam coach could not answer right now. Please try again.')
        setMessages(history)
        return
      }
      setMessages(current => [...current, { role: 'assistant', content: body?.answer ?? '' }])
    } catch {
      setChatError('The exam coach is temporarily unavailable. Please try again.')
      setMessages(history)
    } finally {
      setRunningAction(null)
    }
  }

  const sendChat = () => {
    const message = input.trim()
    if (!message || runningAction) return
    setInput('')
    void askCoach('CHAT', undefined, message)
  }

  const askQuestion = (question: CoachQuestion, action: 'EXPLAIN_QUESTION' | 'GENERATE_SIMILAR_QUESTION') => {
    void askCoach(action, question.questionId, `${ACTION_LABELS[action]} (Question ${question.number})`)
  }

  if (examsQuery.isPending) {
    return (
      <div className="grid gap-4 md:grid-cols-3" aria-busy="true" aria-live="polite">
        {[1, 2, 3].map(item => (
          <Card key={item}>
            <div className="h-24 animate-pulse rounded-xl bg-muted" />
          </Card>
        ))}
      </div>
    )
  }

  const exams = examsQuery.data ?? []
  if (examsQuery.isError) {
    return (
      <>
        <ErrorNotice message="Unable to load your completed exams." onRetry={() => void examsQuery.refetch()} />
        <p className="mt-4 text-sm text-muted-foreground">
          No exam was selected. Once your exams load you can pick one to coach you on.
        </p>
      </>
    )
  }
  if (examsQuery.isSuccess && exams.length === 0) {
    return (
      <Card className="max-w-2xl">
        <div className="flex items-center gap-3">
          <div className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary">
            <Sparkles size={20} />
          </div>
          <div>
            <p className="font-medium">No completed exams yet</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Complete an exam first, then this coach will explain your results one exam at a time.
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
    )
  }

  if (!selected) {
    const filtered = exams.filter(exam =>
      `${exam.title} ${exam.subject}`.toLowerCase().includes(search.toLowerCase()),
    )
    return (
      <>
        <p className="mb-4 text-sm text-muted-foreground">Pick one completed exam to coach you on.</p>
        {deepLinkError && (
          <div role="alert" className="mb-4 flex items-center gap-2 rounded-xl border border-amber-500/30 bg-amber-500/5 px-4 py-3 text-sm text-amber-700">
            <AlertCircle size={16} className="shrink-0" />
            We couldn&rsquo;t find a completed exam for the one you selected. Pick another below.
          </div>
        )}
        <label className="relative mb-4 block">
          <Search size={16} className="pointer-events-none absolute left-3 top-3 text-muted-foreground" />
          <input
            className="field pl-9"
            value={search}
            onChange={event => setSearch(event.target.value)}
            placeholder="Search exams or subjects"
          />
        </label>
        {filtered.length === 0 ? (
          <Card>
            <p className="py-8 text-center text-sm text-muted-foreground">No completed exams match your search.</p>
          </Card>
        ) : (
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {filtered.map(exam => (
              <button
                key={exam.examId}
                type="button"
                onClick={() => selectExam(exam)}
                className="rounded-2xl border border-border bg-card p-5 text-left transition hover:bg-muted active:scale-[.99] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <div className="flex items-start justify-between gap-3">
                  <p className="font-semibold">{exam.title}</p>
                  <span className="shrink-0 rounded-full bg-primary/10 px-2 py-0.5 text-xs font-semibold text-primary">
                    {formatPercent(exam.percentage)}%
                  </span>
                </div>
                <p className="mt-1 text-sm text-muted-foreground">{exam.subject}</p>
                <div className="mt-4 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                  <span className="flex items-center gap-1 rounded-full bg-muted px-2 py-1">
                    <Target size={12} /> {exam.score} points
                  </span>
                  <span className="flex items-center gap-1 rounded-full bg-muted px-2 py-1">
                    <GraduationCap size={12} /> Attempt {exam.attemptNumber}
                  </span>
                  <span className="flex items-center gap-1 rounded-full bg-muted px-2 py-1">
                    <Check size={12} /> {formatDate(exam.completedAt)}
                  </span>
                </div>
              </button>
            ))}
          </div>
        )}
      </>
    )
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-primary/30 bg-primary/[0.04] px-4 py-3">
        <p className="text-sm text-muted-foreground">
          Reviewing <span className="font-medium text-foreground">{selected.title}</span>
          <span className="hidden sm:inline"> — the coach only uses this exam&rsquo;s data.</span>
        </p>
        <button
          type="button"
          onClick={backToPicker}
          className="flex items-center gap-1 rounded-lg border border-border bg-background px-3 py-1.5 text-xs font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ChevronLeft size={14} /> Change exam
        </button>
      </div>

      <Card className="mb-5">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-lg font-semibold">{selected.title}</h2>
              <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-semibold text-primary">
                Attempt {selected.attemptNumber}
              </span>
            </div>
            <p className="mt-1 text-sm text-muted-foreground">
              {selected.subject} &middot; completed {formatDate(selected.completedAt)}
            </p>
          </div>
          <div className="flex items-center gap-4">
            <div className="text-right">
              <p className="text-2xl font-semibold">{formatPercent(selected.percentage)}%</p>
              <p className="text-xs text-muted-foreground">
                {selected.score} points{context ? ` of ${context.total}` : ''}
              </p>
            </div>
          </div>
        </div>
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          <div className="rounded-xl bg-muted p-4">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Score breakdown</p>
            {context ? (
              <div className="mt-3 flex flex-wrap gap-2 text-sm">
                <span className="rounded-full bg-emerald-500/10 px-2.5 py-1 font-semibold text-emerald-700">
                  {context.stats?.correct ?? 0} correct
                </span>
                <span className="rounded-full bg-red-500/10 px-2.5 py-1 font-semibold text-red-700">
                  {context.stats?.incorrect ?? 0} wrong
                </span>
                <span className="rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
                  {context.stats?.unanswered ?? 0} unanswered
                </span>
                <span className="rounded-full bg-primary/10 px-2.5 py-1 font-semibold text-primary">
                  {formatPercent(context.stats?.accuracy ?? 0)}% accuracy
                </span>
              </div>
            ) : (
              <div className="mt-3 h-8 animate-pulse rounded-xl bg-border" />
            )}
          </div>
          {context?.stats && (
            <div className="rounded-xl bg-muted p-4">
              <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">By difficulty</p>
              <div className="mt-3 flex flex-wrap gap-2 text-sm">
                {(['easy', 'medium', 'hard'] as const).map(bucket => (
                  <span key={bucket} className="rounded-full bg-muted px-2.5 py-1">
                    <b className="capitalize">{bucket}</b>{' '}
                    <span className="text-muted-foreground">
                      {formatPercent(
                        context.stats![`${bucket}Total`] > 0
                          ? (context.stats![`${bucket}Correct`] / context.stats![`${bucket}Total`]) * 100
                          : 0,
                      )}%
                    </span>
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>
      </Card>

      <div className="mb-5 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
        {SUGGESTED_ACTIONS.map(action => (
          <button
            key={action}
            type="button"
            disabled={runningAction !== null}
            onClick={() => void askCoach(action)}
            className="flex items-start gap-2 rounded-xl border border-border p-3 text-left text-sm transition hover:bg-muted active:scale-[.99] disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <BarChart3 size={16} className="mt-0.5 shrink-0 text-primary" />
            <span>
              <b>{ACTION_LABELS[action]}</b>
              <span className="mt-0.5 block text-muted-foreground">
                Get an exam-scoped, data-grounded answer.
              </span>
            </span>
          </button>
        ))}
      </div>

      {context?.questions && context.questions.length > 0 && (
        <Card className="mb-5">
          <div className="flex items-center justify-between gap-3">
            <h3 className="font-semibold">Questions in this exam</h3>
            <span className="text-xs text-muted-foreground">
              {mistakes.length > 0 ? `${mistakes.length} to review` : 'No mistakes — great job'}
            </span>
          </div>
          <ul className="mt-4 grid gap-2">
            {context.questions.map(question => (
              <li
                key={question.questionId}
                className="flex flex-wrap items-center gap-2 rounded-xl bg-muted px-3 py-2.5 text-sm"
              >
                <span className="flex items-center gap-1.5 font-semibold">
                  <span className="grid size-5 place-items-center rounded-full bg-background">
                    {question.correct ? (
                      <Check size={12} className="text-emerald-600" />
                    ) : (
                      <X size={12} className="text-red-600" />
                    )}
                  </span>
                  {question.number}.
                </span>
                <span
                  className={`rounded-full px-2 py-0.5 text-xs font-semibold ${difficultyStyles[question.difficultyName] ?? ''}`}
                >
                  {question.difficultyName}
                </span>
                <span className="min-w-0 flex-1 text-muted-foreground">{question.text}</span>
                <div className="flex gap-2">
                  <button
                    type="button"
                    disabled={runningAction !== null}
                    onClick={() => void askQuestion(question, 'EXPLAIN_QUESTION')}
                    className="rounded-lg border border-border px-2.5 py-1 text-xs hover:bg-background disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    Explain
                  </button>
                  <button
                    type="button"
                    disabled={runningAction !== null}
                    onClick={() => void askQuestion(question, 'GENERATE_SIMILAR_QUESTION')}
                    className="rounded-lg border border-border px-2.5 py-1 text-xs hover:bg-background disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    Similar question
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </Card>
      )}

      {chatError && (
        <div className="mb-4">
          <ErrorNotice message={chatError} onRetry={() => setChatError(null)} />
        </div>
      )}

      <Card>
        <h3 className="font-semibold">Ask your exam coach</h3>
        <div className="mt-4 flex min-h-40 flex-col gap-3" aria-live="polite">
          {messages.length === 0 && (
            <p className="text-sm text-muted-foreground">
              Ask anything about this exam — for example &ldquo;What should I focus on next?&rdquo; The coach can only
              use this one exam&rsquo;s data.
            </p>
          )}
          {messages.map((message, index) => (
            <div
              key={index}
              className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-6 ${
                message.role === 'user'
                  ? 'ml-auto bg-primary text-primary-foreground'
                  : 'bg-muted text-foreground'
              }`}
            >
              {message.content}
            </div>
          ))}
          {runningAction && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <RefreshCw size={14} className="animate-spin" /> The coach is thinking…
            </div>
          )}
        </div>
        <div className="mt-4 flex items-center gap-2">
          <input
            value={input}
            onChange={event => setInput(event.target.value)}
            onKeyDown={event => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault()
                sendChat()
              }
            }}
            placeholder="Ask anything about this exam…"
            className="min-w-0 flex-1 rounded-xl border border-border bg-background px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <button
            type="button"
            onClick={sendChat}
            disabled={!input.trim() || runningAction !== null}
            className="grid size-10 shrink-0 place-items-center rounded-xl bg-primary text-primary-foreground disabled:opacity-50"
            aria-label="Send message"
          >
            <Send size={16} />
          </button>
        </div>
      </Card>
    </>
  )
}

function formatPercent(value: number): string {
  const rounded = Math.round(value * 100) / 100
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(2)
}

function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}