'use client'
import { useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { AlertTriangle, Award, CheckCircle2, Clock3, FileText, ListChecks, ShieldCheck, Wifi, XCircle } from 'lucide-react'
import { examApi } from '@/services/examApi'
import { questionApi } from '@/services/questionApi'
import { resultApi } from '@/services/resultApi'
import { retestApi } from '@/services/retestApi'

function CheckRow({ ok, label, detail }: { ok: boolean; label: string; detail: string }) {
  return (
    <li className="flex items-start gap-3">
      {ok ? <CheckCircle2 size={18} className="mt-0.5 shrink-0 text-emerald-600" /> : <AlertTriangle size={18} className="mt-0.5 shrink-0 text-amber-600" />}
      <div className="min-w-0">
        <p className="text-sm font-medium">{label}</p>
        <p className="mt-0.5 text-xs text-muted-foreground">{detail}</p>
      </div>
    </li>
  )
}

export function Preflight({ id }: { id: string }) {
  const navigate = useNavigate()
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

  const netAvailable = useMemo(() => typeof navigator === 'undefined' || navigator.onLine, [])
  const proctoringSupported = useMemo(() => typeof document !== 'undefined' && 'visibilityState' in document && typeof navigator !== 'undefined' && Boolean(navigator.mediaDevices?.enumerateDevices), [])
  const permissionsSupported = useMemo(() => typeof navigator !== 'undefined' && 'permissions' in navigator, [])

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

  return (
    <div className="mx-auto max-w-3xl">
      <header className="mb-8">
        <p className="text-xs font-semibold uppercase tracking-widest text-primary">Student workspace</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-tight">Before you begin</h1>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">Review these details before starting your attempt.</p>
      </header>

      <div className="rounded-2xl border border-border bg-card p-5">
        <div className="flex items-center gap-3">
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
            Retest status: <span className="font-medium">{retestStatus === 'APPROVED' ? 'Approved — this attempt is eligible' : retestStatus === 'PENDING' ? 'Pending approval' : 'Requested'}</span>
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

        <h3 className="mt-8 font-semibold">Preflight checks</h3>
        <ul className="mt-3 space-y-3">
          <CheckRow ok={netAvailable} label="Network connection available" detail={netAvailable ? 'You are online; saves will be sent to the exam service.' : 'You are offline. Choose Start only if you can work offline; answers will sync when you reconnect.'} />
          <CheckRow ok={proctoringSupported} label="Proctoring requirements satisfied" detail={proctoringSupported ? 'Your browser supports the session monitoring features used during the attempt.' : 'Your browser does not report support for all monitoring features. The attempt will still run, but some proctoring signals may be limited.'} />
          <CheckRow ok={permissionsSupported} label="Required browser permissions available" detail={permissionsSupported ? 'The Permissions API is available for the browser features this exam uses.' : 'Permission checks are not available; the attempt will still run.'} />
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

        {!netAvailable && (
          <p className="mt-4 flex items-center gap-2 text-xs text-muted-foreground" role="status">
            <XCircle size={14} className="text-amber-600" /> You are currently offline. Starting will still create your attempt on reconnect.
          </p>
        )}
      </div>
    </div>
  )
}