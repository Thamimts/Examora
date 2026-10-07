import { describe, expect, it } from 'vitest'
import { buildCreateUserPayload } from './createUserPayload'

describe('buildCreateUserPayload', () => {
  it('requires a non-blank name', () => {
    expect(buildCreateUserPayload({ name: '  ', email: 'a@b.co', password: 'password123' }).ok).toBe(false)
    expect(buildCreateUserPayload({ name: 'A', email: 'a@b.co', password: 'password123' }).ok).toBe(false)
  })

  it('requires a well-formed email and normalizes it to lowercase', () => {
    const invalid = buildCreateUserPayload({ name: 'Ada', email: 'not-an-email', password: 'password123' })
    expect(invalid.ok).toBe(false)
    const valid = buildCreateUserPayload({ name: 'Ada', email: ' Ada@Example.com ', password: 'password123' })
    expect(valid.ok).toBe(true)
    if (valid.ok) expect(valid.payload.email).toBe('ada@example.com')
  })

  it('rejects passwords shorter than 8 characters', () => {
    expect(buildCreateUserPayload({ name: 'Ada', email: 'a@b.co', password: 'short' }).ok).toBe(false)
  })

  it('defaults the role to STUDENT when omitted', () => {
    const result = buildCreateUserPayload({ name: 'Ada', email: 'a@b.co', password: 'password123' })
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.payload.role).toBe('STUDENT')
  })

  it('keeps an explicit role and trims the name', () => {
    const result = buildCreateUserPayload({ name: '  Ada Lovelace  ', email: 'a@b.co', password: 'password123', role: 'ADMIN' })
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.payload.name).toBe('Ada Lovelace')
      expect(result.payload.role).toBe('ADMIN')
    }
  })
})