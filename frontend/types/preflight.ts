export type PreflightCheckStatus = 'CHECKING' | 'READY' | 'BLOCKED' | 'UNAVAILABLE' | 'NOT_STARTED'

export type PreflightCheckId = 'camera' | 'microphone' | 'screen-share' | 'fullscreen' | 'network' | 'browser'

export interface PreflightCheckResult {
  status: PreflightCheckStatus
  detail: string
}

export interface PreflightCheckDefinition {
  id: PreflightCheckId
  label: string
  /** Whether the exam service enforces this check. Today the backend defines no device requirements. */
  required: boolean
  /** Interactive checks need an explicit user action (permission prompt or browser gesture). */
  interactive: boolean
}

export interface PreflightPolicy {
  serverDefinesRequirements: boolean
  requiredCheckIds: PreflightCheckId[]
  note: string
}

export const PREFLIGHT_CHECKS: PreflightCheckDefinition[] = [
  { id: 'camera', label: 'Camera', required: false, interactive: true },
  { id: 'microphone', label: 'Microphone', required: false, interactive: true },
  { id: 'screen-share', label: 'Screen sharing', required: false, interactive: true },
  { id: 'fullscreen', label: 'Fullscreen', required: false, interactive: true },
  { id: 'network', label: 'Network', required: false, interactive: false },
  { id: 'browser', label: 'Browser readiness', required: false, interactive: false },
]

export const PREFLIGHT_POLICY: PreflightPolicy = {
  serverDefinesRequirements: false,
  requiredCheckIds: [],
  note: 'The exam service does not currently define device requirements, so every readiness check is advisory (informational). A blocked or unavailable check never stops your attempt automatically, and permission state is not treated as cheating. Complete each check so you know the exact state of your session before starting.',
}