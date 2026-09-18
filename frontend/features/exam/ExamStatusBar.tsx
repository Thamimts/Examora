'use client'
import { useEffect, useState } from 'react'
import { Clock3 } from 'lucide-react'
import { cn, formatRemaining } from '@/lib/utils'
import { ProctoringBadge } from '@/features/proctor/ProctoringBadge'
import { FaceCountIndicator } from '@/features/proctor/FaceCountIndicator'
import type { ProctoringStatus } from '@/lib/proctorClient'
import type { FaceCountState } from '@/ai/faceCount'

export type CountdownTier = 'normal' | 'low' | 'critical' | 'expired'

function tierFor(seconds: number): CountdownTier {
  if (seconds <= 0) return 'expired'
  if (seconds <= 60) return 'critical'
  if (seconds <= 300) return 'low'
  return 'normal'
}

export function ExamStatusBar({ title, index, total, expiresAt, initialSeconds, syncKey, saveLabel, proctoringStatus, faceAnalysis, onCountdownZero, onTierChange }: {
  title: string
  index: number
  total: number
  expiresAt: string | null
  initialSeconds: number
  syncKey: number
  saveLabel: string | null
  proctoringStatus: ProctoringStatus
  faceAnalysis?: FaceCountState | null
  onCountdownZero: () => void
  onTierChange: (tier: CountdownTier) => void
}) {
  const [seconds, setSeconds] = useState(() => Math.max(0, initialSeconds))

  useEffect(() => {
    setSeconds(Math.max(0, initialSeconds))
    const target = expiresAt ? new Date(expiresAt).getTime() : Date.now() + initialSeconds * 1000
    const update = () => setSeconds(Math.max(0, Math.ceil((target - Date.now()) / 1000)))
    update()
    const timer = window.setInterval(update, 1000)
    return () => window.clearInterval(timer)
  }, [expiresAt, initialSeconds, syncKey])

  useEffect(() => {
    if (seconds <= 0) onCountdownZero()
  }, [seconds, onCountdownZero])

  useEffect(() => {
    onTierChange(tierFor(seconds))
  }, [seconds, onTierChange])

  const timerClasses = seconds <= 0
    ? 'bg-red-500/10 text-red-600'
    : seconds <= 60
      ? 'bg-red-500/10 text-red-600 animate-pulse'
      : seconds <= 300
        ? 'bg-amber-500/10 text-amber-700'
        : 'bg-muted text-muted-foreground'

  return (
    <div className="mb-6 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border bg-card p-4">
      <div className="min-w-0">
        <p className="text-xs font-semibold uppercase tracking-widest text-primary">Live attempt</p>
        <h1 className="mt-1 truncate text-lg font-semibold leading-7 sm:text-xl">{title}</h1>
        <p className="mt-1 text-xs text-muted-foreground">Question {index + 1} of {total}</p>
      </div>
      <div className="flex flex-wrap items-center gap-2 sm:gap-3">
        {saveLabel && <p className="text-xs text-muted-foreground" role="status">{saveLabel}</p>}
        <ProctoringBadge status={proctoringStatus} />
        {faceAnalysis && <FaceCountIndicator state={faceAnalysis} />}
        <div className={cn('flex items-center gap-2 rounded-xl px-3 py-2 text-sm font-semibold tabular-nums', timerClasses)} aria-live="polite">
          <Clock3 size={16} />
          {formatRemaining(seconds)}
        </div>
      </div>
    </div>
  )
}