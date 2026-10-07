'use client'
import { useQuery } from '@tanstack/react-query'
import { UserRound, GraduationCap, ShieldCheck, Settings2, Mail, AtSign, BadgeCheck, DoorOpen, MapPin, Armchair } from 'lucide-react'
import { userApi } from '@/services/userApi'
import { enrolmentApi } from '@/services/enrolmentApi'
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

function StatusPill({ status }: { status?: string | null }) {
  if (!status) return <span className="inline-flex rounded-full bg-muted px-2 py-0.5 text-[11px] font-semibold text-muted-foreground">Not started</span>
  const tone = status === 'STARTED' ? 'bg-emerald-500/10 text-emerald-600' : status === 'SUBMITTED' ? 'bg-sky-500/10 text-sky-600' : status === 'EXPIRED' ? 'bg-muted text-muted-foreground' : 'bg-destructive/10 text-destructive'
  const label = status === 'STARTED' ? 'In progress' : status.replaceAll('_', ' ').toLowerCase()
  return <span className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-semibold capitalize ${tone}`}>{label}</span>
}

export default function StudentProfile() {
  const meQuery = useQuery({ queryKey: ['profile-me'], queryFn: async () => (await userApi.me()).data.data, retry: 1 })
  const enrolmentsQuery = useQuery({ queryKey: ['profile-enrolments'], queryFn: async () => (await enrolmentApi.my()).data.data, retry: 1 })
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
            <div><SectionHeading icon={GraduationCap} title="Academic information" description="Identity fields your institution linked to this account." /><div className="mt-3 divide-y divide-border"><InfoRow label="Roll number" value={user.rollNumber || 'Not set'} /><InfoRow label="Date of birth" value={user.dateOfBirth || 'Not set'} /><InfoRow label="Department" value={user.department || 'Not set'} /><InfoRow label="Batch" value={user.batch || 'Not set'} /></div><p className="mt-3 text-xs text-muted-foreground">Your roll number and date of birth were used to provision this account. Once a password is set, sign-in uses your email address and password only.</p></div>
          </div>
        </Card>

        <Card>
          <div className="px-5 pt-5"><SectionHeading icon={DoorOpen} title="Exam centre" description="Where you are seated for your enrolled exams and how far each attempt has progressed." /></div>
          <div className="px-5 pb-5 pt-3">
            {enrolmentsQuery.isPending ? <div className="h-24 animate-pulse rounded-xl bg-muted" aria-busy="true" /> : enrolmentsQuery.isError ? <p className="rounded-xl bg-muted/60 p-4 text-sm text-destructive">Unable to load your exam centre information.</p> : (enrolmentsQuery.data?.length ?? 0) === 0 ? <p className="rounded-xl bg-muted/60 p-4 text-sm text-muted-foreground">You are not enrolled in any exams yet.</p> : (
              <ul className="divide-y divide-border">
                {enrolmentsQuery.data!.map(enrolment => (
                  <li key={enrolment.enrollmentId} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm">
                    <div className="min-w-0">
                      <p className="truncate font-medium">{enrolment.examTitle}</p>
                      <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                        {enrolment.centreName ? <span className="inline-flex items-center gap-1"><MapPin size={12} />{enrolment.centreName}</span> : <span className="inline-flex items-center gap-1"><MapPin size={12} />Centre not assigned</span>}
                        {enrolment.roomName ? <span className="inline-flex items-center gap-1"><DoorOpen size={12} />Room {enrolment.roomName}{enrolment.roomCode ? ` (${enrolment.roomCode})` : ''}</span> : null}
                        {enrolment.seatNumber != null ? <span className="inline-flex items-center gap-1"><Armchair size={12} />Seat {enrolment.seatNumber}</span> : null}
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
                      <span className="rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium capitalize text-muted-foreground">{enrolment.status.toLowerCase()}</span>
                      <StatusPill status={enrolment.attemptStatus} />
                    </div>
                  </li>
                ))}
              </ul>
            )}
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
