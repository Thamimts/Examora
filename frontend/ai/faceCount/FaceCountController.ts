import type { ProctorSignalInput } from '@/types/proctor'
import { FaceCountPolicy } from './policies'
import { MediaPipeFaceCountDetector } from './MediaPipeFaceCountDetector'
import { DEFAULT_FACE_COUNT_CONFIG, type FaceCountConfig, type FaceCountDetector, type FaceCountState, type FaceCountControllerStats } from './types'

export const FACE_COUNT_ANOMALY_SIGNAL_TYPE = 'FACE_COUNT_ANOMALY' as const
export const CLIENT_AI_SOURCE = 'CLIENT_AI' as const

export interface CameraTrackLike {
  stop: () => void
  addEventListener?: (type: string, listener: () => void) => void
  removeEventListener?: (type: string, listener: () => void) => void
}

export interface CameraTracksLike {
  getTracks: () => CameraTrackLike[]
}

/** Minimal video-surface contract used by the controller so tests need no DOM. */
export interface CameraVideoLike {
  srcObject: unknown
  readyState: number
  currentTime: number
  play: () => Promise<void> | void
  pause: () => void
}

export interface FaceCountControllerOptions {
  detector: FaceCountDetector
  video: CameraVideoLike
  stream: CameraTracksLike
  submit: (signal: ProctorSignalInput) => Promise<unknown>
  onStateChange: (state: FaceCountState) => void
  config?: Partial<FaceCountConfig>
  /** Inject an epoch-ms clock for deterministic tests. */
  now?: () => number
  /** Injectable timer fns (defaults to globals). */
  setIntervalFn?: typeof setInterval
  clearIntervalFn?: typeof clearInterval
}

export class FaceCountController {
  private readonly config: FaceCountConfig
  private readonly policy: FaceCountPolicy
  private readonly now: () => number
  private readonly setIntervalFn: typeof setInterval
  private readonly clearIntervalFn: typeof clearInterval

  private timer: ReturnType<typeof setInterval> | number | null = null
  private disposed = false
  private started = false
  private paused = false
  private lastVideoTime = -1
  private lastFaceCount: number | null = null
  private lastConfidence: number | null = null

  private readonly stats: FaceCountControllerStats = {
    initialized: true,
    initDurationMs: null,
    detectionCount: 0,
    lastDetectionDurationMs: null,
    averageDetectionDurationMs: null,
    signalCount: 0,
    lastError: null,
  }
  private detectionDurationsMs: number[] = []
  private onVisibilityChanged: (() => void) | null = null
  private stopTrackListeners: Array<() => void> = []

  constructor(private readonly deps: FaceCountControllerOptions) {
    this.config = { ...DEFAULT_FACE_COUNT_CONFIG, ...deps.config }
    this.policy = new FaceCountPolicy(this.config)
    this.now = deps.now ?? (() => Date.now())
    this.setIntervalFn = deps.setIntervalFn ?? setInterval
    this.clearIntervalFn = deps.clearIntervalFn ?? clearInterval
  }

  start(): void {
    if (this.disposed || this.started) return
    this.started = true
    this.attachTrackEnded()
    this.attachVisibilityTracking()
    void this.deps.video.play()
    this.resume()
  }

  pause(): void {
    this.paused = true
    this.clearTimer()
    this.reportState()
  }

  resume(): void {
    if (this.disposed) return
    this.paused = false
    this.clearTimer()
    this.timer = this.setIntervalFn(() => this.tick(), this.config.detectionIntervalMs)
    this.reportState()
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.clearTimer()
    try {
      this.deps.video.pause()
    } catch {
      /* ignore */
    }
    this.detachVisibilityTracking()
    this.detachTrackEnded()
    try {
      this.deps.stream.getTracks().forEach((track) => track.stop())
    } catch {
      /* ignore */
    }
    try {
      this.deps.detector.dispose()
    } catch {
      /* ignore */
    }
  }

  getStats(): FaceCountControllerStats {
    return { ...this.stats }
  }

  private tick(): void {
    if (this.disposed || this.paused) return
    const video = this.deps.video
    if (video.readyState < 2 || video.currentTime === this.lastVideoTime) return
    this.lastVideoTime = video.currentTime

    const startedAt = this.now()
    try {
      const detection = this.deps.detector.detect(video as HTMLVideoElement)
      const durationMs = Math.max(0, this.now() - startedAt)
      this.trackDetectionDuration(durationMs)
      this.lastFaceCount = detection.faceCount
      this.lastConfidence = detection.confidence
      this.reportState()

      const outcome = this.policy.observe({
        faceCount: detection.faceCount,
        confidence: detection.confidence,
        detectedAt: this.now(),
      })
      if (outcome.type === 'anomaly-signal') this.emitSignal(outcome.signal)
    } catch (error) {
      this.stats.lastError = error instanceof Error ? error.message : String(error)
      this.reportState(true)
    }
  }

