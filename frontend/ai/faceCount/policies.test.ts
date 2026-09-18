import { describe, expect, it } from 'vitest'
import { FaceCountPolicy, faceCountIsAnomalous, faceCountIsNormal } from './policies'
import { DEFAULT_FACE_COUNT_CONFIG } from './types'

const TICK_MS = 250

describe('FaceCountPolicy', () => {
  it('treats exactly one face as NORMAL and emits nothing', () => {
    const policy = new FaceCountPolicy()
    const outcome = policy.observe({ faceCount: 1, confidence: 0.9, detectedAt: 0 })
    expect(outcome.type).toBe('normal')
    expect(policy.observe({ faceCount: 1, confidence: 0.9, detectedAt: TICK_MS }).type).toBe('normal')
  })

  it('anomaly helpers agree', () => {
    expect(faceCountIsNormal(1)).toBe(true)
    expect(faceCountIsAnomalous(0)).toBe(true)
    expect(faceCountIsAnomalous(2)).toBe(true)
    expect(faceCountIsNormal(2)).toBe(false)
  })

  it('requires anomalyConfirmations consecutive anomalous frames before a signal', () => {
    const policy = new FaceCountPolicy()
    expect(policy.observe({ faceCount: 2, confidence: 0.8, detectedAt: 0 }).type).toBe('anomaly-streak')
    expect(policy.observe({ faceCount: 2, confidence: 0.7, detectedAt: TICK_MS }).type).toBe('anomaly-streak')
    const third = policy.observe({ faceCount: 2, confidence: 0.9, detectedAt: 2 * TICK_MS })
    expect(third.type).toBe('anomaly-signal')
    if (third.type === 'anomaly-signal') {
      expect(third.signal.faceCount).toBe(2)
      expect(third.signal.confidence).toBe(0.9)
      expect(third.signal.durationMs).toBe(2 * TICK_MS)
      expect(third.signal.confirmations).toBe(3)
    }
  })

  it('a short transient of 2 faces produces no signal', () => {
    const policy = new FaceCountPolicy()
    policy.observe({ faceCount: 2, confidence: 0.8, detectedAt: 0 })
    policy.observe({ faceCount: 1, confidence: 0.9, detectedAt: TICK_MS })
    const outcome = policy.observe({ faceCount: 2, confidence: 0.8, detectedAt: 2 * TICK_MS })
    expect(outcome.type).toBe('anomaly-streak')
  })

  it('emits an anomaly signal for zero faces with null confidence', () => {
    const policy = new FaceCountPolicy()
    policy.observe({ faceCount: 0, confidence: null, detectedAt: 0 })
    policy.observe({ faceCount: 0, confidence: null, detectedAt: TICK_MS })
    const third = policy.observe({ faceCount: 0, confidence: null, detectedAt: 2 * TICK_MS })
    expect(third.type).toBe('anomaly-signal')
    if (third.type === 'anomaly-signal') {
      expect(third.signal.faceCount).toBe(0)
      expect(third.signal.confidence).toBeNull()
    }
  })

  it('rate limits: one signal per cooldown while an anomaly persists, not one per frame', () => {
    const policy = new FaceCountPolicy()
    const signals: number[] = []
    for (let i = 0; i < 120; i += 1) {
      const outcome = policy.observe({ faceCount: 2, confidence: 0.85, detectedAt: i * TICK_MS })
      if (outcome.type === 'anomaly-signal') signals.push(outcome.signal.occurredAt)
    }
    expect(signals.length).toBe(3)
    for (let i = 1; i < signals.length; i += 1) {
      expect(signals[i] - signals[i - 1]).toBeGreaterThanOrEqual(DEFAULT_FACE_COUNT_CONFIG.cooldownMs)
    }
  })

  it('resets the cooldown when a normal observation interrupts the anomaly', () => {
    const policy = new FaceCountPolicy()
    for (let i = 0; i < 3; i += 1) policy.observe({ faceCount: 2, confidence: 0.8, detectedAt: i * TICK_MS })
    policy.observe({ faceCount: 1, confidence: 0.9, detectedAt: 3 * TICK_MS })
    const outcome = policy.observe({ faceCount: 2, confidence: 0.8, detectedAt: 4 * TICK_MS })
    expect(outcome.type).toBe('anomaly-streak')
  })
})