'use client'
import { useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { FilePlus2, Monitor, TrendingUp } from 'lucide-react'
import { analyticsApi } from '@/services/analyticsApi'
import { examApi } from '@/services/examApi'
import { Card, Header } from '@/components/shared'
import { MetricTile, formatPercent } from './intelligence-common'
import { ExamIntelligenceList } from './ExamIntelligenceList'

export function TeacherCommandCenter() {
  const navigate = useNavigate()
  const examsQuery = useQuery({
    queryKey: ['teacher-analytics-exams'],
    queryFn: async () => (await examApi.list()).data.data,
    retry: 1,
  })
  const summariesQuery = useQuery({
    queryKey: ['analytics-summaries'],
    queryFn: async () => (await analyticsApi.examSummaries()).data.data,
    retry: 1,
  })

  const summaries = summariesQuery.data ?? []
  const publishedCount = (examsQuery.data ?? []).filter(exam => exam.status !== 'DRAFT').length
  const studentsTouched = summaries.reduce((total, summary) => total + summary.participants, 0)
  const submissions = summaries.reduce((total, summary) => total + summary.submissions, 0)
  const withCompletion = summaries.filter(summary => summary.completionRate != null)
  const averageCompletion = withCompletion.length
    ? Math.round((withCompletion.reduce((total, summary) => total + (summary.completionRate ?? 0), 0) / withCompletion.length) * 100) / 100
    : null

  const subjectBreakdown = useMemo(() => {
    const map = new Map<string, { exams: number; scores: number[]; submissions: number }>()
    for (const summary of summaries) {
      const group = map.get(summary.subject) ?? { exams: 0, scores: [] as number[], submissions: 0 }
      group.exams += 1
      group.submissions += summary.submissions
      if (summary.averageScore != null) group.scores.push(summary.averageScore)
      map.set(summary.subject, group)
    }
    return Array.from(map.entries()).map(([subject, group]) => ({
      subject,
      exams: group.exams,
      submissions: group.submissions,
      average: group.scores.length
        ? Math.round((group.scores.reduce((a, b) => a + b, 0) / group.scores.length) * 100) / 100
        : null,
    }))
  }, [summaries])

  return (
    <>
      <Header
        title="Command center"
        description="A live view of how your assessments are performing, with every metric computed from real results."
      />

      <div className="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <MetricTile label="Published exams" value={publishedCount} sub="Across this workspace" />
        <MetricTile label="Students touched" value={studentsTouched} sub="Across all assessments" />
        <MetricTile label="Submissions" value={submissions} sub="Submitted attempts" />
        <MetricTile label="Average completion" value={averageCompletion == null ? '—' : `${averageCompletion}%`} sub="Across exams with activity" />
      </div>

      <div className="mb-6 grid gap-6 lg:grid-cols-[1.4fr_1fr]">
        <Card>
          <div className="flex items-center gap-2">
            <TrendingUp className="text-primary" size={18} />
            <h2 className="font-semibold">Assessment performance</h2>
          </div>
          {subjectBreakdown.length ? (
            <div className="mt-4 divide-y divide-border">
              {subjectBreakdown.map(item => (
                <div key={item.subject} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm first:pt-0">
                  <div>
                    <p className="font-medium">{item.subject}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {item.exams} {item.exams === 1 ? 'exam' : 'exams'} · {item.submissions} submissions
                    </p>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="font-semibold">{item.average == null ? '—' : `${item.average}%`}</span>
                    <button
                      type="button"
                      onClick={() => navigate('/teacher/analytics')}
                      className="rounded-lg border border-border px-3 py-1.5 text-xs hover:bg-muted"
                    >
                      Open intelligence
                    </button>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <p className="py-8 text-center text-sm text-muted-foreground">
              Publish an exam to start seeing performance here.
            </p>
          )}
        </Card>
        <Card>
          <h2 className="font-semibold">Quick actions</h2>
          <div className="mt-4 grid gap-3">
            <button
              type="button"
              onClick={() => navigate('/teacher/exams/create')}
              className="rounded-xl border border-border p-3 text-left text-sm transition hover:bg-muted active:scale-[.99]"
            >
              <b className="flex items-center gap-2"><FilePlus2 size={16} /> Create exam</b>
              <span className="mt-1 block text-muted-foreground">Draft and publish a new assessment</span>
            </button>
            <button
              type="button"
              onClick={() => navigate('/teacher/analytics')}
              className="rounded-xl border border-border p-3 text-left text-sm transition hover:bg-muted active:scale-[.99]"
            >
              <b className="flex items-center gap-2"><TrendingUp size={16} /> Exam intelligence</b>
              <span className="mt-1 block text-muted-foreground">Per-question and per-student breakdowns</span>
            </button>
            <button
              type="button"
              onClick={() => navigate('/teacher/monitor')}
              className="rounded-xl border border-border p-3 text-left text-sm transition hover:bg-muted active:scale-[.99]"
            >
              <b className="flex items-center gap-2"><Monitor size={16} /> Live monitor</b>
              <span className="mt-1 block text-muted-foreground">Watch active sessions in real time</span>
            </button>
          </div>
        </Card>
      </div>

      <ExamIntelligenceList compact />
    </>
  )
}