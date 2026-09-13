'use client'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Monitor, Radar } from 'lucide-react'
import { examApi } from '@/services/examApi'
import { Card, Header } from '@/components/shared'
import { EmptyState, StatusPill } from './intelligence-common'
import { ExamIntelligenceList } from './ExamIntelligenceList'
import { ExamIntelligence } from './ExamIntelligence'

export function MonitorPicker() {
  const navigate = useNavigate()
  const examsQuery = useQuery({
    queryKey: ['teacher-analytics-exams'],
    queryFn: async () => (await examApi.list()).data.data,
    retry: 1,
  })

  const examable = (examsQuery.data ?? []).filter(exam => exam.status !== 'DRAFT')

  return (
    <>
      <Header
        title="Live monitor"
        description="Choose a published exam to watch its real-time proctoring feed."
      />
      <Card>
        <div className="flex items-center gap-2">
          <Radar className="text-primary" size={18} />
          <h2 className="font-semibold">Select an exam</h2>
        </div>
        {examsQuery.isPending ? (
          <div className="mt-4 space-y-3" aria-busy="true">
            {[1, 2, 3].map(item => <div key={item} className="h-16 animate-pulse rounded-xl bg-muted" />)}
          </div>
        ) : examable.length ? (
          <div className="mt-4 divide-y divide-border">
            {examable.map(exam => (
              <div key={exam.id} className="flex flex-wrap items-center justify-between gap-4 py-4 first:pt-0">
                <div className="min-w-0">
                  <p className="flex flex-wrap items-center gap-2 font-medium">
                    {exam.title}
                    <StatusPill status={exam.status} />
                  </p>
                  <p className="mt-0.5 text-sm text-muted-foreground">
                    {exam.subject} · {exam.participants} participants
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => navigate(`/teacher/monitor/${exam.id}`)}
                  className="inline-flex items-center gap-2 rounded-xl bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground transition hover:bg-primary/90"
                >
                  <Monitor size={16} />
                  Open monitor
                </button>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState title="No publishable exams yet" hint="Publish an exam to start live monitoring." />
        )}
      </Card>
    </>
  )
}

export function AdminAnalytics() {
  return <ExamIntelligenceList basePath="/admin/analytics" />
}

export function AdminAnalyticsDetail() {
  return <ExamIntelligence />
}