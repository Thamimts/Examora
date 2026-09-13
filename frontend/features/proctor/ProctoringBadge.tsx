import { MinusCircle, ShieldCheck, WifiOff } from 'lucide-react'
import type { ProctoringStatus } from '@/lib/proctorClient'

const STATUS_CONFIG: Record<ProctoringStatus, { label: string; className: string; icon: typeof ShieldCheck }> = {
  active: {
    label: 'Proctoring active',
    className: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-700',
    icon: ShieldCheck,
  },
  reconnecting: {
    label: 'Proctoring reconnecting',
    className: 'border-amber-500/30 bg-amber-500/10 text-amber-700 animate-pulse',
    icon: WifiOff,
  },
  offline: {
    label: 'Proctoring offline',
    className: 'border-red-500/30 bg-red-500/10 text-red-600',
    icon: WifiOff,
  },
  unavailable: {
    label: 'Proctoring unavailable',
    className: 'border-border bg-muted text-muted-foreground',
    icon: MinusCircle,
  },
}

export function ProctoringBadge({ status }: { status: ProctoringStatus }) {
  const config = STATUS_CONFIG[status]
  const Icon = config.icon
  return (
    <div role="status" className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold ${config.className}`}>
      <Icon size={14} />
      {config.label}
    </div>
  )
}