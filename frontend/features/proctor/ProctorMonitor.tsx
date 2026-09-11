'use client'
import { useCallback, useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { Card, Header } from '@/components/shared'
import { useAuthStore } from '@/store/authStore'
import { useMonitorStore } from '@/store/monitorStore'
import { useProctorFeed, type ConnectionState } from '@/hooks/useProctorFeed'
import { proctorApi } from '@/services/proctorApi'
import type { ProctorUpdate, RiskLevel } from '@/types/proctor'

const riskColor: Record<RiskLevel, string> = {
  LOW: 'text-emerald-600 bg-emerald-500/10',
  MEDIUM: 'text-amber-600 bg-amber-500/10',
  HIGH: 'text-red-600 bg-red-500/10',
}

const statusColor: Record<string, string> = {
  STARTED: 'text-emerald-600 bg-emerald-500/10',
  SUBMITTED: 'text-blue-600 bg-blue-500/10',
  EXPIRED: 'text-slate-500 bg-slate-500/10',
}

function ConnectionBadge({ state }: { state: ConnectionState }) {
  const label =
    state === 'connected'
      ? 'Live'
      : state === 'connecting'
        ? 'Connecting…'
        : 'Disconnected'
  const dot =
    state === 'connected'
      ? 'bg-emerald-500'
      : state === 'connecting'
        ? 'bg-amber-500 animate-pulse'
        : 'bg-slate-400'
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-border px-3 py-1 text-xs font-medium">
      <span className={`size-1.5 rounded-full ${dot}`} />
      {label}
    </span>
  )
}

export function ProctorMonitor() {
  const { id: examId = '' } = useParams()
  const token = useAuthStore((s) => s.token)
  const { examTitle, attempts, setMonitorData, mergeUpdate } = useMonitorStore()

  const onEvent = useCallback(
    (update: ProctorUpdate) => {
      mergeUpdate(update)
    },
    [mergeUpdate],
  )

  const { connectionState } = useProctorFeed(examId || null, token, onEvent)

  useEffect(() => {
    if (!examId) return
    let cancelled = false
    proctorApi
      .monitor(examId)
      .then((res) => {
        if (!cancelled) setMonitorData(res.data.data)
      })
      .catch(() => {
        /* REST failure – WebSocket will still push updates */
      })
    return () => {
      cancelled = true
    }
  }, [examId, setMonitorData])

  const activeCount = attempts.filter((a) => a.activeNow).length
  const highRiskCount = attempts.filter((a) => a.riskLevel === 'HIGH').length

  return (
    <>
      <Header
        title={examTitle || 'Live proctoring monitor'}
        description="Real-time session health and risk signals from the active exam."
      />

      <div className="mb-5 flex items-center justify-between">
        <div className="flex gap-4 text-sm text-muted-foreground">
          <span>
            {attempts.length} attempt{attempts.length !== 1 ? 's' : ''}
          </span>
          <span>
            {activeCount} active
          </span>
          {highRiskCount > 0 && (
            <span className="text-red-600">
              {highRiskCount} high risk
            </span>
          )}
        </div>
        <ConnectionBadge state={connectionState} />
      </div>

      {attempts.length === 0 ? (
        <Card>
          <p className="py-12 text-center text-sm text-muted-foreground">
            No attempts yet. Updates will appear here in real time.
          </p>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {attempts.map((attempt) => (
            <AttemptCard key={attempt.attemptId} attempt={attempt} />
          ))}
        </div>
      )}
    </>
  )
}

function AttemptCard({ attempt }: { attempt: import('@/types/proctor').ProctorAttempt }) {
  const {
    student,
    status,
    activeNow,
    riskLevel,
    riskScore,
    eventCount,
    latestProctorEvent,
    lastActivityAt,
  } = attempt

  return (
    <Card className="flex flex-col gap-3">
      <div className="flex items-start justify-between">
        <div className="min-w-0">
          <p className="truncate font-medium">{student.name}</p>
          <p className="truncate text-xs text-muted-foreground">{student.email}</p>
        </div>
        <span
          className={`shrink-0 rounded-full px-2.5 py-0.5 text-xs font-medium ${statusColor[status] ?? 'bg-muted text-muted-foreground'}`}
        >
          {status}
        </span>
      </div>

      <div className="grid grid-cols-3 gap-2 text-center text-xs">
        <div className="rounded-lg bg-muted p-2">
          <p className="text-muted-foreground">Risk</p>
          <p className={`mt-0.5 font-semibold ${riskColor[riskLevel]?.split(' ')[0] ?? ''}`}>
            {riskLevel}
          </p>
        </div>
        <div className="rounded-lg bg-muted p-2">
          <p className="text-muted-foreground">Score</p>
          <p className="mt-0.5 font-semibold">{riskScore}</p>
        </div>
        <div className="rounded-lg bg-muted p-2">
          <p className="text-muted-foreground">Events</p>
          <p className="mt-0.5 font-semibold">{eventCount}</p>
        </div>
      </div>

      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span className={activeNow ? 'text-emerald-600 font-medium' : ''}>
          {activeNow ? 'Active now' : 'Inactive'}
        </span>
        {lastActivityAt && (
          <time>{new Date(lastActivityAt).toLocaleTimeString()}</time>
        )}
      </div>

      {latestProctorEvent && (
        <div className="rounded-lg border border-border p-2.5 text-xs">
          <p className="font-medium">
            {latestProctorEvent.type.replaceAll('_', ' ')}
          </p>
          <p className="mt-0.5 text-muted-foreground">
            {new Date(latestProctorEvent.occurredAt).toLocaleString()}
          </p>
        </div>
      )}
    </Card>
  )
}
