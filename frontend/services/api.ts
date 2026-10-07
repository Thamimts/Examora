import axios from 'axios'
import { getAuthSnapshot } from '@/store/authStore'

const DEV_FALLBACK_API_URL = 'http://localhost:8080/api'

export const resolveApiBaseUrl = (): string => {
  const configured =
    process.env.NEXT_PUBLIC_API_URL?.trim() ||
    (typeof window !== 'undefined'
      ? (window as Window & { __ENV__?: { VITE_API_URL?: string } }).__ENV__?.VITE_API_URL?.trim()
      : undefined) ||
    undefined
  if (configured) return configured
  const message =
    '[Examora] NEXT_PUBLIC_API_URL is not configured. Set NEXT_PUBLIC_API_URL to the backend API base URL (e.g. https://api.examora.example.com/api).'
  if (process.env.NODE_ENV === 'production') {
    // In production, surface the misconfiguration loudly. During Next.js build (SSR, no window)
    // we keep the build alive by falling back with an error log — the runtime client will still
    // fail fast before any localhost request is silently made.
    console.error(`${message} Production requires an explicit API URL; not silently falling back to localhost.`)
    if (typeof window !== 'undefined') {
      throw new Error('NEXT_PUBLIC_API_URL is required in production')
    }
    return DEV_FALLBACK_API_URL
  }
  if (typeof window !== 'undefined') {
    console.warn(`${message} Using development fallback http://localhost:8080/api`)
  }
  return DEV_FALLBACK_API_URL
}
export const api = axios.create({ baseURL: resolveApiBaseUrl(), headers: { 'Content-Type': 'application/json' } })
api.interceptors.request.use((config) => { const token = getAuthSnapshot().token; if (token) config.headers.Authorization = `Bearer ${token}`; return config })
const PUBLIC_PATH_PREFIXES = ['/auth/', '/status', '/db/health']
const isPublicRequest = (config?: { url?: string }) => { const url = config?.url; if (!url) return false; const path = (url.startsWith('/') ? url : `/${url}`).split('?')[0]; return PUBLIC_PATH_PREFIXES.some(prefix => path.startsWith(prefix)) }
api.interceptors.response.use((response) => response, (error) => { if (error.response?.status === 401 && typeof window !== 'undefined' && !isPublicRequest(error.config)) { const { token, logout } = getAuthSnapshot(); if (token) logout() } return Promise.reject(error) })
export default api
