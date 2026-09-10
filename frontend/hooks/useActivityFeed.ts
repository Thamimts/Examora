import { useEffect } from 'react'
import type { ActivityEvent, Role } from '@/types'
import api from '@/services/api'

const wsUrl = () => {
  const configuredApiUrl = String(api.defaults.baseURL || '/api')
  const base = new URL(configuredApiUrl, window.location.origin)
  const protocol = base.protocol === 'https:' ? 'wss:' : 'ws:'
  const path = base.pathname.replace(/\/api\/?$/, '') || '/'
  return `${protocol}//${base.host}${path.replace(/\/$/, '')}/ws`
}
const frame = (command: string, headers: Record<string, string> = {}) => {
  const headerLines = Object.entries(headers).map(([key, value]) => `${key}:${value}`).join('\n')
  return `${command}${headerLines ? `\n${headerLines}` : ''}\n\n\0`
}

const disconnectFrame = 'DISCONNECT\n\n\0'

export function useActivityFeed(role: Role | undefined, token: string | null, onEvent: (event: ActivityEvent) => void, refresh: () => void) {
  useEffect(() => {
    if (!token || (role !== 'STUDENT' && role !== 'ADMIN')) return
    let socket: WebSocket | undefined; let reconnect: number | undefined; let fallback: number | undefined; let closed = false; let retry = 0
    const connect = () => {
      if (closed) return
      const currentSocket = new WebSocket(wsUrl())
      socket = currentSocket
      let subscribed = false
      currentSocket.onmessage = ({ data }) => String(data).split('\0').forEach(raw => {
        if (!raw) return
        const [head, body = ''] = raw.split('\n\n')
        if (head.startsWith('MESSAGE')) {
          if (!body) return
          try { onEvent(JSON.parse(body)) } catch { /* ignore malformed frames */ }
          return
        }
        if (head.startsWith('CONNECTED')) {
          if (subscribed || currentSocket.readyState !== WebSocket.OPEN) return
          subscribed = true
          currentSocket.send(frame('SUBSCRIBE', { id: 'activity', destination: role === 'ADMIN' ? '/topic/admin/activity' : '/user/queue/activity', ack: 'auto' }))
        }
      })
      currentSocket.onopen = () => {
        retry = 0
        currentSocket.send(frame('CONNECT', {
          'accept-version': '1.2',
          'heart-beat': '10000,10000',
          authorization: `Bearer ${token}`,
        }))
      }
      currentSocket.onclose = () => {
        if (socket === currentSocket && !closed) {
          reconnect = window.setTimeout(connect, Math.min(30000, 1000 * 2 ** retry))
          retry += 1
        }
      }
      currentSocket.onerror = () => { if (currentSocket.readyState !== WebSocket.CLOSED) currentSocket.close() }
    }
    connect()
    fallback = window.setInterval(refresh, 30000)
    return () => {
      closed = true
      if (reconnect) window.clearTimeout(reconnect)
      if (fallback) window.clearInterval(fallback)
      const currentSocket = socket
      socket = undefined
      if (currentSocket?.readyState === WebSocket.OPEN) {
        currentSocket.send(disconnectFrame)
        window.setTimeout(() => currentSocket.close(), 50)
      } else {
        currentSocket?.close()
      }
    }
  }, [role, token, onEvent, refresh])
}