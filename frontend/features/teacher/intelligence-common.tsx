'use client'
import type { ReactNode } from 'react'
import type { OptionDistribution, QuestionAnalytics } from '@/types/analytics'
import type { OptionLabel } from '@/types/analytics'
import type { RiskLevel } from '@/types/proctor'

export const FLAG_LABELS: Record<string, string> = {
  LOW_SAMPLE_SIZE: 'Small sample',
  UNUSUALLY_LOW_ACCURACY: 'Low accuracy',
  UNUSUALLY_HIGH_ACCURACY: 'High accuracy',
  HIGH_UNANSWERED_RATE: 'Unanswered',
}

export const ATTENTION_ORDER: string[] = [
  'HIGH_UNANSWERED_RATE',
  'UNUSUALLY_LOW_ACCURACY',
  'LOW_SAMPLE_SIZE',
  'UNUSUALLY_HIGH_ACCURACY',
]

export function formatPercent(value: number | null | undefined): string {
  return typeof value === 'number' ? `${Math.round(value * 100) / 100}%` : '—'
}

export function formatDuration(seconds: number | null | undefined): string {
  if (seconds == null) return '—'
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return minutes > 0 ? `${minutes}m ${rest}s` : `${rest}s`
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}

export function MetricTile({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div className="rounded-2xl border border-border bg-card p-5">
      <p className="text-sm text-muted-foreground">{label}</p>
      <p className="mt-2 text-3xl font-semibold">{value}</p>
      {sub ? <p className="mt-1 text-xs text-muted-foreground">{sub}</p> : null}
    </div>
  )
}

const riskClasses: Record<RiskLevel, string> = {
  LOW: 'text-emerald-600 bg-emerald-500/10',
  MEDIUM: 'text-amber-600 bg-amber-500/10',
  HIGH: 'text-red-600 bg-red-500/10',
}

export function RiskBadge({ level, score }: { level: RiskLevel | undefined | null; score?: number | null }) {
  if (!level) return null
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${riskClasses[level]}`}>
      {level}
      {typeof score === 'number' ? <span>({score})</span> : null}
    </span>
  )
}

export function StatusPill({ status }: { status: string | undefined | null }) {
  if (!status) return null
  const className =
    status === 'DRAFT'
      ? 'text-slate-500 bg-slate-500/10'
      : status === 'PUBLISHED'
        ? 'text-blue-600 bg-blue-500/10'
        : status === 'ACTIVE'
          ? 'text-emerald-600 bg-emerald-500/10'
          : status === 'COMPLETED' || status === 'ENDED'
            ? 'text-violet-600 bg-violet-500/10'
            : 'text-amber-600 bg-amber-500/10'
  return (
    <span className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium capitalize ${className}`}>
      {status.toLowerCase()}
    </span>
  )
}

export function attentionFlags(question: QuestionAnalytics): string[] {
  return ATTENTION_ORDER.filter(flag => question.flags.includes(flag))
}

export function AttentionChips({ flags }: { flags: string[] }) {
  const visible = flags.filter(flag => FLAG_LABELS[flag])
  if (!visible.length) return null
  return (
    <div className="flex flex-wrap gap-1.5">
      {visible.map(flag => (
        <span key={flag} className="rounded-full bg-red-500/10 px-2 py-0.5 text-[11px] font-medium text-red-600">
          {FLAG_LABELS[flag]}
        </span>
      ))}
    </div>
  )
}

export function optionLetter(index: number): string {
  return String.fromCharCode(65 + index)
}

export function OptionBars({
  distribution,
  labels,
  answered,
}: {
  distribution: OptionDistribution[]
  labels: OptionLabel[]
  answered: number
}) {
  const countByOption = new Map<string, number>()
  for (const item of distribution) countByOption.set(item.optionId ?? '', item.count)
  const rows = labels.length
    ? [...labels].sort((a, b) => a.displayOrder - b.displayOrder)
    : distribution
        .filter(item => item.optionId)
        .map(item => ({
          questionId: '',
          optionId: item.optionId ?? '',
          text: '',
          displayOrder: distribution.findIndex(x => x.optionId === item.optionId),
        }))
  if (!rows.length) return <p className="py-6 text-center text-sm text-muted-foreground">No answer data for this question.</p>
  return (
    <div className="mt-4 space-y-3">
      {rows.map((row, index) => {
        const count = countByOption.get(row.optionId) ?? 0
        const percent = answered > 0 ? Math.round((count / answered) * 100) : 0
        return (
          <div key={`${row.optionId}-${index}`} className="flex items-center gap-3">
            <span className="grid size-7 shrink-0 place-items-center rounded-lg border border-border text-xs font-semibold">
              {optionLetter(index)}
            </span>
            <div className="min-w-0 flex-1">
              <div className="flex items-center justify-between gap-3">
                <p className="truncate text-sm">{row.text || 'Unanswered option'}</p>
                <p className="shrink-0 text-xs text-muted-foreground">
                  {count} {count === 1 ? 'answer' : 'answers'} · {percent}%
                </p>
              </div>
              <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full rounded-full bg-primary transition-[width]"
                  style={{ width: `${percent}%` }}
                />
              </div>
            </div>
          </div>
        )
      })}
    </div>
  )
}

export function EmptyState({ title, hint = '' }: { title: string; hint?: string }) {
  return (
    <div className="flex flex-col items-center gap-2 py-10 text-center">
      <p className="text-sm text-muted-foreground">{title}</p>
      {hint ? <p className="text-xs text-muted-foreground/70">{hint}</p> : null}
    </div>
  )
}