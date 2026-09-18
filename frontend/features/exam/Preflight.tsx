'use client'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Award, Camera, CheckCircle2, ChevronLeft, ChevronRight, Circle, Clock3, FileText, Info, Laptop, ListChecks, Loader2, Maximize2, Mic, MinusCircle, MonitorUp, RefreshCw, ShieldCheck, Wifi, XCircle } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { examApi } from '@/services/examApi'
import { questionApi } from '@/services/questionApi'
import { resultApi } from '@/services/resultApi'
import { retestApi } from '@/services/retestApi'
import { checkFor } from '@/lib/preflightChecks'
import { PREFLIGHT_CHECKS, PREFLIGHT_POLICY } from '@/types/preflight'
import type { PreflightCheckDefinition, PreflightCheckId, PreflightCheckResult, PreflightCheckStatus } from '@/types/preflight'

const STATUS_DOT: Record<PreflightCheckStatus, LucideIcon> = {
  CHECKING: Loader2,
  READY: CheckCircle2,
  BLOCKED: XCircle,
  UNAVAILABLE: MinusCircle,
  NOT_STARTED: Circle,
}

const STATUS_PILL: Record<PreflightCheckStatus, { label: string; className: string }> = {
  CHECKING: { label: 'Checking', className: 'border-border bg-muted text-muted-foreground' },
  READY: { label: 'Ready', className: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-700' },
  BLOCKED: { label: 'Blocked', className: 'border-red-500/30 bg-red-500/10 text-red-600' },
  UNAVAILABLE: { label: 'Not available', className: 'border-amber-500/30 bg-amber-500/10 text-amber-700' },
  NOT_STARTED: { label: 'Not checked', className: 'border-border bg-muted text-muted-foreground' },
}

const FEATURE_ICON: Record<PreflightCheckId, LucideIcon> = {
  camera: Camera,
  microphone: Mic,
  'screen-share': MonitorUp,
  fullscreen: Maximize2,
  network: Wifi,
  browser: Laptop,
}

const initialStatuses = (): Record<PreflightCheckId, PreflightCheckStatus> => ({
  camera: 'NOT_STARTED',
  microphone: 'NOT_STARTED',
  'screen-share': 'NOT_STARTED',
  fullscreen: 'NOT_STARTED',
  network: 'NOT_STARTED',
  browser: 'NOT_STARTED',
})

const initialDetails = (): Record<PreflightCheckId, string> => ({
  camera: '',
  microphone: '',
  'screen-share': '',
  fullscreen: '',
  network: '',
  browser: '',
})

function CheckRow({ def, status, detail, checking, onRun }: {
  def: PreflightCheckDefinition
  status: PreflightCheckStatus
  detail: string
  checking: boolean
  onRun: () => void
}) {
  const FeatureIcon = FEATURE_ICON[def.id]
  const StatusDot = STATUS_DOT[status]
  const pill = STATUS_PILL[status]
  const tone = status === 'BLOCKED' ? 'border-red-500/30 bg-red-500/5' : status === 'READY' ? 'border-emerald-500/30 bg-emerald-500/5' : 'border-border bg-card'
  return (
    <li className={`flex items-start gap-3 rounded-xl border p-4 ${tone}`}>
      <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-muted text-muted-foreground"><FeatureIcon size={18} /></div>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="text-sm font-semibold">{def.label}</p>
          <span className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${pill.className}`}>
            <StatusDot size={10} className={checking ? 'animate-spin' : undefined} />
            {checking ? 'Checking' : pill.label}
          </span>
          <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${def.required ? 'border-primary/30 bg-primary/10 text-primary' : 'border-border bg-muted text-muted-foreground'}`}>
            {def.required ? 'Required' : 'Advisory'}
          </span>
        </div>
        <p className="mt-1 text-xs leading-5 text-muted-foreground">{status !== 'NOT_STARTED' ? detail : 'Not checked yet.'}</p>
        {def.interactive && (
          <button type="button" onClick={onRun} disabled={checking} className="mt-2 rounded-lg border border-border px-3 py-1.5 text-xs font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-60">
            {checking ? 'Checking…' : status === 'NOT_STARTED' ? `Check ${def.label.toLowerCase()}` : 'Check again'}
          </button>
        )}
      </div>
    </li>
  )
}

