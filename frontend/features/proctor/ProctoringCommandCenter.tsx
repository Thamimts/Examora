'use client'
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  Clock3,
  DoorOpen,
  Loader2,
  MonitorUp,
  Radio,
  RefreshCw,
  Search,
  ShieldCheck,
  Timer,
  Users,
  XCircle,
} from 'lucide-react'
import { Card, Header } from '@/components/shared'
import { useToast } from '@/components/feedback'
import { useAuthStore } from '@/store/authStore'
import { useProctorFeed, type ConnectionState } from '@/hooks/useProctorFeed'
import { proctorApi } from '@/services/proctorApi'
import { examRoomApi } from '@/services/examRoomApi'
import { TeacherScreenViewer, type ViewerState } from '@/features/exam/TeacherScreenViewer'
import type {
  CommandCenterData,
  CommandCenterExam,
  CommandCenterRoom,
  CommandCenterStudent,
  ExamAccessStatus,
  ProctorEvent,
  ProctorUpdate,
  RiskLevel,
  WarningLevel,
} from '@/types/proctor'

type FilterKey = 'ALL' | 'ACTIVE' | 'WARNING' | 'HIGH_RISK' | 'OFFLINE' | 'TERMINATED' | 'SCREEN_NOT_SHARED' | 'CAMERA_OFF'

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL', label: 'All' },
  { key: 'ACTIVE', label: 'Active' },
  { key: 'WARNING', label: 'Warning' },
  { key: 'HIGH_RISK', label: 'High risk' },
  { key: 'OFFLINE', label: 'Offline' },
  { key: 'TERMINATED', label: 'Terminated' },
  { key: 'SCREEN_NOT_SHARED', label: 'Screen not shared' },
  { key: 'CAMERA_OFF', label: 'Camera off' },
]

const warningPill: Record<WarningLevel, string> = {
  NONE: 'bg-slate-500/10 text-slate-600',
  WARNING_1: 'bg-amber-500/10 text-amber-600',
  WARNING_2: 'bg-orange-500/10 text-orange-600',
  WARNING_3: 'bg-red-500/10 text-red-600',
}

const riskPill: Record<RiskLevel, string> = {
  LOW: 'bg-emerald-500/10 text-emerald-600',
  MEDIUM: 'bg-amber-500/10 text-amber-600',
  HIGH: 'bg-red-500/10 text-red-600',
}

const accessPill: Record<ExamAccessStatus, string> = {
  ELIGIBLE: 'bg-emerald-500/10 text-emerald-600',
  SUSPENDED: 'bg-red-500/10 text-red-600',
  RETEST_PENDING: 'bg-amber-500/10 text-amber-600',
  RETEST_APPROVED: 'bg-sky-500/10 text-sky-600',
  RETEST_REJECTED: 'bg-rose-500/10 text-rose-600',
}

const attemptStatusPill: Record<string, string> = {
  STARTED: 'bg-emerald-500/10 text-emerald-600',
  SUBMITTED: 'bg-blue-500/10 text-blue-600',
  EXPIRED: 'bg-slate-500/10 text-slate-600',
  PROCTOR_TERMINATED: 'bg-red-500/10 text-red-600',
}

const examStatusPill: Record<string, string> = {
  PUBLISHED: 'bg-emerald-500/10 text-emerald-600',
  DRAFT: 'bg-slate-500/10 text-slate-600',
  CLOSED: 'bg-red-500/10 text-red-600',
}

const roomStatusPill: Record<CommandCenterRoom['status'], string> = {
  WAITING: 'bg-amber-500/10 text-amber-600',
  ACTIVE: 'bg-emerald-500/10 text-emerald-600',
  ENDED: 'bg-slate-500/10 text-slate-600',
}

function timeAt(value: string | null | undefined) {
  if (!value) return '—'
  return new Date(value).toLocaleTimeString()
}

function dateTimeAt(value: string | null | undefined) {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}

