import { useEffect, useRef, useState } from 'react'
import type { RoomActivity } from '@/types/examRoom'
import { wsUrl, frame, disconnectFrame } from './useActivityFeed'

export type RoomConnectionState = 'connecting' | 'connected' | 'disconnected'

export function useExamRoomFeed(
  roomId: string | null,
  token: string | null,
  onEvent: (event: RoomActivity) => void,
) {
  const [connectionState, setConnectionState] = useState<RoomConnectionState>('disconnected')
  const onEventRef = useRef(onEvent)
  onEventRef.current = onEvent

  useEffect(() => {
    if (!token || !roomId) return

    let socket: WebSocket | undefined
    let reconnectTimer: number | undefined
    let closed = false
    let retry = 0
    const subId = `exam-room-${roomId}`

    const connect = () => {
      if (closed) return
      setConnectionState('connecting')
      const currentSocket = new WebSocket(wsUrl())
      socket = currentSocket
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
                onEventRef.current(JSON.parse(body))
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
              currentSocket.send(
                frame('SUBSCRIBE', {
                  id: subId,
                  destination: `/topic/exam-rooms/${roomId}/activity`,
                  ack: 'auto',
                }),
              )
            }
          })
      }

      currentSocket.onopen = () => {
        currentSocket.send(
          frame('CONNECT', {
            'accept-version': '1.2',
            'heart-beat': '10000,10000',
            authorization: `Bearer ${token}`,
          }),
        )
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
  }, [roomId, token])

  return { connectionState }
}