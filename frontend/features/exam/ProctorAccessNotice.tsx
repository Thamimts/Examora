'use client'
import { AlertCircle, CheckCircle2, Clock3, RefreshCw, ShieldAlert, XCircle } from 'lucide-react'
import type { ExamAccessStatus, WarningLevel } from '@/types/proctor'

type Variant = 'terminated' | 'finalizing' | 'pending' | 'rejected' | 'approved'

export function ProctorAccessNotice({
  examTitle,
  warningCount,
  warningLevel,
  attemptStatus,
  accessStatus,
  hasResult,
  retestBusy,
  retestError,
  onRequestRetest,
  onRefresh,
  onBackToExams,
  onViewResult,
  onGoToExamCenter,
}: {
  examTitle: string
  warningCount: number
  warningLevel: WarningLevel
  attemptStatus: string | null
  accessStatus: ExamAccessStatus
  hasResult: boolean
  retestBusy: boolean
  retestError: string | null
  onRequestRetest: () => void
  onRefresh: () => void
  onBackToExams: () => void
  onViewResult: () => void
  onGoToExamCenter: () => void
}) {
  const variant: Variant = accessStatus === 'RETEST_PENDING'
    ? 'pending'
    : accessStatus === 'RETEST_REJECTED'
      ? 'rejected'
      : accessStatus === 'RETEST_APPROVED'
        ? 'approved'
        : accessStatus === 'SUSPENDED' || attemptStatus === 'PROCTOR_TERMINATED'
          ? 'terminated'
          : 'finalizing'

  const tone = variant === 'approved'
    ? { ring: 'border-emerald-500/30', chip: 'bg-emerald-500/10 text-emerald-600' }
    : variant === 'pending'
      ? { ring: 'border-amber-400/40', chip: 'bg-amber-500/10 text-amber-600' }
      : { ring: 'border-destructive/40', chip: 'bg-destructive/10 text-destructive' }

  const heading: Record<Variant, string> = {
    terminated: 'Your attempt was ended',
    finalizing: 'Your attempt is being ended',
    pending: 'Retest request pending review',
    rejected: 'Retest request rejected',
    approved: 'Retest approved',
  }

  const body: Record<Variant, string> = {
    terminated: 'Proctoring recorded three qualifying violations, so your access to this exam is currently suspended. Your submitted work has not been graded into a new result. Request a retest to continue.',
    finalizing: 'Proctoring recorded the maximum number of violations. The exam service is finalising your attempt — this usually takes a moment.',
    pending: 'Your retest request is waiting for admin review.',
    rejected: 'Your retest request was rejected.',
    approved: 'Your retest request has been approved. Join the exam room when it becomes available.',
  }

  return (
    <div className="mx-auto max-w-2xl">
      <section className={`rounded-2xl border bg-card p-6 shadow-sm ${tone.ring}`}>
        <div role={variant === 'approved' || variant === 'pending' ? 'status' : 'alert'} className="text-center">
          <div className={`mx-auto grid size-12 place-items-center rounded-full ${tone.chip}`}>
            {variant === 'approved' ? <CheckCircle2 size={22} />
              : variant === 'pending' ? <Clock3 size={22} />
              : variant === 'finalizing' ? <RefreshCw size={22} />
              : variant === 'rejected' ? <XCircle size={22} />
              : <ShieldAlert size={22} />}
          </div>
          <h1 className="mt-4 text-xl font-semibold tracking-tight">{heading[variant]}</h1>
          <p className="mt-1 text-sm text-muted-foreground">{examTitle}</p>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-muted-foreground">{body[variant]}</p>

          {(variant === 'terminated' || variant === 'rejected') && (
            <p className="mx-auto mt-4 inline-flex items-center gap-2 rounded-full border border-border bg-muted/60 px-3 py-1 text-xs font-medium text-muted-foreground">
              <AlertCircle size={13} /> {warningLevel === 'WARNING_3' ? '3 of 3 warnings used' : `${warningCount} of 3 warnings used`}
            </p>
          )}
        </div>

        {retestError && <p className="mt-5 text-center text-sm text-destructive" role="alert">{retestError}</p>}

        <div className="mt-7 flex flex-wrap justify-center gap-3">
          {variant === 'approved' && (
            <button type="button" onClick={onGoToExamCenter} className="rounded-xl bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]">
              Go to Exam Center
            </button>
          )}
          {(variant === 'terminated' || variant === 'rejected') && (
            <button type="button" disabled={retestBusy} onClick={onRequestRetest} className="rounded-xl bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-60">
              {retestBusy ? 'Sending request…' : 'Request retest'}
            </button>
          )}
          {variant === 'finalizing' && (
            <button type="button" onClick={onRefresh} className="flex items-center gap-2 rounded-xl bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]">
              <RefreshCw size={15} /> Refresh status
            </button>
          )}
          {hasResult && (
            <button type="button" onClick={onViewResult} className="rounded-xl border border-border px-5 py-2.5 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]">
              View result
            </button>
          )}
          <button type="button" onClick={onBackToExams} className="rounded-xl border border-border px-5 py-2.5 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]">
            Back to exams
          </button>
        </div>
      </section>
    </div>
  )
}
