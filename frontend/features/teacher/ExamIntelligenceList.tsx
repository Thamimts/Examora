'use client'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQueries, useQuery } from '@tanstack/react-query'
import { Activity, ArrowUpDown, Monitor, Search, TrendingUp } from 'lucide-react'
import { analyticsApi } from '@/services/analyticsApi'
import { examApi } from '@/services/examApi'
import { Card, Header } from '@/components/shared'
import type { ExamAnalyticsDetail, ExamAnalyticsSummary } from '@/types/analytics'
import { AttentionChips, EmptyState, MetricTile, StatusPill, formatPercent } from './intelligence-common'

type SortKey = 'submissions' | 'average' | 'completion' | 'title' | 'attention'
type StatusFilter = 'ALL' | 'DRAFT' | 'PUBLISHED' | 'UPCOMING' | 'ACTIVE' | 'COMPLETED' | 'ENDED'

function summarizeAttention(detail: ExamAnalyticsDetail | undefined): number {
  if (!detail) return 0
  let count = 0
  for (const question of detail.questionAnalytics) {
    if (question.flags.some(flag => /LOW|UNANSWERED/.test(flag))) count += 1
  }
  return count
}

export function ExamIntelligenceList({ compact = false, basePath = '/teacher/analytics' }: { compact?: boolean; basePath?: string }) {
  const navigate = useNavigate()
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<StatusFilter>('ALL')
  const [sort, setSort] = useState<SortKey>('submissions')

  const summariesQuery = useQuery({
    queryKey: ['analytics-summaries'],
    queryFn: async () => (await analyticsApi.examSummaries()).data.data,
    retry: 1,
  })
  const examsQuery = useQuery({
    queryKey: ['teacher-analytics-exams'],
    queryFn: async () => (await examApi.list()).data.data,
    retry: 1,
  })

  const summaries = summariesQuery.data ?? []
  const summaryById = useMemo(
    () => new Map<string, ExamAnalyticsSummary>(summaries.map(summary => [summary.examId, summary])),
    [summaries],
  )

  const detailQueries = useQueries({
    queries: summaries.map(summary => ({
      queryKey: ['analytics-detail', summary.examId],
      queryFn: async () => (await analyticsApi.examDetail(summary.examId)).data.data,
      staleTime: 60_000,
      retry: 1,
    })),
  })

  const rows = useMemo(() => {
    const exams = examsQuery.data ?? []
    const base = exams.map(exam => {
      const summary = summaryById.get(exam.id)
      const detail = detailQueries.find(query => query.data?.examId === exam.id)?.data
      const attention = summarizeAttention(detail)
      return {
        exam,
        summary,
        detail,
        attention,
      }
    })
    const filtered = base.filter(row => {
      const term = search.trim().toLowerCase()
      const matchesSearch =
        !term ||
        row.exam.title.toLowerCase().includes(term) ||
        row.exam.subject.toLowerCase().includes(term)
      const matchesStatus = status === 'ALL' || row.exam.status === status
      return matchesSearch && matchesStatus
    })
    return filtered.sort((a, b) => {
      const left = a.summary
      const right = b.summary
      switch (sort) {
        case 'title':
          return a.exam.title.localeCompare(b.exam.title)
        case 'average':
          return (right?.averageScore ?? -1) - (left?.averageScore ?? -1)
        case 'completion':
          return (right?.completionRate ?? -1) - (left?.completionRate ?? -1)
        case 'attention':
          return b.attention - a.attention
        default:
          return (right?.submissions ?? 0) - (left?.submissions ?? 0)
      }
    })
  }, [examsQuery.data, summaryById, detailQueries, search, status, sort])

  const publishedCount = (examsQuery.data ?? []).filter(exam => exam.status !== 'DRAFT').length
  const totalSubmissions = summaries.reduce((total, summary) => total + summary.submissions, 0)
  const participants = summaries.reduce((total, summary) => total + summary.participants, 0)
  const attentionTotal = rows.reduce((total, row) => total + row.attention, 0)
  const attentionRows = [...rows].sort((a, b) => b.attention - a.attention)

  const content = (
    <>
      {!compact ? (
        <div className="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <MetricTile label="Published exams" value={publishedCount} sub="Across this workspace" />
          <MetricTile label="Students touched" value={participants} sub="Across all assessments" />
          <MetricTile label="Submissions" value={totalSubmissions} sub="Submitted attempts" />
          <MetricTile
            label="Attention flags"
            value={attentionTotal}
            sub="Questions flagged across exams"
          />
        </div>
      ) : null}

      {!compact ? (
        <div className="mb-6 grid gap-4 lg:grid-cols-2">
          <Card>
            <div className="flex items-center gap-2">
              <Activity className="text-primary" size={18} />
              <h2 className="font-semibold">Needs your attention</h2>
            </div>
            {attentionRows.length ? (
              <div className="mt-4 divide-y divide-border">
                {attentionRows.slice(0, 5).map(row => (
                  <button
                    key={row.exam.id}
                    type="button"
                    onClick={() => navigate(`${basePath}/${row.exam.id}`)}
                    className="flex w-full items-center justify-between gap-3 py-3 text-left transition hover:bg-muted/60 focus-visible:outline-none"
                  >
                    <span>
                      <b className="block text-sm">{row.exam.title}</b>
                      <span className="mt-0.5 block text-xs text-muted-foreground">
                        {row.attention} flagged {row.attention === 1 ? 'question' : 'questions'}
                      </span>
                    </span>
                    <span className="rounded-full bg-red-500/10 px-2.5 py-0.5 text-xs font-medium text-red-600">
                      {row.attention}
                    </span>
                  </button>
                ))}
              </div>
            ) : (
              <EmptyState title="No flagged questions" hint="All questions are performing normally." />
            )}
          </Card>
          <Card>
            <div className="flex items-center gap-2">
              <TrendingUp className="text-primary" size={18} />
              <h2 className="font-semibold">Subject breakdown</h2>
            </div>
            {summaries.length ? (
              <div className="mt-4 divide-y divide-border">
                {Array.from(
                  summaries.reduce((map, summary) => {
                    const current = map.get(summary.subject) ?? { exams: 0, scores: [] as number[] }
                    current.exams += 1
                    if (summary.averageScore != null) current.scores.push(summary.averageScore)
                    map.set(summary.subject, current)
                    return map
                  }, new Map<string, { exams: number; scores: number[] }>()),
                ).map(([subject, group]) => {
                  const average = group.scores.length
                    ? Math.round((group.scores.reduce((a, b) => a + b, 0) / group.scores.length) * 100) / 100
                    : null
                  return (
                    <div key={subject} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm first:pt-0">
                      <div>
                        <p className="font-medium">{subject}</p>
                        <p className="mt-0.5 text-xs text-muted-foreground">
                          {group.exams} {group.exams === 1 ? 'exam' : 'exams'}
                        </p>
                      </div>
                      <p className="font-semibold">{average == null ? '—' : `${average}%`}</p>
                    </div>
                  )
                })}
              </div>
            ) : (
              <EmptyState title="No exam data yet" />
            )}
          </Card>
        </div>
      ) : null}

      <Card>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-semibold">Exam intelligence</h2>
          <div className="flex flex-wrap items-center gap-2">
            <label className="relative">
              <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
              <input
                value={search}
                onChange={event => setSearch(event.target.value)}
                placeholder="Search exams…"
                className="field h-9 w-48 pl-9"
                aria-label="Search exams"
              />
            </label>
            <select
              value={status}
              onChange={event => setStatus(event.target.value as StatusFilter)}
              className="field h-9"
              aria-label="Filter by status"
            >
              <option value="ALL">All statuses</option>
              <option value="DRAFT">Draft</option>
              <option value="PUBLISHED">Published</option>
              <option value="ACTIVE">Active</option>
              <option value="UPCOMING">Upcoming</option>
              <option value="COMPLETED">Completed</option>
              <option value="ENDED">Ended</option>
            </select>
            <button
              type="button"
              onClick={() => {
                const order: SortKey[] = ['submissions', 'average', 'completion', 'attention', 'title']
                setSort(order[(order.indexOf(sort) + 1) % order.length])
              }}
              className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-border px-3 text-sm hover:bg-muted"
            >
              <ArrowUpDown size={14} />
              {sort === 'submissions'
                ? 'Submissions'
                : sort === 'average'
                  ? 'Average'
                  : sort === 'completion'
                    ? 'Completion'
                    : sort === 'attention'
                      ? 'Attention'
                      : 'Title'}
            </button>
          </div>
        </div>

        {examsQuery.isPending ? (
          <div className="mt-4 space-y-3" aria-busy="true">
            {[1, 2, 3].map(item => <div key={item} className="h-16 animate-pulse rounded-xl bg-muted" />)}
          </div>
        ) : rows.length ? (
          <div className="mt-4 divide-y divide-border">
            {rows.map(row => {
              const summary = row.summary
              const hasActivity = summary ? summary.submissions + summary.participants > 0 : row.exam.participants > 0
              return (
                <div key={row.exam.id} className="grid gap-3 py-4 first:pt-0 md:grid-cols-[1.5fr_1fr_1fr_1fr] md:items-center">
                  <button
                    type="button"
                    onClick={() => navigate(`${basePath}/${row.exam.id}`)}
                    className="min-w-0 text-left focus-visible:outline-none"
                  >
                    <p className="flex flex-wrap items-center gap-2 font-medium hover:text-primary">
                      {row.exam.title}
                      <StatusPill status={row.exam.status} />
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {row.exam.subject} · {row.exam.duration} min · {row.exam.participants} participants
                    </p>
                    <div className="mt-2">
                      <AttentionChips flags={Array.from(new Set(row.detail?.questionAnalytics.flatMap(question => question.flags) ?? []))} />
                    </div>
                  </button>
                  <div>
                    <p className="text-xs text-muted-foreground">Average</p>
                    <p className="mt-0.5 font-semibold">{formatPercent(summary?.averageScore ?? null)}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">median {formatPercent(summary?.medianScore ?? null)}</p>
                  </div>
                  <div>
                    <p className="text-xs text-muted-foreground">Completion</p>
                    <p className="mt-0.5 font-semibold">{formatPercent(summary?.completionRate ?? null)}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {summary?.submissions ?? 0} of {summary?.participants ?? 0} submitted
                    </p>
                  </div>
                  <div className="flex flex-wrap items-center gap-2 md:justify-end">
                    {hasActivity && row.attention > 0 ? (
                      <span className="rounded-full bg-red-500/10 px-2.5 py-0.5 text-xs font-medium text-red-600">
                        {row.attention} flag{row.attention === 1 ? '' : 's'}
                      </span>
                    ) : summary && summary.participants === 0 && row.exam.status !== 'DRAFT' ? (
                      <span className="rounded-full bg-muted px-2.5 py-0.5 text-xs font-medium text-muted-foreground">
                        Awaiting students
                      </span>
                    ) : null}
                    <button
                      type="button"
                      onClick={() => navigate(`${basePath}/${row.exam.id}`)}
                      className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-2 text-xs hover:bg-muted"
                    >
                      <TrendingUp size={13} />
                      Intelligence
                    </button>
                    <button
                      type="button"
                      disabled={row.exam.status === 'DRAFT'}
                      onClick={() => navigate(`/teacher/monitor/${row.exam.id}`)}
                      className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-2 text-xs disabled:opacity-40"
                    >
                      <Monitor size={13} />
                      Monitor
                    </button>
                  </div>
                </div>
              )
            })}
          </div>
        ) : examsQuery.isError ? (
          <div role="alert" className="mt-4 flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-destructive">Unable to load exam intelligence.</p>
            <button type="button" className="rounded-lg border border-border px-3 py-2 text-sm" onClick={() => { void examsQuery.refetch() }}>
              Retry
            </button>
          </div>
        ) : (
          <EmptyState title={search || status !== 'ALL' ? 'No exams match your filters.' : 'No exams yet.'} hint="Publish an exam to start collecting analytics." />
        )}
      </Card>
    </>
  )

  if (compact) return content
  return (
    <>
      <Header
        title="Exam intelligence"
        description="Live performance breakdowns computed from your published assessments and submitted attempts."
      />
      {content}
    </>
  )
}