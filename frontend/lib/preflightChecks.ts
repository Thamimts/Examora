import { resolveApiBaseUrl } from '@/services/api'
import type { PreflightCheckId, PreflightCheckResult } from '@/types/preflight'

const noMediaDevices = (detail: string): PreflightCheckResult => ({ status: 'UNAVAILABLE', detail })

/**
 * Camera readiness: requests a video track, verifies permission/device, then
 * stops every acquired track immediately. Nothing is recorded or uploaded.
 * Only ever called from an explicit user action.
 */
export async function checkCamera(): Promise<PreflightCheckResult> {
  const md = typeof navigator !== 'undefined' ? navigator.mediaDevices : undefined
  if (!md || typeof md.getUserMedia !== 'function') {
    return noMediaDevices('This browser does not expose the camera API (getUserMedia). The exam still runs, but signals that rely on a camera may be limited.')
  }
  let stream: MediaStream | null = null
  try {
    stream = await md.getUserMedia({ video: true })
  } catch (error) {
    const name = (error as { name?: string })?.name
    if (name === 'NotAllowedError' || name === 'PermissionDeniedError') {
      return { status: 'BLOCKED', detail: 'Camera permission was denied. Allow camera access for this site in your browser, then run the check again. Permission state is never treated as cheating.' }
    }
    if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
      return noMediaDevices('No camera device was found on this machine.')
    }
    if (name === 'NotReadableError' || name === 'TrackStartError') {
      return noMediaDevices('Your camera is already in use elsewhere or could not be opened.')
    }
    if (name === 'SecurityError') {
      return noMediaDevices('Camera access is blocked in this context (the app requires a secure HTTPS connection).')
    }
    return noMediaDevices('Camera access could not be verified.')
  } finally {
    if (stream) stream.getTracks().forEach((track) => track.stop())
  }
  return { status: 'READY', detail: 'Camera detected and permission granted. The stream was closed immediately — nothing was recorded, stored or uploaded.' }
}

/**
 * Microphone readiness: requests an audio track, verifies permission/device, then
 * stops every acquired track immediately. No audio is recorded or uploaded.
 * Only ever called from an explicit user action.
 */
export async function checkMicrophone(): Promise<PreflightCheckResult> {
  const md = typeof navigator !== 'undefined' ? navigator.mediaDevices : undefined
  if (!md || typeof md.getUserMedia !== 'function') {
    return noMediaDevices('This browser does not expose the microphone API (getUserMedia). The exam still runs, but signals that rely on a microphone may be limited.')
  }
  let stream: MediaStream | null = null
  try {
    stream = await md.getUserMedia({ audio: true })
  } catch (error) {
    const name = (error as { name?: string })?.name
    if (name === 'NotAllowedError' || name === 'PermissionDeniedError') {
      return { status: 'BLOCKED', detail: 'Microphone permission was denied. Allow microphone access for this site in your browser, then run the check again. Permission state is never treated as cheating.' }
    }
    if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
      return noMediaDevices('No microphone device was found on this machine.')
    }
    if (name === 'NotReadableError' || name === 'TrackStartError') {
      return noMediaDevices('Your microphone is already in use elsewhere or could not be opened.')
    }
    if (name === 'SecurityError') {
      return noMediaDevices('Microphone access is blocked in this context (the app requires a secure HTTPS connection).')
    }
    return noMediaDevices('Microphone access could not be verified.')
  } finally {
    if (stream) stream.getTracks().forEach((track) => track.stop())
  }
  return { status: 'READY', detail: 'Microphone detected and permission granted. The stream was closed immediately — no audio was recorded, stored or uploaded.' }
}

/**
 * Screen sharing readiness: requests a display stream so the browser shows its
 * picker, then stops every acquired track immediately. The stream is never
 * transmitted, recorded or used with WebRTC. Only ever called from a user action.
 */
export async function checkScreenShare(): Promise<PreflightCheckResult> {
  const md = typeof navigator !== 'undefined' ? navigator.mediaDevices : undefined
  if (!md || typeof md.getDisplayMedia !== 'function') {
    return noMediaDevices('This browser does not expose screen sharing (getDisplayMedia). The exam still runs, but screen sharing will not be possible in this session.')
  }
  let stream: MediaStream | null = null
  try {
    stream = await md.getDisplayMedia({ video: true, audio: false })
  } catch (error) {
    const name = (error as { name?: string })?.name
    return {
      status: 'BLOCKED',
      detail: name === 'NotAllowedError' || name === 'AbortError'
        ? 'Screen sharing was not selected (the dialog was closed or access was denied). Browsers cannot grant screen sharing silently — run the check again and pick a screen or window when prompted.'
        : 'Screen sharing could not be verified in this context.',
    }
  } finally {
    if (stream) stream.getTracks().forEach((track) => track.stop())
  }
  return { status: 'READY', detail: 'Screen sharing can be requested. When the browser prompt appears, select the screen or window you intend to share. Nothing was recorded or sent.' }
}

/**
 * Fullscreen readiness: requests fullscreen on the document root as part of a
 * user-triggered check, confirms it engaged, then releases it. Exiting
 * fullscreen is not treated as a warning here — that belongs to the live
 * proctoring session while the attempt is running.
 */
