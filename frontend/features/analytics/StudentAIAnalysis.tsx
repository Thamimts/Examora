'use client'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AlertCircle, RefreshCw, Sparkles } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/store/authStore'
import { Card, Header } from '@/components/shared'
import type { StudentAiAnalysis } from '@/types/ai'

type AnalysisError = Error & { status?: number }

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message ? error.message : 'Unable to load AI analysis.'
}

export function StudentAIAnalysis() {
  const token = useAuthStore((state) => state.token)
  const navigate = useNavigate()
  const [regenerating, setRegenerating] = useState(false)
  const [regenerateError, setRegenerateError] = useState<string | null>(null)

  const query = useQuery({
    queryKey: ['student-ai-analysis'],
    enabled: Boolean(token),
    queryFn: async () => {
      const response = await fetch('/api/student/ai-analysis', {
        headers: { Authorization: `Bearer ${token}` },
        cache: 'no-store',
      })
      const body = await response.json().catch(() => null)
      if (!response.ok) {
        const error: AnalysisError = new Error(body?.error ?? 'Unable to load AI analysis')
        error.status = response.status
        throw error
      }
      return body as StudentAiAnalysis
    },
    retry: 1,
  })

  const hasNoExams = (query.error as { status?: number } | undefined)?.status === 422

  const regenerate = async () => {
    if (!token || regenerating) return
    setRegenerating(true)
    setRegenerateError(null)
    try {
      const response = await fetch('/api/student/ai-analysis', {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
        cache: 'no-store',
      })
      const body = await response.json().catch(() => null)
      if (!response.ok) {
        setRegenerateError(body?.error ?? 'Unable to regenerate the analysis.')
        return
      }
      await query.refetch()
    } catch {
      setRegenerateError('Unable to reach the analysis service. Please try again.')
    } finally {
      setRegenerating(false)
    }
  }

  return (
    <>
      <Header
        title="AI Performance Analysis"
        description="Personalized insights generated from your submitted exam results."
      />
      {query.isPending && (
        <div className="grid gap-4 md:grid-cols-3" aria-busy="true" aria-live="polite">
          {[1, 2, 3].map(item => (
            <Card key={item}>
              <div className="h-20 animate-pulse rounded-xl bg-muted" />
            </Card>
          ))}
        </div>
      )}
      {query.isError && hasNoExams && (
        <Card className="max-w-2xl">
          <div className="flex items-center gap-3">
            <div className="grid size-11 place-items-center rounded-xl bg-primary/10 text-primary">
              <Sparkles size={20} />
            </div>
            <div>
              <p className="font-medium">No analysis yet</p>
              <p className="mt-1 text-sm text-muted-foreground">
                Complete at least one exam, then come back here for personalized guidance.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => navigate('/student/exams')}
            className="mt-5 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"
          >
            Browse exams
          </button>
        </Card>
      )}
      {query.isError && !hasNoExams && (
        <Card className="border-destructive/30">
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3 text-destructive">
              <AlertCircle size={20} />
              <div>
                <p className="font-medium">Analysis is temporarily unavailable</p>
                <p className="mt-1 text-sm text-muted-foreground">{errorMessage(query.error)}</p>
              </div>
            </div>
            <button
              type="button"
              onClick={() => query.refetch()}
              className="rounded-xl bg-primary px-4 py-2 text-sm text-primary-foreground"
            >
              Retry analysis
            </button>
          </div>
        </Card>
      )}
      {query.data && (
        <>
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-muted-foreground">
              {query.data.summary} insights are regenerated on demand.
            </p>
            <button
              type="button"
              disabled={regenerating}
              onClick={regenerate}
              className="flex items-center gap-2 rounded-xl border border-border px-4 py-2 text-sm hover:bg-muted disabled:opacity-60"
            >
              <RefreshCw size={15} className={regenerating ? 'animate-spin' : ''} />
              {regenerating ? 'Regenerating...' : 'Regenerate analysis'}
            </button>
          </div>
          {regenerateError && (
            <p role="alert" className="mb-4 text-sm text-destructive">
              {regenerateError}
            </p>
          )}
          <div className="grid gap-5 lg:grid-cols-2">
            <Card className="lg:col-span-2">
              <h2 className="font-semibold">Summary</h2>
              <p className="mt-3 text-sm leading-6 text-muted-foreground">{query.data.summary}</p>
            </Card>
            <Card>
              <h2 className="font-semibold">Strengths</h2>
              <ul className="mt-4 grid gap-3">
                {query.data.strengths.map(item => (
                  <li key={item} className="rounded-xl bg-muted p-3 text-sm">{item}</li>
                ))}
              </ul>
            </Card>
            <Card>
              <h2 className="font-semibold">Weaknesses</h2>
              <ul className="mt-4 grid gap-3">
                {query.data.weaknesses.map(item => (
                  <li key={item} className="rounded-xl bg-muted p-3 text-sm">{item}</li>
                ))}
              </ul>
            </Card>
            <Card>
              <h2 className="font-semibold">Recommendations</h2>
              <ul className="mt-4 grid gap-3">
                {query.data.recommendations.map(item => (
                  <li key={item} className="rounded-xl bg-muted p-3 text-sm">{item}</li>
                ))}
              </ul>
            </Card>
            <Card>
              <h2 className="font-semibold">Priority topics</h2>
              <ul className="mt-4 grid gap-3">
                {query.data.priorityTopics.map(item => (
                  <li key={item} className="rounded-xl bg-muted p-3 text-sm">{item}</li>
                ))}
              </ul>
            </Card>
            <Card className="lg:col-span-2">
              <h2 className="font-semibold">Study priorities</h2>
              <div className="mt-4 grid gap-3">
                {query.data.studyPriorities.map(item => (
                  <div key={item.topic} className="rounded-xl border border-border p-4">
                    <div className="flex items-center justify-between gap-3">
                      <p className="font-medium">{item.topic}</p>
                      <span className="text-xs text-muted-foreground">{item.priority}</span>
                    </div>
                    <p className="mt-2 text-sm text-muted-foreground">{item.reason}</p>
                  </div>
                ))}
              </div>
            </Card>
          </div>
        </>
      )}
    </>
  )
}