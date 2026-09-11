export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'

export type ProctorEventType =
  | 'TAB_SWITCH'
  | 'WINDOW_BLUR'
  | 'CAMERA_OFF'
  | 'MULTIPLE_FACES'
  | 'AUDIO_DETECTED'
  | 'NETWORK_INTERRUPTION'

export interface ProctorStudent {
  id: string
  name: string
  email: string
}

export interface ProctorEvent {
  id: string
  eventId: string | null
  attemptId: string
  type: string
  occurredAt: string
  serverReceivedAt: string
  metadata: Record<string, unknown> | null
}

export interface ProctorAttempt {
  attemptId: string
  attemptNumber: number
  status: string
  startedAt: string
  expiresAt: string
  student: ProctorStudent
  activeNow: boolean
  riskLevel: RiskLevel
  riskScore: number
  eventCount: number
  latestProctorEvent: ProctorEvent | null
  lastActivityAt: string | null
}

export interface ProctorMonitorData {
  examId: string
  examTitle: string
  attempts: ProctorAttempt[]
}

export interface ProctorUpdate {
  examId: string
  attemptId: string
  status: string
  student: ProctorStudent
  riskLevel: RiskLevel
  riskScore: number
  latestEvent: ProctorEvent | null
  eventCount: number
  sequence: number
  occurredAt: string
}
