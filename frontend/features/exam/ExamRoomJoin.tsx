'use client'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ArrowRight, DoorOpen, KeyRound, Loader2, Users } from 'lucide-react'
import { examRoomApi } from '@/services/examRoomApi'
import { roomStatusLabels, type ExamRoom } from '@/types/examRoom'

const normalizeCode = (value: string) => value.replace(/[^A-Z0-9]/gi, '').toUpperCase().slice(0, 8)

const roomIsOpen = (room: Pick<ExamRoom, 'status'>) => room.status === 'WAITING' || room.status === 'ACTIVE'

export function ExamRoomJoin() {
  const navigate = useNavigate()
  const [code, setCode] = useState('')
  const [submittedCode, setSubmittedCode] = useState('')

  const myRoomsQuery = useQuery({ queryKey: ['my-exam-rooms'], queryFn: async () => (await examRoomApi.mine()).data.data, retry: 1 })

  const joinMutation = useMutation({
    mutationFn: (roomCode: string) => examRoomApi.join(roomCode),
    onSuccess: (response) => {
      const result = response.data.data
      if (result?.roomId) navigate(`/student/exams/room/${result.roomId}`)
    },
    onError: async (error: unknown) => {
      const status = (error as { response?: { status?: number } })?.response?.status
      const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
      if (status === 409 && message?.toLowerCase().includes('already joined')) {
        try {
          const rooms = (await examRoomApi.mine()).data.data
          const existing = rooms.find(room => room.roomCode === submittedCode)
          if (existing?.roomId) {
            navigate(`/student/exams/room/${existing.roomId}`)
            return
          }
        } catch {
          /* fall through to the inline error */
        }
      }
    },
  })

  const joinedRooms = (myRoomsQuery.data ?? []).filter(roomIsOpen)
  const joinError = joinMutation.isError ? (joinMutation.error as { response?: { data?: { message?: string } } })?.response?.data?.message : null
  const genericError = joinMutation.isError ? !joinError : false

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const normalized = normalizeCode(code)
    if (!normalized) return
    setSubmittedCode(normalized)
    joinMutation.mutate(normalized)
  }

  return (
    <div className="mx-auto max-w-2xl">
      <header className="mb-8">
        <p className="text-xs font-semibold uppercase tracking-widest text-primary">Student workspace</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-tight">Join an exam room</h1>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">Enter the room code your teacher shared, then wait for the exam to begin.</p>
      </header>

      <section className="rounded-2xl border border-border bg-card p-6">
        <form onSubmit={submit} className="space-y-4" aria-label="Join exam room by code">
          <label className="block text-sm font-medium">
            Room code
            <input
              className="field mt-2 text-center text-2xl font-bold tracking-[0.35em] uppercase"
              value={code}
              onChange={event => setCode(normalizeCode(event.target.value))}
              placeholder="XXXXXXXX"
              inputMode="text"
              autoComplete="off"
              spellCheck={false}
              aria-label="Room code"
            />
          </label>
          {joinError && <p className="text-sm text-destructive" role="alert">{joinError}</p>}
          {genericError && <p className="text-sm text-destructive" role="alert">Unable to join the room. Check your connection and try again.</p>}
          <button
            type="submit"
            disabled={normalizeCode(code).length < 6 || joinMutation.isPending}
            className="flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98] disabled:opacity-60"
          >
            {joinMutation.isPending ? <><Loader2 size={16} className="animate-spin" /> Joining room…</> : <><KeyRound size={16} /> Join room</>}
          </button>
        </form>

        <p className="mt-4 flex items-center gap-2 text-xs text-muted-foreground">
          <DoorOpen size={14} className="shrink-0" />
          After the teacher starts the room you can continue to the exam preflight from the waiting room.
        </p>
      </section>

      <div className="mt-6">
        <div className="flex items-center justify-between px-1">
          <h2 className="text-sm font-semibold text-muted-foreground">Your open rooms</h2>
          {myRoomsQuery.isPending && <Loader2 size={14} className="animate-spin text-muted-foreground" aria-label="Loading your rooms" />}
        </div>
        {myRoomsQuery.isError ? (
          <p className="mt-2 text-sm text-muted-foreground">Couldn&apos;t load your waiting rooms.</p>
        ) : joinedRooms.length ? (
          <div className="mt-3 space-y-3">
            {joinedRooms.map(room => (
              <button
                key={room.roomId}
                type="button"
                onClick={() => navigate(`/student/exams/room/${room.roomId}`)}
                className="flex w-full items-center justify-between gap-3 rounded-2xl border border-border bg-card p-4 text-left transition hover:bg-muted active:scale-[0.99] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <div className="min-w-0">
                  <p className="truncate font-medium">{room.examTitle || 'Untitled exam'}</p>
                  <p className="mt-1 flex items-center gap-2 text-sm text-muted-foreground">
                    <Users size={14} className="shrink-0" />
                    {room.roomCode} · {roomStatusLabels[room.status]}
                  </p>
                </div>
                <span className="flex shrink-0 items-center gap-1 text-sm font-medium text-primary"><ArrowRight size={16} /> Open</span>
              </button>
            ))}
          </div>
        ) : (
          <p className="mt-2 text-sm text-muted-foreground">You are not in any open waiting rooms right now.</p>
        )}
      </div>
    </div>
  )
}