export async function checkFullscreen(): Promise<PreflightCheckResult> {
  if (typeof document === 'undefined') return noMediaDevices('Fullscreen is not supported in this context.')
  const root = document.documentElement
  const supported = 'fullscreenEnabled' in document && typeof root.requestFullscreen === 'function'
  if (!supported) {
    return noMediaDevices('This browser does not expose the Fullscreen API, so fullscreen cannot be requested here. The exam still runs normally.')
  }
  return new Promise<PreflightCheckResult>((resolve) => {
    let settled = false
    const timer = window.setTimeout(() => {
      finish({ status: 'BLOCKED', detail: 'Fullscreen did not engage after the request. Run the check again with a direct click.' })
    }, 6000)

    const finish = (result: PreflightCheckResult) => {
      if (settled) return
      settled = true
      window.clearTimeout(timer)
      document.removeEventListener('fullscreenchange', onChange)
      resolve(result)
    }

    const onChange = () => {
      if (document.fullscreenElement === root) {
        if (typeof document.exitFullscreen === 'function') void document.exitFullscreen().catch(() => undefined)
        finish({ status: 'READY', detail: 'Fullscreen engaged and was released after the check. You can enter fullscreen again before starting; leaving fullscreen is not penalised.' })
      }
    }

    document.addEventListener('fullscreenchange', onChange)
    try {
      const request = root.requestFullscreen()
      if (request && typeof request.then === 'function') {
        request.catch((error: unknown) => {
          handleRequestError((error as { name?: string })?.name)
        })
      }
    } catch (error) {
      handleRequestError((error as { name?: string })?.name)
    }

    function handleRequestError(name?: string) {
      finish({
        status: 'BLOCKED',
        detail: name === 'TypeError'
          ? 'Fullscreen was blocked by the browser. Click Check fullscreen to try again in the next step of the flow.'
          : 'Fullscreen could not be requested in this browser. The exam still runs without it.',
      })
    }
  })
}

/**
 * Network readiness: verifies online status and reachability of the existing
 * application API. No latency numbers are invented and no new endpoint is added.
 */
export async function checkNetwork(): Promise<PreflightCheckResult> {
  if (typeof navigator !== 'undefined' && typeof navigator.onLine === 'boolean' && navigator.onLine === false) {
    return { status: 'BLOCKED', detail: 'You are offline. Saved answers are queued in your browser and retry automatically when the connection returns.' }
  }
  const controller = new AbortController()
  const timer = typeof window !== 'undefined' ? window.setTimeout(() => controller.abort(), 4000) : undefined
  try {
    const res = await fetch(`${resolveApiBaseUrl()}/status`, { signal: controller.signal })
    if (timer !== undefined) window.clearTimeout(timer)
    if (!res.ok) return { status: 'BLOCKED', detail: 'Online, but the exam service did not respond correctly. Your progress is still saved and retried.' }
    return { status: 'READY', detail: 'Online and connected to the exam service.' }
  } catch (error) {
    const aborted = (error as { name?: string })?.name === 'AbortError'
    return { status: 'BLOCKED', detail: aborted ? 'The exam service did not respond in time. Saved progress retries automatically once it recovers.' : 'The exam service could not be reached right now. Saved progress retries automatically when the connection returns.' }
  }
}

/**
 * Browser readiness: reports which capabilities the workspace expectations rely
 * on. Synchronous, no permissions involved, no fingerprinting and no device
 * information is collected or sent anywhere.
 */
export function checkBrowser(): PreflightCheckResult {
  if (typeof navigator === 'undefined' || typeof document === 'undefined') {
    return noMediaDevices('Browser capability detection is unavailable in this environment.')
  }
  const md = navigator.mediaDevices
  const missing: string[] = []
  if (!md) missing.push('mediaDevices')
  else {
    if (typeof md.getUserMedia !== 'function') missing.push('getUserMedia (camera and microphone)')
    if (typeof md.getDisplayMedia !== 'function') missing.push('getDisplayMedia (screen sharing)')
  }
  if (!('fullscreenEnabled' in document) || typeof document.documentElement.requestFullscreen !== 'function') missing.push('Fullscreen API')
  if (!('visibilityState' in document)) missing.push('Visibility API')
  if (missing.length === 0) {
    return { status: 'READY', detail: 'This browser exposes the camera, microphone, screen sharing and fullscreen APIs the exam relies on.' }
  }
  return { status: 'UNAVAILABLE', detail: `This browser does not expose: ${missing.join(', ')}. The exam still runs, but proctoring signals that rely on those APIs may be limited or absent.` }
}

export const checkFor = (id: PreflightCheckId): Promise<PreflightCheckResult> => {
  switch (id) {
    case 'camera':
      return checkCamera()
    case 'microphone':
      return checkMicrophone()
    case 'screen-share':
      return checkScreenShare()
    case 'fullscreen':
      return checkFullscreen()
    case 'network':
      return checkNetwork()
    case 'browser':
      return Promise.resolve(checkBrowser())
  }
}