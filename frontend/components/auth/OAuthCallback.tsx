'use client'
import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuthStore } from '@/store/authStore'
import { userApi } from '@/services/userApi'

export function OAuthCallback() {
  const { setAuth } = useAuthStore()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token') || (typeof window !== 'undefined' ? new URLSearchParams(window.location.hash.slice(1)).get('token') : null)
  const returnTo = searchParams.get('to')
  const [error, setError] = useState('')

  useEffect(() => {
    if (!token) return
    let active = true
    ;(async () => {
      useAuthStore.setState({ token })
      window.history.replaceState({}, document.title, window.location.pathname + window.location.search)
      const response = await userApi.me()
      if (!active) return
      const user = response.data.data
      setAuth({ user, token })
      const target = returnTo && returnTo.startsWith('/') && returnTo !== '/' && returnTo !== '/login' && returnTo !== '/register' ? returnTo : `/${user.role.toLowerCase()}/dashboard`
      navigate(target, { replace: true })
    })().catch(() => { if (active) { useAuthStore.getState().logout(); setError('This sign-in link failed. Please sign in again.') } })
    return () => { active = false }
  }, [token, returnTo, navigate, setAuth])

  if (error || !token) {
    return <div className="grid min-h-screen place-items-center p-6"><section className="w-full max-w-md rounded-2xl border border-border bg-card p-5" role="alert"><p className="text-sm text-destructive">{error || 'This sign-in link is missing a token.'}</p></section></div>
  }
  return <div className="grid min-h-screen place-items-center p-6"><section className="w-full max-w-md rounded-2xl border border-border bg-card p-5" aria-busy="true"><div className="mx-auto size-8 animate-spin rounded-full border-2 border-border border-t-primary" /> <p className="mt-4 text-center text-sm text-muted-foreground">Completing your sign-in...</p></section></div>
}