  private emitSignal(signal: { faceCount: number; confidence: number | null; occurredAt: number; durationMs: number }): void {
    const payload: ProctorSignalInput = {
      signalType: FACE_COUNT_ANOMALY_SIGNAL_TYPE,
      source: CLIENT_AI_SOURCE,
      occurredAt: new Date(signal.occurredAt).toISOString(),
      durationMs: Math.round(signal.durationMs),
      metadata: { faceCount: signal.faceCount },
    }
    if (signal.confidence !== null && Number.isFinite(signal.confidence)) {
      payload.confidence = Math.min(1, Math.max(0, signal.confidence))
    }
    this.stats.signalCount += 1
    Promise.resolve(this.deps.submit(payload)).catch((error: unknown) => {
      this.stats.lastError = error instanceof Error ? error.message : String(error)
    })
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      this.clearIntervalFn(this.timer as Parameters<typeof clearInterval>[0])
      this.timer = null
    }
  }

  private trackDetectionDuration(durationMs: number): void {
    this.stats.detectionCount += 1
    this.stats.lastDetectionDurationMs = durationMs
    this.detectionDurationsMs.push(durationMs)
    if (this.detectionDurationsMs.length > 60) this.detectionDurationsMs.shift()
    const total = this.detectionDurationsMs.reduce((sum, value) => sum + value, 0)
    this.stats.averageDetectionDurationMs = total / this.detectionDurationsMs.length
  }

  private reportState(hadError = false): void {
    if (this.disposed) return
    let state: FaceCountState
    if (hadError) {
      state = { status: 'error', faceCount: this.lastFaceCount, confidence: this.lastConfidence, detail: 'Face analysis temporarily unavailable' }
    } else if (this.paused) {
      state = { status: 'paused', faceCount: this.lastFaceCount, confidence: this.lastConfidence, detail: 'Paused while tab is inactive' }
    } else if (this.started) {
      state = { status: 'active', faceCount: this.lastFaceCount, confidence: this.lastConfidence }
    } else {
      state = { status: 'initializing', faceCount: null, confidence: null }
    }
    this.deps.onStateChange(state)
  }

  private attachVisibilityTracking(): void {
    if (typeof document === 'undefined') return
    this.onVisibilityChanged = () => {
      if (document.hidden) this.pause()
      else this.resume()
    }
    document.addEventListener('visibilitychange', this.onVisibilityChanged)
  }

  private detachVisibilityTracking(): void {
    if (this.onVisibilityChanged && typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.onVisibilityChanged)
    }
    this.onVisibilityChanged = null
  }

  private attachTrackEnded(): void {
    for (const track of this.deps.stream.getTracks()) {
      const onEnded = () => {
        if (this.disposed) return
        this.clearTimer()
        this.deps.onStateChange({ status: 'unavailable', faceCount: this.lastFaceCount, confidence: this.lastConfidence, detail: 'Camera analysis unavailable' })
      }
      if (typeof track.addEventListener === 'function' && typeof track.removeEventListener === 'function') {
        track.addEventListener('ended', onEnded)
        this.stopTrackListeners.push(() => track.removeEventListener?.('ended', onEnded))
      }
    }
  }

  private detachTrackEnded(): void {
    this.stopTrackListeners.forEach((remove) => remove())
    this.stopTrackListeners = []
  }
}

export interface StartFaceCountControllerOptions {
  submit: FaceCountControllerOptions['submit']
  onStateChange: FaceCountControllerOptions['onStateChange']
  config?: Partial<FaceCountConfig>
  /** Injectable camera acquisition (defaults to getUserMedia). */
  acquireCamera?: () => Promise<MediaStream>
  /** Injectable video-element factory (defaults to document.createElement). */
  createVideo?: () => CameraVideoLike
}

const USER_FACING_VIDEO_CONSTRAINTS: MediaStreamConstraints = {
  video: {
    facingMode: 'user',
    width: { ideal: 640 },
    height: { ideal: 480 },
    frameRate: { ideal: 15 },
  },
  audio: false,
}

/**
 * Acquires the camera exactly once for an active attempt, initialises the local
 * detector and starts the controller. Any failure (unsupported browser, denied or
 * missing camera, model init error) rejects so the caller can surface a truthful
 * "Camera analysis unavailable" state — it never fabricates observations.
 */
export async function startFaceCountController(options: StartFaceCountControllerOptions): Promise<FaceCountController> {
  const acquireCamera = options.acquireCamera ?? defaultAcquireCamera
  const config = { ...DEFAULT_FACE_COUNT_CONFIG, ...options.config }

  options.onStateChange({ status: 'initializing', faceCount: null, confidence: null, detail: 'Starting camera analysis' })

  const stream = await acquireCamera()
  const video = (options.createVideo ?? createVideoElement)()
  video.srcObject = stream

  const detector = new MediaPipeFaceCountDetector()
  try {
    await detector.initialize(config)
  } catch (error) {
    stream.getTracks().forEach((track) => track.stop())
    throw error instanceof Error ? error : new Error(String(error))
  }

  const controller = new FaceCountController({
    detector,
    video,
    stream,
    submit: options.submit,
    onStateChange: options.onStateChange,
    config,
  })
  controller.start()
  return controller
}

async function defaultAcquireCamera(): Promise<MediaStream> {
  if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
    throw new Error('Camera access is not supported in this browser.')
  }
  return navigator.mediaDevices.getUserMedia(USER_FACING_VIDEO_CONSTRAINTS)
}

function createVideoElement(): CameraVideoLike {
  const video = document.createElement('video')
  video.muted = true
  video.playsInline = true
  video.autoplay = true
  return video
}