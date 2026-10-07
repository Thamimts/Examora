'use client'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { KeyRound, ShieldCheck } from 'lucide-react'
import { authApi } from '@/services/authApi'
import { userApi } from '@/services/userApi'
import { useAuthStore } from '@/store/authStore'
import { homeForRole } from '@/types'
import { useToast } from '@/components/feedback'

export default function FirstLoginSetup() {
  const navigate = useNavigate()
  const toast = useToast()
  const user = useAuthStore(state => state.user)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState('')

  const mutation = useMutation({
    mutationFn: () => authApi.changePassword({ currentPassword, newPassword }),
    onSuccess: async () => {
      toast.success('Password set. You can now use your exam account.')
      try { await userApi.me() } catch { /* profile refresh is best-effort */ }
      navigate(user ? homeForRole(user.role) : '/login', { replace: true })
    },
    onError: (cause: unknown) => {
      const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
      setError(message || 'Unable to set your password. Please try again.')
    },
  })

  const valid = currentPassword.length >= 1 && newPassword.length >= 8 && newPassword === confirmPassword
  const mismatch = confirmPassword.length > 0 && newPassword !== confirmPassword

  return (
    <div className="grid min-h-screen place-items-center p-6">
      <div className="w-full max-w-md rounded-2xl border border-border bg-card p-6">
        <div className="mb-6 flex items-center gap-3">
          <div className="grid size-9 place-items-center rounded-xl bg-primary text-primary-foreground"><ShieldCheck size={20} /></div>
          <b>Examwise</b>
        </div>
        <h1 className="text-2xl font-semibold">Set your password</h1>
        <p className="mt-2 text-sm text-muted-foreground">Your account was provisioned with initial credentials. Set a new password to keep using it — your roll number and date of birth will no longer sign you in.</p>
        {error && <p role="alert" className="mt-4 rounded-xl bg-destructive/10 p-3 text-sm text-destructive">{error}</p>}
        <form className="mt-6 space-y-4" onSubmit={event => { event.preventDefault(); if (valid) mutation.mutate() }}>
          <label className="block text-sm font-medium">Current password (the initial one you just used)
            <input className="field mt-2" type="password" autoComplete="current-password" value={currentPassword} onChange={e => setCurrentPassword(e.target.value)} />
          </label>
          <label className="block text-sm font-medium">New password
            <input className="field mt-2" type="password" autoComplete="new-password" value={newPassword} onChange={e => setNewPassword(e.target.value)} placeholder="At least 8 characters" />
          </label>
          <label className="block text-sm font-medium">Confirm new password
            <input className="field mt-2" type="password" autoComplete="new-password" value={confirmPassword} onChange={e => setConfirmPassword(e.target.value)} />
            {mismatch && <span className="mt-1 block text-xs text-destructive">Passwords do not match.</span>}
          </label>
          <button type="submit" disabled={!valid || mutation.isPending} className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary px-4 py-3 text-sm font-medium text-primary-foreground transition hover:bg-primary/90 active:scale-[.98] disabled:opacity-60">
            <KeyRound size={16} />{mutation.isPending ? 'Setting password...' : 'Set password'}
          </button>
        </form>
      </div>
    </div>
  )
}