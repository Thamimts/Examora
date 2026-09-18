export interface FaceCountConfig {
  /** Interval between camera detection ticks (ms). 250ms => 4 detections/sec (within the 2-5/sec requirement). */
  detectionIntervalMs: number
  /** Consecutive anomalous observations required before a FACE_COUNT_ANOMALY signal is emitted. */
  anomalyConfirmations: number
  /** Minimum delay between FACE_COUNT_ANOMALY signals while an anomaly persists (ms). */
  cooldownMs: number
  /** Minimum model confidence for a face to be counted by the detector. */
  minFaceConfidence: number
}

export const DEFAULT_FACE_COUNT_CONFIG: FaceCountConfig = {
  detectionIntervalMs: 250,
  anomalyConfirmations: 3,
  cooldownMs: 10_000,
  minFaceConfidence: 0.5,
}

export type FaceCountStatus = 'initializing' | 'active' | 'paused' | 'unavailable' | 'error'

export interface FaceCountState {
  status: FaceCountStatus
  /** Most recent observed face count (only meaningful while active). */
  faceCount: number | null
  /** Most recent detector confidence in 0-1 (null when nothing was detected). */
  confidence: number | null
  /** Neutral, non-alarming human-readable detail. */
  detail?: string
}

export interface FaceCountDetection {
  faceCount: number
  /** Normalized 0-1 confidence for the observation; null when no face was detected. */
  confidence: number | null
  /** Epoch ms when the frame was analysed. */
  detectedAt: number
}

export interface FaceCountDetector {
  readonly label: string
  initialize(config?: FaceCountConfig): Promise<void>
  detect(video: HTMLVideoElement): FaceCountDetection
  dispose(): void
}

export interface FaceCountControllerStats {
  initialized: boolean
  initDurationMs: number | null
  detectionCount: number
  lastDetectionDurationMs: number | null
  averageDetectionDurationMs: number | null
  signalCount: number
  lastError: string | null
}