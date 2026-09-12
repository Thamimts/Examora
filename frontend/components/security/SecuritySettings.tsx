'use client'
import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { authApi } from '@/services/authApi'
import { resolveApiBaseUrl } from '@/services/api'
import { useToast } from '@/components/feedback'
import { KeyRound, Lock, ShieldCheck, Smartphone } from 'lucide-react'

function Field({ label, type, value, onChange, placeholder, autoComplete }: { label: string; type: string; value: string; onChange: (value: string) => void; placeholder?: string; autoComplete?: string }) {
  return <label className="block text-sm font-medium">{label}<input className="field mt-2" type={type} value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder} autoComplete={autoComplete} /></label>
}

export default function SecuritySettings() {
  const toast = useToast()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [otpauthUri, setOtpauthUri] = useState('')
  const [setupSecret, setSetupSecret] = useState('')
  const [setupCode, setSetupCode] = useState('')
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null)
  const [disablePassword, setDisablePassword] = useState('')

  const statusQuery = useQuery({ queryKey: ['2fa-status'], queryFn: async () => (await authApi.twoFactorStatus()).data.data, retry: 1 })
  const providersQuery = useQuery({ queryKey: ['oauth-providers'], queryFn: async () => (await authApi.oauthProviders()).data.data, retry: 1 })
  const enabledProviders = providersQuery.data?.filter(provider => provider.enabled) ?? []

  const passwordMutation = useMutation({ mutationFn: () => authApi.changePassword({ currentPassword, newPassword }), onSuccess: () => { toast.success('Password updated.'); setCurrentPassword(''); setNewPassword('') }, onError: (error: unknown) => toast.error(((error as { response?: { data?: { message?: string } } }).response?.data?.message) || 'Unable to change the password.') })

  const setupMutation = useMutation({
    mutationFn: () => authApi.twoFactorSetup(),
    onSuccess: response => { setOtpauthUri(response.data.data.otpauthUri); setSetupSecret(response.data.data.secret) },
    onError: (error: unknown) => toast.error(((error as { response?: { data?: { message?: string } } }).response?.data?.message) || 'Unable to start two-factor setup.'),
  })

  const confirmMutation = useMutation({
    mutationFn: () => authApi.twoFactorConfirm(setupCode),
    onSuccess: response => { setRecoveryCodes(response.data.data.recoveryCodes); setSetupCode(''); statusQuery.refetch() },
    onError: (error: unknown) => toast.error(((error as { response?: { data?: { message?: string } } }).response?.data?.message) || 'That code did not verify.'),
  })

  const disableMutation = useMutation({
    mutationFn: () => authApi.twoFactorDisable({ password: disablePassword }),
    onSuccess: () => { toast.success('Two-factor authentication disabled.'); setDisablePassword(''); setOtpauthUri(''); setSetupSecret(''); statusQuery.refetch() },
    onError: (error: unknown) => toast.error(((error as { response?: { data?: { message?: string } } }).response?.data?.message) || 'Unable to disable two-factor authentication.'),
  })

  const twoFactorEnabled = statusQuery.data?.enabled === true

  return (
    <div className="mx-auto grid max-w-4xl gap-6 lg:grid-cols-2">
      <section className="rounded-2xl border border-border bg-card p-5">
        <div className="flex items-center gap-3">
          <div className="grid size-10 place-items-center rounded-xl bg-primary/10 text-primary"><Lock /></div>
          <div>
            <h2 className="font-semibold">Password</h2>
            <p className="text-sm text-muted-foreground">Keep your account secure with a strong password.</p>
          </div>
        </div>
        <form className="mt-5 space-y-4" onSubmit={e => { e.preventDefault(); passwordMutation.mutate() }}>
          <Field label="Current password" type="password" value={currentPassword} onChange={setCurrentPassword} autoComplete="current-password" />
          <Field label="New password" type="password" value={newPassword} onChange={setNewPassword} autoComplete="new-password" placeholder="At least 8 characters" />
          <button type="submit" disabled={passwordMutation.isPending || !currentPassword || newPassword.length < 8} className="rounded-xl bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground transition hover:bg-primary/90 active:scale-[.98] disabled:opacity-60">{passwordMutation.isPending ? 'Updating...' : 'Update password'}</button>
        </form>
      </section>

      <section className="rounded-2xl border border-border bg-card p-5">
        <div className="flex items-center gap-3">
          <div className="grid size-10 place-items-center rounded-xl bg-primary/10 text-primary"><Smartphone /></div>
          <div>
            <h2 className="font-semibold">Two-factor authentication</h2>
            <p className="text-sm text-muted-foreground">{twoFactorEnabled ? 'An authenticator app is protecting this account.' : 'Add an authenticator app for extra protection.'}</p>
          </div>
        </div>
        {twoFactorEnabled ? (
          <form className="mt-5 space-y-4" onSubmit={e => { e.preventDefault(); disableMutation.mutate() }}>
            <p className="text-sm text-muted-foreground">Disabling two-factor authentication requires your password.</p>
            <Field label="Password" type="password" value={disablePassword} onChange={setDisablePassword} autoComplete="current-password" />
            <button type="submit" disabled={disableMutation.isPending || !disablePassword} className="rounded-xl border border-border px-4 py-2.5 text-sm font-medium text-muted-foreground transition hover:bg-muted active:scale-[.98] disabled:opacity-60">{disableMutation.isPending ? 'Disabling...' : 'Disable two-factor'}</button>
          </form>
        ) : otpauthUri ? (
          <div className="mt-5 space-y-4">
            <p className="text-sm text-muted-foreground">Scan this setup URI with your authenticator app. If you cannot scan it, enter the secret manually.</p>
            <p className="overflow-x-auto rounded-xl bg-muted p-3 text-xs break-all">{otpauthUri}</p>
            <div className="flex items-center gap-2">
              <KeyRound size={14} />
              <p className="overflow-x-auto rounded-xl bg-muted px-3 py-2 text-xs break-all font-mono">{setupSecret}</p>
            </div>
            <Field label="Verify with a code" type="text" value={setupCode} onChange={value => setSetupCode(value.replace(/\D/g, '').slice(0, 6))} placeholder="6-digit code" autoComplete="one-time-code" />
            <button type="button" disabled={confirmMutation.isPending || setupCode.length !== 6} onClick={() => confirmMutation.mutate()} className="rounded-xl bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground transition hover:bg-primary/90 active:scale-[.98] disabled:opacity-60">{confirmMutation.isPending ? 'Verifying...' : 'Confirm and enable'}</button>
          </div>
        ) : recoveryCodes ? (
          <div className="mt-5 space-y-4">
            <p className="text-sm font-medium">Save these recovery codes somewhere safe. Each code can be used once if you ever lose your authenticator.</p>
            <div className="grid grid-cols-2 gap-2 rounded-xl bg-muted p-3">
              {recoveryCodes.map(code => <code key={code} className="text-sm">{code}</code>)}
            </div>
            <button type="button" onClick={() => setRecoveryCodes(null)} className="rounded-xl border border-border px-4 py-2.5 text-sm font-medium text-muted-foreground transition hover:bg-muted active:scale-[.98]">I saved my codes</button>
          </div>
        ) : (
          <div className="mt-5">
            <button type="button" disabled={setupMutation.isPending} onClick={() => setupMutation.mutate()} className="rounded-xl bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground transition hover:bg-primary/90 active:scale-[.98] disabled:opacity-60">{setupMutation.isPending ? 'Preparing...' : 'Enable two-factor authentication'}</button>
          </div>
        )}
      </section>

      <section className="rounded-2xl border border-border bg-card p-5">
        <div className="flex items-center gap-3">
          <div className="grid size-10 place-items-center rounded-xl bg-primary/10 text-primary"><ShieldCheck /></div>
          <div>
            <h2 className="font-semibold">Sign-in options</h2>
            <p className="text-sm text-muted-foreground">Connect an account to sign in without a password.</p>
          </div>
        </div>
        <div className="mt-5 space-y-3">
          {enabledProviders.length ? enabledProviders.map(provider => <a key={provider.provider} className="block" href={`${resolveApiBaseUrl()}/auth/oauth/${provider.provider}/start?returnTo=/settings/security`}><button type="button" className="flex w-full items-center justify-between rounded-xl border border-border p-3 text-sm transition hover:bg-muted active:scale-[.99]"><span>{provider.label}</span><ShieldCheck size={16} /></button></a>) : <p className="text-sm text-muted-foreground">No external sign-in providers are configured on the server.</p>}
        </div>
      </section>
    </div>
  )
}