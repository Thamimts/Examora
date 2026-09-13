import type { StudentProctorEvent, StudentProctorEventType } from '@/types/proctor'

export type ProctoringStatus = 'active' | 'reconnecting' | 'offline' | 'unavailable'

export interface ProctorClientOptions {
  attemptId: string
  submit: (events: StudentProctorEvent[]) => Promise<void>
  onStateChange: (status: ProctoringStatus) => void
}

export interface ProctorClientHandle {
  start: () => void
  dispose: () => void
}

const BATCH_SIZE = 10
const QUEUE_LIMIT = 60
const FLUSH_INTERVAL_MS = 1000
const RETRY_BACKOFF_MS = [1000, 2000, 4000, 8000, 16000, 30000]
const CAMERA_POLL_MS = 30000

const THROTTLE_MS: Record<StudentProctorEventType, number> = {
  TAB_SWITCH: 3000,
  WINDOW_BLUR: 3000,
  FULLSCREEN_EXIT: 3000,
  NETWORK_INTERRUPTION: 10000,
  CAMERA_OFF: 30000,
  MULTIPLE_FACES: 3000,
  AUDIO_DETECTED: 3000,
}

const newEventId = (): string =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`

const httpStatus = (error: unknown): number | null => {
  const status = (error as { response?: { status?: number } })?.response?.status
  return typeof status === 'number' ? status : null
}

const captureSupported = (): boolean =>
  typeof document !== 'undefined' && document.addEventListener !== undefined && document.visibilityState !== undefined

export function createProctorClient(options: ProctorClientOptions): ProctorClientHandle {
  const { attemptId, submit, onStateChange } = options

  const queue: StudentProctorEvent[] = []
  const lastSentAt = new Map<StudentProctorEventType, number>()
  let flushing = false
  let failures = 0
  let nextRetryAt = 0
  let flushTimer: number | undefined
  let cameraTimer: number | undefined
  let disposed = false

  const now = () => Date.now()

  const setState = (status: ProctoringStatus) => {
    if (!disposed) onStateChange(status)
  }

  const enqueue = (type: StudentProctorEventType, metadata?: Record<string, unknown>) => {
    const last = lastSentAt.get(type)
    if (last !== undefined && now() - last < THROTTLE_MS[type]) return
    while (queue.length >= QUEUE_LIMIT) queue.shift()
    queue.push({
      eventId: newEventId(),
      attemptId,
      type,
      occurredAt: new Date().toISOString(),
      metadata,
    })
    lastSentAt.set(type, now())
    if (!flushing && queue.length >= BATCH_SIZE) void tryFlush()
  }

  const tryFlush = async () => {
    if (flushing || disposed) return
    if (queue.length === 0) return
    if (now() < nextRetryAt) return
    if (typeof navigator === 'undefined' || navigator.onLine === false) return

    const batch = queue.slice(0, BATCH_SIZE)
    flushing = true
    try {
      await submit(batch)
      queue.splice(0, batch.length)
      failures = 0
      nextRetryAt = 0
      setState('active')
    } catch (error) {
      const status = httpStatus(error)
      if (status !== null && status >= 400 && status < 500) {
        queue.splice(0, batch.length)
        failures = 0
        nextRetryAt = 0
      } else {
        failures += 1
        if (failures > RETRY_BACKOFF_MS.length) {
          queue.splice(0, batch.length)
          failures = 0
          nextRetryAt = 0
        } else {
          nextRetryAt = now() + RETRY_BACKOFF_MS[failures - 1]
          setState('reconnecting')
        }
      }
    } finally {
      flushing = false
    }
  }

  const handleVisibilityChange = () => {
    if (document.visibilityState === 'hidden') enqueue('TAB_SWITCH')
  }

  const handleWindowBlur = () => {
    if (!document.hidden) enqueue('WINDOW_BLUR')
  }

  const handleFullscreenChange = () => {
    if (document.fullscreenElement === null || document.fullscreenElement === undefined) {
      enqueue('FULLSCREEN_EXIT')
    }
  }

  const handleOffline = () => {
    enqueue('NETWORK_INTERRUPTION')
    setState('offline')
  }

  const handleOnline = () => {
    if (disposed) return
    setState('reconnecting')
    void tryFlush()
  }

  const detectCamera = async () => {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) {
      return
    }
    try {
      const devices = await navigator.mediaDevices.enumerateDevices()
      const hasVideoInput = devices.some((device) => device.kind === 'videoinput')
      if (!hasVideoInput) enqueue('CAMERA_OFF')
    } catch {
      setState('unavailable')
    }
  }

  const subscribe = () => {
    document.addEventListener('visibilitychange', handleVisibilityChange)
    window.addEventListener('blur', handleWindowBlur)
    if (document.exitFullscreen !== undefined) {
      document.addEventListener('fullscreenchange', handleFullscreenChange)
    }
    window.addEventListener('offline', handleOffline)
    window.addEventListener('online', handleOnline)
  }

  const unsubscribe = () => {
    document.removeEventListener('visibilitychange', handleVisibilityChange)
    window.removeEventListener('blur', handleWindowBlur)
    if (document.exitFullscreen !== undefined) {
      document.removeEventListener('fullscreenchange', handleFullscreenChange)
    }
    window.removeEventListener('offline', handleOffline)
    window.removeEventListener('online', handleOnline)
  }

  const start = () => {
    if (disposed) return
    if (!captureSupported()) {
      setState('unavailable')
      return
    }
    subscribe()
    flushTimer = window.setInterval(() => void tryFlush(), FLUSH_INTERVAL_MS)
    cameraTimer = window.setInterval(() => void detectCamera(), CAMERA_POLL_MS)
    void detectCamera()
    setState('active')
  }

  const dispose = () => {
    if (disposed) return
    disposed = true
    unsubscribe()
    if (flushTimer !== undefined) window.clearInterval(flushTimer)
    if (cameraTimer !== undefined) window.clearInterval(cameraTimer)
    queue.length = 0
    setState('unavailable')
  }

  return { start, dispose }
}