'use client'
import dynamic from 'next/dynamic'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { BrowserRouter, Navigate, NavLink, Route, Routes, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { Activity, AlertCircle, BarChart3, BookOpen, Award, Check, ChevronLeft, ChevronRight, Clock3, FileText, History as HistoryIcon, LayoutDashboard, ListChecks, LogOut, MinusCircle, MoreHorizontal, Plus, RotateCcw, Save, Search, ShieldCheck, Sparkles, Target, Timer, Trash2, TrendingUp, Users, X, KeyRound } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useAuthStore } from '@/store/authStore'
import type { ActivityEvent, LoginRole, OAuthProviderInfo, Result, Role, User } from '@/types'
import type { ActiveAttemptInfo, ExamResultReview, QuestionReview } from '@/types/exam'
import type { StudentAiAnalysis } from '@/types/ai'
import { z } from 'zod'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { QueryClient, QueryClientProvider, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/services/authApi'
import { resolveApiBaseUrl } from '@/services/api'
import { userApi } from '@/services/userApi'
import { examApi } from '@/services/examApi'
import { questionApi } from '@/services/questionApi'
import { resultApi } from '@/services/resultApi'
import { activityApi } from '@/services/activityApi'
import { retestApi } from '@/services/retestApi'
import { analyticsApi } from '@/services/analyticsApi'
import { adaptiveApi } from '@/services/adaptiveApi'
import { useActivityFeed } from '@/hooks/useActivityFeed'
import { QuestionBank } from '@/features/admin/QuestionBank'
import { PracticeSession } from '@/features/adaptive/PracticeSession'
import { ProctorMonitor } from '@/features/proctor/ProctorMonitor'
import { TeacherCommandCenter } from '@/features/teacher/TeacherCommandCenter'
import { ExamIntelligenceList } from '@/features/teacher/ExamIntelligenceList'
import { ExamIntelligence } from '@/features/teacher/ExamIntelligence'
import { AdminAnalytics, MonitorPicker } from '@/features/teacher/MonitorPicker'
import { StudentAICoach } from '@/features/analytics/StudentAICoach'
import { StudentAIPractice } from '@/features/ai/StudentAIPractice'
import { StudentPerformance } from '@/features/analytics/StudentPerformance'
import { ConfirmDialog, ToastProvider, useToast } from '@/components/feedback'
import { OAuthCallback } from '@/components/auth/OAuthCallback'
import SecuritySettings from '@/components/security/SecuritySettings'
import { Preflight } from '@/features/exam/Preflight'
import { ExamWorkspace } from '@/features/exam/ExamWorkspace'

type NavItem = { label: string; href: string; icon: LucideIcon }
type NavSection = { label: string; items: NavItem[] }
type MobileNavItem = { label: string; icon: LucideIcon; href?: string; more?: boolean }

const studentNavItems: NavItem[] = [
  { label: 'Dashboard', href: '/student/dashboard', icon: LayoutDashboard },
  { label: 'My exams', href: '/student/exams', icon: BookOpen },
  { label: 'Practice', href: '/student/practice', icon: Target },
  { label: 'Performance', href: '/student/analysis', icon: BarChart3 },
  { label: 'AI Coach', href: '/student/ai-analysis', icon: Sparkles },
  { label: 'History', href: '/student/history', icon: HistoryIcon },
  { label: 'Retests', href: '/student/retest-requests', icon: RotateCcw },
  { label: 'Security', href: '/settings/security', icon: KeyRound },
]
const studentNavSections: NavSection[] = [
  { label: 'Main', items: studentNavItems.slice(0, 2) },
  { label: 'Learning', items: studentNavItems.slice(2, 5) },
  { label: 'Activity', items: studentNavItems.slice(5) },
]
const nav: Record<Role, NavItem[]> = {
  STUDENT: studentNavItems,
  TEACHER: [
    { label: 'Dashboard', href: '/teacher/dashboard', icon: LayoutDashboard },
    { label: 'Exams', href: '/teacher/exams', icon: BookOpen },
    { label: 'Create exam', href: '/teacher/exams/create', icon: FileText },
    { label: 'Analytics', href: '/teacher/analytics', icon: BarChart3 },
    { label: 'Live monitor', href: '/teacher/monitor', icon: Activity },
    { label: 'Security', href: '/settings/security', icon: KeyRound },
  ],
  ADMIN: [
    { label: 'Dashboard', href: '/admin/dashboard', icon: LayoutDashboard },
    { label: 'Students', href: '/admin/users', icon: Users },
    { label: 'Exams', href: '/admin/exams', icon: BookOpen },
    { label: 'Question bank', href: '/admin/question-bank', icon: ListChecks },
    { label: 'Results', href: '/admin/results', icon: BarChart3 },
    { label: 'Analytics', href: '/admin/analytics', icon: TrendingUp },
    { label: 'Security', href: '/settings/security', icon: KeyRound },
  ],
}
function navActive(itemHref: string, isActive: boolean, pathname: string): boolean {
  if (isActive) return true
  return itemHref === '/student/practice' && pathname.startsWith('/student/adaptive')
}
function getMobileItems(role: Role): MobileNavItem[] {
  if (role === 'STUDENT') {
    return [
      { label: studentNavItems[0].label, icon: studentNavItems[0].icon, href: studentNavItems[0].href },
      { label: studentNavItems[1].label, icon: studentNavItems[1].icon, href: studentNavItems[1].href },
      { label: studentNavItems[2].label, icon: studentNavItems[2].icon, href: studentNavItems[2].href },
      { label: studentNavItems[5].label, icon: studentNavItems[5].icon, href: studentNavItems[5].href },
      { label: 'More', icon: MoreHorizontal, more: true },
    ]
  }
  const primary = nav[role].slice(0, 3)
  return [...primary.map(item => ({ label: item.label, icon: item.icon, href: item.href })), { label: 'More', icon: MoreHorizontal, more: true }]
}
function getDrawerItems(role: Role): NavItem[] {
  if (role === 'STUDENT') {
    const bottomHrefs = getMobileItems(role).filter(item => item.href).map(item => item.href)
    return studentNavItems.filter(item => !bottomHrefs.includes(item.href))
  }
  return nav[role].slice(3)
}
function NavGroup({ label, items }: { label?: string; items: NavItem[] }) {
  const { pathname } = useLocation()
  return (
    <div>
      {label ? <p className="px-3 pb-2 text-[11px] font-semibold uppercase tracking-widest text-muted-foreground/70">{label}</p> : null}
      <div className="space-y-1">
        {items.map(item => (
          <NavLink key={item.href} to={item.href} className={({ isActive }) => `flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition ${navActive(item.href, isActive, pathname) ? 'bg-primary text-primary-foreground shadow-sm' : 'text-muted-foreground hover:bg-muted hover:text-foreground'}`}>
            <item.icon size={18} />
            <span>{item.label}</span>
          </NavLink>
        ))}
      </div>
    </div>
  )
}
function SidebarNav({ role }: { role: Role }) {
  if (role === 'STUDENT') {
    return (
      <div className="space-y-6">
        {studentNavSections.map(section => <NavGroup key={section.label} label={section.label} items={section.items} />)}
      </div>
    )
  }
  return <NavGroup items={nav[role]} />
}
function DrawerLink({ item, onNavigate }: { item: NavItem; onNavigate: () => void }) {
  const { pathname } = useLocation()
  return (
    <NavLink to={item.href} onClick={onNavigate} className={({ isActive }) => `flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition ${navActive(item.href, isActive, pathname) ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-muted hover:text-foreground'}`}>
      <item.icon size={18} />
      <span>{item.label}</span>
    </NavLink>
  )
}
function Shell({ children }: { children: React.ReactNode }) { const { user, logout } = useAuthStore(); const navigate = useNavigate(); const location = useLocation(); const [confirmLogout, setConfirmLogout] = useState(false); const [loggingOut, setLoggingOut] = useState(false); const [moreOpen, setMoreOpen] = useState(false); const toast = useToast(); if (!user) return <Navigate to="/login" replace />; const initials = user.name ? user.name.trim().split(/\s+/).map(part => part[0]).filter(Boolean).slice(0, 2).join('').toUpperCase() : 'S'; const signOut = async () => { setLoggingOut(true); try { await authApi.logout(); toast.success('You have been signed out.'); } catch { toast.error('Signed out locally; the server could not be reached.') } finally { logout(); navigate('/login'); setLoggingOut(false) } }; return <div className="min-h-screen bg-background"><aside className="fixed inset-y-0 left-0 hidden w-64 border-r border-border bg-card p-5 lg:flex lg:flex-col"><div className="flex items-center gap-3 px-2 pb-10"><div className="grid size-9 place-items-center rounded-xl bg-primary text-primary-foreground"><ShieldCheck size={20}/></div><b>Examwise</b></div><nav className="mt-2 flex-1 overflow-y-auto px-1"><SidebarNav role={user.role} /></nav><div className="mt-auto border-t border-border pt-3"><div className="flex items-center gap-3 rounded-xl px-2 py-2"><div className="grid size-9 shrink-0 place-items-center rounded-full bg-primary/10 text-sm font-semibold text-primary">{initials}</div><div className="min-w-0"><p className="truncate text-sm font-medium">{user.name}</p><p className="truncate text-xs text-muted-foreground">{user.email}</p></div></div><button className="mt-1 flex w-full items-center gap-2 rounded-xl px-2 py-2 text-sm font-medium text-muted-foreground transition hover:bg-muted hover:text-foreground" onClick={() => setConfirmLogout(true)}><LogOut size={16}/> Sign out</button></div></aside><main className="min-h-screen pb-20 lg:pl-64 lg:pb-0"><div className="mx-auto max-w-7xl p-4 sm:p-5 md:p-8">{children}</div></main><nav aria-label="Mobile navigation" className="fixed inset-x-0 bottom-0 z-20 grid grid-cols-5 border-t border-border bg-card/95 p-2 backdrop-blur lg:hidden">{getMobileItems(user.role).map(item => item.more ? <button key={item.label} type="button" onClick={() => setMoreOpen(true)} className="min-w-0 rounded-lg px-1 py-2 text-center text-[11px] text-muted-foreground transition hover:text-foreground active:scale-[.97]"><MoreHorizontal className="mx-auto" size={18}/><span className="mt-1 block truncate">{item.label}</span></button> : <NavLink key={item.href} to={item.href!} className={({isActive}) => `min-w-0 rounded-lg px-1 py-2 text-center text-[11px] ${navActive(item.href!, isActive, location.pathname) ? 'bg-primary text-primary-foreground' : 'text-muted-foreground'}`}><item.icon className="mx-auto" size={18}/><span className="mt-1 block truncate">{item.label}</span></NavLink>)}</nav>{moreOpen ? <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="More menu"><div className="absolute inset-0 bg-black/40" onClick={() => setMoreOpen(false)}/><div className="absolute inset-x-0 bottom-0 max-h-[80vh] overflow-y-auto rounded-t-2xl border-t border-border bg-card p-4 pb-8"><div className="mb-4 flex items-center justify-between"><p className="text-sm font-semibold">Menu</p><button type="button" aria-label="Close menu" className="rounded-lg p-1.5 text-muted-foreground transition hover:bg-muted" onClick={() => setMoreOpen(false)}><X size={18}/></button></div><div className="mb-4 flex items-center gap-3 rounded-xl bg-muted p-3"><div className="grid size-10 shrink-0 place-items-center rounded-full bg-primary/10 text-sm font-semibold text-primary">{initials}</div><div className="min-w-0"><p className="truncate text-sm font-medium">{user.name}</p><p className="truncate text-xs text-muted-foreground">{user.email}</p></div></div><div className="space-y-1">{getDrawerItems(user.role).map(item => <DrawerLink key={item.href} item={item} onNavigate={() => setMoreOpen(false)} />)}</div><button type="button" onClick={() => { setMoreOpen(false); setConfirmLogout(true) }} className="mt-4 flex w-full items-center justify-center gap-2 rounded-xl border border-border px-3 py-2.5 text-sm font-medium text-muted-foreground transition hover:bg-muted hover:text-foreground"><LogOut size={16}/> Sign out</button></div></div> : null}<ConfirmDialog open={confirmLogout} title="Sign out?" description="Any in-progress work should be submitted before you leave." confirmLabel="Sign out" busy={loggingOut} onCancel={() => setConfirmLogout(false)} onConfirm={signOut}/></div> }
function Header({ title, description }: { title: string; description: string }) { const role = useAuthStore((state) => state.user?.role); return <header className="mb-8"><p className="text-xs font-semibold uppercase tracking-widest text-primary">{role} workspace</p><h1 className="mt-2 text-3xl font-semibold tracking-tight">{title}</h1><p className="mt-2 text-sm leading-6 text-muted-foreground">{description}</p></header> }
function Card({ children, className = '' }: { children: React.ReactNode; className?: string }) { return <section className={`rounded-2xl border border-border bg-card p-5 ${className}`}>{children}</section> }
function StudentExams() {
  const navigate = useNavigate()
  const toast = useToast()
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState<'ALL' | 'AVAILABLE' | 'COMPLETED'>('ALL')

  const examsQuery = useQuery({ queryKey: ['student-exams'], queryFn: async () => (await examApi.list()).data.data, retry: 1 })
  const resultsQuery = useQuery({ queryKey: ['my-results'], queryFn: async () => (await resultApi.mine()).data.data, retry: 1 })
  const retestsQuery = useQuery({ queryKey: ['my-retests'], queryFn: async () => (await retestApi.mine()).data.data, retry: 1 })

  const retestMutation = useMutation({
    mutationFn: (examId: string) => retestApi.request(examId),
    onSuccess: () => { toast.success('Retest request submitted.'); retestsQuery.refetch() },
    onError: (error: any) => {
      const msg = error?.response?.data?.message
      if (error?.response?.status === 409) toast.error(msg || 'A retest request already exists for this exam.')
      else if (error?.response?.status === 403) toast.error('You do not have permission to request a retest.')
      else toast.error('Unable to submit retest request. Please try again.')
    },
  })

  const completedByExam = useMemo(() => {
    const map = new Map<string, Result>()
    for (const r of resultsQuery.data ?? []) if (r.examId) map.set(r.examId, r)
    return map
  }, [resultsQuery.data])

  const retestByExam = useMemo(() => {
    const map = new Map<string, string>()
    for (const r of retestsQuery.data ?? []) map.set(r.examId, r.status)
    return map
  }, [retestsQuery.data])

  const isLoading = examsQuery.isPending || resultsQuery.isPending
  const isError = examsQuery.isError || resultsQuery.isError

  const exams = useMemo(() => {
    if (!examsQuery.data) return []
    return examsQuery.data
      .map(exam => {
        const result = completedByExam.get(exam.id)
        const retestStatus = retestByExam.get(exam.id) ?? null
        const isCompleted = Boolean(result)
        const percentage = result && result.total > 0 ? Math.round((result.score * 10000) / result.total) / 100 : null
        return { ...exam, isCompleted, result, percentage, retestStatus }
      })
      .filter(exam => {
        const matchesSearch = !search || exam.title.toLowerCase().includes(search.toLowerCase()) || exam.subject.toLowerCase().includes(search.toLowerCase())
        const matchesFilter = filter === 'ALL' || (filter === 'AVAILABLE' && !exam.isCompleted) || (filter === 'COMPLETED' && exam.isCompleted)
        return matchesSearch && matchesFilter
      })
      .sort((a, b) => (a.isCompleted ? 1 : 0) - (b.isCompleted ? 1 : 0))
  }, [examsQuery.data, completedByExam, retestByExam, search, filter])

  return (
    <>
      <Header title="Exam center" description="Review assigned assessments, start when you are ready, and track your progress." />
      <Card>
        <div className="flex flex-col gap-3 sm:flex-row">
          <label className="relative flex-1">
            <Search size={16} className="pointer-events-none absolute left-3 top-3 text-muted-foreground" />
            <input className="field pl-9" value={search} onChange={e => setSearch(e.target.value)} placeholder="Search by title or subject..." />
            {search && <button type="button" className="absolute right-3 top-3 text-muted-foreground hover:text-foreground" onClick={() => setSearch('')}><X size={14} /></button>}
          </label>
          <div className="flex gap-1" role="tablist" aria-label="Filter exams">
            {(['ALL', 'AVAILABLE', 'COMPLETED'] as const).map(tab => (
              <button key={tab} type="button" role="tab" aria-selected={filter === tab}
                className={`rounded-lg px-3 py-2 text-sm font-medium transition ${filter === tab ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted hover:text-foreground'}`}
                onClick={() => setFilter(tab)}>
                {tab === 'ALL' ? 'All' : tab === 'AVAILABLE' ? 'Available' : 'Completed'}
              </button>
            ))}
          </div>
        </div>
        {isLoading && <div className="mt-5 grid gap-4 md:grid-cols-2" aria-busy="true">{[1,2,3,4].map(i => <div key={i} className="h-44 animate-pulse rounded-xl bg-muted" />)}</div>}
        {!isLoading && isError && <div className="py-8 text-center"><p className="text-sm text-destructive">Unable to load exams.</p><button type="button" className="mt-3 rounded-lg border border-border px-3 py-2 text-sm" onClick={() => { examsQuery.refetch(); resultsQuery.refetch() }}>Retry</button></div>}
        {!isLoading && !isError && exams.length === 0 && (
          <p className="py-10 text-center text-sm text-muted-foreground">
            {filter === 'ALL' && !search ? 'No exams are available right now.' : filter === 'AVAILABLE' ? 'No upcoming exams.' : filter === 'COMPLETED' ? 'No completed exams yet.' : 'No exams match your search.'}
          </p>
        )}
        {!isLoading && !isError && exams.length > 0 && (
          <div className="mt-5 grid gap-4 md:grid-cols-2">
            {exams.map(exam => (
              <Card key={exam.id}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <p className="text-xs font-medium text-primary">{exam.subject}</p>
                      <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-semibold ${exam.isCompleted ? 'bg-emerald-500/10 text-emerald-600' : 'bg-primary/10 text-primary'}`}>{exam.isCompleted ? 'Completed' : 'Available'}</span>
                    </div>
                    <h2 className="mt-2 truncate font-semibold">{exam.title}</h2>
                    <p className="mt-1.5 flex items-center gap-1.5 text-sm text-muted-foreground">
                      <Clock3 size={14} className="shrink-0" />{exam.duration} min
                      {exam.startAt && exam.endAt && <> · {new Date(exam.startAt).toLocaleDateString()} – {new Date(exam.endAt).toLocaleDateString()}</>}
                    </p>
                  </div>
                  <BookOpen size={20} className="shrink-0 text-primary" />
                </div>
                {exam.isCompleted && exam.result && (
                  <div className="mt-3 rounded-lg bg-muted/60 px-3 py-2 text-sm">
                    Score: <span className="font-semibold">{exam.result.score}/{exam.result.total}</span>
                    {exam.percentage !== null && <span className="ml-1.5 text-muted-foreground">({exam.percentage}%)</span>}
                  </div>
                )}
                <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-border pt-3">
                  {!exam.isCompleted ? (
                    <>
                      <button type="button" className="flex items-center gap-1.5 rounded-xl border border-border px-4 py-2 text-sm font-medium transition hover:bg-muted active:scale-[.98]"
                        onClick={() => navigate(`/student/exams/${exam.id}/instructions`)}>View Instructions</button>
                      <button type="button"
                        className="flex items-center gap-1.5 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition hover:opacity-90 active:scale-[.98]"
                        onClick={() => navigate(`/student/exams/${exam.id}/instructions`)}>
                        Start <ChevronRight size={14} />
                      </button>
                    </>
                  ) : (
                    <>
                      <button type="button" className="flex items-center gap-1.5 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition hover:opacity-90 active:scale-[.98]"
                        onClick={() => navigate(`/student/exams/${exam.id}/result`)}>View Result</button>
                      {exam.retestStatus === null || exam.retestStatus === 'REJECTED' ? (
                        <button type="button" disabled={retestMutation.isPending && retestMutation.variables === exam.id}
                          className="flex items-center gap-1.5 rounded-xl border border-border px-4 py-2 text-sm font-medium transition hover:bg-muted active:scale-[.98] disabled:opacity-50"
                          onClick={() => retestMutation.mutate(exam.id)}>
                          <RotateCcw size={14} />{retestMutation.isPending && retestMutation.variables === exam.id ? 'Requesting...' : 'Request Retest'}
                        </button>
                      ) : (
                        <span className="inline-flex items-center gap-1.5 rounded-xl border border-border px-4 py-2 text-sm text-muted-foreground">
                          {exam.retestStatus === 'PENDING' && <><Clock3 size={14} />Retest pending</>}
                          {exam.retestStatus === 'APPROVED' && <><Check size={14} className="text-emerald-500" />Retest approved</>}
                        </span>
                      )}
                    </>
                  )}
                </div>
              </Card>
            ))}
          </div>
        )}
      </Card>
    </>
  )
}
function StudentPractice() { const navigate = useNavigate(); const examsQuery = useQuery({ queryKey: ['student-practice'], queryFn: async () => (await examApi.list()).data.data, retry: 1 }); return <><Header title="Adaptive Practice" description="Drill any exam with questions that adjust to your skill as you answer."/>{examsQuery.isPending ? <div className="grid gap-4 md:grid-cols-2" aria-busy="true">{[1,2,3,4].map(item => <Card key={item}><div className="h-24 animate-pulse rounded-xl bg-muted"/></Card>)}</div> : examsQuery.isError ? <Card><div role="alert" className="flex flex-wrap items-center justify-between gap-3"><p className="text-sm text-destructive">Unable to load available exams.</p><button className="rounded-lg border border-border px-3 py-2 text-sm" onClick={() => examsQuery.refetch()}>Retry</button></div></Card> : examsQuery.data?.length ? <div className="grid gap-4 md:grid-cols-2">{examsQuery.data.map(exam => <Card key={exam.id}><div className="flex items-start justify-between"><div><p className="text-xs font-medium text-primary">{exam.subject}</p><h2 className="mt-2 font-semibold">{exam.title}</h2><p className="mt-2 text-sm text-muted-foreground">{exam.duration} minutes</p></div><Target className="text-primary"/></div><div className="mt-5"><button className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground" onClick={() => navigate(`/student/adaptive/${exam.id}`)}>Start practice <ChevronRight size={16}/></button></div></Card>)}</div> : <Card><p className="py-8 text-center text-sm text-muted-foreground">No exams are available to practice right now.</p></Card>}</> }
function Instructions() { const { id = '' } = useParams(); return <Preflight id={id} /> }
function Attempt() { const { id = '' } = useParams(); return <ExamWorkspace id={id} /> }
function QuestionReviewCard({ question }: { question: QuestionReview }) {
  const status = question.answered ? (question.correct ? 'correct' : 'incorrect') : 'unanswered'
  const badge = status === 'correct'
    ? <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2.5 py-1 text-xs font-semibold text-emerald-700"><Check size={12} />Correct</span>
    : status === 'incorrect'
      ? <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/10 px-2.5 py-1 text-xs font-semibold text-amber-700"><X size={12} />Incorrect</span>
      : <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-xs font-semibold text-muted-foreground"><MinusCircle size={12} />Unanswered</span>
  return (
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">Question {question.number}</p>
        {badge}
      </div>
      <h3 className="mt-3 font-medium leading-6">{question.questionText}</h3>
      <ul className="mt-4 space-y-2">
        {question.options.map((option, optionIndex) => {
          const isCorrect = option === question.correctOptionText
          const isSelected = question.answered && option === question.selectedOptionText
          const tileClass = isCorrect ? 'border-emerald-500/40 bg-emerald-500/5' : isSelected ? 'border-amber-500/40 bg-amber-500/5' : 'border-border'
          return (
            <li key={`${question.questionId}-${optionIndex}`} className={`flex flex-wrap items-center gap-3 rounded-xl border p-3 text-sm ${tileClass}`}>
              <span className="grid size-6 shrink-0 place-items-center rounded-md bg-muted text-xs font-semibold text-muted-foreground">{String.fromCharCode(65 + optionIndex)}</span>
              <span className="min-w-0 flex-1 break-words">{option}</span>
              <span className="flex shrink-0 items-center gap-2">
                {isSelected && <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-[11px] font-semibold text-muted-foreground"><Check size={11} className="text-emerald-600" />Your answer</span>}
                {isCorrect && <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-[11px] font-semibold text-emerald-700"><Check size={11} />Correct answer</span>}
              </span>
            </li>
          )
        })}
      </ul>
      <div className="mt-4 grid gap-2 rounded-xl bg-muted/60 p-3 text-sm sm:grid-cols-2">
        <p className="break-words"><span className="text-muted-foreground">Your answer:</span> {question.answered ? <b>{question.selectedOptionText}</b> : <span className="italic text-muted-foreground">Not answered</span>}</p>
        <p className="break-words"><span className="text-muted-foreground">Correct answer:</span> <b>{question.correctOptionText}</b></p>
      </div>
    </Card>
  )
}

function ResultPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const reviewQuery = useQuery({
    queryKey: ['exam-result-review', id],
    queryFn: async () => (await examApi.resultReview(id)).data.data,
    enabled: Boolean(id),
    retry: 1,
  })
  if (reviewQuery.isPending) {
    return (
      <>
        <Header title="Exam result" description="Loading your question-by-question review..." />
        <div className="mx-auto max-w-3xl space-y-4" aria-busy="true">
          <div className="h-44 animate-pulse rounded-2xl bg-muted" />
          <div className="h-40 animate-pulse rounded-2xl bg-muted" />
          <div className="h-40 animate-pulse rounded-2xl bg-muted" />
        </div>
      </>
    )
  }
  const status = (reviewQuery.error as { response?: { status?: number } } | null)?.response?.status
  if (reviewQuery.isError || !reviewQuery.data) {
    const notFound = status === 404
    const forbidden = status === 403
    const message = notFound
      ? 'No result was found for this exam. Submit the exam to unlock the full question-by-question review.'
      : forbidden
        ? 'You do not have access to this result.'
        : 'Unable to load your result. Please try again.'
    return (
      <>
        <Header title="Exam result" description="Your result review is not available right now." />
        <Card className="mx-auto max-w-2xl">
          <div role="alert" className="text-center">
            <div className="mx-auto grid size-12 place-items-center rounded-full bg-muted text-muted-foreground"><AlertCircle size={22} /></div>
            <p className="mt-4 text-sm text-muted-foreground">{message}</p>
            <div className="mt-6 flex flex-wrap justify-center gap-3">
              {!notFound && !forbidden && <button type="button" className="rounded-xl bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground" onClick={() => reviewQuery.refetch()}>Try again</button>}
              <button type="button" className="rounded-xl border border-border px-4 py-2.5 text-sm" onClick={() => navigate('/student/exams')}>Back to My Exams</button>
            </div>
          </div>
        </Card>
      </>
    )
  }
  const review = reviewQuery.data
  return (
    <>
      <Header title="Exam result" description={`${review.examTitle} · ${review.subject} · ${review.date}`} />
      <div className="mx-auto max-w-3xl space-y-4">
        <Card>
          <div className="text-center">
            <div className="mx-auto grid size-16 place-items-center rounded-full bg-emerald-500/10 text-emerald-600"><Award size={30} /></div>
            <p className="mt-5 text-5xl font-semibold">{review.percentage}%</p>
            <p className="mt-2 text-sm text-muted-foreground">{review.score} of {review.total} correct</p>
          </div>
          <div className="mt-8 grid grid-cols-2 gap-3 text-center sm:grid-cols-5">
            <div className="rounded-xl bg-muted p-3"><b className="text-xl">{review.score}</b><p className="mt-1 text-xs text-muted-foreground">Score</p></div>
            <div className="rounded-xl bg-muted p-3"><b className="text-xl">{review.percentage}%</b><p className="mt-1 text-xs text-muted-foreground">Percentage</p></div>
            <div className="rounded-xl bg-emerald-500/10 p-3"><b className="text-xl text-emerald-600">{review.correctCount}</b><p className="mt-1 text-xs font-medium text-emerald-700">Correct</p></div>
            <div className="rounded-xl bg-amber-500/10 p-3"><b className="text-xl text-amber-600">{review.incorrectCount}</b><p className="mt-1 text-xs font-medium text-amber-700">Incorrect</p></div>
            <div className="rounded-xl bg-muted p-3"><b className="text-xl">{review.unansweredCount}</b><p className="mt-1 text-xs text-muted-foreground">Unanswered</p></div>
          </div>
        </Card>
        {review.questions.length === 0 ? (
          <Card><p className="py-8 text-center text-sm text-muted-foreground">No question details are available for this result.</p></Card>
        ) : (
          review.questions.map(question => <QuestionReviewCard key={question.questionId} question={question} />)
        )}
        <div className="pt-2">
          <button type="button" onClick={() => navigate('/student/exams')} className="w-full rounded-xl border border-border px-4 py-3 text-sm">Back to My Exams</button>
        </div>
      </div>
    </>
  )
}
function History() { const resultsQuery = useQuery({ queryKey: ['my-results'], queryFn: async () => (await resultApi.mine()).data.data, retry: 1 }); return <><Header title="Exam history" description="Review your completed attempts and results."/><Card>{resultsQuery.isPending ? <div className="space-y-3" aria-busy="true">{[1,2,3].map(item => <div key={item} className="h-14 animate-pulse rounded-xl bg-muted"/>)}</div> : resultsQuery.isError ? <div role="alert" className="flex flex-wrap items-center justify-between gap-3"><p className="text-sm text-destructive">Unable to load your results.</p><button className="rounded-lg border border-border px-3 py-2 text-sm" onClick={() => resultsQuery.refetch()}>Retry</button></div> : resultsQuery.data?.length ? <div className="divide-y divide-border">{resultsQuery.data.map(r => { const percentage = r.total > 0 ? Math.round((r.score * 10000) / r.total) / 100 : 0; return <div key={r.id} className="flex items-center justify-between gap-4 py-4 first:pt-0"><div><p className="font-medium">{r.examTitle}</p><p className="mt-1 text-sm text-muted-foreground">{r.subject} · {r.date} · {r.score}/{r.total}</p></div><span className="font-semibold text-emerald-600">{percentage}%</span></div> })}</div> : <p className="py-8 text-center text-sm text-muted-foreground">No completed exams yet.</p>}</Card></> }
function TeacherExams() { const navigate = useNavigate(); const queryClient = useQueryClient(); const examsQuery = useQuery({ queryKey: ['teacher-exams'], queryFn: async () => (await examApi.list()).data.data, retry: 1 }); const publishMutation = useMutation({ mutationFn: (id: string) => examApi.publish(id), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['teacher-exams'] }) }); const deleteMutation = useMutation({ mutationFn: (id: string) => examApi.remove(id), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['teacher-exams'] }) }); return <><Header title="Exam management" description="Create, edit, validate, and publish assessments."/><div className="mb-5 flex justify-end"><button onClick={() => navigate('/teacher/exams/create')} className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2.5 text-sm text-primary-foreground"><Plus size={16}/> Create exam</button></div><Card>{examsQuery.isPending ? <div className="space-y-3" aria-busy="true">{[1,2,3].map(item => <div key={item} className="h-16 animate-pulse rounded-xl bg-muted"/>)}</div> : examsQuery.isError ? <div role="alert" className="flex flex-wrap items-center justify-between gap-3"><p className="text-sm text-destructive">Unable to load exams.</p><button className="rounded-lg border border-border px-3 py-2 text-sm" onClick={() => examsQuery.refetch()}>Retry</button></div> : examsQuery.data?.length ? <div className="divide-y divide-border">{examsQuery.data.map(e => <div key={e.id} className="flex flex-wrap items-center justify-between gap-4 py-4 first:pt-0"><div><p className="font-medium">{e.title}</p><p className="mt-1 text-sm text-muted-foreground">{e.subject} · {e.duration} min · {e.status} · {e.participants} participants</p></div><div className="flex gap-2"><button className="rounded-lg border border-border px-3 py-2 text-xs" onClick={() => navigate(`/teacher/exams/${e.id}/questions`)}>Questions</button><button disabled={e.status === 'DRAFT'} className="rounded-lg border border-border px-3 py-2 text-xs disabled:opacity-50" onClick={() => navigate(`/teacher/monitor/${e.id}`)}>Monitor</button><button disabled={publishMutation.isPending || e.status !== 'DRAFT'} className="rounded-lg bg-primary/10 px-3 py-2 text-xs text-primary disabled:opacity-50" onClick={() => publishMutation.mutate(e.id)}>Publish</button><button className="rounded-lg p-2 text-muted-foreground" onClick={() => deleteMutation.mutate(e.id)}><Trash2 size={15}/></button></div></div>)}</div> : <p className="py-8 text-center text-sm text-muted-foreground">No exams created yet.</p>}</Card></> }
function CreateExam() { const navigate = useNavigate(); const queryClient = useQueryClient(); const schema = z.object({ title: z.string().min(3), subject: z.string().min(2), date: z.string().min(1), duration: z.coerce.number().min(1).max(300) }); const { register, handleSubmit, formState: { errors } } = useForm({ resolver: zodResolver(schema), defaultValues: { date: new Date().toISOString().slice(0, 10), duration: 60 } }); const createMutation = useMutation({ mutationFn: (values: { title: string; subject: string; date: string; duration: number }) => examApi.create(values), onSuccess: response => { queryClient.invalidateQueries({ queryKey: ['teacher-exams'] }); navigate(`/teacher/exams/${response.data.data.id}/questions`) } }); return <><Header title="Create an exam" description="Set the assessment details, then add and validate questions."/><Card className="max-w-2xl"><form className="space-y-5" onSubmit={handleSubmit(values => createMutation.mutate(values))}><label className="block text-sm font-medium">Title<input className="field mt-2" {...register('title')} placeholder="e.g. Biology midterm"/>{errors.title && <span className="text-xs text-destructive">Enter a title</span>}</label><label className="block text-sm font-medium">Subject<input className="field mt-2" {...register('subject')} placeholder="Biology"/>{errors.subject && <span className="text-xs text-destructive">Enter a subject</span>}</label><label className="block text-sm font-medium">Exam date<input className="field mt-2" type="date" {...register('date')} /></label><label className="block text-sm font-medium">Duration in minutes<input className="field mt-2" type="number" {...register('duration')} />{errors.duration && <span className="text-xs text-destructive">Enter a valid duration</span>}</label>{createMutation.isError && <p className="text-sm text-destructive">Unable to create this exam.</p>}<button disabled={createMutation.isPending} className="flex items-center gap-2 rounded-xl bg-primary px-4 py-3 text-sm text-primary-foreground disabled:opacity-60"><Save size={16}/> {createMutation.isPending ? 'Saving...' : 'Save draft'}</button></form></Card></> }
function QuestionsPage() { const { id = '' } = useParams(); const queryClient = useQueryClient(); const [text, setText] = useState(''); const [options, setOptions] = useState(['', '', '', '']); const [correctIndex, setCorrectIndex] = useState(0); const questionsQuery = useQuery({ queryKey: ['exam-questions', id], queryFn: async () => (await questionApi.list(id)).data.data, enabled: Boolean(id), retry: 1 }); const createMutation = useMutation({ mutationFn: () => { const cleanedOptions = options.map(option => option.trim()).filter(Boolean); const answer = options[correctIndex]?.trim(); return questionApi.create(id, { text, options: cleanedOptions, answer }) }, onSuccess: () => { setText(''); setOptions(['', '', '', '']); setCorrectIndex(0); queryClient.invalidateQueries({ queryKey: ['exam-questions', id] }) } }); const deleteMutation = useMutation({ mutationFn: (questionId: string) => questionApi.remove(questionId), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['exam-questions', id] }) }); const cleanedOptions = options.map(option => option.trim()).filter(Boolean); const canAdd = text.trim().length > 0 && cleanedOptions.length >= 2 && Boolean(options[correctIndex]?.trim()); return <><Header title="Question authoring" description="Build multiple-choice questions and mark the correct answer."/><Card><div className="grid gap-4"><label className="block text-sm font-medium">Question text<input className="field mt-2" value={text} onChange={e => setText(e.target.value)} placeholder="Question text"/></label><div className="grid gap-3 md:grid-cols-2">{options.map((option, optionIndex) => <label key={optionIndex} className="block text-sm font-medium">Option {optionIndex + 1}<div className="mt-2 flex gap-2"><input className="field" value={option} onChange={e => setOptions(current => current.map((item, i) => i === optionIndex ? e.target.value : item))} placeholder={`Option ${optionIndex + 1}`}/><button type="button" aria-label={`Mark option ${optionIndex + 1} correct`} onClick={() => setCorrectIndex(optionIndex)} className={`grid size-11 shrink-0 place-items-center rounded-xl border ${correctIndex === optionIndex ? 'border-primary bg-primary text-primary-foreground' : 'border-border'}`}><Check size={17}/></button></div></label>)}</div>{createMutation.isError && <p className="text-sm text-destructive">Unable to add this question. Check the options and correct answer.</p>}<button disabled={!canAdd || createMutation.isPending} className="w-fit rounded-xl bg-primary px-4 py-2 text-sm text-primary-foreground disabled:opacity-50" onClick={() => createMutation.mutate()}>Add question</button></div><div className="mt-6 space-y-3">{questionsQuery.isPending ? <div className="h-24 animate-pulse rounded-xl bg-muted"/> : questionsQuery.isError ? <p className="text-sm text-destructive">Unable to load questions.</p> : questionsQuery.data?.length ? questionsQuery.data.map((q, i) => <div key={q.id} className="flex items-start justify-between rounded-xl bg-muted p-4"><div><p className="text-xs text-primary">Question {i + 1} · MCQ</p><p className="mt-1 text-sm font-medium">{q.text}</p><p className="mt-2 text-xs text-muted-foreground">Options: {q.options.join(', ')}</p>{q.answer && <p className="mt-1 text-xs font-medium text-emerald-700">Correct answer: {q.answer}</p>}</div><button onClick={() => deleteMutation.mutate(q.id)} className="text-muted-foreground"><Trash2 size={16}/></button></div>) : <p className="rounded-xl bg-muted p-6 text-center text-sm text-muted-foreground">No questions added yet.</p>}</div></Card></> }
function Protected({ roles, children }: { roles: Role[]; children: React.ReactNode }) { const user = useAuthStore(s => s.user); const hydrated = useAuthStore(s => s.hydrated); useEffect(() => { if (!hydrated) useAuthStore.persist.rehydrate() }, [hydrated]); if (!hydrated) return <div className="grid min-h-screen place-items-center bg-background p-6"><p className="text-sm text-muted-foreground" role="status">Loading your session...</p></div>; if (!user) return <Navigate to="/login" replace/>; if (!roles.includes(user.role)) return <Navigate to={`/${user.role.toLowerCase()}/dashboard`} replace/>; return <Shell>{children}</Shell> }
function AdminUsers() { const query = useQuery({ queryKey: ['admin-users'], queryFn: async () => (await userApi.list()).data.data, retry: 1 }); return <><Header title="User management" description="Review users provisioned by the examination service."/><Card>{query.isPending ? <div className="space-y-3" aria-busy="true">{[1,2,3].map(item => <div key={item} className="h-14 animate-pulse rounded-xl bg-muted"/>)}</div> : query.isError ? <div role="alert" className="flex flex-wrap items-center justify-between gap-3"><p className="text-sm text-destructive">Unable to load users from the API.</p><button className="rounded-lg border border-border px-3 py-2 text-sm" onClick={() => query.refetch()}>Retry</button></div> : query.data?.length ? <div className="overflow-x-auto"><table className="w-full min-w-[560px] text-left text-sm"><thead className="border-b border-border text-xs uppercase tracking-wide text-muted-foreground"><tr><th className="px-3 py-3">Name</th><th className="px-3 py-3">Email</th><th className="px-3 py-3">Role</th></tr></thead><tbody className="divide-y divide-border">{query.data.map(user => <tr key={user.id}><td className="px-3 py-4 font-medium">{user.name}</td><td className="px-3 py-4 text-muted-foreground">{user.email}</td><td className="px-3 py-4">{user.role}</td></tr>)}</tbody></table></div> : <p className="py-8 text-center text-sm text-muted-foreground">No users found.</p>}</Card></> }
function AdminResults() { const [search, setSearch] = useState(''); const [status, setStatus] = useState('ALL'); const resultsQuery = useQuery({ queryKey: ['admin-results'], queryFn: async () => (await resultApi.list()).data.data, retry: 1 }); const results = useMemo(() => (resultsQuery.data ?? []).filter(result => { const percentage = result.total ? (result.score / result.total) * 100 : 0; const label = percentage >= 50 ? 'PASS' : 'FAIL'; return `${result.examTitle} ${result.subject}`.toLowerCase().includes(search.toLowerCase()) && (status === 'ALL' || label === status) }), [resultsQuery.data, search, status]); return <><Header title="Results" description="Review performance across submitted examination attempts."/><Card><div className="flex flex-col gap-3 sm:flex-row"><label className="relative flex-1"><Search size={16} className="pointer-events-none absolute left-3 top-3 text-muted-foreground"/><input className="field pl-9" value={search} onChange={e => setSearch(e.target.value)} placeholder="Search exam or subject"/></label><select className="field sm:w-36" value={status} onChange={e => setStatus(e.target.value)}><option value="ALL">All status</option><option value="PASS">Pass</option><option value="FAIL">Fail</option></select></div>{resultsQuery.isPending ? <div className="mt-5 space-y-3">{[1,2,3].map(i => <div key={i} className="h-16 animate-pulse rounded-xl bg-muted"/>)}</div> : resultsQuery.isError ? <div className="py-8 text-center"><p className="text-sm text-destructive">Unable to load results.</p><button className="mt-3 rounded-lg border border-border px-3 py-2 text-sm" onClick={() => resultsQuery.refetch()}>Retry</button></div> : results.length ? <div className="mt-5 overflow-x-auto"><table className="w-full min-w-[620px] text-left text-sm"><thead className="border-b border-border text-xs uppercase tracking-wide text-muted-foreground"><tr><th className="p-3">Exam</th><th className="p-3">Subject</th><th className="p-3">Score</th><th className="p-3">Status</th><th className="p-3">Date</th></tr></thead><tbody className="divide-y divide-border">{results.map(r => { const pct = r.total ? Math.round(r.score * 100 / r.total) : 0; return <tr key={r.id}><td className="p-3 font-medium">{r.examTitle}</td><td className="p-3 text-muted-foreground">{r.subject}</td><td className="p-3">{r.score}/{r.total} · {pct}%</td><td className={`p-3 font-medium ${pct >= 50 ? 'text-emerald-600' : 'text-destructive'}`}>{pct >= 50 ? 'PASS' : 'FAIL'}</td><td className="p-3 text-muted-foreground">{r.date}</td></tr> })}</tbody></table></div> : <p className="py-10 text-center text-sm text-muted-foreground">No results match these filters.</p>}</Card></> }
function HistoryEnhanced() { const navigate = useNavigate(); const [search, setSearch] = useState(''); const [outcome, setOutcome] = useState('ALL'); const query = useQuery({ queryKey: ['my-results'], queryFn: async () => (await resultApi.mine()).data.data, retry: 1 }); const results = useMemo(() => (query.data ?? []).filter(r => { const passed = r.total ? r.score / r.total >= .5 : false; return `${r.examTitle} ${r.subject}`.toLowerCase().includes(search.toLowerCase()) && (outcome === 'ALL' || (outcome === 'PASS') === passed) }), [query.data, search, outcome]); return <><Header title="Exam history" description="Search completed assessments and revisit each saved result."/><Card><div className="flex flex-col gap-3 sm:flex-row"><label className="relative flex-1"><Search size={16} className="pointer-events-none absolute left-3 top-3 text-muted-foreground"/><input className="field pl-9" value={search} onChange={e => setSearch(e.target.value)} placeholder="Search exams or subjects"/></label><select className="field sm:w-36" value={outcome} onChange={e => setOutcome(e.target.value)}><option value="ALL">All results</option><option value="PASS">Passed</option><option value="FAIL">Not passed</option></select></div>{query.isPending ? <div className="mt-5 space-y-3">{[1,2,3].map(i => <div key={i} className="h-16 animate-pulse rounded-xl bg-muted"/>)}</div> : query.isError ? <div className="py-8 text-center"><p className="text-sm text-destructive">Unable to load your results.</p><button className="mt-3 rounded-lg border border-border px-3 py-2 text-sm" onClick={() => query.refetch()}>Retry</button></div> : results.length ? <div className="mt-5 divide-y divide-border">{results.map(r => { const pct = r.total ? Math.round(r.score * 100 / r.total) : 0; return <div key={r.id} className="flex flex-wrap items-center justify-between gap-3 py-4"><div><p className="font-medium">{r.examTitle}</p><p className="mt-1 text-sm text-muted-foreground">{r.subject} · {r.date} · {r.score}/{r.total}</p></div><div className="flex items-center gap-4"><span className={pct >= 50 ? 'font-semibold text-emerald-600' : 'font-semibold text-destructive'}>{pct}%</span><button className="rounded-lg border border-border px-3 py-2 text-xs" onClick={() => navigate(`/student/exams/${r.examId}/result`)}>View result</button></div></div> })}</div> : <p className="py-10 text-center text-sm text-muted-foreground">No completed exams match these filters.</p>}</Card></> }
function Dashboard() {
  const user = useAuthStore((state) => state.user)
  const token = useAuthStore((state) => state.token)
  const examsQuery = useQuery({
    queryKey: ['dashboard-exams', user?.role],
    queryFn: async () => (await examApi.list()).data.data,
    enabled: Boolean(user),
    retry: 1,
  })
  const resultsQuery = useQuery({
  queryKey: ['dashboard-results', user?.id],
  queryFn: async () => (await resultApi.mine()).data.data,
  enabled: user?.role === 'STUDENT',
  retry: 1,
  })
  const usersQuery = useQuery({
  queryKey: ['dashboard-users'],
  queryFn: async () => (await userApi.list()).data.data,
  enabled: user?.role === 'ADMIN',
  retry: 1,
  })
  const activityQuery = useQuery({ queryKey: ['dashboard-activity', user?.role, user?.id], queryFn: async () => (await activityApi.recent()).data.data, enabled: user?.role === 'STUDENT' || user?.role === 'ADMIN', retry: 1 })
  const activityClient = useQueryClient()
  const addActivity = useCallback((event: ActivityEvent) => activityClient.setQueryData<ActivityEvent[]>(['dashboard-activity', user?.role, user?.id], current => [event, ...(current ?? []).filter(item => item.id !== event.id)].slice(0, 20)), [activityClient, user?.id, user?.role])
  const refreshActivity = useCallback(() => { activityQuery.refetch() }, [activityQuery.refetch])
  useActivityFeed(user?.role, token, addActivity, refreshActivity)
  const exams = examsQuery.data ?? []
  const results = resultsQuery.data ?? []
  const completed = results.length
  const average = completed ? Math.round(results.reduce((sum, item) => sum + (item.total ? (item.score * 100) / item.total : 0), 0) / completed) : 0
  const published = exams.filter((exam) => exam.status !== 'DRAFT').length

  return (
    <>
      <Header
        title={user?.role === 'ADMIN' ? 'System overview' : user?.role === 'TEACHER' ? 'Teacher dashboard' : `Good morning, ${user?.name ?? 'student'}`}
        description="Live numbers pulled from the backend database."
      />
      <div className="grid gap-4 sm:grid-cols-3">
        <Card>
          <p className="text-sm text-muted-foreground">{user?.role === 'STUDENT' ? 'Completed exams' : 'Total exams'}</p>
          <p className="mt-2 text-3xl font-semibold">{user?.role === 'STUDENT' ? completed : exams.length}</p>
        </Card>
        <Card>
          <p className="text-sm text-muted-foreground">{user?.role === 'STUDENT' ? 'Average score' : 'Published exams'}</p>
          <p className="mt-2 text-3xl font-semibold">{user?.role === 'STUDENT' ? `${average}%` : published}</p>
        </Card>
        <Card>
          <p className="text-sm text-muted-foreground">{user?.role === 'STUDENT' ? 'Available exams' : 'Users in system'}</p>
          <p className="mt-2 text-3xl font-semibold">{user?.role === 'STUDENT' ? exams.length : user?.role === 'ADMIN' ? (usersQuery.data?.length ?? 0) : exams.length}</p>
        </Card>
      </div>
      <Card className="mt-6">
        <h2 className="font-semibold">Recent activity</h2>
        {activityQuery.isPending ? <div className="mt-4 space-y-3">{[1, 2, 3].map(item => <div key={item} className="h-12 animate-pulse rounded-xl bg-muted"/>)}</div> : activityQuery.isError ? <div className="mt-4 flex items-center justify-between gap-3"><p className="text-sm text-destructive">Unable to load recent activity.</p><button className="rounded-lg border border-border px-3 py-2 text-sm" onClick={() => activityQuery.refetch()}>Retry</button></div> : activityQuery.data?.length ? <div className="mt-4 divide-y divide-border">{activityQuery.data.map(event => <div key={event.id} className="flex items-center justify-between gap-4 py-3 text-sm"><p>{event.message}</p><time className="shrink-0 text-xs text-muted-foreground">{new Date(event.createdAt).toLocaleString()}</time></div>)}</div> : <p className="mt-3 text-sm text-muted-foreground">No recent activity yet.</p>}
      </Card>
    </>
  )
}
function formatCountdown(totalSeconds: number) {
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  if (hours > 0) return `${hours}h ${String(minutes).padStart(2, '0')}m ${String(seconds).padStart(2, '0')}s`
  if (minutes > 0) return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  return `${seconds}s`
}

function formatStartsIn(target: number) {
  const minutes = Math.max(1, Math.round((target - Date.now()) / 60000))
  if (minutes < 60) return `Starts in ${minutes}m`
  const hours = Math.floor(minutes / 60)
  const remainder = minutes % 60
  if (hours < 24) return remainder ? `Starts in ${hours}h ${remainder}m` : `Starts in ${hours}h`
  const days = Math.floor(hours / 24)
  const remHours = hours % 24
  return remHours ? `Starts in ${days}d ${remHours}h` : `Starts in ${days}d`
}

function ActiveExamCard({ attempt, onResume, onExpired }: { attempt: ActiveAttemptInfo; onResume: () => void; onExpired: () => void }) {
  const [secondsLeft, setSecondsLeft] = useState(() => Math.max(0, attempt.remainingSeconds))
  useEffect(() => {
    setSecondsLeft(Math.max(0, attempt.remainingSeconds))
    const target = new Date(attempt.expiresAt).getTime()
    const update = () => setSecondsLeft(Math.max(0, Math.ceil((target - Date.now()) / 1000)))
    update()
    const timer = window.setInterval(update, 1000)
    return () => window.clearInterval(timer)
  }, [attempt.remainingSeconds, attempt.expiresAt])
  const expired = secondsLeft <= 0
  useEffect(() => {
    if (expired) onExpired()
  }, [expired, onExpired])
  const low = !expired && secondsLeft <= 300
  return (
    <Card className="border-primary/40 bg-primary/[0.04]">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary"><Timer size={20} /></div>
          <div>
            <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-xs font-semibold text-primary"><Activity size={12} />In progress</span>
            <h2 className="mt-1.5 text-xl font-semibold">{attempt.examTitle}</h2>
            <p className="mt-1 text-sm text-muted-foreground">{attempt.subject} · {attempt.duration} minutes</p>
          </div>
        </div>
        <div className="flex items-center gap-5">
          <div className="text-right">
            <p className="text-sm text-muted-foreground">Time remaining</p>
            <p className={`mt-0.5 text-2xl font-semibold tabular-nums ${low ? 'text-amber-600' : 'text-foreground'}`}>{expired ? 'Expired' : formatCountdown(secondsLeft)}</p>
          </div>
          <button type="button" className="flex items-center gap-1 rounded-xl bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground transition hover:bg-primary/90 active:scale-[.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={onResume}>Resume exam <ChevronRight size={16} /></button>
        </div>
      </div>
    </Card>
  )
}

function DashboardV2() {
  const user = useAuthStore((state) => state.user)
  const token = useAuthStore((state) => state.token)
  const navigate = useNavigate()
  const isStudent = user?.role === 'STUDENT'
  const examsQuery = useQuery({ queryKey: ['dashboard-exams', user?.role], queryFn: async () => (await examApi.list()).data.data, enabled: Boolean(user), retry: 1 })
  const analyticsQuery = useQuery({ queryKey: ['dashboard-analytics', user?.id], queryFn: async () => (await analyticsApi.performance()).data.data, enabled: isStudent, retry: 1 })
  const attemptsQuery = useQuery({ queryKey: ['dashboard-attempts', user?.id], queryFn: async () => (await examApi.activeAttempts()).data.data, enabled: isStudent, retry: 1 })
  const sessionsQuery = useQuery({ queryKey: ['dashboard-sessions', user?.id], queryFn: async () => (await adaptiveApi.sessions()).data.data, enabled: isStudent, retry: 1 })
  const activityQuery = useQuery({ queryKey: ['dashboard-activity', user?.role, user?.id], queryFn: async () => (await activityApi.recent()).data.data, enabled: isStudent || user?.role === 'ADMIN', retry: 1 })
  const aiQuery = useQuery({ queryKey: ['dashboard-ai-analysis', user?.id], queryFn: async () => {
    const response = await fetch('/api/student/ai-analysis', { headers: { Authorization: `Bearer ${token}` }, cache: 'no-store' })
    const body = await response.json().catch(() => null)
    if (!response.ok) {
      const error = new Error((body as { error?: string } | null)?.error ?? 'Unable to load AI recommendations') as Error & { status?: number }
      error.status = response.status
      throw error
    }
    return body as StudentAiAnalysis
  }, enabled: Boolean(token) && isStudent, retry: 1 })
  const activityClient = useQueryClient()
  const addActivity = useCallback((event: ActivityEvent) => activityClient.setQueryData<ActivityEvent[]>(['dashboard-activity', user?.role, user?.id], current => [event, ...(current ?? []).filter(item => item.id !== event.id)].slice(0, 20)), [activityClient, user?.id, user?.role])
  useActivityFeed(user?.role, token, addActivity, () => { void activityQuery.refetch() })
  const exams = examsQuery.data ?? []
  const analytics = analyticsQuery.data
  const activeAttempt = (attemptsQuery.data ?? [])[0]
  const sessions = sessionsQuery.data ?? []
  const latestSession = sessions[0] ?? null
  const available = exams.filter(exam => exam.status === 'UPCOMING')
  const upcoming = (isStudent ? exams.filter(exam => (exam.status === 'UPCOMING' || exam.status === 'ACTIVE') && (!exam.endAt || new Date(exam.endAt).getTime() > Date.now())) : available).slice(0, 3)
  const activity = [...(activityQuery.data ?? [])].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
  const loading = examsQuery.isPending || (isStudent && (analyticsQuery.isPending || attemptsQuery.isPending || sessionsQuery.isPending))
  const aiStatus = (aiQuery.error as { status?: number } | null)?.status
  const practiceAccuracy = latestSession && latestSession.answeredCount > 0 ? Math.round((latestSession.correctCount / latestSession.answeredCount) * 100) : null
  const trend = (analytics?.recentScoreTrend ?? []).map(point => ({ name: point.date || 'Attempt', score: point.score }))
  return <>
    <Header title={isStudent ? `Good morning, ${user.name}` : user?.role === 'TEACHER' ? 'Teacher dashboard' : 'System overview'} description="Live numbers pulled from the backend database." />
    {loading ? <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" aria-busy="true" aria-live="polite">{[1, 2, 3, 4].map(item => <Card key={item}><div className="h-14 animate-pulse rounded-xl bg-muted" /></Card>)}</div> : <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">{isStudent ? <>
      <Card><div className="flex items-center justify-between gap-3"><p className="text-sm text-muted-foreground">Completed exams</p><BookOpen className="size-5 text-primary" /></div><p className="mt-2 text-3xl font-semibold">{analytics?.completedExamCount ?? 0}</p></Card>
      <Card><div className="flex items-center justify-between gap-3"><p className="text-sm text-muted-foreground">Average score</p><TrendingUp className="size-5 text-primary" /></div><p className="mt-2 text-3xl font-semibold">{analytics ? `${Math.round(analytics.averageScore)}%` : '—'}</p></Card>
      <Card><div className="flex items-center justify-between gap-3"><p className="text-sm text-muted-foreground">Accuracy</p><Activity className="size-5 text-primary" /></div><p className="mt-2 text-3xl font-semibold">{analytics ? `${Math.round(analytics.accuracy)}%` : '—'}</p></Card>
      <Card><div className="flex items-center justify-between gap-3"><p className="text-sm text-muted-foreground">Highest score</p><Award className="size-5 text-primary" /></div><p className="mt-2 text-3xl font-semibold">{analytics?.highestScore == null ? '—' : `${Math.round(analytics.highestScore)}%`}</p></Card>
    </> : <>
      <Card><p className="text-sm text-muted-foreground">Total exams</p><p className="mt-2 text-3xl font-semibold">{exams.length}</p></Card>
      <Card><p className="text-sm text-muted-foreground">Published exams</p><p className="mt-2 text-3xl font-semibold">{exams.filter(exam => exam.status !== 'DRAFT').length}</p></Card>
      <Card><p className="text-sm text-muted-foreground">Active exams</p><p className="mt-2 text-3xl font-semibold">{available.length}</p></Card>
      <Card><p className="text-sm text-muted-foreground">Exam subjects</p><p className="mt-2 text-3xl font-semibold">{new Set(exams.map(exam => exam.subject)).size}</p></Card>
    </>}</div>}
    {isStudent && (attemptsQuery.isPending ? <div className="mt-6 h-24 animate-pulse rounded-2xl bg-muted" aria-busy="true" /> : activeAttempt ? <div className="mt-6"><ActiveExamCard attempt={activeAttempt} onResume={() => navigate(`/student/exams/${activeAttempt.examId}`)} onExpired={() => { void attemptsQuery.refetch() }} /></div> : attemptsQuery.isError ? <p className="mt-6 text-sm text-muted-foreground">Couldn't load your active exam. <button type="button" className="underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => { void attemptsQuery.refetch() }}>Retry</button></p> : null)}
    <div className="mt-6 grid gap-6 lg:grid-cols-2">
      <Card><div className="flex items-center justify-between gap-3"><div><h2 className="font-semibold">Practice progress</h2><p className="mt-1 text-sm text-muted-foreground">Your adaptive practice sessions</p></div><Target className="size-5 text-primary" /></div>{sessionsQuery.isPending ? <div className="mt-4 h-32 animate-pulse rounded-xl bg-muted" aria-busy="true" /> : sessionsQuery.isError ? <div role="alert" className="mt-4 flex items-center justify-between gap-3"><p className="text-sm text-destructive">Unable to load practice sessions.</p><button type="button" className="rounded-lg border border-border px-3 py-2 text-sm" onClick={() => { void sessionsQuery.refetch() }}>Retry</button></div> : latestSession ? <div className="mt-4 flex flex-wrap items-center justify-between gap-4"><div><p className="text-sm text-muted-foreground">{latestSession.subject || 'Subject'} · {latestSession.status === 'COMPLETED' ? 'Completed' : latestSession.status === 'EXPIRED' ? 'Expired' : `Level ${latestSession.level}`}</p><b className="mt-1 block">{latestSession.examTitle}</b><div className="mt-3 flex flex-wrap gap-4 text-sm text-muted-foreground"><span>{latestSession.answeredCount} of {latestSession.targetQuestionCount} questions answered</span>{practiceAccuracy != null && <span>{practiceAccuracy}% correct</span>}</div></div><button type="button" className="rounded-xl bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground transition hover:bg-primary/90 active:scale-[.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => navigate(`/student/adaptive/${latestSession.examId}`)}>Continue practice <ChevronRight size={16} /></button></div> : <div className="mt-4 flex flex-col items-center gap-3 py-6 text-center"><p className="text-sm text-muted-foreground">Start adaptive practice to build your skills.</p><button type="button" className="rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition hover:bg-primary/90 active:scale-[.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => navigate('/student/practice')}>Start practice</button></div>}</Card>
      <Card><div className="flex items-center justify-between gap-3"><div><h2 className="font-semibold">AI recommendations</h2><p className="mt-1 text-sm text-muted-foreground">Personalized study guidance</p></div><Sparkles className="size-5 text-primary" /></div>{aiQuery.isPending ? <div className="mt-4 h-32 animate-pulse rounded-xl bg-muted" aria-busy="true" /> : aiQuery.isError && aiStatus === 422 ? <p className="mt-4 text-sm text-muted-foreground">Complete an exam to unlock personalized AI recommendations.</p> : aiQuery.isError ? <div className="mt-4 flex items-center justify-between gap-3"><p className="text-sm text-muted-foreground">AI recommendations are temporarily unavailable.</p><button type="button" className="rounded-lg border border-border px-3 py-2 text-sm" onClick={() => { void aiQuery.refetch() }}>Retry</button></div> : aiQuery.data ? <ul className="mt-4 divide-y divide-border">{aiQuery.data.recommendations.slice(0, 3).map((recommendation, index) => <li key={index} className="flex items-start gap-3 py-3 text-sm"><Sparkles className="mt-0.5 size-4 shrink-0 text-primary" />{recommendation}</li>)}</ul> : null}</Card>
    </div>
    <div className="mt-6 grid gap-6 lg:grid-cols-[1.4fr_1fr]"><Card><div className="flex items-center justify-between gap-3"><div><h2 className="font-semibold">Performance trend</h2><p className="mt-1 text-sm text-muted-foreground">Score across your completed exams</p></div><button type="button" className="rounded-lg border border-border px-3 py-2 text-sm transition hover:bg-muted active:scale-[.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => navigate('/student/history')}>View history</button></div>{trend.length ? <div className="mt-5 h-56" aria-label="Performance trend chart"><ResponsiveContainer width="100%" height="100%"><LineChart data={trend}><CartesianGrid strokeDasharray="3 3" className="stroke-border" /><XAxis dataKey="name" tick={{ fontSize: 11 }} /><YAxis domain={[0, 100]} tick={{ fontSize: 11 }} /><Tooltip /><Line type="monotone" dataKey="score" stroke="hsl(var(--primary))" strokeWidth={2} dot={{ r: 3 }} /></LineChart></ResponsiveContainer></div> : analyticsQuery.isError ? <p className="py-12 text-center text-sm text-destructive">Unable to load your performance.</p> : <p className="py-12 text-center text-sm text-muted-foreground">Complete an exam to see your performance trend.</p>}</Card><Card><div className="flex items-center justify-between gap-3"><h2 className="font-semibold">Upcoming exams</h2><button type="button" className="text-sm text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => navigate(isStudent ? '/student/exams' : '/teacher/exams')}>View all</button></div>{examsQuery.isError ? <div role="alert" className="mt-4 flex items-center justify-between gap-3"><p className="text-sm text-destructive">Unable to load exams.</p><button type="button" className="rounded-lg border border-border px-3 py-2 text-sm" onClick={() => { void examsQuery.refetch() }}>Retry</button></div> : upcoming.length ? <div className="mt-4 divide-y divide-border">{upcoming.map(exam => <button type="button" key={exam.id} className="flex w-full items-center justify-between gap-3 py-3 text-left transition hover:bg-muted/60 active:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => navigate(isStudent ? `/student/exams/${exam.id}/instructions` : '/teacher/exams')}><span><b className="block">{exam.title}</b><span className="mt-1 block text-sm text-muted-foreground">{exam.startAt ? formatStartsIn(new Date(exam.startAt).getTime()) : `${exam.subject} · ${exam.duration} min`}</span></span><ChevronRight className="shrink-0 text-muted-foreground" /></button>)}</div> : <p className="py-8 text-center text-sm text-muted-foreground">No upcoming exams are available.</p>}</Card></div>
    <div className="mt-6 grid gap-6 lg:grid-cols-2"><Card><h2 className="font-semibold">Recent activity</h2>{activityQuery.isPending ? <div className="mt-4 h-32 animate-pulse rounded-xl bg-muted" aria-busy="true" /> : activity.length ? <div className="mt-4 divide-y divide-border">{activity.slice(0, 5).map(event => <div key={event.id} className="flex items-center justify-between gap-4 py-3 text-sm"><p>{event.message}</p><time className="shrink-0 text-xs text-muted-foreground">{new Date(event.createdAt).toLocaleDateString()}</time></div>)}</div> : <p className="mt-4 text-sm text-muted-foreground">No recent activity.</p>}</Card><Card><h2 className="font-semibold">Quick actions</h2><div className="mt-4 grid gap-3"><button type="button" className="rounded-xl border border-border p-3 text-left text-sm transition hover:bg-muted active:scale-[.99] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => navigate(isStudent ? '/student/exams' : '/teacher/exams')}><b>Browse exams</b><span className="mt-1 block text-muted-foreground">Find your next assessment</span></button><button type="button" className="rounded-xl border border-border p-3 text-left text-sm transition hover:bg-muted active:scale-[.99] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => navigate(isStudent ? '/student/practice' : '/teacher/exams')}><b>Practice</b><span className="mt-1 block text-muted-foreground">Adaptive questions that match your level</span></button><button type="button" className="rounded-xl border border-border p-3 text-left text-sm transition hover:bg-muted active:scale-[.99] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => navigate(isStudent ? '/student/analysis' : '/admin/analytics')}><b>View performance</b><span className="mt-1 block text-muted-foreground">Review your exam results and analytics</span></button><button type="button" className="rounded-xl border border-border p-3 text-left text-sm transition hover:bg-muted active:scale-[.99] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => navigate(isStudent ? '/student/ai-analysis' : '/admin/analytics')}><b>AI coach</b><span className="mt-1 block text-muted-foreground">Personalized learning insights</span></button></div></Card></div>
  </>
}
function RealAuth({ mode }: { mode: 'login' | 'register' }) {
  const { setAuth } = useAuthStore(); const navigate = useNavigate(); const [error, setError] = useState(''); const [loading, setLoading] = useState(false); const [loginRole, setLoginRole] = useState<LoginRole>('STUDENT'); const [challengeToken, setChallengeToken] = useState(''); const [code, setCode] = useState(''); const [recoveryMode, setRecoveryMode] = useState(false); const [providers, setProviders] = useState<OAuthProviderInfo[]>([])
  useEffect(() => { if (mode !== 'login') return; let active = true; authApi.oauthProviders().then(response => { if (active) setProviders(response.data.data) }).catch(() => { if (active) setProviders([]) }); return () => { active = false } }, [mode])
  const schema = mode === 'login' ? z.object({ email: z.string().email(), password: z.string().min(1) }) : z.object({ name: z.string().min(2), email: z.string().email(), password: z.string().min(8) })
  const { register, handleSubmit, formState: { errors } } = useForm<any>({ resolver: zodResolver(schema) })
  const submit = async (values: any) => { setLoading(true); setError(''); try { let user: User; let token: string; if (mode === 'login') { const response = await authApi.login({ email: values.email, password: values.password }); const auth = response.data.data; if (auth.requiresTwoFactor || auth.challengeToken) { setChallengeToken(auth.challengeToken || ''); setCode(''); return } if (!auth.token || !auth.user) throw new Error('incomplete login'); token = auth.token; user = auth.user; setAuth({ token, user }) } else { const auth = (await authApi.register(values)).data.data; token = auth.token; user = auth.user; setAuth({ token, user }) } navigate(`/${user.role.toLowerCase()}/dashboard`) } catch (cause: unknown) { const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message; setError(message || 'Unable to reach the examination service. Confirm that the backend is running and try again.') } finally { setLoading(false) } }
  const verifyTwoFactor = async (event: React.FormEvent) => { event.preventDefault(); setLoading(true); setError(''); try { const response = recoveryMode ? await authApi.recoverTwoFactor({ challengeToken, recoveryCode: code }) : await authApi.verifyTwoFactor({ challengeToken, code }); const auth = response.data.data; setAuth(auth); navigate(`/${auth.user.role.toLowerCase()}/dashboard`) } catch (cause: unknown) { const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message; setError(message || 'That code could not be verified.') } finally { setLoading(false) } }
  if (challengeToken) return <div className="grid min-h-screen place-items-center p-6"><Card className="w-full max-w-md"><div className="mb-8 flex items-center gap-3"><div className="grid size-9 place-items-center rounded-xl bg-primary text-primary-foreground"><ShieldCheck size={20}/></div><b>Examwise</b></div><h1 className="text-2xl font-semibold">Two-factor authentication</h1><p className="mt-2 text-sm text-muted-foreground">{recoveryMode ? 'Enter one of the recovery codes you saved when enabling two-factor authentication.' : 'Enter the 6-digit code from your authenticator app.'}</p><form className="mt-6 space-y-4" onSubmit={verifyTwoFactor}><label className="block text-sm font-medium">{recoveryMode ? 'Recovery code' : 'Authenticator code'}<input className="field mt-2" inputMode={recoveryMode ? 'text' : 'numeric'} autoComplete="one-time-code" value={code} onChange={e => setCode(recoveryMode ? e.target.value.trim().toUpperCase() : e.target.value.replace(/\D/g, '').slice(0, 6))} placeholder={recoveryMode ? 'XXXX-XXXX' : '6-digit code'}/></label><button type="submit" disabled={loading} className="w-full rounded-xl bg-primary px-4 py-3 text-sm font-medium text-primary-foreground transition hover:bg-primary/90 active:scale-[.98] disabled:opacity-60">{loading ? 'Verifying...' : 'Verify'}</button></form><button type="button" className="mt-5 w-full text-center text-sm text-primary hover:underline" onClick={() => { setRecoveryMode(value => !value); setCode(''); setError('') }}>{recoveryMode ? 'Use my authenticator app instead' : 'Use a recovery code instead'}</button>{error && <p className="mt-4 text-sm text-destructive">{error}</p>}</Card></div>
  return <div className="grid min-h-screen place-items-center p-6"><Card className="w-full max-w-md"><div className="mb-8 flex items-center gap-3"><div className="grid size-9 place-items-center rounded-xl bg-primary text-primary-foreground"><ShieldCheck size={20}/></div><b>Examwise</b></div><h1 className="text-2xl font-semibold">{mode === 'login' ? 'Welcome back' : 'Create your student account'}</h1><p className="mt-2 text-sm text-muted-foreground">{mode === 'login' ? 'Choose your sign-in area, then use your examination account.' : 'Registration creates a student account. Administrator accounts are provisioned separately.'}</p><p className="mt-2 text-sm text-muted-foreground">{mode === 'login' ? <>New here? <NavLink className="font-medium text-primary hover:underline" to="/register">Create a student account</NavLink></> : <>Already have an account? <NavLink className="font-medium text-primary hover:underline" to="/login">Sign in</NavLink></>}</p>{error && <p role="alert" className="mt-4 rounded-xl bg-destructive/10 p-3 text-sm text-destructive">{error}</p>}<form className="mt-6 space-y-4" onSubmit={handleSubmit(submit)}>{mode === 'login' && <fieldset><legend className="text-sm font-medium">Sign in as</legend><div className="mt-2 grid grid-cols-2 gap-3"><button type="button" onClick={() => setLoginRole('STUDENT')} aria-pressed={loginRole === 'STUDENT'} className={`rounded-xl border p-3 text-left text-sm ${loginRole === 'STUDENT' ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-muted'}`}><span className="block font-medium">Student</span><span className="mt-1 block text-xs">Take exams and view results</span></button><button type="button" onClick={() => setLoginRole('ADMIN')} aria-pressed={loginRole === 'ADMIN'} className={`rounded-xl border p-3 text-left text-sm ${loginRole === 'ADMIN' ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-muted'}`}><span className="block font-medium">Administrator</span><span className="mt-1 block text-xs">Open the admin panel</span></button></div></fieldset>}{mode === 'register' && <label className="block text-sm font-medium">Full name<input className="field mt-2" {...register('name')} placeholder="Your name"/>{errors.name && <span className="text-xs text-destructive">Enter your name</span>}</label>}<label className="block text-sm font-medium">Email<input className="field mt-2" type="email" autoComplete="email" {...register('email')} placeholder="you@school.edu"/>{errors.email && <span className="text-xs text-destructive">Enter a valid email</span>}</label><label className="block text-sm font-medium">Password<input className="field mt-2" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} {...register('password')} placeholder={mode === 'login' ? 'Your password' : 'At least 8 characters'}/>{errors.password && <span className="text-xs text-destructive">{mode === 'login' ? 'Enter your password' : 'Use at least 8 characters'}</span>}</label><button type="submit" disabled={loading} className="w-full rounded-xl bg-primary px-4 py-3 text-sm font-medium text-primary-foreground transition hover:bg-primary/90 active:scale-[.98] disabled:opacity-60">{loading ? 'Signing in...' : mode === 'login' ? 'Sign in' : 'Create account'}</button></form>{mode === 'login' && providers.length > 0 && <div className="mt-6 border-t border-border pt-5"><p className="text-center text-xs uppercase tracking-widest text-muted-foreground">Or continue with</p><div className="mt-3 grid gap-2">{providers.map(provider => <a key={provider.provider} className="block" href={`${resolveApiBaseUrl()}/auth/oauth/${provider.provider}/start`}><button type="button" className="w-full rounded-xl border border-border p-3 text-sm font-medium text-muted-foreground transition hover:bg-muted active:scale-[.98]">{provider.label}</button></a>)}</div></div>}</Card></div>
}

function RetestRequests({ admin = false }: { admin?: boolean }) {
  const query = useQuery({ queryKey: [admin ? 'admin-retests' : 'my-retests'], queryFn: async () => (await (admin ? retestApi.admin() : retestApi.mine())).data.data, retry: 1 })
  const review = useMutation({ mutationFn: ({ id, status }: { id: string; status: 'APPROVED' | 'REJECTED' }) => retestApi.review(id, status), onSuccess: () => query.refetch() })
  return <><Header title={admin ? 'Retest requests' : 'Retest requests'} description={admin ? 'Review student requests securely.' : 'Track your requests for another attempt.'} /><Card><div className="flex flex-col gap-3" aria-live="polite">{query.isPending ? <p role="status">Loading requests...</p> : query.isError ? <p role="alert">Unable to load requests.</p> : query.data?.length ? query.data.map((item) => <div key={item.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border p-4"><div><p className="font-medium">{item.examTitle}</p><p className="text-sm text-muted-foreground">{item.status} · {new Date(item.requestedAt).toLocaleDateString()}</p></div>{admin && item.status === 'PENDING' && <div className="flex gap-2"><button type="button" disabled={review.isPending} onClick={() => review.mutate({ id: item.id, status: 'APPROVED' })} className="rounded-lg border border-border px-3 py-2 text-sm hover:bg-muted disabled:opacity-50">Approve</button><button type="button" disabled={review.isPending} onClick={() => review.mutate({ id: item.id, status: 'REJECTED' })} className="rounded-lg border border-border px-3 py-2 text-sm hover:bg-muted disabled:opacity-50">Reject</button></div>}</div>) : <p className="text-sm text-muted-foreground">No retest requests yet.</p>}</div></Card></>
}

function AuthGateway() {
  const [searchParams] = useSearchParams()
  const user = useAuthStore(state => state.user)
  if (searchParams.get('token')) return <OAuthCallback />
  if (user) return <Navigate to={`/${user.role.toLowerCase()}/dashboard`} replace />
  return <Navigate to="/login" replace />
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<AuthGateway />} />
      <Route path="/login" element={<RealAuth mode="login" />} />
      <Route path="/register" element={<RealAuth mode="register" />} />
      <Route path="/oauth/callback" element={<OAuthCallback />} />
      <Route path="/settings/security" element={<Protected roles={['STUDENT', 'TEACHER', 'ADMIN']}><SecuritySettings /></Protected>} />
      <Route path="/student/ai-analysis" element={<Protected roles={['STUDENT']}><StudentAICoach /></Protected>} />
      <Route path="/student/ai-practice/:id" element={<Protected roles={['STUDENT']}><StudentAIPractice /></Protected>} />
      <Route path="/student/analysis" element={<Protected roles={['STUDENT']}><StudentPerformance /></Protected>} />
      <Route path="/student/adaptive/:id" element={<Protected roles={['STUDENT']}><PracticeSession /></Protected>} />
      <Route path="/student/practice" element={<Protected roles={['STUDENT']}><StudentPractice /></Protected>} />
      <Route path="/teacher/monitor/:id" element={<Protected roles={['TEACHER', 'ADMIN']}><ProctorMonitor /></Protected>} />
      <Route path="/teacher/monitor" element={<Protected roles={['TEACHER', 'ADMIN']}><MonitorPicker /></Protected>} />
      <Route path="/teacher/analytics" element={<Protected roles={['TEACHER', 'ADMIN']}><ExamIntelligenceList /></Protected>} />
      <Route path="/teacher/analytics/:examId" element={<Protected roles={['TEACHER', 'ADMIN']}><ExamIntelligence /></Protected>} />
      <Route path="/admin/analytics" element={<Protected roles={['ADMIN']}><AdminAnalytics /></Protected>} />
      <Route path="/admin/analytics/:examId" element={<Protected roles={['ADMIN']}><ExamIntelligence /></Protected>} />
      <Route path="/student/dashboard" element={<Protected roles={['STUDENT']}><DashboardV2 /></Protected>} />
      <Route path="/student/exams" element={<Protected roles={['STUDENT']}><StudentExams /></Protected>} />
      <Route path="/student/exams/:id/instructions" element={<Protected roles={['STUDENT']}><Instructions /></Protected>} />
      <Route path="/student/exams/:id" element={<Protected roles={['STUDENT']}><Attempt /></Protected>} />
      <Route path="/student/exams/:id/result" element={<Protected roles={['STUDENT']}><ResultPage /></Protected>} />
      <Route path="/student/history" element={<Protected roles={['STUDENT']}><HistoryEnhanced /></Protected>} />
      <Route path="/student/retest-requests" element={<Protected roles={['STUDENT']}><RetestRequests /></Protected>} />
      <Route path="/admin/retest-requests" element={<Protected roles={['ADMIN']}><RetestRequests admin /></Protected>} />
      <Route path="/teacher/dashboard" element={<Protected roles={['TEACHER']}><TeacherCommandCenter /></Protected>} />
      <Route path="/teacher/exams" element={<Protected roles={['TEACHER', 'ADMIN']}><TeacherExams /></Protected>} />
      <Route path="/teacher/exams/create" element={<Protected roles={['TEACHER', 'ADMIN']}><CreateExam /></Protected>} />
      <Route path="/teacher/exams/:id/questions" element={<Protected roles={['TEACHER', 'ADMIN']}><QuestionsPage /></Protected>} />
      <Route path="/admin/dashboard" element={<Protected roles={['ADMIN']}><DashboardV2 /></Protected>} />
      <Route path="/admin/users" element={<Protected roles={['ADMIN']}><AdminUsers /></Protected>} />
      <Route path="/admin/exams" element={<Protected roles={['ADMIN']}><TeacherExams /></Protected>} />
      <Route path="/admin/question-bank" element={<Protected roles={['ADMIN']}><QuestionBank /></Protected>} />
      <Route path="/admin/results" element={<Protected roles={['ADMIN']}><AdminResults /></Protected>} />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
const queryClient = new QueryClient()
function ClientPage() { return <QueryClientProvider client={queryClient}><ToastProvider><BrowserRouter><App/></BrowserRouter></ToastProvider></QueryClientProvider> }
export default dynamic(() => Promise.resolve(ClientPage), { ssr: false })
