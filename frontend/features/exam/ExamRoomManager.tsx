'use client'
import { useCallback, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, ClipboardCopy, Check, DoorOpen, Hourglass, Loader2, Play, Square, Users } from 'lucide-react'
import { examApi } from '@/services/examApi'
import { examRoomApi } from '@/services/examRoomApi'
import { useAuthStore } from '@/store/authStore'
import { useExamRoomFeed } from '@/hooks/useExamRoomFeed'
import { ConfirmDialog, useToast } from '@/components/feedback'
import { roomStatusLabels, type ExamRoom, type RoomActivity } from '@/types/examRoom'

const formatTime = (value: string | null) => (value ? new Date(value).toLocaleString() : null)
const initialsFor = (name: string | null) => (name ? name.trim().split(/\s+/).map(part => part[0]).filter(Boolean).slice(0, 2).join('').toUpperCase() : 'S')

export function ExamRoomManager({ examId }: { examId: string }) {
  const queryClient = useQueryClient()
  const token = useAuthStore(state => state.token)
  const role = useAuthStore(state => state.user?.role)
  const toast = useToast()
  const [copied, setCopied] = useState(false)
  const [pendingAction, setPendingAction] = useState<'START' | 'END' | null>(null)

  const examQuery = useQuery({ queryKey: ['exam', examId], queryFn: async () => (await examApi.get(examId)).data.data, enabled: Boolean(examId), retry: 1 })
  const roomsQuery = useQuery({ queryKey: ['my-exam-rooms'], queryFn: async () => (await examRoomApi.mine()).data.data, retry: 1 })

  const rooms = (roomsQuery.data ?? []).filter(room => room.examId === examId)
  const currentRoom: ExamRoom | null =
    rooms.find(room => room.status !== 'ENDED') ??
    [...rooms].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))[0] ??
    null

  const createMutation = useMutation({
    mutationFn: () => examRoomApi.create(examId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['my-exam-rooms'] })
      toast.success('Exam room created. Share the code with your students.')
    },
    onError: (error: any) => {
      toast.error(error?.response?.data?.message || 'Unable to create an exam room.')
    },
  })

  const detailQuery = useQuery({
    queryKey: ['exam-room', currentRoom?.roomId],
    queryFn: async () => (await examRoomApi.get(currentRoom!.roomId)).data.data,
    enabled: Boolean(currentRoom?.roomId),
    refetchInterval: 30000,
    retry: 1,
  })

  const onActivity = useCallback((event: RoomActivity) => {
    if (event.type === 'ROOM_CREATED' || event.type === 'ROOM_STARTED' || event.type === 'ROOM_ENDED' || event.type === 'MEMBER_JOINED') {
      void queryClient.invalidateQueries({ queryKey: ['my-exam-rooms'] })
      void detailQuery.refetch()
    }
  }, [queryClient, detailQuery.refetch])

  const { connectionState } = useExamRoomFeed(currentRoom?.roomId ?? null, token, onActivity)

  const startMutation = useMutation({
    mutationFn: () => examRoomApi.start(currentRoom!.roomId),
    onSuccess: () => {
      setPendingAction(null)
      void queryClient.invalidateQueries({ queryKey: ['my-exam-rooms'] })
      void detailQuery.refetch()
      toast.success('The exam room has started. Students can now continue to the preflight.')
    },
    onError: (error: any) => {
      setPendingAction(null)
      void detailQuery.refetch()
      toast.error(error?.response?.data?.message || 'Unable to start the room. Refresh and try again.')
    },
  })

  const endMutation = useMutation({
    mutationFn: () => examRoomApi.end(currentRoom!.roomId),
    onSuccess: () => {
      setPendingAction(null)
      void queryClient.invalidateQueries({ queryKey: ['my-exam-rooms'] })
      void detailQuery.refetch()
      toast.success('The exam room has ended.')
    },
    onError: (error: any) => {
      setPendingAction(null)
      void detailQuery.refetch()
      toast.error(error?.response?.data?.message || 'Unable to end the room. Refresh and try again.')
    },
  })

  const copyCode = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard unavailable */
    }
  }

  const dialogMemberCount = detailQuery.data?.room?.memberCount ?? 0

  return (
    <div className="mx-auto max-w-3xl">
      <header className="mb-8">
        <p className="text-xs font-semibold uppercase tracking-widest text-primary">{role === 'ADMIN' ? 'Administrator' : 'Teacher'} workspace</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-tight">Exam room</h1>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">Create a room, share the code, and start the exam when everyone is ready.</p>
      </header>

      {examQuery.isPending ? (
        <div className="rounded-2xl border border-border bg-card p-5"><div className="h-20 animate-pulse rounded-xl bg-muted" /></div>
      ) : examQuery.isError || !examQuery.data ? (
        <div className="rounded-2xl border border-destructive/30 bg-card p-5">
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3 text-destructive"><AlertTriangle size={20} /><p className="text-sm">This exam could not be loaded.</p></div>
            <button type="button" onClick={() => void examQuery.refetch()} className="flex items-center gap-2 rounded-xl border border-border px-3 py-1.5 text-sm hover:bg-muted">Retry</button>
          </div>
        </div>
      ) : (
        <div className="flex items-center gap-3 rounded-2xl border border-border bg-card p-5">
          <div className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary"><DoorOpen /></div>
          <div className="min-w-0">
            <h2 className="truncate font-semibold">{examQuery.data.title}</h2>
            <p className="mt-1 text-sm text-muted-foreground">{examQuery.data.subject} · {examQuery.data.duration} minutes</p>
          </div>
        </div>
      )}

      <div className="mt-6 rounded-2xl border border-border bg-card p-5">
        {!currentRoom ? (
          <div className="flex flex-col items-center gap-4 py-6 text-center">
            <div className="grid size-14 place-items-center rounded-2xl bg-muted text-muted-foreground"><Hourglass size={26} /></div>
            <div>
              <h2 className="font-semibold">No exam room yet</h2>
              <p className="mt-1 text-sm text-muted-foreground">Create a room to get an 8-character code your students can join.</p>
            </div>
            <button
              type="button"
              disabled={createMutation.isPending}
              onClick={() => createMutation.mutate()}
              className="flex items-center gap-2 rounded-xl bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 active:scale-[.98] disabled:opacity-60"
            >
              {createMutation.isPending ? <><Loader2 size={16} className="animate-spin" /> Creating room…</> : <><DoorOpen size={16} /> Create exam room</>}
            </button>
          </div>
        ) : roomsQuery.isPending || detailQuery.isPending ? (
          <div className="space-y-3" aria-busy="true">{[1, 2, 3].map(item => <div key={item} className="h-14 animate-pulse rounded-xl bg-muted" />)}</div>
        ) : detailQuery.isError || !detailQuery.data ? (
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3 text-destructive"><AlertTriangle size={20} /><p className="text-sm">The room could not be loaded.</p></div>
            <button type="button" onClick={() => void detailQuery.refetch()} className="flex items-center gap-2 rounded-xl border border-border px-3 py-1.5 text-sm hover:bg-muted">Retry</button>
          </div>
        ) : (() => {
          const room = detailQuery.data.room
          const members = detailQuery.data.members
          return (
            <>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <h2 className="font-semibold">Room {room.roomCode}</h2>
                <p className="mt-1 flex items-center gap-2 text-sm text-muted-foreground">
                  <Users size={14} />{room.memberCount} {room.memberCount === 1 ? 'student' : 'students'} joined
                </p>
              </div>
              <div className="flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => void copyCode(room.roomCode)}
                  className="group flex items-center gap-2 rounded-xl border border-border px-3 py-2 text-xs font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  aria-label={`Copy room code ${room.roomCode}`}
                >
                  <span className="text-sm font-bold tracking-[0.25em]">{room.roomCode}</span>
                  {copied ? <Check size={14} className="text-emerald-600" /> : <ClipboardCopy size={14} className="text-muted-foreground group-hover:text-foreground" />}
                </button>
                <span className={`inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-semibold ${room.status === 'ACTIVE' ? 'bg-emerald-500/10 text-emerald-600' : room.status === 'ENDED' ? 'bg-muted text-muted-foreground' : 'bg-amber-500/10 text-amber-600'}`}>
                  {connectionState === 'connected' ? <span className="size-1.5 rounded-full bg-current" /> : <span className="size-1.5 animate-pulse rounded-full bg-current" />}
                  {roomStatusLabels[room.status]}
                </span>
              </div>
            </div>

            {formatTime(room.startedAt) && <p className="mt-3 text-sm text-muted-foreground">Started at <span className="font-medium text-foreground">{formatTime(room.startedAt)}</span></p>}
            {formatTime(room.endedAt) && <p className="mt-1 text-sm text-muted-foreground">Ended at <span className="font-medium text-foreground">{formatTime(room.endedAt)}</span></p>}
            {connectionState !== 'connected' && (
              <p className="mt-2 flex items-center gap-1.5 text-xs text-muted-foreground" role="status">
                <Loader2 size={12} className="animate-spin" /> Live updates paused — reconnecting… The list still refreshes automatically.
              </p>
            )}

            {room.status === 'WAITING' && (
              <div className="mt-6 flex flex-wrap items-end justify-between gap-4 border-t border-border pt-5">
                <p className="text-sm leading-6 text-muted-foreground">Starting the room unlocks <span className="font-medium text-foreground">Continue to Preflight</span> for every student who has joined.</p>
                <button
                  type="button"
                  onClick={() => setPendingAction('START')}
                  className="flex min-h-11 items-center justify-center gap-2 rounded-xl bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 active:scale-[.98]"
                >
                  <Play size={16} /> Start exam
                </button>
              </div>
            )}
            {room.status === 'ACTIVE' && (
              <div className="mt-6 flex flex-wrap items-end justify-between gap-4 border-t border-border pt-5">
                <p className="text-sm leading-6 text-muted-foreground">The room is live. End it when the exam session is complete.</p>
                <button
                  type="button"
                  onClick={() => setPendingAction('END')}
                  className="flex min-h-11 items-center justify-center gap-2 rounded-xl border border-destructive/40 px-6 py-3 text-sm font-semibold text-destructive transition hover:bg-destructive/10 active:scale-[.98]"
                >
                  <Square size={16} /> End exam
                </button>
              </div>
            )}
            {room.status === 'ENDED' && (
              <p className="mt-6 border-t border-border pt-5 text-sm text-muted-foreground">This room has ended. Create a new room if you want to run this exam again.</p>
            )}

            <div className="mt-6 border-t border-border pt-5">
              <h3 className="text-sm font-semibold text-muted-foreground">Students who joined</h3>
              {members.length ? (
                <ul className="mt-3 divide-y divide-border">
                  {members.map(member => (
                    <li key={member.memberId} className="flex items-center gap-3 py-3">
                      <div className="grid size-9 shrink-0 place-items-center rounded-full bg-primary/10 text-sm font-semibold text-primary">{initialsFor(member.studentName)}</div>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium">{member.studentName || 'Student'}</p>
                        {member.studentEmail && <p className="truncate text-xs text-muted-foreground">{member.studentEmail}</p>}
                      </div>
                      <span className="shrink-0 text-xs text-muted-foreground">{formatTime(member.joinedAt) ?? 'Joined'}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="mt-3 text-sm text-muted-foreground">No students have joined yet. Share the room code above.</p>
              )}
            </div>
          </>
          )
        })()}
      </div>

      <ConfirmDialog
        open={pendingAction === 'START'}
        title="Start this exam room?"
        description={`Starting unlocks Continue to Preflight for the ${dialogMemberCount} students in this room. You can end the room at any time.`}
        confirmLabel="Start exam"
        busy={startMutation.isPending}
        onCancel={() => setPendingAction(null)}
        onConfirm={() => startMutation.mutate()}
      />
      <ConfirmDialog
        open={pendingAction === 'END'}
        title="End this exam room?"
        description="Students in this room will no longer be able to continue to the preflight from the waiting room."
        confirmLabel="End exam"
        destructive
        busy={endMutation.isPending}
        onCancel={() => setPendingAction(null)}
        onConfirm={() => endMutation.mutate()}
      />
    </div>
  )
}