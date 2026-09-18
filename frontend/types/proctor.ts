export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'

export type WarningLevel = 'NONE' | 'WARNING_1' | 'WARNING_2' | 'WARNING_3'

export type ExamAccessStatus = 'ELIGIBLE' | 'SUSPENDED' | 'RETEST_PENDING' | 'RETEST_APPROVED' | 'RETEST_REJECTED'

export type ProctorEventType =
  | 'TAB_SWITCH'
  | 'WINDOW_BLUR'
  | 'CAMERA_OFF'
  | 'MULTIPLE_FACES'
  | 'AUDIO_DETECTED'
  | 'NETWORK_INTERRUPTION'

export type StudentProctorEventType = ProctorEventType | 'FULLSCREEN_EXIT'

export type ProctorSignalSource =
  | 'BROWSER'
  | 'CLIENT_AI'
  | 'SERVER_AI'
  | 'HUMAN_INVIGILATOR'
  | 'SYSTEM'

export type FutureAiSignalType =
  | 'FACE_COUNT_ANOMALY'
  | 'PHONE_DETECTED'
  | 'UNKNOWN_OBJECT'
  | 'GAZE_ANOMALY'
  | 'HEAD_POSE_ANOMALY'

export type ProctorSignalType = StudentProctorEventType | FutureAiSignalType

export interface ProctorSignalInput {
  signalId?: string
  signalType: ProctorSignalType
  source?: ProctorSignalSource
  occurredAt?: string
  confidence?: number
  durationMs?: number
  metadata?: Record<string, unknown>
}

export interface SubmitSignalResponse {
  saved: number
}

export interface StudentProctorEvent {
  eventId: string
  attemptId: string
  type: StudentProctorEventType
  occurredAt: string
  metadata?: Record<string, unknown>
}

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
  source: ProctorSignalSource
  confidence: number | null
  durationMs: number | null
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

export interface ProctorSummary {
  examId: string
  examTitle: string
  hasAttempts: boolean
  totalAttempts: number
  activeAttempts: number
  eventCount: number
  riskLevel: RiskLevel
  riskScore: number | null
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
  warningCount: number
  warningLevel: WarningLevel
  accessStatus: ExamAccessStatus
}

export interface CommandCenterExam {
  examId: string
  examTitle: string
  status: string
  duration: number
  totalStudents: number
  joinedStudents: number
  activeAttempts: number
  submittedAttempts: number
  offlineParticipants: number
  totalWarnings: number
  terminatedAttempts: number
}

export interface CommandCenterRoom {
  roomId: string
  roomCode: string
  status: 'WAITING' | 'ACTIVE' | 'ENDED'
  memberCount: number
  startedAt: string | null
  endedAt: string | null
}

export interface CommandCenterStudent {
  studentId: string
  studentName: string
  studentEmail: string
  roomId: string | null
  roomCode: string | null
  roomStatus: 'WAITING' | 'ACTIVE' | 'ENDED' | null
  attemptId: string | null
  attemptNumber: number | null
  attemptStatus: string | null
  activeNow: boolean
  startedAt: string | null
  expiresAt: string | null
  warningCount: number
  warningLevel: WarningLevel
  accessStatus: ExamAccessStatus
  riskLevel: RiskLevel
  riskScore: number
  eventCount: number
  latestProctorEvent: ProctorEvent | null
  lastActivityAt: string | null
  cameraOff: boolean | null
  fullscreenExited: boolean | null
  audioSignalCount: number
  networkInterruptionCount: number
}

export interface CommandCenterData {
  exam: CommandCenterExam
  rooms: CommandCenterRoom[]
  roster: CommandCenterStudent[]
}