function ConnectionBadge({ state }: { state: ConnectionState }) {
  const label = state === 'connected' ? 'Live' : state === 'connecting' ? 'Connecting…' : 'Offline'
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

function mergeUpdate(prev: CommandCenterData, update: ProctorUpdate): CommandCenterData {
  const roster = prev.roster.map((s) => {
    if (s.attemptId !== update.attemptId) return s
    const latestEvent = update.latestEvent
    return {
      ...s,
      attemptStatus: update.status,
      activeNow: update.status === 'STARTED',
      warningCount: update.warningCount,
      warningLevel: update.warningLevel,
      accessStatus: update.accessStatus,
      riskLevel: update.riskLevel,
      riskScore: update.riskScore,
      eventCount: update.eventCount,
      latestProctorEvent: latestEvent ?? s.latestProctorEvent,
      lastActivityAt: update.occurredAt,
      cameraOff: latestEvent ? (latestEvent.type === 'CAMERA_OFF' ? true : null) : s.cameraOff,
      fullscreenExited: latestEvent ? (latestEvent.type === 'FULLSCREEN_EXIT' ? true : null) : s.fullscreenExited,
    }
  })
  const exam = computeExam(prev.exam, roster)
  return { ...prev, roster, exam }
}

function computeExam(exam: CommandCenterExam, roster: CommandCenterStudent[]): CommandCenterExam {
  const totalStudents = roster.length
  const joinedStudents = roster.filter((s) => s.roomId).length
  const activeAttempts = roster.filter((s) => s.activeNow).length
  const submittedAttempts = roster.filter((s) => s.attemptStatus === 'SUBMITTED').length
  const terminatedAttempts = roster.filter((s) => s.attemptStatus === 'PROCTOR_TERMINATED').length
  const totalWarnings = roster.reduce((sum, s) => sum + s.warningCount, 0)
  return {
    ...exam,
    totalStudents,
    joinedStudents,
    activeAttempts,
    submittedAttempts,
    offlineParticipants: Math.max(0, joinedStudents - activeAttempts),
    totalWarnings,
    terminatedAttempts,
  }
}

export function ProctoringCommandCenter() {
  const { id: examId = '' } = useParams()
  const token = useAuthStore((s) => s.token)
  const queryClient = useQueryClient()
  const toast = useToast()
  const [roomFilter, setRoomFilter] = useState<string>('ALL')
  const [filter, setFilter] = useState<FilterKey>('ALL')
  const [search, setSearch] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [screenStates, setScreenStates] = useState<Record<string, ViewerState>>({})
  const sequencesRef = useRef<Map<string, number>>(new Map())
  const wasConnectedRef = useRef(false)

  const { data, isPending, isError, refetch } = useQuery({
    queryKey: ['command-center', examId],
    queryFn: async () => (await proctorApi.commandCenter(examId)).data.data,
    refetchInterval: 15000,
    retry: 1,
  })

  const onEvent = useCallback(
    (update: ProctorUpdate) => {
      const key = ['command-center', examId]
      let matched = false
      queryClient.setQueryData<CommandCenterData>(key, (prev) => {
        if (!prev) return prev
        matched = prev.roster.some((s) => s.attemptId === update.attemptId)
        if (!matched) return prev
        const last = sequencesRef.current.get(update.attemptId)
        if (last !== undefined && update.sequence <= last) return prev
        sequencesRef.current.set(update.attemptId, update.sequence)
        return mergeUpdate(prev, update)
      })
      if (!matched) {
        queryClient.invalidateQueries({ queryKey: key })
      }
    },
    [examId, queryClient],
  )

  const { connectionState } = useProctorFeed(examId || null, token, onEvent)

  useEffect(() => {
    if (connectionState === 'connected' && !wasConnectedRef.current) {
      wasConnectedRef.current = true
      queryClient.invalidateQueries({ queryKey: ['command-center', examId] })
    }
    if (connectionState !== 'connected') wasConnectedRef.current = false
  }, [connectionState, examId, queryClient])

  const roster = useMemo(() => data?.roster ?? [], [data])
  const rooms = useMemo(() => data?.rooms ?? [], [data])

  useEffect(() => {
    if (!selectedId && roster.length > 0) {
      const preferred = roster.find((s) => s.activeNow) ?? roster[0]
      setSelectedId(preferred.studentId)
    }
  }, [selectedId, roster])

  useEffect(() => {
    if (selectedId && !roster.some((s) => s.studentId === selectedId)) {
      setSelectedId(null)
    }
  }, [selectedId, roster])

  const selected = useMemo(() => roster.find((s) => s.studentId === selectedId) ?? null, [roster, selectedId])

  const screenFilterMatches = useCallback(
    (student: CommandCenterStudent) => {
      const state = screenStates[student.studentId]
      return state !== undefined && state !== 'live'
    },
    [screenStates],
  )

  const visible = useMemo(() => {
    const term = search.trim().toLowerCase()
    return roster.filter((s) => {
      if (roomFilter !== 'ALL' && s.roomId !== roomFilter) return false
      switch (filter) {
        case 'ACTIVE':
          if (!s.activeNow) return false
          break
        case 'WARNING':
          if (s.warningCount < 1) return false
          break
        case 'HIGH_RISK':
          if (s.riskLevel !== 'HIGH') return false
          break
        case 'OFFLINE':
          if (s.activeNow) return false
          break
        case 'TERMINATED':
          if (s.attemptStatus !== 'PROCTOR_TERMINATED') return false
          break
        case 'SCREEN_NOT_SHARED':
          if (!screenFilterMatches(s)) return false
          break
        case 'CAMERA_OFF':
          if (s.cameraOff !== true) return false
          break
        case 'ALL':
          break
      }
      if (!term) return true
      return (
        s.studentName.toLowerCase().includes(term) ||
        s.studentEmail.toLowerCase().includes(term) ||
        (s.roomCode ?? '').toLowerCase().includes(term)
      )
    })
  }, [roster, roomFilter, filter, search, screenFilterMatches])

  const sortedRooms = useMemo(() => {
    const order: Record<CommandCenterRoom['status'], number> = { ACTIVE: 0, WAITING: 1, ENDED: 2 }
    return [...rooms].sort((a, b) => order[a.status] - order[b.status] || a.roomCode.localeCompare(b.roomCode))
  }, [rooms])

  const handleViewerState = useCallback((studentId: string, state: ViewerState) => {
    setScreenStates((current) => (current[studentId] === state ? current : { ...current, [studentId]: state }))
  }, [])

  const endRoomMutation = useMutation({
    mutationFn: (roomId: string) => examRoomApi.end(roomId),
    onSuccess: () => {
      toast.success('The exam room has been ended for all students.')
      queryClient.invalidateQueries({ queryKey: ['command-center', examId] })
    },
    onError: () => {
      toast.error('Unable to end the room. It may already be ended or the exam is not active.')
    },
  })

  if (isPending) {
    return (
      <>
        <Header title="Proctoring command center" description="Loading the live examination dashboard…" />
        <div className="space-y-3" aria-busy="true">
          {[1, 2, 3].map((item) => (
            <div key={item} className="h-16 animate-pulse rounded-xl bg-muted" />
          ))}
        </div>
      </>
    )
  }

  if (isError || !data) {
    return (
      <>
        <Header title="Proctoring command center" description="Live examination monitoring, security and risk." />
        <Card>
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3 p-6">
            <p className="text-sm text-destructive">Unable to load the command center.</p>
            <button
              type="button"
              onClick={() => refetch()}
              className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm"
            >
              <RefreshCw size={14} /> Retry
            </button>
          </div>
        </Card>
      </>
    )
  }

  return (
    <>
      <Header
        title={data.exam.examTitle || 'Proctoring command center'}
        description="Rooms, student media states, risk, warnings and real-time proctoring events."
      />

      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${examStatusPill[data.exam.status] ?? 'bg-muted text-muted-foreground'}`}>
            {data.exam.status.replaceAll('_', ' ')}
          </span>
          <span className="inline-flex items-center gap-1.5 text-sm text-muted-foreground">
            <Timer size={14} /> {data.exam.duration} min
          </span>
          <button
            type="button"
            onClick={() => {
              sequencesRef.current.clear()
              refetch()
            }}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-xs font-medium text-muted-foreground transition hover:bg-muted"
          >
            <RefreshCw size={13} /> Refresh
          </button>
        </div>
        <ConnectionBadge state={connectionState} />
      </div>

      <div className="mb-6 grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-7">
        <MetricCard label="Total students" value={data.exam.totalStudents} icon={Users} tone="default" />
        <MetricCard label="Joined" value={data.exam.joinedStudents} icon={DoorOpen} tone="default" />
        <MetricCard label="Active attempts" value={data.exam.activeAttempts} icon={Activity} tone="good" />
        <MetricCard label="Submitted" value={data.exam.submittedAttempts} icon={CheckCircle2} tone="ok" />
        <MetricCard label="Offline" value={data.exam.offlineParticipants} icon={Radio} tone="warn" />
        <MetricCard label="Warnings" value={data.exam.totalWarnings} icon={AlertTriangle} tone="warn" />
        <MetricCard label="Terminated" value={data.exam.terminatedAttempts} icon={XCircle} tone="bad" />
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => setRoomFilter('ALL')}
          className={`rounded-full border px-3 py-1.5 text-xs font-medium transition ${roomFilter === 'ALL' ? 'border-primary bg-primary text-primary-foreground' : 'border-border hover:bg-muted'}`}
        >
          All rooms
        </button>
        {sortedRooms.map((room) => (
          <button
            key={room.roomId}
            type="button"
            onClick={() => setRoomFilter(roomFilter === room.roomId ? 'ALL' : room.roomId)}
            className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-medium transition ${roomFilter === room.roomId ? 'border-primary bg-primary text-primary-foreground' : 'border-border hover:bg-muted'}`}
          >
            <span className={`size-1.5 rounded-full ${room.status === 'ACTIVE' ? 'bg-emerald-500' : room.status === 'WAITING' ? 'bg-amber-500' : 'bg-slate-400'}`} />
            {room.roomCode}
            <span className={`rounded-full px-1.5 py-0.5 ${roomFilter === room.roomId ? 'bg-primary-foreground/15 text-primary-foreground' : 'bg-muted text-muted-foreground'}`}>
              {room.memberCount}
            </span>
          </button>
        ))}
      </div>

      <div className="mb-5 flex flex-wrap items-center gap-2">
        {FILTERS.map((item) => {
          const count =
            item.key === 'ALL'
              ? roster.length
              : roster.filter((s) =>
                  item.key === 'ACTIVE'
                    ? s.activeNow
                    : item.key === 'WARNING'
                      ? s.warningCount >= 1
                      : item.key === 'HIGH_RISK'
                        ? s.riskLevel === 'HIGH'
                        : item.key === 'OFFLINE'
                          ? !s.activeNow
                          : item.key === 'TERMINATED'
                            ? s.attemptStatus === 'PROCTOR_TERMINATED'
                            : item.key === 'CAMERA_OFF'
                              ? s.cameraOff === true
                              : screenFilterMatches(s),
                ).length
          return (
            <button
              key={item.key}
              type="button"
              onClick={() => setFilter(item.key === filter ? 'ALL' : item.key)}
              className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-medium transition ${filter === item.key ? 'border-primary bg-primary text-primary-foreground' : 'border-border hover:bg-muted'}`}
            >
              {item.label}
              <span className={`rounded-full px-1.5 text-[11px] ${filter === item.key ? 'bg-primary-foreground/15 text-primary-foreground' : 'bg-muted text-muted-foreground'}`}>
                {count}
              </span>
            </button>
          )
        })}
        <div className="relative ml-auto">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
          <input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search name, email or room"
            aria-label="Search roster"
            className="w-56 rounded-xl border border-border bg-background py-2 pl-9 pr-3 text-sm outline-none transition focus-visible:ring-2 focus-visible:ring-ring"
          />
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[320px_minmax(0,1fr)_360px]">
        <Card className="flex max-h-[calc(100vh-16rem)] flex-col overflow-hidden">
          <div className="border-b border-border px-4 py-3">
            <p className="text-sm font-semibold">
              Roster <span className="text-muted-foreground">({visible.length} of {roster.length})</span>
            </p>
          </div>
          <div className="flex-1 overflow-y-auto">
            {visible.length === 0 ? (
              <p className="px-4 py-8 text-center text-sm text-muted-foreground">No students match this view.</p>
            ) : (
              <ul className="divide-y divide-border">
                {visible.map((student) => (
                  <RosterRow
                    key={student.studentId}
                    student={student}
                    screenState={screenStates[student.studentId]}
                    active={student.studentId === selectedId}
                    onSelect={() => setSelectedId(student.studentId)}
                  />
                ))}
              </ul>
            )}
          </div>
        </Card>

        <Card className="flex flex-col gap-4">
          {selected ? (
            <>
              <SelectedStudentPanel student={selected} endRoomMutation={endRoomMutation} />
              <ScreenSection student={selected} onViewerState={handleViewerState} />
            </>
          ) : (
            <div className="flex flex-col items-center justify-center gap-2 py-16 text-center">
              <MonitorUp size={24} className="text-muted-foreground" />
              <p className="text-sm text-muted-foreground">Select a student from the roster to inspect.</p>
            </div>
          )}
        </Card>

        <Card className="flex flex-col overflow-hidden">
          <TimelineCard attemptId={selected?.attemptId ?? null} studentName={selected?.studentName ?? null} riskScore={selected?.riskScore} eventCount={selected?.eventCount ?? 0} />
        </Card>
      </div>
    </>
  )
}

function MetricCard({ label, value, icon: Icon, tone }: {
  label: string
  value: number
  icon: typeof Users
  tone: 'default' | 'good' | 'ok' | 'warn' | 'bad'
}) {
  const iconClass =
    tone === 'good' ? 'bg-emerald-500/10 text-emerald-600'
      : tone === 'ok' ? 'bg-blue-500/10 text-blue-600'
        : tone === 'warn' ? 'bg-amber-500/10 text-amber-600'
          : tone === 'bad' ? 'bg-red-500/10 text-red-600'
            : 'bg-muted text-muted-foreground'
  return (
    <Card className="flex items-center gap-3 p-3">
      <span className={`flex size-9 shrink-0 items-center justify-center rounded-xl ${iconClass}`}>
        <Icon size={16} />
      </span>
      <div className="min-w-0">
        <p className="truncate text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
        <p className="text-xl font-semibold">{value}</p>
      </div>
    </Card>
  )
}

function RosterRow({ student, screenState, active, onSelect }: {
  student: CommandCenterStudent
  screenState?: ViewerState
  active: boolean
  onSelect: () => void
}) {
  const warning = student.warningCount >= 1
  const terminated = student.attemptStatus === 'PROCTOR_TERMINATED'
  const dot = terminated
    ? 'bg-red-500'
    : student.activeNow
      ? 'bg-emerald-500'
      : student.attemptStatus === 'SUBMITTED'
        ? 'bg-blue-500'
        : 'bg-slate-400'
  return (
    <li>
      <button
        type="button"
        onClick={onSelect}
        className={`flex w-full items-center gap-3 px-4 py-3 text-left transition hover:bg-muted ${active ? 'bg-muted/70' : ''}`}
      >
        <span className={`size-2 shrink-0 rounded-full ${dot} ${student.activeNow ? 'animate-pulse' : ''}`} />
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-2">
            <span className="truncate text-sm font-medium">{student.studentName}</span>
            {warning && (
              <span className={`shrink-0 rounded-full px-1.5 py-0.5 text-[10px] font-semibold ${warningPill[student.warningLevel]}`}>
                {student.warningCount}
              </span>
            )}
          </span>
          <span className="mt-0.5 flex items-center gap-2 text-[11px] text-muted-foreground">
            {student.roomCode && <span className="truncate">{student.roomCode}</span>}
            <span className={`shrink-0 rounded-full px-1.5 py-0.5 font-medium ${riskPill[student.riskLevel]}`}>
              {student.riskLevel}
            </span>
            {screenState && (
              <span className="inline-flex items-center gap-1">
                <MonitorUp size={11} className={screenState === 'live' ? 'text-emerald-600' : 'text-muted-foreground'} />
                {screenState}
              </span>
            )}
          </span>
        </span>
        <ChevronRight size={14} className="shrink-0 text-muted-foreground" />
      </button>
    </li>
  )
}

function SelectedStudentPanel({ student, endRoomMutation }: {
  student: CommandCenterStudent
  endRoomMutation: { mutate: (roomId: string) => void }
}) {
  const [confirmingEnd, setConfirmingEnd] = useState(false)
  const terminated = student.attemptStatus === 'PROCTOR_TERMINATED'
  const suspended = student.accessStatus === 'SUSPENDED'
  const roomEnded = student.roomStatus === 'ENDED'

  return (
    <div>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-medium">{student.studentName}</p>
          <p className="truncate text-xs text-muted-foreground">{student.studentEmail}</p>
        </div>
        {student.roomCode && (
          <span className={`shrink-0 rounded-full px-2.5 py-0.5 text-xs font-medium ${roomStatusPill[student.roomStatus ?? 'ACTIVE']}`}>
            {student.roomCode} · {student.roomStatus}
          </span>
        )}
      </div>

      {(terminated || suspended) && (
        <p role="alert" className="mt-3 flex items-center gap-2 rounded-xl bg-red-500/10 px-3 py-2 text-xs font-medium text-red-600">
          <AlertTriangle size={14} />
          {terminated ? 'Attempt terminated by the proctoring policy (WARNING_3).' : 'Student access is suspended for this exam.'}
        </p>
      )}

      <div className="mt-4 grid grid-cols-2 gap-3 text-xs sm:grid-cols-3">
        <InfoTile label="Attempt">
          {student.attemptStatus ? (
            <span className={`rounded-full px-2 py-0.5 font-semibold ${attemptStatusPill[student.attemptStatus] ?? 'bg-muted text-muted-foreground'}`}>
              {student.attemptStatus.replaceAll('_', ' ')} #{student.attemptNumber ?? 1}
            </span>
          ) : (
            <span className="text-muted-foreground">Not started</span>
          )}
        </InfoTile>
        <InfoTile label="Warning level">
          <span className={`rounded-full px-2 py-0.5 font-semibold ${warningPill[student.warningLevel]}`}>
            {student.warningLevel.replaceAll('_', ' ')} ({student.warningCount})
          </span>
        </InfoTile>
        <InfoTile label="Access">
          <span className={`rounded-full px-2 py-0.5 font-semibold ${accessPill[student.accessStatus]}`}>
            {student.accessStatus.replaceAll('_', ' ')}
          </span>
        </InfoTile>
        <InfoTile label="Risk">
          <span className="flex items-center gap-1.5 font-semibold">
            <span className={`rounded-full px-2 py-0.5 ${riskPill[student.riskLevel]}`}>{student.riskLevel}</span>
            <span className="text-muted-foreground">{Math.round(student.riskScore)}/100</span>
          </span>
        </InfoTile>
        <InfoTile label="Camera">
          {student.cameraOff === true ? (
            <span className="font-semibold text-red-600">Off {'(' + timeAt(student.latestProctorEvent?.occurredAt) + ')'}</span>
          ) : (
            <span className="text-muted-foreground">—</span>
          )}
        </InfoTile>
        <InfoTile label="Mic / audio">
          {student.audioSignalCount > 0 ? (
            <span className="font-semibold text-amber-600">{student.audioSignalCount} signal{student.audioSignalCount !== 1 ? 's' : ''}</span>
          ) : (
            <span className="text-muted-foreground">—</span>
          )}
        </InfoTile>
        <InfoTile label="Fullscreen">
          {student.fullscreenExited === true ? (
            <span className="font-semibold text-amber-600">Exited {'(' + timeAt(student.latestProctorEvent?.occurredAt) + ')'}</span>
          ) : (
            <span className="text-muted-foreground">—</span>
          )}
        </InfoTile>
        <InfoTile label="Network">
          {student.networkInterruptionCount > 0 ? (
            <span className="font-semibold text-amber-600">{student.networkInterruptionCount} interruption{student.networkInterruptionCount !== 1 ? 's' : ''}</span>
          ) : (
            <span className="text-muted-foreground">—</span>
          )}
        </InfoTile>
        <InfoTile label="Last activity">
          <span className={student.activeNow ? 'font-semibold text-emerald-600' : ''}>
            {student.activeNow ? 'Active · ' : ''}{timeAt(student.lastActivityAt)}
          </span>
        </InfoTile>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-3">
        {student.roomId && student.roomStatus === 'ACTIVE' && !roomEnded && (
          confirmingEnd ? (
            <span className="flex items-center gap-2 rounded-xl border border-red-500/30 bg-red-500/5 px-3 py-1.5 text-xs text-red-600">
              End this room for all students?
              <button type="button" onClick={() => endRoomMutation.mutate(student.roomId!)} className="font-semibold underline">
                Yes, end room
              </button>
              <button type="button" onClick={() => setConfirmingEnd(false)} className="text-muted-foreground underline">
                Cancel
              </button>
            </span>
          ) : (
            <button
              type="button"
              onClick={() => setConfirmingEnd(true)}
              className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-xs font-medium text-muted-foreground transition hover:bg-muted"
            >
              <XCircle size={13} /> End room
            </button>
          )
        )}
        {student.attemptId && (
          <span className="text-[11px] text-muted-foreground">
            Started {dateTimeAt(student.startedAt)} · expires {timeAt(student.expiresAt)}
          </span>
        )}
      </div>
    </div>
  )
}

function InfoTile({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="rounded-xl bg-muted p-2.5">
      <p className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
      <div className="mt-1">{children}</div>
    </div>
  )
}

function ScreenSection({ student, onViewerState }: {
  student: CommandCenterStudent
  onViewerState: (studentId: string, state: ViewerState) => void
}) {
  const roomId = student.roomId
  const roomQuery = useQuery({
    queryKey: ['room-detail', roomId],
    queryFn: async () => (await examRoomApi.get(roomId!)).data.data,
    enabled: Boolean(roomId),
    retry: 1,
  })

  if (!roomId) {
    return (
      <div className="rounded-xl border border-border bg-muted/40 p-6 text-center">
        <ShieldCheck size={20} className="mx-auto mb-2 text-muted-foreground" />
        <p className="text-sm text-muted-foreground">This student has no exam room yet, so screen sharing cannot be requested.</p>
      </div>
    )
  }

  if (roomQuery.isPending) {
    return (
      <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground" aria-busy="true">
        <Loader2 size={14} className="animate-spin" /> Loading room members…
      </div>
    )
  }

  if (roomQuery.isError || !roomQuery.data) {
    return (
      <div className="rounded-xl border border-border bg-muted/40 p-6 text-center text-sm text-muted-foreground">
        Room details could not be loaded.
      </div>
    )
  }

  return (
    <TeacherScreenViewer
      key={roomId}
      roomId={roomId}
      members={roomQuery.data.members}
      status={student.roomStatus ?? 'ACTIVE'}
      defaultSelectedStudentId={student.studentId}
      onViewerState={onViewerState}
    />
  )
}

function TimelineCard({ attemptId, studentName, riskScore, eventCount }: {
  attemptId: string | null
  studentName: string | null
  riskScore: number | undefined
  eventCount: number
}) {
  const { data, isPending } = useQuery({
    queryKey: ['attempt-events', attemptId],
    queryFn: async () => (await proctorApi.attemptEvents(attemptId!, 100)).data.data,
    enabled: Boolean(attemptId),
    retry: 1,
  })

  return (
    <>
      <div className="border-b border-border px-4 py-3">
        <p className="flex items-center gap-2 text-sm font-semibold">
          <Clock3 size={14} /> Event timeline
        </p>
        {attemptId && (
          <p className="mt-0.5 text-[11px] text-muted-foreground">
            {studentName ?? 'Student'} · {eventCount} events · risk {Math.round(riskScore ?? 0)}/100 (server-scored)
          </p>
        )}
      </div>
      <div className="flex-1 overflow-y-auto p-4">
        {!attemptId ? (
          <p className="py-8 text-center text-sm text-muted-foreground">Select a student with an active attempt to see their event timeline.</p>
        ) : data && data.length > 0 ? (
          <ul className="relative space-y-4 before:absolute before:bottom-1 before:left-[5px] before:top-1 before:w-px before:bg-border">
            {data.map((event) => (
              <TimelineEvent key={event.id} event={event} />
            ))}
          </ul>
        ) : (
          <div className="py-8 text-center">
            <p className="text-sm text-muted-foreground">
              {isPending ? 'Loading events…' : 'No proctoring events have been recorded for this attempt yet.'}
            </p>
          </div>
        )}
      </div>
    </>
  )
}

function TimelineEvent({ event }: { event: ProctorEvent }) {
  const type = event.type.replaceAll('_', ' ')
  const isNetwork = event.type === 'NETWORK_INTERRUPTION'
  const isFullscreen = event.type === 'FULLSCREEN_EXIT'
  const metadataEntries = Object.entries(event.metadata ?? {}).slice(0, 2)
  let badgeClass = 'bg-slate-500/10 text-slate-600'
  if (isNetwork) badgeClass = 'bg-sky-500/10 text-sky-600'
  if (isFullscreen) badgeClass = 'bg-amber-500/10 text-amber-600'
  if (event.type === 'TAB_SWITCH' || event.type === 'WINDOW_BLUR') badgeClass = 'bg-amber-500/10 text-amber-600'
  if (event.type === 'MULTIPLE_FACES') badgeClass = 'bg-red-500/10 text-red-600'
  let dot = 'bg-slate-400'
  if (event.type === 'MULTIPLE_FACES' || event.type === 'CAMERA_OFF') dot = 'bg-red-500'
  if (event.type === 'AUDIO_DETECTED' || event.type === 'TAB_SWITCH' || event.type === 'WINDOW_BLUR' || isFullscreen) dot = 'bg-amber-500'
  if (isNetwork) dot = 'bg-sky-500'
  return (
    <li className="relative pl-4">
      <span className={`absolute left-0 top-1 size-2.5 rounded-full ring-4 ring-background ${dot}`} />
      <p className="flex flex-wrap items-center gap-2">
        <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${badgeClass}`}>{type}</span>
        <time className="text-[11px] text-muted-foreground">{dateTimeAt(event.occurredAt)}</time>
      </p>
      {metadataEntries.length > 0 && (
        <p className="mt-1.5 flex flex-wrap gap-1.5">
          {metadataEntries.map(([key, value]) => (
            <span key={key} className="rounded-md bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">
              {key}: {String(value)}
            </span>
          ))}
        </p>
      )}
      <p className="mt-1 flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
        {event.source && (
          <span className="rounded-md bg-muted px-1.5 py-0.5">source: {event.source.replaceAll('_', ' ')}</span>
        )}
        {typeof event.confidence === 'number' && (
          <span className="rounded-md bg-muted px-1.5 py-0.5">confidence: {event.confidence}</span>
        )}
        {typeof event.durationMs === 'number' && (
          <span className="rounded-md bg-muted px-1.5 py-0.5">duration: {event.durationMs}ms</span>
        )}
      </p>
      {isNetwork && (
        <p className="mt-1 text-[11px] text-muted-foreground">Network interruptions adjust risk but are not counted toward the warning limit.</p>
      )}
    </li>
  )
}