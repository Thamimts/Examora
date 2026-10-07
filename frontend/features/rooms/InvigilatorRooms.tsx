'use client'
import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { DoorOpen, UserRound, Radio, MapPin, Armchair, ChevronLeft, Zap } from 'lucide-react'
import { centreRoomApi } from '@/services/centreApi'
import { useAuthStore } from '@/store/authStore'
import { useRoomFeed } from '@/hooks/useRoomFeed'
import type { ProctorUpdate, RiskLevel } from '@/types/proctor'
import { RiskBadge } from '@/features/teacher/intelligence-common'
import type { InvigilatorRoomSummary, RoomSeating, RoomSeatingStudent } from '@/types/enrolment'

const attemptPill: Record<string, string> = {
  STARTED: 'bg-emerald-500/10 text-emerald-600',
  SUBMITTED: 'bg-sky-500/10 text-sky-600',
  EXPIRED: 'bg-muted text-muted-foreground',
  PROCTOR_TERMINATED: 'bg-destructive/10 text-destructive',
}

function attemptLabel(status?: string | null): string {
  if (!status) return 'Not started'
  return status.replaceAll('_', ' ').toLowerCase()
}

function AttemptPill({ status }: { status?: string | null }) {
  if (!status || status === 'STARTED') {
    const label = status ? 'In progress' : 'Not started'
    return <span className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-semibold ${status ? 'bg-emerald-500/10 text-emerald-600' : 'bg-muted text-muted-foreground'}`}>{label}</span>
  }
  return <span className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-semibold capitalize ${attemptPill[status] ?? 'bg-muted text-muted-foreground'}`}>{attemptLabel(status)}</span>
}

function riskOf(level?: string | null): RiskLevel | null {
  if (level === 'HIGH' || level === 'MEDIUM' || level === 'LOW') return level
  return null
}

type Selected = { roomId: string; examId: string }
type Override = { status: string | null; riskLevel: string | null; riskScore: number | null }

