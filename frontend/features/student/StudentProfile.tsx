'use client'
import { useQuery } from '@tanstack/react-query'
import { UserRound, GraduationCap, ShieldCheck, Settings2, Mail, AtSign, BadgeCheck } from 'lucide-react'
import { userApi } from '@/services/userApi'
import SecuritySettings from '@/components/security/SecuritySettings'

function InfoRow({ label, value }: { label: string; value: string }) {
  return <div className="flex items-center justify-between gap-3 py-3"><span className="text-sm text-muted-foreground">{label}</span><span className="text-sm font-medium">{value}</span></div>
}

function SectionHeading({ icon: Icon, title, description }: { icon: React.ComponentType<{ size?: number }>; title: string; description: string }) {
  return <div className="flex items-center gap-3"><div className="grid size-10 shrink-0 place-items-center rounded-xl bg-primary/10 text-primary"><Icon size={18} /></div><div><h2 className="font-semibold">{title}</h2><p className="text-sm text-muted-foreground">{description}</p></div></div>
}

function Card({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <section className={`rounded-2xl border border-border bg-card ${className}`}>{children}</section>
}

export default function StudentProfile() {
  const meQuery = useQuery({ queryKey: ['profile-me'], queryFn: async () => (await userApi.me()).data.data, retry: 1 })
  const user = meQuery.data

  const initials = user?.name ? user.name.trim().split(/\s+/).map(part => part[0]).filter(Boolean).slice(0, 2).join('').toUpperCase() : 'S'

  return <div className="mx-auto max-w-4xl space-y-6">
    <header className="flex flex-col gap-2"><p className="text-xs font-semibold uppercase tracking-widest text-primary">Student workspace</p><h1 className="text-2xl font-semibold tracking-tight">Profile</h1><p className="text-sm text-muted-foreground">Your account, identity and security settings.</p></header>

    {meQuery.isPending ? <div className="h-40 animate-pulse rounded-2xl bg-muted" aria-busy="true" /> : meQuery.isError ? <Card className="p-5"><p className="text-sm text-destructive">Unable to load your profile. Please try again.</p></Card> : user ? (
      <>
        <Card>
          <div className="flex items-center gap-4 border-b border-border px-5 py-5">
            <div className="grid size-14 shrink-0 place-items-center rounded-2xl bg-primary/10 text-xl font-semibold text-primary">{initials}</div>
            <div className="min-w-0"><h2 className="truncate text-lg font-semibold">{user.name}</h2><p className="flex items-center gap-1.5 text-sm text-muted-foreground"><BadgeCheck size={15} className="text-primary" />{user.role}</p></div>
          </div>
          <div className="grid gap-6 px-5 py-5 sm:grid-cols-2">
            <div><SectionHeading icon={UserRound} title="Personal information" description="Details tied to your authenticated account." /><div className="mt-3 divide-y divide-border"><InfoRow label="Name" value={user.name} /><InfoRow label="Email" value={user.email} /><InfoRow label="Account ID" value={user.id} /></div></div>
            <div><SectionHeading icon={GraduationCap} title="Academic information" description="Academic profile fields on your account." /><div className="mt-3 divide-y divide-border"><InfoRow label="Student record" value="Not configured" /></div><p className="mt-3 text-xs text-muted-foreground">Academic fields shown are limited to what your exam service provides. No academic profile has been linked to this account yet.</p></div>
          </div>
        </Card>

        <Card>
          <div className="px-5 pt-5"><SectionHeading icon={ShieldCheck} title="Security" description="Change your password, manage two-factor authentication and connected accounts." /></div>
          <div className="px-5 pb-5 pt-3"><SecuritySettings /></div>
        </Card>

        <Card>
          <div className="px-5 pt-5"><SectionHeading icon={Settings2} title="Preferences" description="Preferences supported by the application." /></div>
          <div className="px-5 pb-5"><p className="py-4 text-sm text-muted-foreground">No application-supported preferences exist for your account yet. Options shown here are limited to those actually provided by the system.</p></div>
        </Card>
      </>
    ) : null}

    <p className="flex items-center gap-1.5 text-xs text-muted-foreground"><AtSign size={12} /> Profile data is fetched from your authenticated account and refreshed automatically. <Mail size={12} className="ml-2" /> Contact support if your details look incorrect.</p>
  </div>
}
