import { useCallback, useEffect, useRef, useState } from 'react'
import type { RtcSignal, RtcSignalType } from '@/types/webrtc'
import { wsUrl, frame, disconnectFrame } from './useActivityFeed'

export type RtcSignalingState = 'connecting' | 'connected' | 'disconnected'

export type RtcSignalOutbound = {
  type: RtcSignalType
  peerId: string
  sdp?: RTCSessionDescriptionInit | null
  candidate?: RTCIceCandidateInit | null
}

const sendFrame = (command: string, headers: Record<string, string>, body: string) => {
  const headerLines = Object.entries(headers).map(([key, value]) => `${key}:${value}`).join('\n')
  return `${command}\n${headerLines}\n\n${body}\0`
}

export function useRtcSignaling(
  roomId: string | null,
  token: string | null,
  subscription: string | null,
  onSignal: (signal: RtcSignal) => void,
) {
  const [connectionState, setConnectionState] = useState<RtcSignalingState>('disconnected')
  const onSignalRef = useRef(onSignal)
  onSignalRef.current = onSignal
  const socketRef = useRef<WebSocket | null>(null)

  const publish = useCallback((signal: RtcSignalOutbound) => {
    const currentSocket = socketRef.current
    if (!currentSocket || !roomId || currentSocket.readyState !== WebSocket.OPEN) return false
    currentSocket.send(sendFrame('SEND',
      {
        destination: `/app/exam-rooms/${roomId}/webrtc`,
        'content-type': 'application/json',
      },
      JSON.stringify(signal)))
    return true
  }, [roomId])

  useEffect(() => {
    if (!token || !roomId || !subscription) return

    let socket: WebSocket | undefined
    let reconnectTimer: number | undefined
    let closed = false
    let retry = 0
    const subId = `webrtc-${roomId}`

    const connect = () => {
      if (closed) return
      setConnectionState('connecting')
      const currentSocket = new WebSocket(wsUrl())
      socket = currentSocket
      socketRef.current = currentSocket
      let subscribed = false

      currentSocket.onmessage = ({ data }) => {
        String(data)
          .split('\0')
          .forEach((raw) => {
            if (!raw) return
            const [head, body = ''] = raw.split('\n\n')
            if (head.startsWith('MESSAGE')) {
              if (!body) return
              try {
                onSignalRef.current(JSON.parse(body))
              } catch {
                /* ignore malformed frames */
              }
              return
            }
            if (head.startsWith('CONNECTED')) {
              if (subscribed || currentSocket.readyState !== WebSocket.OPEN) return
              subscribed = true
              setConnectionState('connected')
              retry = 0
              currentSocket.send(frame('SUBSCRIBE', { id: subId, destination: subscription, ack: 'auto' }))
            }
          })
      }

      currentSocket.onopen = () => {
        currentSocket.send(frame('CONNECT', {
          'accept-version': '1.2',
          'heart-beat': '10000,10000',
          authorization: `Bearer ${token}`,
        }))
      }

      currentSocket.onclose = () => {
        if (socket === currentSocket && !closed) {
          setConnectionState('disconnected')
          const delay = Math.min(30000, 1000 * 2 ** retry)
          reconnectTimer = window.setTimeout(connect, delay)
          retry += 1
        }
      }

      currentSocket.onerror = () => {
        if (currentSocket.readyState !== WebSocket.CLOSED) currentSocket.close()
      }
    }

    connect()

    return () => {
      closed = true
      socketRef.current = null
      if (reconnectTimer) window.clearTimeout(reconnectTimer)
      const currentSocket = socket
      socket = undefined
      if (currentSocket?.readyState === WebSocket.OPEN) {
        currentSocket.send(disconnectFrame)
        window.setTimeout(() => currentSocket.close(), 50)
      } else {
        currentSocket?.close()
      }
      setConnectionState('disconnected')
    }
  }, [roomId, token, subscription])

  return { connectionState, publish }
}