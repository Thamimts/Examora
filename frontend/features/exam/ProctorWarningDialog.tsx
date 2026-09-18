'use client'
import { AlertTriangle } from 'lucide-react'
import type { WarningLevel } from '@/types/proctor'

const COPY: Record<'WARNING_1' | 'WARNING_2', { title: string; body: string }> = {
  WARNING_1: {
    title: 'Warning 1 of 3',
    body: 'A proctoring rule was broken. Stay on the exam window, keep your camera and microphone available, and remain in fullscreen. You have two warnings left.',
  },
  WARNING_2: {
    title: 'Warning 2 of 3 — final warning',
    body: 'Another proctoring rule was broken. This is your last warning. The next violation will end your attempt and suspend your access to this exam.',
  },
}

export function ProctorWarningDialog({ open, level, warningCount, onAcknowledge }: {
  open: boolean
  level: WarningLevel
  warningCount: number
  onAcknowledge: () => void
}) {
  if (!open || (level !== 'WARNING_1' && level !== 'WARNING_2')) return null
  const copy = COPY[level]
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-background/80 p-4 backdrop-blur-sm" role="alertdialog" aria-modal="true" aria-labelledby="proctor-warning-title" aria-describedby="proctor-warning-body">
      <section className="w-full max-w-md rounded-2xl border border-amber-400/50 bg-card p-6 shadow-2xl">
        <div className="flex items-start gap-3">
          <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-amber-500/10 text-amber-600"><AlertTriangle size={20} /></div>
          <div className="min-w-0">
            <h2 id="proctor-warning-title" className="text-lg font-semibold">{copy.title}</h2>
            <p className="mt-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">Warning {warningCount} of 3</p>
          </div>
        </div>
        <p id="proctor-warning-body" className="mt-4 text-sm leading-6 text-muted-foreground">{copy.body}</p>
        <div className="mt-6 flex justify-end">
          <button type="button" autoFocus onClick={onAcknowledge} className="rounded-xl bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]">
            I understand
          </button>
        </div>
      </section>
    </div>
  )
}
