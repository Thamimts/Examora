import { useEffect, useRef, useState } from 'react'
import { Flag, Check, AlertTriangle } from 'lucide-react'
import { cn, formatRemaining } from '@/lib/utils'
import type { Question } from '@/types'
import { questionState } from './QuestionPalette'

const STATUS_TEXT: Record<string, string> = {
  current: 'Current question',
  answered: 'Answered',
  unanswered: 'Unanswered',
  marked: 'Marked for review',
  'answered-marked': 'Answered and marked',
}

export function SubmissionReviewModal({ open, questions, currentIndex, answers, review, expiresAt, busy, error, onGoTo, onSubmit, onCancel }: {
  open: boolean
  questions: Question[]
  currentIndex: number
  answers: Record<string, string>
  review: Record<string, boolean>
  expiresAt: string | null
  busy: boolean
  error: string | null
  onGoTo: (index: number) => void
  onSubmit: () => void
  onCancel: () => void
}) {
  const dialogRef = useRef<HTMLDivElement | null>(null)
  const cancelRef = useRef<HTMLButtonElement | null>(null)

  const answered = questions.filter(question => Boolean(answers[question.id])).length
  const unanswered = questions.length - answered
  const marked = questions.filter(question => Boolean(review[question.id])).length
  const answeredMarked = questions.filter(question => Boolean(review[question.id] && answers[question.id])).length

  const [remainingSeconds, setRemainingSeconds] = useState(0)
  useEffect(() => {
    if (!open || !expiresAt) return
    const target = new Date(expiresAt).getTime()
    const update = () => setRemainingSeconds(Math.max(0, Math.ceil((target - Date.now()) / 1000)))
    update()
    const timer = window.setInterval(update, 1000)
    return () => window.clearInterval(timer)
  }, [open, expiresAt])

  useEffect(() => {
    if (!open) return
    const previouslyFocused = document.activeElement as HTMLElement | null
    cancelRef.current?.focus()

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        if (!busy) onCancel()
        return
      }
      if (event.key !== 'Tab' || !dialogRef.current) return
      const focusables = Array.from(dialogRef.current.querySelectorAll<HTMLElement>('button:not([disabled])'))
      if (focusables.length === 0) return
      const first = focusables[0]
      const last = focusables[focusables.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      previouslyFocused?.focus?.()
    }
  }, [open, busy, onCancel])

  if (!open) return null

  const stat = (label: string, value: number, tone: 'default' | 'warn' | 'ok') => (
    <div className={cn('rounded-xl p-3 text-center', tone === 'warn' ? 'bg-amber-500/10' : tone === 'ok' ? 'bg-emerald-500/10' : 'bg-muted')}>
      <b className={cn('text-2xl tabular-nums', tone === 'warn' ? 'text-amber-600' : tone === 'ok' ? 'text-emerald-600' : '')}>{value}</b>
      <p className="mt-1 text-xs text-muted-foreground">{label}</p>
    </div>
  )

  return (
    <div className="fixed inset-0 z-40 grid place-items-center bg-background/80 p-4 backdrop-blur-sm" role="dialog" aria-modal="true" aria-labelledby="submit-review-title" aria-describedby="submit-review-description">
      <div ref={dialogRef} className="flex max-h-[92vh] w-full max-w-xl flex-col overflow-hidden rounded-2xl border border-border bg-card shadow-2xl">
        <div className="border-b border-border p-5">
          <h2 id="submit-review-title" className="text-lg font-semibold">Review before submitting</h2>
          <p id="submit-review-description" className="mt-1 text-sm leading-5 text-muted-foreground">Question-by-question status for this attempt. The result is final after submission.</p>
        </div>
        <div className="overflow-y-auto p-5">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            {stat('Questions', questions.length, 'default')}
            {stat('Answered', answered, 'ok')}
            {stat('Unanswered', unanswered, unanswered > 0 ? 'warn' : 'default')}
            {stat('Marked for review', marked, marked > 0 ? 'warn' : 'default')}
            {stat('Answered + marked', answeredMarked, 'default')}
            {stat('Time left', remainingSeconds, remainingSeconds <= 60 ? 'warn' : 'default')}
          </div>
          <ul className="mt-5 divide-y divide-border">
            {questions.map((question, index) => {
              const state = questionState(question.id, index, currentIndex, answers, review)
              return (
                <li key={question.id} className="flex items-center gap-3 py-2.5">
                  <span className="w-6 shrink-0 text-sm font-semibold text-muted-foreground">{index + 1}</span>
                  <span className="min-w-0 flex-1 truncate text-sm">{question.text}</span>
                  <span className={cn('inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold', state === 'unanswered' && 'bg-muted text-muted-foreground', state === 'answered' && 'bg-emerald-500/10 text-emerald-700', state === 'answered-marked' && 'bg-emerald-500/10 text-emerald-700', state === 'marked' && 'bg-amber-500/10 text-amber-700', state === 'current' && 'bg-primary/10 text-primary')}>
                    {state === 'marked' && <Flag size={10} />}
                    {state === 'answered-marked' && <><Check size={10} /><Flag size={10} /></>}
                    {STATUS_TEXT[state]}
                  </span>
                  <button type="button" onClick={() => onGoTo(index)} className="shrink-0 rounded-lg border border-border px-2.5 py-1 text-xs font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">Jump</button>
                </li>
              )
            })}
          </ul>
          {error && <p className="mt-4 text-sm text-destructive" role="alert">{error}</p>}
        </div>
        <div className="border-t border-border p-5">
          <div className="mb-4 flex items-center gap-2 rounded-xl bg-muted/60 px-3 py-2 text-xs text-muted-foreground">
            {busy ? <>
              <AlertTriangle size={14} />Submitting your answers and computing your result…
            </> : <>
              <AlertTriangle size={14} />
              {unanswered > 0 ? `${unanswered} question${unanswered === 1 ? '' : 's'} are unanswered. ` : 'All questions have an answer. '}
              {marked > 0 && `${marked} question${marked === 1 ? '' : 's'} remain marked for review.`}
            </>}
          </div>
          <div className="flex flex-col-reverse justify-end gap-3 sm:flex-row">
            <button type="button" ref={cancelRef} disabled={busy} onClick={onCancel} className="rounded-xl border border-border px-4 py-2.5 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-50">Continue exam</button>
            <button type="button" disabled={busy} aria-busy={busy} onClick={onSubmit} className="rounded-xl bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-60">
              {busy ? 'Submitting...' : 'Submit exam'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}