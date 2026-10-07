import type { CreateUserPayload } from '@/services/userApi'
import type { Role } from '@/types'

export type CreateUserValidation =
  | { ok: true; payload: CreateUserPayload }
  | { ok: false; issues: string[] }

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const ROLL_PATTERN = /^[a-zA-Z0-9-]{2,30}$/
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

export function buildCreateUserPayload(input: {
  name?: string
  email?: string
  password?: string
  role?: Role
  rollNumber?: string
  dateOfBirth?: string
  department?: string
  batch?: string
}): CreateUserValidation {
  const issues: string[] = []
  const name = (input.name ?? '').trim()
  const email = (input.email ?? '').trim().toLowerCase()
  const password = (input.password ?? '').trim()
  const role: Role = input.role ?? 'STUDENT'
  const rollNumber = (input.rollNumber ?? '').trim()
  const dateOfBirth = (input.dateOfBirth ?? '').trim()
  const department = (input.department ?? '').trim() || undefined
  const batch = (input.batch ?? '').trim() || undefined

  const academic = role === 'STUDENT'

  if (name.length < 2) issues.push('name')
  if (!EMAIL_PATTERN.test(email)) issues.push('email')
  if (academic) {
    if (password && password.length < 8) issues.push('password')
    if (rollNumber && !ROLL_PATTERN.test(rollNumber)) issues.push('rollNumber')
    if (dateOfBirth && !DATE_PATTERN.test(dateOfBirth)) issues.push('dateOfBirth')
  } else {
    if (password.length < 8) issues.push('password')
  }

  if (issues.length > 0) {
    return { ok: false, issues }
  }
  return {
    ok: true,
    payload: {
      name,
      email,
      password: password || undefined,
      role,
      rollNumber: rollNumber || undefined,
      dateOfBirth: dateOfBirth || undefined,
      department,
      batch,
    },
  }
}