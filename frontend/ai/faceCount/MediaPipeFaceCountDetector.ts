import type { FaceDetectorOptions } from '@mediapipe/tasks-vision'
import { DEFAULT_FACE_COUNT_CONFIG, type FaceCountConfig, type FaceCountDetection, type FaceCountDetector } from './types'

const WASM_BASE_PATH = '/ai/wasm'
const MODEL_ASSET_PATH = '/ai/blaze_face_short_range.tflite'

/**
 * Browser-local face-count detector backed by MediaPipe Tasks Vision
 * (BlazeFace short-range). All inference runs on-device via WASM + WebGL;
 * no frames or model outputs ever leave the browser.
 */
export class MediaPipeFaceCountDetector implements FaceCountDetector {
  readonly label = 'BlazeFace (MediaPipe Tasks, local WASM/WebGL)'
  private faceDetector: import('@mediapipe/tasks-vision').FaceDetector | null = null
  private disposed = false

  async initialize(config: FaceCountConfig = DEFAULT_FACE_COUNT_CONFIG): Promise<void> {
    const vision = await import('@mediapipe/tasks-vision')
    const fileset = await vision.FilesetResolver.forVisionTasks(WASM_BASE_PATH)
    try {
      this.faceDetector = await vision.FaceDetector.createFromOptions(fileset, this.options(config, 'GPU'))
    } catch {
      this.faceDetector = await vision.FaceDetector.createFromOptions(fileset, this.options(config, 'CPU'))
    }
  }

  detect(video: HTMLVideoElement): FaceCountDetection {
    if (this.disposed || !this.faceDetector) {
      throw new Error('FaceCountDetector is not initialized')
    }
    const result = this.faceDetector.detectForVideo(video, performance.now())
    const detections = result?.detections ?? []
    const faceCount = detections.length
    let confidence: number | null = null
    if (faceCount > 0) {
      let maxScore = 0
      for (const detection of detections) {
        for (const category of detection.categories ?? []) {
          if (category.score > maxScore) maxScore = category.score
        }
      }
      confidence = maxScore > 0 ? maxScore : null
    }
    return { faceCount, confidence, detectedAt: Date.now() }
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    try {
      this.faceDetector?.close()
    } finally {
      this.faceDetector = null
    }
  }

  private options(config: FaceCountConfig, delegate: 'CPU' | 'GPU'): FaceDetectorOptions {
    return {
      baseOptions: {
        modelAssetPath: MODEL_ASSET_PATH,
        delegate,
      },
      runningMode: 'VIDEO',
      minDetectionConfidence: config.minFaceConfidence,
    }
  }
}