'use client'
import { useEffect, useState } from 'react'
import { startFaceCountController, type FaceCountController, type FaceCountState } from '@/ai/faceCount'
import { createProctorSignalClient } from '@/lib/proctorSignalClient'

export interface UseFaceCountDetectionOptions {
  attemptId: string | null
  active: boolean
}

/**
 * Binds the client-side face-count detector to the authenticated exam attempt.
 *
 * - The detector runs only while an attempt is active (`active` mirrors the same
 *   gate used by `useProctorSession`).
 * - The `attemptId` always comes from the authenticated session context; the
 *   frontend never supplies a `studentId`.
 * - The camera is requested exactly once per attempt. When the attempt ends or
 *   a retest changes the `attemptId`, the previous controller is disposed
 *   (camera track released, model unloaded) before a new one is created.
 * - Any failure (browser unsupported, camera denied/missing, model init error)
 *   settles in the truthful "Camera analysis unavailable" state and never
 *   fabricates observations or affects deterministic proctoring rules.
 */
export function useFaceCountDetection({ attemptId, active }: UseFaceCountDetectionOptions): FaceCountState {
  const [state, setState] = useState<FaceCountState>({ status: 'unavailable', faceCount: null, confidence: null })

  useEffect(() => {
    setState({ status: 'unavailable', faceCount: null, confidence: null })
    if (!attemptId || !active) return

    const client = createProctorSignalClient({ attemptId })
    let controller: FaceCountController | null = null
    let disposed = false

    const setStateSafe = (next: FaceCountState) => {
      if (!disposed) setState(next)
    }

    startFaceCountController({
      submit: (signal) => client.submit(signal),
      onStateChange: setStateSafe,
    })
      .then((handle) => {
        if (disposed) {
          handle.dispose()
        } else {
          controller = handle
        }
      })
      .catch(() => {
        setStateSafe({
          status: 'unavailable',
          faceCount: null,
          confidence: null,
          detail: 'Camera analysis unavailable',
        })
      })

    return () => {
      disposed = true
      controller?.dispose()
      client.dispose()
    }
  }, [attemptId, active])

  return state
}