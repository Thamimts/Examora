import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FaceCountController, startFaceCountController } from './FaceCountController'
import type { FaceCountControllerStats, FaceCountDetector, FaceCountState } from './types'
import type { ProctorSignalInput } from '@/types/proctor'

const TICK_MS = 250

interface TrackSpy {
  stop: ReturnType<typeof vi.fn>
}

function fakeStream(): { getTracks: () => Array<{ stop: typeof vi.fn }>; track: TrackSpy } {
  const stop = vi.fn()
  const track = { stop }
  return { getTracks: () => [track], track }
}

function fakeVideo() {
  return {
    srcObject: null,
    readyState: 4,
    currentTime: 0,
    videoWidth: 640,
    videoHeight: 480,
    paused: false,
    play: vi.fn().mockResolvedValue(undefined),
    pause: vi.fn(),
  }
}

type FakeVideo = ReturnType<typeof fakeVideo>

function fakeDetector(results: Array<{ faceCount: number; confidence: number | null }>) {
  let index = 0
  const detect = vi.fn(() => {
    if (results.length === 0 || index >= results.length) return { faceCount: 1, confidence: 0.9, detectedAt: 0 }
    const result = results[index]
    index += 1
    return { faceCount: result.faceCount, confidence: result.confidence, detectedAt: index }
  })
  const dispose = vi.fn()
  const detector: FaceCountDetector = { label: 'fake', initialize: vi.fn().mockResolvedValue(undefined), detect, dispose }
  return { detector, detect, dispose }
}

function makeController(opts: {
  detector: FaceCountDetector
  video?: FakeVideo
  submit?: (signal: ProctorSignalInput) => Promise<unknown>
  onStateChange?: (state: FaceCountState) => void
}) {
  const video = opts.video ?? fakeVideo()
  const stream = fakeStream()
  let clock = 0
  const now = () => clock
  const submit = opts.submit ?? vi.fn().mockResolvedValue({ saved: 1 })
  const states: FaceCountState[] = []
  const onStateChange = opts.onStateChange ?? ((s: FaceCountState) => states.push(s))
  const controller = new FaceCountController({
    detector: opts.detector,
    video,
    stream,
    submit,
    onStateChange,
    now,
  })
  const advance = (ms: number, videoTicks?: number): void => {
    if (videoTicks !== undefined) {
      // Coarse single-step advance (only used by the stale-frame test).
      clock += ms
      video.currentTime += videoTicks
      vi.advanceTimersByTime(ms)
      return
    }
    // Per-tick stepping: each interval firing sees a freshly advanced frame.
    const ticks = Math.floor(ms / TICK_MS)
    const remainder = ms - ticks * TICK_MS
    for (let i = 0; i < ticks; i += 1) {
      clock += TICK_MS
      video.currentTime += 1
      vi.advanceTimersByTime(TICK_MS)
    }
    if (remainder > 0) {
      clock += remainder
      vi.advanceTimersByTime(remainder)
    }
  }
  return { controller, video, stream, submit, states, advance }
}

