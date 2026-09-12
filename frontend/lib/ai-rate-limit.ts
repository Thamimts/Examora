type RateEntry = { windowStart: number; count: number }

export function createRateLimiter({ maxRequests, windowMs }: { maxRequests: number; windowMs: number }) {
  const store = new Map<string, RateEntry>()
  const now = () => Date.now()

  function prune() {
    const current = now()
    for (const [key, entry] of store) {
      if (entry.windowStart + windowMs <= current) store.delete(key)
    }
  }

  return {
    get size() {
      return store.size
    },
    allow(key: string): { ok: boolean; retryAfterSeconds: number; remaining: number } {
      prune()
      const current = now()
      const entry = store.get(key)
      if (!entry || entry.windowStart + windowMs <= current) {
        const fresh = { windowStart: current, count: 1 }
        store.set(key, fresh)
        return { ok: true, retryAfterSeconds: 0, remaining: Math.max(0, maxRequests - fresh.count) }
      }
      if (entry.count >= maxRequests) {
        const retryAfterSeconds = Math.max(1, Math.ceil((entry.windowStart + windowMs - current) / 1000))
        return { ok: false, retryAfterSeconds, remaining: 0 }
      }
      entry.count += 1
      store.set(key, entry)
      return { ok: true, retryAfterSeconds: 0, remaining: Math.max(0, maxRequests - entry.count) }
    },
    reset(key: string) {
      store.delete(key)
    },
  }
}