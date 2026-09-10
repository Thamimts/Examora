'use client'
import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AlertCircle, BarChart3, CheckCircle2, TrendingUp } from 'lucide-react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { analyticsApi } from '@/services/analyticsApi'
import { Card, Header } from '@/components/shared'

function formatScore(value: number | null | undefined): string {
  return typeof value === 'number' ? `${Math.round(value * 100) / 100}%` : '—'
}

export function StudentPerformance() {
  const query = useQuery({
    queryKey: ['student-performance'],
    queryFn: async () => (await analyticsApi.performance()).data.data,
    retry: 1,
  })

  const trend = useMemo(
    () => (query.data?.recentScoreTrend ?? []).map(point => ({ name: point.examTitle, score: point.score, date: point.date })),
    [query.data],
  )

  return (
    <>
      <Header
        title="Performance analysis"
        description="A summary of your exam results, computed from your submitted attempts."
      />
      {query.isPending && (
        <div className="grid gap-4 sm:grid-cols-3" aria-busy="true" aria-live="polite">
          {[1, 2, 3].map(item => (
            <Card key={item}>
              <div className="h-14 animate-pulse rounded-xl bg-muted" />
            </Card>
          ))}
        </div>
      )}
      {query.isError && (
        <Card className="border-destructive/30">
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <AlertCircle size={20} className="text-destructive" />
              <p className="text-sm text-destructive">Unable to load your performance analytics.</p>
            </div>
            <button
              type="button"
              className="rounded-lg border border-border px-3 py-2 text-sm hover:bg-muted"
              onClick={() => query.refetch()}
            >
              Retry
            </button>
          </div>
        </Card>
      )}
      {query.data && query.data.completedExamCount === 0 && (
        <Card>
          <p className="py-8 text-center text-sm text-muted-foreground">
            You have not completed any exams yet. Submit an exam to see your performance analysis.
          </p>
        </Card>
      )}
      {query.data && query.data.completedExamCount > 0 && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            <Card>
              <p className="text-sm text-muted-foreground">Completed exams</p>
              <p className="mt-2 text-3xl font-semibold">{query.data.completedExamCount}</p>
            </Card>
            <Card>
              <p className="text-sm text-muted-foreground">Average score</p>
              <p className="mt-2 text-3xl font-semibold">{formatScore(query.data.averageScore)}</p>
            </Card>
            <Card>
              <p className="text-sm text-muted-foreground">Highest score</p>
              <p className="mt-2 text-3xl font-semibold">{formatScore(query.data.highestScore)}</p>
            </Card>
            <Card>
              <p className="text-sm text-muted-foreground">Lowest score</p>
              <p className="mt-2 text-3xl font-semibold">{formatScore(query.data.lowestScore)}</p>
            </Card>
            <Card>
              <p className="text-sm text-muted-foreground">Accuracy</p>
              <p className="mt-2 text-3xl font-semibold">{formatScore(query.data.accuracy)}</p>
            </Card>
          </div>
          <div className="mt-6 grid gap-6 lg:grid-cols-2">
            <Card>
              <div className="flex items-center gap-2">
                <TrendingUp className="text-primary" size={18} />
                <h2 className="font-semibold">Score trend</h2>
              </div>
              {trend.length ? (
                <div className="mt-5 h-56" aria-label="Recent score trend chart">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={trend}>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                      <XAxis dataKey="name" tick={{ fontSize: 11 }} interval={0} angle={-18} textAnchor="end" height={54} />
                      <YAxis domain={[0, 100]} tick={{ fontSize: 11 }} />
                      <Tooltip labelFormatter={(label, payload) => `${payload?.[0]?.payload?.date ?? label}`} />
                      <Line type="monotone" dataKey="score" stroke="hsl(var(--primary))" strokeWidth={2} dot={{ r: 3 }} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <p className="py-12 text-center text-sm text-muted-foreground">No score history is available yet.</p>
              )}
            </Card>
            <Card>
              <div className="flex items-center gap-2">
                <BarChart3 className="text-primary" size={18} />
                <h2 className="font-semibold">Subject performance</h2>
              </div>
              {query.data.subjectPerformance.length ? (
                <div className="mt-5 divide-y divide-border">
                  {query.data.subjectPerformance.map(subject => (
                    <div key={subject.subject} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm first:pt-0">
                      <div>
                        <p className="font-medium">{subject.subject}</p>
                        <p className="mt-1 text-xs text-muted-foreground">
                          {subject.completedExamCount} exam{subject.completedExamCount === 1 ? '' : 's'}
                        </p>
                      </div>
                      <div className="text-right">
                        <p className="font-semibold">{formatScore(subject.averageScore)}</p>
                        <p className="mt-1 flex items-center justify-end gap-1 text-xs text-muted-foreground">
                          <CheckCircle2 size={12} /> {formatScore(subject.accuracy)} accuracy
                        </p>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="py-12 text-center text-sm text-muted-foreground">No subject breakdown is available yet.</p>
              )}
            </Card>
          </div>
          <Card className="mt-6">
            <div className="flex items-center gap-2">
              <BarChart3 className="text-primary" size={18} />
              <h2 className="font-semibold">Topic performance</h2>
            </div>
            {query.data.topicPerformance.length ? (
              <div className="mt-5 divide-y divide-border">
                {query.data.topicPerformance.map(topic => (
                  <div key={topic.topic} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm first:pt-0">
                    <p className="font-medium">{topic.topic}</p>
                    <p className="text-muted-foreground">
                      {formatScore(topic.averageScore)} · {formatScore(topic.accuracy)} accuracy
                    </p>
                  </div>
                ))}
              </div>
            ) : (
              <p className="py-8 text-center text-sm text-muted-foreground">
                Topic-level data is not available yet.
              </p>
            )}
          </Card>
        </>
      )}
    </>
  )
}