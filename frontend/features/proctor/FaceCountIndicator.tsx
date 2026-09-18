'use client'
import { Cpu, Eye } from 'lucide-react'
import type { FaceCountState } from '@/ai/faceCount'

export function FaceCountIndicator({ state }: { state: FaceCountState }) {
  if (state.status === 'unavailable') {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-xl border border-border bg-muted px-3 py-2 text-xs font-medium text-muted-foreground">
        <CapacityOffIcon />
        <span>Camera analysis unavailable</span>
      </span>
    )
  }
  if (state.status === 'error') {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-xl border border-border bg-muted px-3 py-2 text-xs font-medium text-muted-foreground" title={state.detail}>
        <CapacityOffIcon />
        <span>Camera analysis unavailable</span>
      </span>
    )
  }

  const active = state.status === 'active'
  const label = active ? 'Camera analysis active' : state.status === 'initializing' ? 'Camera analysis starting' : 'Camera analysis paused'
  const dotClass = active ? 'bg-emerald-500' : 'bg-amber-500 animate-pulse'

  return (
    <span
      role="status"
      className="inline-flex items-center gap-1.5 rounded-xl border border-border bg-card px-3 py-2 text-xs text-muted-foreground"
      title="Face presence is analysed locally in the browser. All frames are discarded. Nothing is uploaded to the server."
    >
      <span className="grid size-3.5 place-items-center">
        <span className={`size-1.5 rounded-full ${dotClass}`} />
      </span>
      <Eye size={13} className="shrink-0" />
      <span>{label}</span>
      {active && state.faceCount !== null && <span className="font-semibold text-foreground">Face count: {state.faceCount}</span>}
      <span className="inline-flex items-center gap-1 text-muted-foreground">
        <Cpu size={11} />
        Detector: Client-side
      </span>
    </span>
  )
}

function CapacityOffIcon() {
  return <span aria-hidden className="inline-block size-2 rounded-full bg-muted-foreground/50" />
}