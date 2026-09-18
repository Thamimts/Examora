'use client'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Loader2, MonitorUp, Square, VideoOff } from 'lucide-react'
import { examRoomApi } from '@/services/examRoomApi'
import { useAuthStore } from '@/store/authStore'
import { useRtcSignaling } from '@/hooks/useRtcSignaling'
import { RtcPeer, type PeerConnectionState, type SignalMessage } from '@/lib/rtcPeer'
import type { ExamRoom } from '@/types/examRoom'
import type { RtcSignal } from '@/types/webrtc'

type SharePhase = 'idle' | 'starting' | 'sharing' | 'reconnecting' | 'stopped' | 'failed' | 'unavailable'

const MAX_ICE_RETRIES = 3
const ICE_RETRY_DELAY_MS = 1500

export function StudentScreenShare({ examId }: { examId: string }) {
  const token = useAuthStore((state) => state.token)
  const user = useAuthStore((state) => state.user)
  const [phase, setPhase] = useState<SharePhase>('idle')

  const streamRef = useRef<MediaStream | null>(null)
  const peerRef = useRef<RtcPeer | null>(null)
  const iceRetriesRef = useRef(0)
  const retryTimerRef = useRef<number | undefined>(undefined)
  const manualStopRef = useRef(false)

  const roomsQuery = useQuery({
    queryKey: ['my-exam-rooms'],
    queryFn: async () => (await examRoomApi.mine()).data.data,
    retry: 1,
  })

  const room: ExamRoom | null =
    (roomsQuery.data ?? [])
      .filter((candidate) => candidate.examId === examId)
      .find((candidate) => candidate.status === 'ACTIVE')
      ?? (roomsQuery.data ?? [])
        .filter((candidate) => candidate.examId === examId)
        .find((candidate) => candidate.status !== 'ENDED')
      ?? null

  const signaling = useRtcSignaling(
    room?.roomId ?? null,
    token,
    room ? '/user/queue/webrtc' : null,
    (signal) => handleSignal(signal),
  )

  const stopPeer = useCallback(() => {
    if (retryTimerRef.current) {
      window.clearTimeout(retryTimerRef.current)
      retryTimerRef.current = undefined
    }
    peerRef.current?.stop()
    peerRef.current = null
  }, [])

  const cleanup = useCallback(() => {
    stopPeer()
    const stream = streamRef.current
    streamRef.current = null
    if (stream) {
      stream.getTracks().forEach((track) => track.stop())
    }
  }, [stopPeer])

  const sendBye = useCallback(() => {
    const selfId = user?.id
    if (!selfId) return
    signaling.publish({ peerId: selfId, type: 'bye' })
  }, [user?.id, signaling.publish])

  const stepRef = useRef({
    beginOffer: (_stream: MediaStream, _verbose?: boolean) => {},
    handlePeerState: (_state: PeerConnectionState) => {},
  })

  const beginOffer = useCallback((stream: MediaStream, verbose?: boolean) => {
    const selfId = user?.id
    if (!selfId || !stream) return
    stopPeer()
    if (verbose) setPhase('reconnecting')
    const peer = new RtcPeer({
      role: 'initiator',
      onSignal: (message: SignalMessage) => {
        signaling.publish({ peerId: selfId, type: message.type, sdp: message.sdp, candidate: message.candidate })
      },
      onStateChange: (state: PeerConnectionState) => stepRef.current.handlePeerState(state),
    })
    peerRef.current = peer
    void peer.start(stream).catch(() => {
      stopPeer()
      if (iceRetriesRef.current < MAX_ICE_RETRIES) {
        iceRetriesRef.current += 1
        setPhase('reconnecting')
        retryTimerRef.current = window.setTimeout(() => stepRef.current.beginOffer(stream, true), ICE_RETRY_DELAY_MS)
      } else {
        setPhase('sharing')
      }
    })
  }, [user?.id, signaling.publish, stopPeer])
  stepRef.current.beginOffer = beginOffer

  const handlePeerState = useCallback((state: PeerConnectionState) => {
    const stream = streamRef.current
    if (state === 'connected') {
      iceRetriesRef.current = 0
      setPhase('sharing')
      return
    }
    if ((state === 'disconnected' || state === 'failed') && stream) {
      if (iceRetriesRef.current < MAX_ICE_RETRIES) {
        iceRetriesRef.current += 1
        setPhase('reconnecting')
        retryTimerRef.current = window.setTimeout(() => stepRef.current.beginOffer(stream, true), ICE_RETRY_DELAY_MS)
      } else {
        setPhase('sharing')
      }
    }
  }, [])
  stepRef.current.handlePeerState = handlePeerState

  const reoffer = useCallback((stream: MediaStream) => {
    const peer = peerRef.current
    if (!peer || peer.state === 'failed' || peer.state === 'closed' || peer.state === 'disconnected') {
      stepRef.current.beginOffer(stream, true)
      return
    }
    void peer.start(stream).catch(() => stepRef.current.beginOffer(stream, true))
  }, [])

  const handleViewerReady = () => {
    const stream = streamRef.current
    const selfId = user?.id
    if (!stream || !selfId) {
      if (selfId) signaling.publish({ peerId: selfId, type: 'unavailable' })
      return
    }
    reoffer(stream)
  }

  const handleSignal = (signal: RtcSignal) => {
    if (!signal || signal.senderRole === 'STUDENT' || signal.senderId === user?.id) return
    if (signal.type === 'viewer_ready') {
      handleViewerReady()
      return
    }
    if (signal.type === 'bye') {
      stopPeer()
      if (streamRef.current) setPhase('sharing')
      return
    }
    if (signal.type === 'offer' || signal.type === 'unavailable') return
    if (!peerRef.current) return
    const message: SignalMessage = {
      type: signal.type,
      sdp: signal.sdp ?? null,
      candidate: signal.candidate ?? null,
    }
    const stream = streamRef.current
    if (!stream) return
    void peerRef.current.handleSignal(message).catch(() => stepRef.current.beginOffer(stream, true))
  }

  const start = async () => {
    if (!room || !user || !token) return
    const devices = navigator.mediaDevices
    if (!devices?.getDisplayMedia) {
      setPhase('failed')
      return
    }
    setPhase('starting')
    manualStopRef.current = false
    try {
      const stream = await devices.getDisplayMedia({ video: true, audio: false })
      streamRef.current = stream
      stream.getVideoTracks().forEach((track) => {
        track.addEventListener('ended', () => {
          if (manualStopRef.current) return
          sendBye()
          cleanup()
          setPhase('stopped')
        })
      })
      iceRetriesRef.current = 0
      stepRef.current.beginOffer(stream)
    } catch (error) {
      const name = (error as DOMException | undefined)?.name
      streamRef.current = null
      if (name === 'AbortError' || name === 'NotReadableError') {
        setPhase('stopped')
      } else {
        setPhase('failed')
      }
    }
  }

  const stop = () => {
    manualStopRef.current = true
    sendBye()
    cleanup()
    setPhase('stopped')
  }

  useEffect(() => () => {
    sendBye()
    cleanup()
  }, [cleanup, sendBye])

  const captionText = phase === 'sharing' && signaling.connectionState !== 'connected'
    ? 'Screen sharing is active — reconnecting the viewer connection. No new permission dialog is needed.'
    : caption(phase)

  return (
    <section className="mb-6 rounded-2xl border border-border bg-card p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-start gap-3">
          <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-primary/10 text-primary">
            {phase === 'failed' ? <VideoOff size={18} /> : <MonitorUp size={18} />}
          </div>
          <div>
            <h2 className="flex items-center gap-2 text-sm font-semibold">Screen sharing</h2>
            <p className="mt-0.5 text-xs leading-5 text-muted-foreground">{captionText}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {(phase === 'starting' || phase === 'reconnecting') && <Loader2 size={16} className="animate-spin text-muted-foreground" aria-hidden="true" />}
          {(phase === 'idle' || phase === 'stopped' || phase === 'failed') && (
            <button
              type="button"
              onClick={() => void start()}
              className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]"
            >
              <MonitorUp size={16} /> Share screen
            </button>
          )}
          {phase === 'sharing' && (
            <button
              type="button"
              onClick={stop}
              className="flex items-center gap-2 rounded-xl border border-destructive/40 px-4 py-2 text-sm font-semibold text-destructive transition hover:bg-destructive/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]"
            >
              <Square size={14} /> Stop sharing
            </button>
          )}
          {phase === 'reconnecting' && (
            <button
              type="button"
              onClick={stop}
              className="flex items-center gap-2 rounded-xl border border-destructive/40 px-4 py-2 text-sm font-semibold text-destructive transition hover:bg-destructive/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-[0.98]"
            >
              <Square size={14} /> Stop sharing
            </button>
          )}
        </div>
      </div>
    </section>
  )
}

function caption(phase: SharePhase): string {
  switch (phase) {
    case 'idle':
      return 'Share your screen so your proctor can see your workspace. Only the screen is captured, never audio.'
    case 'starting':
      return 'Waiting for you to choose a screen to share…'
    case 'sharing':
      return 'Your proctor can see your screen. Keep sharing until you finish.'
    case 'reconnecting':
      return 'The viewer connection is being restored. Your screen capture stays active.'
    case 'stopped':
      return 'Screen sharing stopped. You can start again anytime.'
    case 'failed':
      return 'Screen sharing is unavailable. No screen was captured and this is not recorded.'
    case 'unavailable':
      return 'Screen sharing is unavailable until you join this exam room.'
  }
}