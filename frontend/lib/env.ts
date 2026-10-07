/**
 * Centralised environment validation for the frontend.
 *
 * - NEXT_PUBLIC_API_URL is the ONLY public variable the browser may read.
 * - Secrets (GROQ_API_KEY, GOOGLE_CLIENT_SECRET, JWT_SECRET, DB credentials) must
 *   never be prefixed with NEXT_PUBLIC_.
 * - In production, missing NEXT_PUBLIC_API_URL is a hard error; the app must not
 *   silently talk to localhost.
 */

type EnvValidationResult = { ok: boolean; errors: string[]; warnings: string[] }

export function validateFrontendEnv(): EnvValidationResult {
  const errors: string[] = []
  const warnings: string[] = []

  const publicApiUrl = process.env.NEXT_PUBLIC_API_URL?.trim()
  if (!publicApiUrl) {
    if (process.env.NODE_ENV === 'production') {
      errors.push('NEXT_PUBLIC_API_URL is required in production (e.g. https://api.examora.example.com/api)')
    } else {
      warnings.push('NEXT_PUBLIC_API_URL not set — development fallback http://localhost:8080/api will be used')
    }
  } else {
    try {
      const url = new URL(publicApiUrl)
      if (!['http:', 'https:'].includes(url.protocol)) {
        errors.push('NEXT_PUBLIC_API_URL must be an http or https URL')
      }
    } catch {
      errors.push('NEXT_PUBLIC_API_URL is not a valid URL')
    }
  }

  // Guard against accidental secret exposure via NEXT_PUBLIC_
  const forbiddenPublicPrefixes = ['GROQ_API_KEY', 'OPENAI_API_KEY', 'GOOGLE_CLIENT_SECRET', 'GITHUB_CLIENT_SECRET', 'JWT_SECRET', 'DB_PASSWORD']
  for (const key of forbiddenPublicPrefixes) {
    if (process.env[`NEXT_PUBLIC_${key}` as keyof NodeJS.ProcessEnv]) {
      errors.push(`NEXT_PUBLIC_${key} must never be set — ${key} is a server-only secret`)
    }
  }

  return { ok: errors.length === 0, errors, warnings }
}

export function assertFrontendEnv(): void {
  const result = validateFrontendEnv()
  for (const warning of result.warnings) console.warn(`[Examora env] ${warning}`)
  if (!result.ok) {
    for (const error of result.errors) console.error(`[Examora env] ${error}`)
    if (process.env.NODE_ENV === 'production') {
      throw new Error(result.errors.join('; '))
    }
  }
}
