import { DEFAULT_FACE_COUNT_CONFIG, type FaceCountConfig } from './types'

export interface FaceCountObservation {
  faceCount: number
  confidence: number | null
  /** Epoch ms of the observation to which all timing refers. */
  detectedAt: number
}

export interface AnomalySignalPayload {
  faceCount: number
  confidence: number | null
  occurredAt: number
  durationMs: number
  confirmations: number
}

export type FaceCountPolicyOutcome =
  | { type: 'normal' }
  | { type: 'anomaly-streak' }
  | { type: 'anomaly-signal'; signal: AnomalySignalPayload }

/**
 * Temporal stabilization + rate limiting for face-count observations (pure logic).
 *
 * - NORMAL is exactly faceCount === 1. A normal observation resets all state and never emits a signal.
 * - An anomaly (faceCount === 0 or >= 2) must be observed for `anomalyConfirmations` consecutive
 *   detections before a signal is produced, so short detection glitches produce no output.
 * - After a signal, no further signal is emitted for `cooldownMs`. If the anomaly persists past the
 *   cooldown, the next anomalous observation emits one more signal (evidence sampling, one signal per
 *   ~cooldown interval while the anomaly holds — not one per frame).
 */
export class FaceCountPolicy {
  private streak = 0
  private windowStartedAt = 0
  private cooldownUntil = 0

  constructor(private readonly config: FaceCountConfig = DEFAULT_FACE_COUNT_CONFIG) {}

  observe(observation: FaceCountObservation): FaceCountPolicyOutcome {
    const anomalous = observation.faceCount !== 1

    if (!anomalous) {
      this.streak = 0
      this.windowStartedAt = 0
      this.cooldownUntil = 0
      return { type: 'normal' }
    }

    if (this.streak === 0) this.windowStartedAt = observation.detectedAt
    this.streak += 1

    if (this.streak < this.config.anomalyConfirmations) return { type: 'anomaly-streak' }
    if (observation.detectedAt < this.cooldownUntil) return { type: 'anomaly-streak' }

    this.cooldownUntil = observation.detectedAt + this.config.cooldownMs
    return {
      type: 'anomaly-signal',
      signal: {
        faceCount: observation.faceCount,
        confidence: observation.confidence,
        occurredAt: observation.detectedAt,
        durationMs: Math.max(0, observation.detectedAt - this.windowStartedAt),
        confirmations: this.streak,
      },
    }
  }
}

export function faceCountIsNormal(faceCount: number): boolean {
  return faceCount === 1
}

export function faceCountIsAnomalous(faceCount: number): boolean {
  return faceCount !== 1
}