export function Preflight({ id }: { id: string }) {
  const navigate = useNavigate()
  const [step, setStep] = useState<'check' | 'instructions'>('check')
  const [statuses, setStatuses] = useState<Record<PreflightCheckId, PreflightCheckStatus>>(initialStatuses)
  const [details, setDetails] = useState<Record<PreflightCheckId, string>>(initialDetails)
  const [checking, setChecking] = useState<PreflightCheckId | null>(null)
  const [runKey, setRunKey] = useState(0)

  const examQuery = useQuery({ queryKey: ['exam', id], queryFn: async () => (await examApi.get(id)).data.data, enabled: Boolean(id), retry: 1 })
  const questionsQuery = useQuery({ queryKey: ['exam-questions', id], queryFn: async () => (await questionApi.list(id)).data.data, enabled: Boolean(id), retry: 1 })
  const resultsQuery = useQuery({ queryKey: ['my-results'], queryFn: async () => (await resultApi.mine()).data.data, retry: 1 })
  const retestsQuery = useQuery({ queryKey: ['my-retests'], queryFn: async () => (await retestApi.mine()).data.data, retry: 1 })

  const startMutation = useMutation({
    mutationFn: () => examApi.start(id),
    onSuccess: () => navigate(`/student/exams/${id}`),
    onError: (error) => {
      const statusCode = (error as { response?: { status?: number } })?.response?.status
      if (statusCode === 409) navigate(`/student/exams/${id}`)
    },
  })

  const attemptNumber = useMemo(() => (resultsQuery.data ?? []).filter(result => result.examId === id).length + 1, [resultsQuery.data, id])
  const retestStatus = useMemo(() => (retestsQuery.data ?? []).find(request => request.examId === id)?.status ?? null, [retestsQuery.data, id])

  const commit = (checkId: PreflightCheckId, result: PreflightCheckResult) => {
    setStatuses(prev => ({ ...prev, [checkId]: result.status }))
    setDetails(prev => ({ ...prev, [checkId]: result.detail }))
  }

  useEffect(() => {
    let cancelled = false
    setStatuses(prev => ({ ...prev, network: 'CHECKING', browser: 'CHECKING' }))
    setDetails(prev => ({ ...prev, network: 'Verifying your connection…', browser: 'Checking browser capabilities…' }))
    void checkFor('network').then(result => {
      if (cancelled) return
      setStatuses(prev => ({ ...prev, network: result.status }))
      setDetails(prev => ({ ...prev, network: result.detail }))
    })
    void checkFor('browser').then(result => {
      if (cancelled) return
      setStatuses(prev => ({ ...prev, browser: result.status }))
      setDetails(prev => ({ ...prev, browser: result.detail }))
    })
    return () => { cancelled = true }
  }, [runKey])

  const runCheck = async (checkId: PreflightCheckId) => {
    if (checking) return
    setChecking(checkId)
    setStatuses(prev => ({ ...prev, [checkId]: 'CHECKING' }))
    setDetails(prev => ({ ...prev, [checkId]: 'Verifying…' }))
    const result = await checkFor(checkId)
    commit(checkId, result)
    setChecking(null)
  }

  const rerunAll = () => {
    setStatuses(initialStatuses())
    setDetails(initialDetails())
    setRunKey(key => key + 1)
  }

  const allCompleted = PREFLIGHT_CHECKS.every(checkDef => statuses[checkDef.id] !== 'NOT_STARTED')
  const blockedRequired = PREFLIGHT_CHECKS.some(checkDef => checkDef.required && statuses[checkDef.id] === 'BLOCKED')
  const canContinue = allCompleted && !blockedRequired

  const startError = startMutation.isError ? (startMutation.error as { response?: { data?: { message?: string } } })?.response?.data?.message : null

  if (examQuery.isPending || questionsQuery.isPending) return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-8"><p className="text-xs font-semibold uppercase tracking-widest text-primary">Student workspace</p><div className="mt-3 h-9 w-64 animate-pulse rounded-lg bg-muted" /><div className="mt-2 h-4 w-96 max-w-full animate-pulse rounded bg-muted" /></div>
      <div className="rounded-2xl border border-border bg-card p-5"><div className="h-72 animate-pulse rounded-xl bg-muted" /></div>
    </div>
  )
  if (examQuery.isError || !examQuery.data) return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-8"><p className="text-xs font-semibold uppercase tracking-widest text-primary">Student workspace</p><h1 className="mt-2 text-3xl font-semibold tracking-tight">Before you begin</h1></div>
      <div className="rounded-2xl border border-border bg-card p-5"><p className="text-sm text-destructive">Unable to load this exam.</p></div>
    </div>
  )

  const exam = examQuery.data
  const questionCount = questionsQuery.data?.length ?? 0
  const schedule = exam.startAt && exam.endAt ? `${new Date(exam.startAt).toLocaleDateString()} – ${new Date(exam.endAt).toLocaleDateString()}` : exam.date ? new Date(exam.date).toLocaleDateString() : null

  if (step === 'check') return (
    <div className="mx-auto max-w-3xl">
      <header className="mb-8">
        <p className="text-xs font-semibold uppercase tracking-widest text-primary">Student workspace</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-tight">Exam readiness check</h1>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">Verify your device and connection are ready before you begin. Each check only runs when you ask it to.</p>
      </header>

      <div className="rounded-2xl border border-border bg-card p-5">
        <div className="flex items-center gap-3">
          <div className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary"><FileText /></div>
          <div className="min-w-0">
            <h2 className="truncate font-semibold">{exam.title}</h2>
            <p className="text-sm text-muted-foreground">{exam.subject} · {exam.duration} minutes{schedule ? ` · ${schedule}` : ''}</p>
          </div>
        </div>

        <ul className="mt-6 space-y-3">
          {PREFLIGHT_CHECKS.map(checkDef => (
            <CheckRow key={checkDef.id} def={checkDef} status={statuses[checkDef.id]} detail={details[checkDef.id]} checking={checking === checkDef.id} onRun={() => void runCheck(checkDef.id)} />
          ))}
        </ul>

        <div className="mt-4 flex items-start gap-2 rounded-xl bg-muted/60 p-3 text-xs leading-5 text-muted-foreground">
          <Info size={14} className="mt-0.5 shrink-0" />
          <span>{PREFLIGHT_POLICY.note}</span>
        </div>

        <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <button type="button" onClick={() => navigate('/student/exams')} className="rounded-xl border border-border px-5 py-3 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]">Back to exams</button>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <button type="button" onClick={rerunAll} className="flex min-h-11 items-center justify-center gap-2 rounded-xl border border-border px-5 py-3 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]">
              <RefreshCw size={15} /> Run checks again
            </button>
            <button type="button" disabled={!canContinue} onClick={() => setStep('instructions')} className="flex min-h-11 items-center justify-center gap-2 rounded-xl bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-60">
              Continue to Instructions <ChevronRight size={16} />
            </button>
          </div>
        </div>

        {!allCompleted && (
          <p className="mt-4 text-xs text-muted-foreground" role="status">Complete each check (a permission prompt or a clear result) to unlock the next step.</p>
        )}
        {blockedRequired && (
          <p className="mt-4 text-xs text-red-600" role="alert">A required check is blocked. Resolve it before continuing.</p>
        )}
      </div>
    </div>
  )

  return (
    <div className="mx-auto max-w-3xl">
      <header className="mb-8">
        <p className="text-xs font-semibold uppercase tracking-widest text-primary">Student workspace</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-tight">Before you begin</h1>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">Review these details before starting your attempt.</p>
      </header>

      <div className="rounded-2xl border border-border bg-card p-5">
        <button type="button" onClick={() => setStep('check')} className="flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-xs font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]">
          <ChevronLeft size={14} /> Back to readiness checks
        </button>

        <div className="mt-5 flex items-center gap-3">
          <div className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary"><FileText /></div>
          <div className="min-w-0">
            <h2 className="truncate font-semibold">{exam.title}</h2>
            <p className="text-sm text-muted-foreground">{exam.subject} · {exam.duration} minutes{schedule ? ` · ${schedule}` : ''}</p>
          </div>
        </div>

        <div className="mt-6 grid gap-3 rounded-xl bg-muted/50 p-4 text-sm sm:grid-cols-3">
          <div><p className="text-xs text-muted-foreground">Attempt number</p><p className="mt-1 font-semibold">Attempt {attemptNumber}</p></div>
          <div><p className="text-xs text-muted-foreground">Questions</p><p className="mt-1 font-semibold">{questionCount}</p></div>
          <div><p className="text-xs text-muted-foreground">Format</p><p className="mt-1 font-semibold">Multiple choice</p></div>
        </div>

        {retestStatus && (
          <p className="mt-4 flex items-center gap-2 text-sm">
            <Award size={16} className="text-primary" />
            Retest status: <span className="font-medium">{retestStatus === 'APPROVED' ? 'Approved — join an exam room when it becomes available' : retestStatus === 'PENDING' ? 'Pending approval' : 'Requested'}</span>
          </p>
        )}

        <h3 className="mt-8 font-semibold">Important rules</h3>
        <ul className="mt-3 grid gap-2 text-sm text-muted-foreground">
          <li className="flex items-start gap-2"><ListChecks size={16} className="mt-0.5 shrink-0 text-primary" />Select one option for each multiple-choice question.</li>
          <li className="flex items-start gap-2"><ListChecks size={16} className="mt-0.5 shrink-0 text-primary" />Use the question palette to move between questions; you can mark questions for review.</li>
          <li className="flex items-start gap-2"><Clock3 size={16} className="mt-0.5 shrink-0 text-primary" />The timer is authoritative and synced with the exam service. When it reaches zero your attempt closes.</li>
          <li className="flex items-start gap-2"><ShieldCheck size={16} className="mt-0.5 shrink-0 text-primary" />Proctoring is active during the attempt and monitors your session.</li>
          <li className="flex items-start gap-2"><Wifi size={16} className="mt-0.5 shrink-0 text-primary" />Answers are saved as you go. If you lose the connection, work continues and saves sync when you reconnect.</li>
        </ul>

        <p className="mt-6 text-xs leading-5 text-muted-foreground">
          This works on mobile too: use the palette button, the on-screen arrow keys, or Alt+ArrowLeft / Alt+ArrowRight to navigate and press M to mark a question for review.
        </p>

        {startError && <p className="mt-5 text-sm text-destructive" role="alert">{startError}</p>}
        {startMutation.isError && !startError && <p className="mt-5 text-sm text-destructive" role="alert">Unable to start the exam. Try again.</p>}

        <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <button type="button" onClick={() => navigate('/student/exams')} className="rounded-xl border border-border px-5 py-3 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]">Back to exams</button>
          <button type="button" disabled={startMutation.isPending} onClick={() => startMutation.mutate()} className="flex min-h-11 items-center justify-center gap-2 rounded-xl bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-60">
            {startMutation.isPending ? 'Starting your attempt…' : 'Start exam'}
          </button>
        </div>

        {typeof navigator !== 'undefined' && !navigator.onLine && (
          <p className="mt-4 flex items-center gap-2 text-xs text-muted-foreground" role="status">
            <XCircle size={14} className="text-amber-600" /> You are currently offline. Starting will still create your attempt on reconnect.
          </p>
        )}
      </div>
    </div>
  )
}