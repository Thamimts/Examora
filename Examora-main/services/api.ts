import axios from 'axios'
import { getAuthSnapshot } from '@/store/authStore'

export const api = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080',
  headers: {
    'Content-Type': 'application/json',
  },
})

api.interceptors.request.use((config) => {
  const token = getAuthSnapshot().token

  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (
      error.response?.status === 401 &&
      typeof window !== 'undefined'
    ) {
      getAuthSnapshot().logout()
      window.location.assign('/login?expired=1')
    }

    return Promise.reject(error)
  },
)

export default api