describe('FaceCountController', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('runs a bounded detection loop (~4 Hz) and produces no signal for a normal face count', () => {
    const { detector } = fakeDetector([]) // always faceCount 1
    const { controller, advance, submit } = makeController({ detector })
    controller.start()
    advance(1000)
    controller.dispose()
    const stats = controller.getStats()
    expect(stats.detectionCount).toBeGreaterThanOrEqual(3)
    expect(stats.detectionCount).toBeLessThanOrEqual(5)
    expect(submit).not.toHaveBeenCalled()
  })

  it('emits one FACE_COUNT_ANOMALY signal after 3 consecutive 2-face frames', () => {
    const { detector } = fakeDetector([{ faceCount: 2, confidence: 0.8 }, { faceCount: 2, confidence: 0.7 }, { faceCount: 2, confidence: 0.9 }])
    const payloads: ProctorSignalInput[] = []
    const c = makeController({ detector, submit: async (s) => { payloads.push(s); return { saved: 1 } } })
    c.controller.start()
    c.advance(750)
    expect(payloads.length).toBe(1)
    expect(payloads[0].metadata).toMatchObject({ faceCount: 2 })
    expect(payloads[0].metadata!.rawMediaBytesDelta).toBeTypeOf('number')
    expect(payloads[0].metadata!.rawMediaBytesDelta!).toBeGreaterThanOrEqual(0)
  })

  it('does not emit after only two anomalous frames', () => {
    const { detector } = fakeDetector([{ faceCount: 2, confidence: 0.8 }, { faceCount: 2, confidence: 0.7 }])
    const payloads: ProctorSignalInput[] = []
    const c = makeController({ detector, submit: async (s) => { payloads.push(s); return { saved: 1 } } })
    c.controller.start()
    c.advance(750)
    expect(payloads.length).toBe(0)
  })

  it('signals zero-face anomalies with faceCount 0 and no confidence', () => {
    const { detector } = fakeDetector([{ faceCount: 0, confidence: null }, { faceCount: 0, confidence: null }, { faceCount: 0, confidence: null }])
    const payloads: ProctorSignalInput[] = []
    const c = makeController({ detector, submit: async (s) => { payloads.push(s); return { saved: 1 } } })
    c.controller.start()
    c.advance(750)
    expect(payloads.length).toBe(1)
    expect(payloads[0].metadata).toMatchObject({ faceCount: 0 })
    expect(payloads[0].metadata!.rawMediaBytesDelta).toBeTypeOf('number')
    expect(payloads[0].confidence).toBeUndefined()
  })

  it('rate limits: one signal per ~10 s while a 2-face anomaly persists, not one per frame', () => {
    const { detector } = fakeDetector([])
    detector.detect = vi.fn(() => ({ faceCount: 2, confidence: 0.85, detectedAt: 0 }))
    const payloads: ProctorSignalInput[] = []
    const c = makeController({ detector, submit: async (s) => { payloads.push(s); return { saved: 1 } } })
    c.controller.start()
    c.advance(30_000)
    expect(payloads.length).toBe(3)
    for (let i = 1; i < payloads.length; i += 1) {
      const gap = new Date(payloads[i].occurredAt!).getTime() - new Date(payloads[i - 1].occurredAt!).getTime()
      expect(gap).toBeGreaterThanOrEqual(9_000)
    }
  })

  it('pauses detection on request and resumes cleanly', () => {
    const { detector } = fakeDetector([])
    const c = makeController({ detector })
    c.controller.start()
    c.advance(500)
    c.controller.pause()
    const before = c.controller.getStats().detectionCount
    c.advance(1000)
    expect(c.controller.getStats().detectionCount).toBe(before)
    c.controller.resume()
    c.advance(500)
    expect(c.controller.getStats().detectionCount).toBeGreaterThan(before)
  })

  it('dispose stops the loop, releases the camera track and disposes the detector', () => {
    const { detector, dispose } = fakeDetector([])
    const c = makeController({ detector })
    c.controller.start()
    c.advance(500)
    c.controller.dispose()
    expect(dispose).toHaveBeenCalledTimes(1)
    expect(c.stream.track.stop).toHaveBeenCalledTimes(1)
    const before = c.controller.getStats().detectionCount
    c.advance(1000)
    expect(c.controller.getStats().detectionCount).toBe(before)
  })

  it('never signals after disposal (attempt end / retest)', () => {
    const { detector } = fakeDetector([])
    detector.detect = vi.fn(() => ({ faceCount: 2, confidence: 0.8, detectedAt: 0 }))
    const payloads: ProctorSignalInput[] = []
    const c = makeController({ detector, submit: async (s) => { payloads.push(s); return { saved: 1 } } })
    c.controller.start()
    c.advance(500)
    c.controller.dispose()
    c.advance(5000)
    expect(payloads.length).toBe(0)
  })

  it('keeps running and retries after a submit (network) failure and does not break', async () => {
    const { detector } = fakeDetector([])
    detector.detect = vi.fn(() => ({ faceCount: 2, confidence: 0.9, detectedAt: 0 }))
    let calls = 0
    const submit = vi.fn(async () => {
      calls += 1
      if (calls <= 2) throw new Error('network down')
      return { saved: 1 }
    })
    const c = makeController({ detector, submit })
    c.controller.start()
    c.advance(20_250)
    expect(calls).toBeGreaterThanOrEqual(2)
    const stats = c.controller.getStats()
    expect(stats.detectionCount).toBeGreaterThan(0)
  })

  it('submitted payloads contain only structured evidence and never raw media', () => {
    const { detector } = fakeDetector([{ faceCount: 2, confidence: 0.9 }, { faceCount: 2, confidence: 0.9 }, { faceCount: 2, confidence: 0.9 }])
    const payloads: ProctorSignalInput[] = []
    const c = makeController({ detector, submit: async (s) => { payloads.push(s); return { saved: 1 } } })
    c.controller.start()
    c.advance(750)
    expect(payloads).toHaveLength(1)
    const payload = payloads[0]
    expect(payload.signalType).toBe('FACE_COUNT_ANOMALY')
    expect(payload.source).toBe('CLIENT_AI')
    expect(payload.metadata).toMatchObject({ faceCount: 2 })
    expect(payload.metadata!.rawMediaBytesDelta).toBeTypeOf('number')
    expect(payload.metadata!.rawMediaBytesDelta!).toBeGreaterThanOrEqual(0)
    expect(payload.confidence).toBeTypeOf('number')
    expect(payload.confidence!).toBeGreaterThanOrEqual(0)
    expect(payload.confidence!).toBeLessThanOrEqual(1)
    expect(Number.isNaN(Date.parse(payload.occurredAt!))).toBe(false)
    expect(typeof payload.durationMs).toBe('number')
    const serialized = JSON.stringify(payload)
    expect(serialized).not.toMatch(/base64|data:image|blob:|video|stream|frame/i)
  })

  it('skips detection until the video has frames (readyState >= 2)', () => {
    const { detector, detect } = fakeDetector([])
    const video = fakeVideo()
    video.readyState = 0
    const c = makeController({ detector, video })
    c.controller.start()
    c.advance(1000)
    expect(detect).not.toHaveBeenCalled()
  })

  it('does not re-analyse a frame that has not advanced', () => {
    const { detector, detect } = fakeDetector([])
    const video = fakeVideo()
    const c = makeController({ detector, video })
    c.controller.start()
    c.advance(1000, 0) // currentTime not advanced
    expect(detect).toHaveBeenCalledTimes(1)
  })

  it('recovers to active and never signals when the detector throws transiently', () => {
    const { detector } = fakeDetector([])
    let failing = true
    detector.detect = vi.fn(() => {
      if (failing) {
        failing = false
        throw new Error('model hiccup')
      }
      return { faceCount: 1, confidence: 0.9, detectedAt: 0 }
    })
    const payloads: ProctorSignalInput[] = []
    const states: FaceCountState[] = []
    const c = makeController({ detector, submit: async (s) => { payloads.push(s); return { saved: 1 } }, onStateChange: (s) => states.push(s) })
    c.controller.start()
    c.advance(500)
    c.advance(500)
    expect(payloads.length).toBe(0)
    expect(states.some((s) => s.status === 'error')).toBe(true)
    expect(states.some((s) => s.status === 'active')).toBe(true)
  })

  it('startFaceCountController reports initializing then rejects on camera unavailability without any submit', async () => {
    const states: FaceCountState[] = []
    const submit = vi.fn()
    await expect(
      startFaceCountController({
        submit: async (s) => { submit(s); return { saved: 1 } },
        onStateChange: (s) => states.push(s),
        acquireCamera: async () => { throw new Error('NotAllowedError') },
      }),
    ).rejects.toThrow('NotAllowedError')
    expect(states[0].status).toBe('initializing')
    expect(submit).not.toHaveBeenCalled()
  })

  it('exposes truthful stats (no telemetry is sent)', () => {
    const { detector } = fakeDetector([])
    const c = makeController({ detector })
    c.controller.start()
    c.advance(1000)
    const stats: FaceCountControllerStats = c.controller.getStats()
    expect(stats.initialized).toBe(true)
    expect(stats.detectionCount).toBeGreaterThan(0)
    expect(stats.lastDetectionDurationMs).toBeGreaterThanOrEqual(0)
    expect(stats.signalCount).toBe(0)
  })
})