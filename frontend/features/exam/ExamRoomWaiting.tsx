'use client'
import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, Check, ChevronRight, ClipboardCopy, DoorOpen, Hourglass, Loader2, RefreshCw, ShieldCheck, Users, Wifi, WifiOff } from 'lucide-react'
import { examRoomApi } from '@/services/examRoomApi'
import { useAuthStore } from '@/store/authStore'
import { useExamRoomFeed } from '@/hooks/useExamRoomFeed'
import { roomStatusLabels, type RoomActivity } from '@/types/examRoom'

const formatTime = (value: string | null) => (value ? new Date(value).toLocaleString() : null)

const initialsFor = (name: string | null) => (name ? name.trim().split(/\s+/).map(part => part[0]).filter(Boolean).slice(0, 2).join('').toUpperCase() : 'S')

export function ExamRoomWaiting({ roomId }: { roomId: string }) {
  const navigate = useNavigate()
  const token = useAuthStore(state => state.token)
  const [copied, setCopied] = useState(false)
  const [lastActivity, setLastActivity] = useState<RoomActivity | null>(null)

  const detailQuery = useQuery({
    queryKey: ['exam-room', roomId],
    queryFn: async () => (await examRoomApi.get(roomId)).data.data,
    enabled: Boolean(roomId),
    refetchInterval: 30000,
    retry: 1,
  })

  const onActivity = useCallback((event: RoomActivity) => {
    setLastActivity(event)
    if (event.type === 'ROOM_STARTED' || event.type === 'ROOM_ENDED' || event.type === 'MEMBER_JOINED') {
      void detailQuery.refetch()
    }
  }, [detailQuery.refetch])

  const { connectionState } = useExamRoomFeed(roomId, token, onActivity)

  const copyCode = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard unavailable */
    }
  }

  if (detailQuery.isPending) {
    return (
      <div className="mx-auto max-w-2xl">
        <div className="mb-8"><p className="text-xs font-semibold uppercase tracking-widest text-primary">Student workspace</p><div className="mt-3 h-9 w-56 animate-pulse rounded-lg bg-muted" /><div className="mt-2 h-4 w-80 max-w-full animate-pulse rounded bg-muted" /></div>
        <div className="rounded-2xl border border-border bg-card p-5"><div className="h-64 animate-pulse rounded-xl bg-muted" /></div>
      </div>
    )
  }

  if (detailQuery.isError || !detailQuery.data) {
    const status = (detailQuery.error as { response?: { status?: number } } | null)?.response?.status
    const message = status === 403
      ? 'You are not a member of this exam room.'
      : status === 404
        ? 'This exam room could not be found.'
        : 'Unable to load this exam room.'
    return (
      <div className="mx-auto max-w-2xl">
        <header className="mb-8">
          <p className="text-xs font-semibold uppercase tracking-widest text-primary">Student workspace</p>
          <h1 className="mt-2 text-3xl font-semibold tracking-tight">Exam room</h1>
        </header>
        <div className="rounded-2xl border border-destructive/30 bg-card p-5">
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3 text-destructive"><AlertTriangle size={20} /><p className="text-sm">{message}</p></div>
            <button type="button" onClick={() => void detailQuery.refetch()} className="flex items-center gap-2 rounded-xl border border-border px-3 py-1.5 text-sm hover:bg-muted"><RefreshCw size={14} /> Retry</button>
          </div>
          <button type="button" onClick={() => navigate('/student/exams')} className="mt-4 flex items-center gap-2 rounded-xl border border-border px-4 py-2 text-sm font-medium transition hover:bg-muted active:scale-[.98]">Back to exam center</button>
        </div>
      </div>
    )
  }

  const { room, members } = detailQuery.data
  const startedLabel = formatTime(room.startedAt)
  const endedLabel = formatTime(room.endedAt)

  return (
    <div className="mx-auto max-w-2xl">
      <header className="mb-8">
        <p className="text-xs font-semibold uppercase tracking-widest text-primary">Student workspace</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-tight">Exam room</h1>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">Wait here — the room is live and updates in real time as it happens.</p>
      </header>

      <div className="rounded-2xl border border-border bg-card p-5">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary"><DoorOpen /></div>
            <div className="min-w-0">
              <h2 className="truncate font-semibold">{room.examTitle || 'Untitled exam'}</h2>
              <p className="mt-1 flex items-center gap-2 text-sm text-muted-foreground">
                <Users size={14} className="shrink-0" />{room.memberCount} {room.memberCount === 1 ? 'student' : 'students'} in the room
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => void copyCode(room.roomCode)}
            className="group flex items-center gap-2 rounded-xl border border-border px-3 py-2 text-xs font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            aria-label={`Copy room code ${room.roomCode}`}
          >
            <span className="text-sm font-bold tracking-[0.25em]">{room.roomCode}</span>
            {copied ? <Check size={14} className="text-emerald-600" /> : <ClipboardCopy size={14} className="text-muted-foreground group-hover:text-foreground" />}
          </button>
        </div>

        <div className="mt-5 flex flex-wrap items-center gap-2">
          <span className={`inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-semibold ${room.status === 'ACTIVE' ? 'bg-emerald-500/10 text-emerald-600' : room.status === 'ENDED' ? 'bg-muted text-muted-foreground' : 'bg-amber-500/10 text-amber-600'}`}>
            {connectionState === 'connected' ? <Wifi size={13} /> : <WifiOff size={13} />}
            {roomStatusLabels[room.status]}
          </span>
          {connectionState !== 'connected' && (
            <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground" role="status">
              <Loader2 size={12} className="animate-spin" /> Live updates paused — reconnecting…
            </span>
          )}
        </div>

        {startedLabel && <p className="mt-3 text-sm text-muted-foreground">Started at <span className="font-medium text-foreground">{startedLabel}</span></p>}
        {endedLabel && <p className="mt-1 text-sm text-muted-foreground">Ended at <span className="font-medium text-foreground">{endedLabel}</span></p>}

        <div className="mt-6">
          {room.status === 'WAITING' && (
            <div className="rounded-xl bg-muted/50 p-4">
              <p className="flex items-start gap-2 text-sm leading-6 text-muted-foreground">
                <Hourglass size={16} className="mt-0.5 shrink-0 text-amber-600" />
                The exam has not started yet. Stay on this screen — when your teacher starts the room, the button to continue to the preflight will appear here.
              </p>
            </div>
          )}
          {room.status === 'ACTIVE' && (
            <div className="rounded-xl bg-emerald-500/10 p-4">
              <p className="flex items-start gap-2 text-sm leading-6 text-emerald-700 dark:text-emerald-400">
                <ShieldCheck size={16} className="mt-0.5 shrink-0" />
                The room has started. Review the exam details and rules before your attempt.
              </p>
            </div>
          )}
          {room.status === 'ENDED' && (
            <div className="rounded-xl bg-muted/50 p-4">
              <p className="flex items-start gap-2 text-sm leading-6 text-muted-foreground">
                <AlertTriangle size={16} className="mt-0.5 shrink-0 text-amber-600" />
                This exam room has ended. No further action is available in this room.
              </p>
            </div>
          )}
          {lastActivity && (
            <p className="mt-3 text-xs text-muted-foreground" role="status">Latest update: {lastActivity.message}</p>
          )}
        </div>

        {room.status !== 'ENDED' && (
          <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <button type="button" onClick={() => navigate('/student/exams')} className="flex items-center gap-2 rounded-xl border border-border px-5 py-3 text-sm font-medium transition hover:bg-muted active:scale-[.98]">Back to exam center</button>
            {room.status === 'ACTIVE' && room.examId && (
              <button
                type="button"
                onClick={() => navigate(`/student/exams/${room.examId}/instructions`)}
                className="flex min-h-11 items-center justify-center gap-2 rounded-xl bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[.98]"
              >
                Continue to Preflight <ChevronRight size={16} />
              </button>
            )}
          </div>
        )}
      </div>

      <div className="mt-6">
        <h2 className="px-1 text-sm font-semibold text-muted-foreground">Students in the room</h2>
        {members.length ? (
          <div className="mt-3 divide-y divide-border rounded-2xl border border-border bg-card">
            {members.map(member => (
              <div key={member.memberId} className="flex items-center gap-3 p-4">
                <div className="grid size-9 shrink-0 place-items-center rounded-full bg-primary/10 text-sm font-semibold text-primary">{initialsFor(member.studentName)}</div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{member.studentName || 'Student'}</p>
                  {member.studentEmail && <p className="truncate text-xs text-muted-foreground">{member.studentEmail}</p>}
                </div>
                <span className="shrink-0 text-xs text-muted-foreground">{formatTime(member.joinedAt) ?? 'Joined'}</span>
              </div>
            ))}
          </div>
        ) : (
          <p className="mt-3 rounded-2xl border border-border bg-card p-5 text-center text-sm text-muted-foreground">No students have joined yet.</p>
        )}
      </div>
    </div>
  )
}