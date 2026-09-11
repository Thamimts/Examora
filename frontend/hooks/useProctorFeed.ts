import { useEffect, useRef, useState } from 'react'
import type { ProctorUpdate } from '@/types/proctor'
import { wsUrl, frame, disconnectFrame } from './useActivityFeed'

export type ConnectionState = 'connecting' | 'connected' | 'disconnected'

export function useProctorFeed(
  examId: string | null,
  token: string | null,
  onEvent: (event: ProctorUpdate) => void,
) {
  const [connectionState, setConnectionState] = useState<ConnectionState>('disconnected')
  const onEventRef = useRef(onEvent)
  onEventRef.current = onEvent

  useEffect(() => {
    if (!token || !examId) return

    let socket: WebSocket | undefined
    let reconnectTimer: number | undefined
    let closed = false
    let retry = 0
    const subId = `proctor-${examId}`

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
                  destination: `/topic/exams/${examId}/activity`,
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
  }, [examId, token])

  return { connectionState }
}
