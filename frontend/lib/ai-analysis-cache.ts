import { createHash } from 'node:crypto'

export type CachedAnalysis<T> = {
  fingerprint: string
  value: T
  expiresAt: number
}

export function createStudentCacheKey(token: string): string {
  const clean = token.startsWith('Bearer ') ? token.slice('Bearer '.length) : token
  try {
    const payload = clean.split('.')[1]
    if (!payload) throw new Error('missing payload segment')
    const claims = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'))
    if (typeof claims?.userId === 'string' && claims.userId) return `user:${claims.userId}`
  } catch {
    // fall through to the token-hash key
  }
  return `token:${createHash('sha256').update(clean).digest('hex')}`
}

export function createBoundedCache<T>({ maxEntries, ttlMs }: { maxEntries: number; ttlMs: number }) {
  const store = new Map<string, CachedAnalysis<T>>()
  const now = () => Date.now()

  function prune() {
    const current = now()
    for (const [key, entry] of store) {
      if (entry.expiresAt <= current) store.delete(key)
    }
  }

  return {
    get size() {
      return store.size
    },
    get(key: string, fingerprint: string): T | null {
      prune()
      const entry = store.get(key)
      if (!entry) return null
      if (entry.fingerprint !== fingerprint || entry.expiresAt <= now()) {
        store.delete(key)
        return null
      }
      return entry.value
    },
    set(key: string, fingerprint: string, value: T) {
      prune()
      store.set(key, { fingerprint, value, expiresAt: now() + ttlMs })
      while (store.size > maxEntries) {
        const oldest = store.keys().next().value
        if (oldest === undefined) break
        store.delete(oldest)
      }
    },
    delete(key: string) {
      store.delete(key)
    },
  }
}