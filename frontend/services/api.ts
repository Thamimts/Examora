import axios from 'axios'
import { getAuthSnapshot } from '@/store/authStore'
export const api = axios.create({ baseURL: process.env.NEXT_PUBLIC_API_URL || (typeof window !== 'undefined' ? (window as Window & { __ENV__?: { VITE_API_URL?: string } }).__ENV__?.VITE_API_URL : undefined) || 'http://localhost:8080/api', headers: { 'Content-Type': 'application/json' } })
api.interceptors.request.use((config) => { const token = getAuthSnapshot().token; if (token) config.headers.Authorization = `Bearer ${token}`; return config })
const PUBLIC_PATH_PREFIXES = ['/auth/', '/status', '/db/health']
const isPublicRequest = (config?: { url?: string }) => { const url = config?.url; if (!url) return false; const path = (url.startsWith('/') ? url : `/${url}`).split('?')[0]; return PUBLIC_PATH_PREFIXES.some(prefix => path.startsWith(prefix)) }
api.interceptors.response.use((response) => response, (error) => { if (error.response?.status === 401 && typeof window !== 'undefined' && !isPublicRequest(error.config)) { const { token, logout } = getAuthSnapshot(); if (token) logout() } return Promise.reject(error) })
export default api
