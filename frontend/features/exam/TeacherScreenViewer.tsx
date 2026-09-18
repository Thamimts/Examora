'use client'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Loader2, MonitorUp, Radio, RefreshCw, VideoOff } from 'lucide-react'
import { useAuthStore } from '@/store/authStore'
import { useRtcSignaling } from '@/hooks/useRtcSignaling'
import { RtcPeer, type PeerConnectionState, type SignalMessage } from '@/lib/rtcPeer'
import type { RoomMember } from '@/types/examRoom'
import type { ExamRoomStatus } from '@/types/examRoom'
import type { RtcSignal } from '@/types/webrtc'

export type ViewerState = 'connecting' | 'live' | 'reconnecting' | 'disconnected' | 'unavailable'

type ViewEntry = {
  studentId: string
  state: ViewerState
  peer: RtcPeer | null
  stream: MediaStream | null
}

export function TeacherScreenViewer({ roomId, members, status, defaultSelectedStudentId = null, onViewerState }: {
  roomId: string
  members: RoomMember[]
  status: ExamRoomStatus
  defaultSelectedStudentId?: string | null
  onViewerState?: (studentId: string, state: ViewerState) => void
}) {
  const token = useAuthStore((state) => state.token)
  const selfId = useAuthStore((state) => state.user?.id)
  const [, setTick] = useState(0)
  const [selected, setSelected] = useState<string | null>(defaultSelectedStudentId)
  const viewsRef = useRef<Map<string, ViewEntry>>(new Map())
  const sentReadyRef = useRef<Set<string>>(new Set())

  const notify = useCallback((studentId: string, state: ViewerState) => {
    onViewerState?.(studentId, state)
  }, [onViewerState])

  useEffect(() => {
    setSelected(defaultSelectedStudentId)
  }, [defaultSelectedStudentId])

  const bump = useCallback(() => setTick((value) => value + 1), [])

  const signaling = useRtcSignaling(
    roomId,
    token,
    `/topic/exam-rooms/${roomId}/webrtc`,
    (signal) => handleSignal(signal),
  )

  const createPeer = useCallback((studentId: string): RtcPeer =>
    new RtcPeer({
      role: 'responder',
      onSignal: (message: SignalMessage) => {
        signaling.publish({ peerId: studentId, type: message.type, sdp: message.sdp, candidate: message.candidate })
      },
      onStateChange: (state: PeerConnectionState) => {
        const entry = viewsRef.current.get(studentId)
        if (!entry) return
        if (state === 'connected') {
          entry.state = 'live'
        } else if (state === 'disconnected' || state === 'failed') {
          entry.state = 'reconnecting'
        } else if (state === 'closed') {
          entry.state = 'disconnected'
        }
        notify(studentId, entry.state)
        bump()
      },
      onRemoteStream: (stream: MediaStream) => {
        const current = viewsRef.current.get(studentId)
        if (current) {
          current.stream = stream
          bump()
        }
      },
    }), [signaling.publish, bump, notify])

  const ensureEntry = useCallback((studentId: string, state: ViewerState = 'connecting'): ViewEntry => {
    const existing = viewsRef.current.get(studentId)
    if (existing) return existing
    const entry: ViewEntry = { studentId, state, peer: null, stream: null }
    viewsRef.current.set(studentId, entry)
    return entry
  }, [])

  const handleSignal = useCallback((signal: RtcSignal) => {
    if (!signal || !signal.senderId || signal.senderId === selfId) return
    if (signal.senderRole !== 'STUDENT') return
    const studentId = signal.senderId
    if (signal.type === 'viewer_ready' || signal.type === 'answer') return
    if (signal.type === 'bye') {
      const entry = viewsRef.current.get(studentId)
      if (entry) {
        entry.peer?.stop()
        entry.peer = null
        entry.stream = null
        entry.state = 'disconnected'
        notify(studentId, entry.state)
        bump()
      }
      return
    }
    if (signal.type === 'unavailable') {
      const entry = ensureEntry(studentId, 'unavailable')
      entry.peer?.stop()
      entry.peer = null
      entry.stream = null
      entry.state = 'unavailable'
      notify(studentId, entry.state)
      bump()
      return
    }
    let peer = viewsRef.current.get(studentId)?.peer ?? null
    if (!peer) {
      if (signal.type !== 'offer') return
      peer = createPeer(studentId)
      const entry = ensureEntry(studentId, 'connecting')
      entry.peer = peer
      entry.state = 'connecting'
      notify(studentId, entry.state)
      setSelected((current) => current ?? studentId)
    }
    void peer.handleSignal({
      type: signal.type as 'offer' | 'ice',
      sdp: signal.sdp ?? null,
      candidate: signal.candidate ?? null,
    }).catch(() => {
      const current = viewsRef.current.get(studentId)
      if (current) {
        current.state = 'reconnecting'
        notify(studentId, current.state)
        bump()
      }
    })
  }, [selfId, createPeer, ensureEntry, notify, bump])

  const selectStudent = useCallback((studentId: string) => {
    ensureEntry(studentId, 'connecting')
    notify(studentId, 'connecting')
    setSelected(studentId)
    if (signaling.connectionState === 'connected') {
      sentReadyRef.current.add(studentId)
      signaling.publish({ peerId: studentId, type: 'viewer_ready' })
    }
  }, [ensureEntry, notify, signaling.connectionState, signaling.publish])

  useEffect(() => {
    if (signaling.connectionState !== 'connected') {
      sentReadyRef.current.clear()
      return
    }
    if (!selected) return
    if (sentReadyRef.current.has(selected)) return
    sentReadyRef.current.add(selected)
    signaling.publish({ peerId: selected, type: 'viewer_ready' })
  }, [signaling.connectionState, selected, signaling.publish])

  useEffect(() => () => {
    const currentSelfId = selfId
    viewsRef.current.forEach((entry) => {
      if (entry.peer && currentSelfId) signaling.publish({ peerId: entry.studentId, type: 'bye' })
      entry.peer?.stop()
    })
    viewsRef.current.clear()
  }, [selfId, signaling.publish])

  const nameFor = (studentId: string) =>
    members.find((member) => member.studentId === studentId)?.studentName ?? 'Student'
  const roomEnded = status === 'ENDED'
  const selectedEntry = selected ? viewsRef.current.get(selected) ?? null : null

  return (
    <div className="mt-6 border-t border-border pt-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-muted-foreground">
          <MonitorUp size={16} /> Screen sharing
        </h3>
        {signaling.connectionState !== 'connected' && !roomEnded && (
          <p className="flex items-center gap-1.5 text-xs text-muted-foreground" role="status">
            <Loader2 size={12} className="animate-spin" /> Live updates paused — reconnecting…
          </p>
        )}
      </div>

      {roomEnded ? (
        <p className="mt-4 rounded-xl border border-border bg-muted/40 p-5 text-center text-sm text-muted-foreground">
          <VideoOff size={18} className="mx-auto mb-2 text-muted-foreground" />
          Screen sharing was stopped when this exam room ended.
        </p>
      ) : (
        <>
          {members.length > 0 && (
            <ul className="mt-4 flex flex-wrap gap-2" aria-label="Students in this room">
              {members.map((member) => {
                const entry = viewsRef.current.get(member.studentId)
                const active = member.studentId === selected
                return (
                  <li key={member.studentId}>
                    <button
                      type="button"
                      onClick={() => selectStudent(member.studentId)}
                      className={`flex items-center gap-2 rounded-full border px-3 py-1.5 text-xs font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${active ? 'border-primary bg-primary/10 text-primary' : 'border-border hover:bg-muted'}`}
                    >
                      <span className={`size-1.5 rounded-full ${entry ? dotClass(entry.state) : 'bg-muted-foreground'}`} />
                      {member.studentName || 'Student'}
                    </button>
                  </li>
                )
              })}
            </ul>
          )}

          <div className="mt-4">
            {selectedEntry ? (
              selectedEntry.state === 'live' ? (
                <>
                  <StreamVideo stream={selectedEntry.stream} />
                  <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
                    <p className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
                      <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-2.5 py-1 text-xs font-semibold text-emerald-600">
                        <span className="size-1.5 rounded-full bg-emerald-500" /> LIVE
                      </span>
                      <span>{nameFor(selectedEntry.studentId)}</span>
                    </p>
                  </div>
                </>
              ) : selectedEntry.state === 'unavailable' ? (
                <StatePanel
                  label="UNAVAILABLE"
                  tone="muted"
                  message="This student is not sharing their screen right now."
                  hint="Ask them to choose Share screen in the exam workspace, then request the stream again."
                  onRequest={() => selectStudent(selectedEntry.studentId)}
                />
              ) : selectedEntry.state === 'disconnected' ? (
                <StatePanel
                  label="DISCONNECTED"
                  tone="muted"
                  message="This student has stopped sharing their screen."
                  hint="Media travels peer-to-peer and is never stored."
                  onRequest={() => selectStudent(selectedEntry.studentId)}
                />
              ) : (
                <StatePanel
                  label={selectedEntry.state === 'reconnecting' ? 'RECONNECTING' : 'CONNECTING'}
                  tone={selectedEntry.state === 'reconnecting' ? 'warning' : 'info'}
                  message="Waiting for the student's live screen…"
                  hint="Selecting a student asks them to resume sharing without another permission dialog."
                  spinner
                  onRequest={() => selectStudent(selectedEntry.studentId)}
                />
              )
            ) : (
              <div className="rounded-xl border border-border bg-muted/40 p-6 text-center">
                <Radio size={20} className="mx-auto mb-2 text-muted-foreground" />
                <p className="text-sm text-muted-foreground">
                  {members.length > 0
                    ? 'Select a student above to see their screen.'
                    : 'No students have joined this room yet.'}
                </p>
                <p className="mt-1 text-xs text-muted-foreground">Media travels peer-to-peer and is never stored.</p>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  )
}

function dotClass(state: ViewerState): string {
  switch (state) {
    case 'live':
      return 'bg-emerald-500'
    case 'reconnecting':
      return 'bg-amber-500'
    case 'connecting':
      return 'bg-sky-500'
    case 'unavailable':
    case 'disconnected':
      return 'bg-muted-foreground'
  }
}

function StatePanel({ label, tone, message, hint, spinner, onRequest }: {
  label: string
  tone: 'info' | 'warning' | 'muted'
  message: string
  hint: string
  spinner?: boolean
  onRequest: () => void
}) {
  const toneClass = tone === 'info'
    ? 'bg-sky-500/10 text-sky-600'
    : tone === 'warning'
      ? 'bg-amber-500/10 text-amber-600'
      : 'bg-muted text-muted-foreground'
  return (
    <div className="rounded-xl border border-border bg-muted/40 p-6 text-center">
      <p className="flex items-center justify-center gap-2">
        <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${toneClass}`}>
          {spinner && <Loader2 size={11} className="animate-spin" />}
          {label}
        </span>
      </p>
      <p className="mt-3 text-sm text-muted-foreground">{message}</p>
      <p className="mt-1 text-xs text-muted-foreground">{hint}</p>
      <button
        type="button"
        onClick={onRequest}
        className="mt-4 inline-flex items-center gap-2 rounded-xl border border-border px-4 py-2 text-sm font-semibold text-muted-foreground transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]"
      >
        <RefreshCw size={14} /> Request stream
      </button>
    </div>
  )
}

function StreamVideo({ stream }: { stream: MediaStream | null }) {
  const ref = useRef<HTMLVideoElement | null>(null)
  useEffect(() => {
    if (ref.current) ref.current.srcObject = stream
  }, [stream])
  return (
    <div className="overflow-hidden rounded-xl border border-border bg-black">
      <video ref={ref} autoPlay playsInline muted className="aspect-video w-full object-contain" />
      {!stream && <p className="py-16 text-center text-xs text-muted-foreground">Waiting for the video stream…</p>}
    </div>
  )
}