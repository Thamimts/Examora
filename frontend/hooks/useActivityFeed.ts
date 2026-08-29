import { useEffect } from 'react'
import type { ActivityEvent, Role } from '@/types'
import api from '@/services/api'

const wsUrl = (token: string) => {
  const configuredApiUrl = String(api.defaults.baseURL || '/api')
  const base = new URL(configuredApiUrl, window.location.origin)
  const protocol = base.protocol === 'https:' ? 'wss:' : 'ws:'
  const path = base.pathname.replace(/\/api\/?$/, '') || '/'
  return `${protocol}//${base.host}${path.replace(/\/$/, '')}/ws?token=${encodeURIComponent(token)}`
}
const frame = (command: string, headers: Record<string, string> = {}) => `${command}\n${Object.entries(headers).map(([key, value]) => `${key}:${value}`).join('\n')}\n\n\0`

export function useActivityFeed(role: Role | undefined, token: string | null, onEvent: (event: ActivityEvent) => void, refresh: () => void) {
  useEffect(() => {
    if (!token || (role !== 'STUDENT' && role !== 'ADMIN')) return
    let socket: WebSocket | undefined; let reconnect: number | undefined; let fallback: number | undefined; let closed = false; let retry = 0
    const connect = () => {
      socket = new WebSocket(wsUrl(token))
      socket.onmessage = ({ data }) => String(data).split('\0').forEach(raw => {
        const [head, body = ''] = raw.split('\n\n'); if (!head.startsWith('CONNECTED')) { if (!head.startsWith('MESSAGE')) return; try { onEvent(JSON.parse(body)) } catch {} ; return }
        socket?.send(frame('SUBSCRIBE', { id: 'activity', destination: role === 'ADMIN' ? '/topic/admin/activity' : '/user/queue/activity', ack: 'auto' }))
      })
      socket.onopen = () => { retry = 0; socket?.send(frame('CONNECT', { 'accept-version': '1.2', 'heart-beat': '10000,10000' })) }
      socket.onclose = () => { if (!closed) { reconnect = window.setTimeout(connect, Math.min(30000, 1000 * 2 ** retry)); retry += 1 } }
      socket.onerror = () => socket?.close()
    }
    connect()
    fallback = window.setInterval(refresh, 30000)
    return () => { closed = true; if (socket?.readyState === WebSocket.OPEN) socket.send(frame('DISCONNECT')); socket?.close(); if (reconnect) window.clearTimeout(reconnect); if (fallback) window.clearInterval(fallback) }
  }, [role, token, onEvent, refresh])
}