export default function InvigilatorRooms() {
  const token = useAuthStore(state => state.token)
  const summaryQuery = useQuery({ queryKey: ['my-room-summaries'], queryFn: async () => (await centreRoomApi.mineSummary()).data.data, retry: 1 })
  const [selected, setSelected] = useState<Selected | null>(null)
  const [live, setLive] = useState<Array<{ update: ProctorUpdate; key: string }>>([])
  const [overrides, setOverrides] = useState<Record<string, Override>>({})

  const seatingQuery = useQuery({
    queryKey: ['room-seating', selected?.roomId, selected?.examId],
    queryFn: async () => (await centreRoomApi.seating(selected!.roomId, selected!.examId)).data.data,
    enabled: Boolean(selected),
    retry: 1,
  })

  useEffect(() => {
    setLive([])
    setOverrides({})
  }, [selected?.roomId, selected?.examId])

  const summaries = useMemo(() => {
    const rows: InvigilatorRoomSummary[] = []
    const seen = new Set<string>()
    for (const row of summaryQuery.data ?? []) {
      if (row.examId === null || row.examId === undefined || row.examId === '') {
        if (!seen.has(row.roomId)) { seen.add(row.roomId); rows.push(row) }
        continue
      }
      rows.push(row)
    }
    return rows
  }, [summaryQuery.data])

  const rooms = useMemo(() => {
    const map = new Map<string, InvigilatorRoomSummary[]>()
    for (const row of summaries) {
      const list = map.get(row.roomId) ?? []
      list.push(row)
      map.set(row.roomId, list)
    }
    return [...map.entries()]
      .map(([roomId, rows]) => ({ roomId, rows }))
      .sort((a, b) => a.rows[0].roomName.localeCompare(b.rows[0].roomName))
  }, [summaries])

  useRoomFeed(selected?.roomId ?? null, token, (update: ProctorUpdate) => {
    const key = `${update.attemptId ?? ''}-${update.sequence ?? ''}`
    setLive(prev => {
      if (prev.some(item => item.key === key && item.update.examId === update.examId)) return prev
      return [{ update, key }, ...prev].slice(0, 80)
    })
    if (update.student?.id) {
      setOverrides(prev => ({ ...prev, [update.student.id]: { status: update.status ?? null, riskLevel: update.riskLevel ?? null, riskScore: update.riskScore ?? null } }))
    }
  })

  const seating: RoomSeating | undefined = seatingQuery.data

  const roster: RoomSeatingStudent[] = useMemo(() => {
    return (seating?.students ?? []).map(s => {
      const o = overrides[s.studentId]
      return { ...s, status: o?.status ?? s.status ?? null, riskLevel: o?.riskLevel ?? s.riskLevel ?? null }
    })
  }, [seating, overrides])

  const highRisk = roster.filter(s => s.riskLevel === 'HIGH').length
  const activeNow = roster.filter(s => s.status === 'STARTED').length

  return (
    <div className="space-y-6">
      <header>
        <p className="text-xs font-semibold uppercase tracking-widest text-primary">Invigilator workspace</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-tight">Exam rooms</h1>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">Rooms you invigilate, their occupancy and risk state, and live room-scoped proctoring updates.</p>
      </header>

      {selected && (
        <div className="rounded-2xl border border-border bg-card">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border p-4">
            <div className="flex flex-wrap items-center gap-3">
              <button type="button" className="flex items-center gap-1 rounded-lg border border-border px-3 py-2 text-xs font-medium" onClick={() => setSelected(null)}><ChevronLeft size={13} />All rooms</button>
              <div className="flex items-center gap-3">
                <div className="grid size-10 place-items-center rounded-xl bg-primary/10 text-primary"><DoorOpen size={18} /></div>
                <div>
                  <h2 className="font-semibold">{seating?.roomName ?? 'Room'}</h2>
                  <p className="text-sm text-muted-foreground">{seating?.roomCode} · {seating?.centreName || seating?.centreId}</p>
                </div>
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-3 py-1 text-xs font-semibold text-primary"><Armchair size={13} />{roster.length}/{seating?.capacity ?? 0} seated</span>
              {activeNow > 0 && <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-3 py-1 text-xs font-semibold text-emerald-600"><Zap size={13} />{activeNow} active</span>}
              {highRisk > 0 && <span className="inline-flex items-center gap-1.5 rounded-full bg-destructive/10 px-3 py-1 text-xs font-semibold text-destructive">{highRisk} high-risk</span>}
            </div>
          </div>

          {seatingQuery.isPending ? (
            <div className="h-56 animate-pulse bg-muted/50" aria-busy="true" />
          ) : seatingQuery.isError ? (
            <div className="p-10 text-center text-sm text-destructive">Unable to load this room's seating for the selected exam.</div>
          ) : seating ? (
            <>
              <div className="grid gap-4 lg:grid-cols-3">
                <div className="lg:col-span-2 p-5">
                  <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                    <h3 className="flex items-center gap-2 text-sm font-semibold uppercase tracking-widest text-muted-foreground"><UserRound size={14} />Student roster</h3>
                    {seating.invigilatorName && <p className="text-xs text-muted-foreground">Invigilator: {seating.invigilatorName}</p>}
                  </div>
                  {roster.length === 0 ? (
                    <p className="rounded-xl bg-muted/60 p-8 text-center text-sm text-muted-foreground">No students seated in this room for the selected exam yet.</p>
                  ) : (
                    <div className="overflow-x-auto">
                      <table className="w-full text-sm">
                        <thead>
                          <tr className="border-b border-border text-left text-xs uppercase tracking-wider text-muted-foreground">
                            <th className="py-2 pr-3 font-medium">Seat</th>
                            <th className="py-2 pr-3 font-medium">Student</th>
                            <th className="py-2 pr-3 font-medium">Roll no</th>
                            <th className="py-2 pr-3 font-medium">Status</th>
                            <th className="py-2 font-medium">Risk</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                          {roster.map(student => (
                            <tr key={student.assignmentId}>
                              <td className="py-2.5 pr-3 text-muted-foreground">#{student.seatNumber}</td>
                              <td className="py-2.5 pr-3 font-medium">{student.studentName}</td>
                              <td className="py-2.5 pr-3 text-muted-foreground">{student.rollNumber || '—'}</td>
                              <td className="py-2.5 pr-3"><AttemptPill status={student.status} /></td>
                              <td className="py-2.5"><RiskBadge level={riskOf(student.riskLevel)} score={student.riskLevel == null ? null : overrides[student.studentId]?.riskScore} /></td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
                <div className="border-t border-border p-5 lg:border-l lg:border-t-0">
                  <div className="mb-3 flex items-center justify-between">
                    <p className="flex items-center gap-2 text-sm font-semibold uppercase tracking-widest text-muted-foreground"><Armchair size={14} />Seating plan</p>
                    <span className="text-xs text-muted-foreground">{roster.length}/{seating.capacity}</span>
                  </div>
                  <div className="grid grid-cols-3 gap-2 sm:grid-cols-4 lg:grid-cols-3 xl:grid-cols-4">
                    {Array.from({ length: Math.max(seating.capacity, 1) }, (_, i) => {
                      const student = roster.find(s => s.seatNumber === i + 1)
                      const tone = student?.riskLevel === 'HIGH' ? 'border-destructive/50 bg-destructive/10 text-destructive' : student?.riskLevel === 'MEDIUM' ? 'border-amber-500/50 bg-amber-500/10 text-amber-600' : student ? 'border-primary/40 bg-primary/5' : 'border-border bg-muted/40'
                      return (
                        <div key={i + 1} className={`rounded-xl border p-2.5 text-center ${tone}`}>
                          <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">Seat {i + 1}</p>
                          {student ? <p className="mt-1 truncate text-xs font-medium" title={student.studentName}>{student.studentName}</p> : <p className="mt-1 text-xs text-muted-foreground/60">—</p>}
                        </div>
                      )
                    })}
                  </div>
                </div>
              </div>
            </>
          ) : null}
        </div>
      )}

      {!selected && (
        <>
          {summaryQuery.isPending ? (
            <div className="grid gap-4 md:grid-cols-2" aria-busy="true">{Array.from({ length: 4 }, (_, i) => <div key={i} className="h-40 animate-pulse rounded-2xl bg-muted" />)}</div>
          ) : summaryQuery.isError ? (
            <div className="rounded-2xl border border-border bg-card p-10 text-center text-sm text-destructive">Unable to load your rooms. Please try again.</div>
          ) : rooms.length === 0 ? (
            <div className="rounded-2xl border border-border bg-card p-10 text-center text-sm text-muted-foreground">
              <DoorOpen className="mx-auto size-8 text-primary/50" />
              <p className="mt-3">No active rooms are assigned to you yet. Rooms appear here once an admin assigns you as invigilator.</p>
            </div>
          ) : (
            <div className="space-y-4">
              {rooms.map(({ roomId, rows }) => {
                const empty = rows.find(r => (r.examId === null || r.examId === undefined || r.examId === ''))
                const capacity = rows[0].capacity
                return (
                  <div key={roomId} className="rounded-2xl border border-border bg-card">
                    <div className="flex flex-wrap items-center gap-3 border-b border-border px-5 py-4">
                      <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-primary/10 text-primary"><DoorOpen size={18} /></div>
                      <div className="min-w-0 flex-1">
                        <h2 className="truncate font-semibold">{rows[0].roomName}</h2>
                        <p className="text-sm text-muted-foreground">{rows[0].roomCode} · {rows[0].centreName || rows[0].centreId}</p>
                      </div>
                      <span className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-3 py-1 text-xs font-semibold text-primary"><Armchair size={13} />{rows[0].occupied}/{capacity} seats</span>
                    </div>
                    <div className="divide-y divide-border">
                      {empty && (
                        <div className="flex items-center justify-between gap-3 px-5 py-3 text-sm text-muted-foreground">
                          <span>{capacity - rows[0].occupied} seats available — no exam is using this room yet.</span>
                          <span className="rounded-full bg-muted px-2.5 py-0.5 text-[11px] font-medium">Idle</span>
                        </div>
                      )}
                      {rows.filter(r => r.examId).map(row => (
                        <div key={`${row.roomId}-${row.examId}`} className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
                          <div className="min-w-0">
                            <p className="truncate font-medium">{row.examTitle}</p>
                            <div className="mt-1.5 flex flex-wrap items-center gap-2">
                              <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-2.5 py-0.5 text-[11px] font-semibold text-emerald-600"><Zap size={11} />{row.activeCount} active</span>
                              <span className="inline-flex items-center gap-1.5 rounded-full bg-muted px-2.5 py-0.5 text-[11px] font-semibold text-muted-foreground">{row.submittedCount} submitted</span>
                              <RiskBadge level={riskOf(row.riskLevel)} score={row.riskLevel ? row.riskScore : null} />
                            </div>
                          </div>
                          <div className="flex items-center gap-2">
                            {row.live ? (
                              <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-2.5 py-0.5 text-[11px] font-semibold text-emerald-600"><span className="size-1.5 animate-pulse rounded-full bg-emerald-500" />Live</span>
                            ) : (
                              <span className="rounded-full bg-muted px-2.5 py-0.5 text-[11px] font-medium text-muted-foreground">No live activity</span>
                            )}
                            <button type="button" className="rounded-xl bg-primary px-3.5 py-2 text-xs font-medium text-primary-foreground transition hover:opacity-90" onClick={() => setSelected({ roomId: row.roomId, examId: row.examId! })}>Monitor</button>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </>
      )}

      {selected && (
        <div className="rounded-2xl border border-border bg-card p-5">
          <div className="flex items-center justify-between gap-3">
            <h2 className="flex items-center gap-2 font-semibold"><Radio size={17} className="text-primary" />Live room activity</h2>
            <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-3 py-1 text-xs font-semibold text-emerald-600"><span className="size-1.5 rounded-full bg-emerald-500" />Live</span>
          </div>
          {live.length === 0 ? <p className="mt-4 rounded-xl bg-muted/60 p-6 text-center text-sm text-muted-foreground">Waiting for proctoring signals from students in this room.</p> : (
            <ul className="mt-4 divide-y divide-border">
              {live.map(({ update, key }) => (
                <li key={key} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm">
                  <div className="min-w-0"><p className="font-medium">{update.student?.name ?? 'Student'}</p><p className="truncate text-xs text-muted-foreground">{update.occurredAt ? new Date(update.occurredAt).toLocaleTimeString() : ''} · {update.status || 'ACTIVE'} · {update.eventCount} events</p></div>
                  <div className="flex items-center gap-2">
                    {update.latestEvent && <span className="rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium">{update.latestEvent.type}</span>}
                    <RiskBadge level={riskOf(update.riskLevel)} score={update.riskScore} />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <p className="flex items-center gap-1.5 text-xs text-muted-foreground"><MapPin size={12} /> Room activity is scoped to this room only; you need to be its invigilator to view it.</p>
    </div>
  )
}