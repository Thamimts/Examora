'use client'
import { useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  AlertCircle,
  ArrowRight,
  Award,
  BookOpen,
  CheckCircle2,
  History as HistoryIcon,
  Minus,
  RefreshCw,
  Sparkles,
  Target,
  TrendingDown,
  TrendingUp,
} from 'lucide-react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Card, ErrorCard, Header } from '@/components/shared'
import { useStudentLearning } from '@/features/analytics/useStudentLearning'
import {
  dimensionLabel,
  formatDelta,
  formatPercent,
  priorityTone,
  recommendationLabel,
  severityTone,
  trendLabel,
  trendTone,
} from '@/lib/student-learning'
import type { Confidence } from '@/types/learning-intelligence'
import type { Recommendation } from '@/types/student-recommendation'

const HISTORY_ROW_LIMIT = 10

function confidenceTone(confidence: Confidence): string {
  switch (confidence) {
    case 'HIGH_CONFIDENCE':
      return 'bg-emerald-500/10 text-emerald-700'
    case 'MEDIUM_CONFIDENCE':
      return 'bg-amber-500/10 text-amber-700'
    case 'LOW_CONFIDENCE':
      return 'bg-muted text-muted-foreground'
    case 'INSUFFICIENT_DATA':
      return 'bg-muted text-muted-foreground'
  }
}

function TrendIcon({ tone }: { tone: ReturnType<typeof trendTone> }) {
  if (tone === 'up') return <TrendingUp size={16} className="text-emerald-600" />
  if (tone === 'down') return <TrendingDown size={16} className="text-amber-600" />
  return <Minus size={16} className="text-muted-foreground" />
}

function RecommendationRow({ recommendation, onPractice, onReview }: { recommendation: Recommendation; onPractice: () => void; onReview: () => void }) {
  const accuracy = recommendation.supportingMetrics?.accuracy
  const actionLabel = recommendation.action === 'REVIEW' ? 'Review exams' : 'Practice now'
  return (
    <li className="flex flex-wrap items-center justify-between gap-4 py-4 first:pt-0">
      <div>
        <p className="font-medium">{recommendationLabel(recommendation.code)}</p>
        <p className="mt-1 text-sm text-muted-foreground">
          {recommendation.code === 'INCREASE_PRACTICE'
            ? `Aim for ${recommendation.targetQuestionCount} practice questions`
            : accuracy != null
              ? recommendation.dimension != null
                ? `Focus on ${dimensionLabel(recommendation.dimension)} — ${formatPercent(accuracy)}% accuracy`
                : `Suggested ${recommendation.targetQuestionCount} practice questions`
              : `Suggested ${recommendation.targetQuestionCount} practice questions`}
        </p>
      </div>
      <div className="flex items-center gap-2">
        {recommendation.priority !== 'LOW' && (
          <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${priorityTone(recommendation.priority)}`}>
            {recommendation.priority} priority
          </span>
        )}
        {recommendation.action === 'MAINTENANCE' ? (
          <span className="text-xs font-medium text-muted-foreground">On track, keep going</span>
        ) : (
          <button
            type="button"
            onClick={recommendation.action === 'REVIEW' ? onReview : onPractice}
            className="flex items-center gap-1 rounded-lg bg-primary px-3 py-2 text-xs font-medium text-primary-foreground"
          >
            {actionLabel} <ArrowRight size={12} />
          </button>
        )}
      </div>
    </li>
  )
}

export function StudentPerformance() {
  const navigate = useNavigate()
  const { profileQuery, intelligenceQuery, progressQuery, recommendationsQuery, refetchAll } = useStudentLearning()

  const profile = profileQuery.data
  const intelligence = intelligenceQuery.data
  const progress = progressQuery.data
  const recommendations = recommendationsQuery.data

  const chartData = useMemo(() => {
    const history = progress?.examProgress.history ?? []
    return history
      .map(point => (point.percentage == null ? null : { name: truncateTitle(point.examTitle, 18), date: point.date, score: point.percentage }))
      .filter((point): point is { name: string; date: string; score: number } => point !== null)
  }, [progress])

  const historyNewestFirst = useMemo(
    () => [...(progress?.examProgress.history ?? [])].reverse(),
    [progress],
  )

  if (profileQuery.isPending) return <Skeleton />

  if (profileQuery.isError) {
    return (
      <>
        <Header
          title="Student learning center"
          description="Your performance, strengths, focus areas, and what to do next — built from your real results."
        />
        <ErrorCard message="Unable to load your learning profile." onRetry={refetchAll} />
      </>
    )
  }

  if (!profile) return <Skeleton />

  const trendToneValue = trendTone?.(profile.trend.direction)
  const trendDelta = formatDelta(profile.trend.delta)
  const sufficient = intelligence?.dataQuality.sufficientData ?? false
  const actionableRecommendations = (recommendations?.recommendations ?? []).filter(
    rec => rec.code !== 'BUILD_BASELINE',
  )
  const hasMoreHistory = historyNewestFirst.length > HISTORY_ROW_LIMIT

  return (
    <>
      <Header
        title="Student learning center"
        description="Your performance, strengths, focus areas, and what to do next — built from your real results."
      />

      <section aria-label="Learning overview">
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Card>
            <div className="flex items-center justify-between gap-3">
              <p className="text-sm text-muted-foreground">Average score</p>
              <Award size={18} className="text-primary" />
            </div>
            <p className="mt-2 text-3xl font-semibold">{formatPercent(profile.overall.averageScore)}</p>
          </Card>
          <Card>
            <div className="flex items-center justify-between gap-3">
              <p className="text-sm text-muted-foreground">Completed exams</p>
              <BookOpen size={18} className="text-primary" />
            </div>
            <p className="mt-2 text-3xl font-semibold">{profile.overall.completedExams}</p>
          </Card>
          <Card>
            <div className="flex items-center justify-between gap-3">
              <p className="text-sm text-muted-foreground">Practice accuracy</p>
              <Target size={18} className="text-primary" />
            </div>
            <p className="mt-2 text-3xl font-semibold">{formatPercent(profile.practice.accuracy)}</p>
          </Card>
          <Card>
            <div className="flex items-center justify-between gap-3">
              <p className="text-sm text-muted-foreground">Current trend</p>
              {trendToneValue && <TrendIcon tone={trendToneValue} />}
            </div>
            <p className="mt-2 text-3xl font-semibold">{trendLabel(profile.trend.direction)}</p>
            {profile.trend.delta != null && (
              <p className="mt-1 text-xs text-muted-foreground">{formatDelta(profile.trend.delta)} vs previous exams</p>
            )}
          </Card>
        </div>
      </section>

      <section aria-label="Your progress" className="mt-6">
        <Card>
          <div className="flex items-center justify-between gap-3">
            <div>
              <h2 className="font-semibold">Your progress</h2>
              <p className="mt-1 text-sm text-muted-foreground">Score across your completed exams</p>
            </div>
            <button
              type="button"
              className="rounded-lg border border-border px-3 py-2 text-sm transition hover:bg-muted active:scale-[.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              onClick={() => navigate('/student/history')}
            >
              View history
            </button>
          </div>
          {progressQuery.isError ? (
            <div className="mt-4">
              <ErrorCard message="Unable to load your progress." onRetry={() => void progressQuery.refetch()} />
            </div>
          ) : chartData.length ? (
            <div className="mt-5 h-56" aria-label="Line chart of your score across completed exams">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                  <XAxis dataKey="name" tick={{ fontSize: 11 }} interval="preserveStartEnd" />
                  <YAxis domain={[0, 100]} tick={{ fontSize: 11 }} />
                  <Tooltip labelFormatter={(label, payload) => `${payload?.[0]?.payload?.date ?? label}`} />
                  <Line type="monotone" dataKey="score" stroke="hsl(var(--primary))" strokeWidth={2} dot={{ r: 3 }} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          ) : (
            <p className="mt-4 text-sm text-muted-foreground">
              Complete an exam to see how your score changes over time.
            </p>
          )}
        </Card>
      </section>

      <section aria-label="Your strengths and focus areas" className="mt-6 grid gap-6 lg:grid-cols-2">
        <Card>
          <h2 className="font-semibold">Your strengths</h2>
          <p className="mt-1 text-sm text-muted-foreground">Where your accuracy is already strong</p>
          {intelligenceQuery.isError ? (
            <div className="mt-4">
              <ErrorCard message="Unable to load your strengths." onRetry={() => void intelligenceQuery.refetch()} />
            </div>
          ) : intelligence?.strengths.length ? (
            <ul className="mt-4 divide-y divide-border">
              {intelligence.strengths.map(strength => (
                <li key={strength.dimension} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm first:pt-0">
                  <div className="flex items-center gap-2">
                    <CheckCircle2 size={15} className="text-emerald-600" />
                    <span className="font-medium">{dimensionLabel(strength.dimension)}</span>
                  </div>
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <span className="font-semibold text-emerald-700">{formatPercent(strength.accuracy)}% accuracy</span>
                    <span className="rounded-full bg-muted px-2 py-0.5">{strength.observations} observations</span>
                    <span className={`rounded-full px-2 py-0.5 ${confidenceTone(strength.confidence)}`}>
                      {strength.confidence.replace(/_/g, ' ').toLowerCase()}
                    </span>
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-4 text-sm text-muted-foreground">
              {sufficient ? 'No strengths identified yet — keep going.' : 'Complete an exam to start identifying your strengths.'}
            </p>
          )}
        </Card>

        <Card>
          <h2 className="font-semibold">Focus areas</h2>
          <p className="mt-1 text-sm text-muted-foreground">Where your accuracy is weakest</p>
          {intelligenceQuery.isError ? (
            <div className="mt-4">
              <ErrorCard message="Unable to load your focus areas." onRetry={() => void intelligenceQuery.refetch()} />
            </div>
          ) : intelligence?.weaknesses.length ? (
            <ul className="mt-4 divide-y divide-border">
              {intelligence.weaknesses.map(weakness => (
                <li key={weakness.dimension} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm first:pt-0">
                  <div className="flex items-center gap-2">
                    <Target size={15} className="text-amber-600" />
                    <span className="font-medium">{dimensionLabel(weakness.dimension)}</span>
                  </div>
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <span className={`rounded-full px-2 py-0.5 font-semibold ${severityTone(weakness.severity)}`}>
                      {weakness.severity.replace(/_/g, ' ').toLowerCase()}
                    </span>
                    <span className="font-semibold text-amber-700">{formatPercent(weakness.accuracy)}% accuracy</span>
                    <span className="rounded-full bg-muted px-2 py-0.5">{weakness.observations} observations</span>
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-4 text-sm text-muted-foreground">
              {sufficient ? 'Nothing stands out as weak right now — great consistency.' : 'Complete an exam to identify focus areas.'}
            </p>
          )}
        </Card>
      </section>

      <section aria-label="What to do next" className="mt-6">
        <Card>
          <h2 className="font-semibold">What to do next</h2>
          <p className="mt-1 text-sm text-muted-foreground">Prioritized next steps from the recommendation engine</p>
          {recommendationsQuery.isError ? (
            <div className="mt-4">
              <ErrorCard message="Unable to load your recommendations." onRetry={() => void recommendationsQuery.refetch()} />
            </div>
          ) : actionableRecommendations.length ? (
            <ul className="mt-2 divide-y divide-border">
              {actionableRecommendations.map(recommendation => (
                <RecommendationRow
                  key={recommendation.code}
                  recommendation={recommendation}
                  onPractice={() => navigate('/student/practice')}
                  onReview={() => navigate('/student/history')}
                />
              ))}
            </ul>
          ) : (
            <p className="mt-4 text-sm text-muted-foreground">
              Complete a few exams so the engine can recommend what to practice next.
            </p>
          )}
        </Card>
      </section>

      <section aria-label="Exam history" className="mt-6">
        <Card>
          <div className="flex items-center justify-between gap-3">
            <div>
              <h2 className="font-semibold">Exam history</h2>
              <p className="mt-1 text-sm text-muted-foreground">Every completed exam you can revisit or ask the AI coach about</p>
            </div>
            <button
              type="button"
              className="rounded-lg border border-border px-3 py-2 text-sm transition hover:bg-muted active:scale-[.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              onClick={() => void progressQuery.refetch()}
            >
              Refresh
            </button>
          </div>
          {progressQuery.isError ? (
            <div className="mt-4">
              <ErrorCard message="Unable to load your exam history." onRetry={() => void progressQuery.refetch()} />
            </div>
          ) : historyNewestFirst.length ? (
            <ul className="mt-4 divide-y divide-border">
              {historyNewestFirst.slice(0, HISTORY_ROW_LIMIT).map(point => {
                const passed = point.percentage != null && point.percentage >= 50
                return (
                  <li
                    key={point.examId}
                    className="flex flex-wrap items-center justify-between gap-3 py-4 text-sm first:pt-0"
                  >
                    <div>
                      <p className="font-medium">{point.examTitle}</p>
                      <p className="mt-1 text-muted-foreground">
                        {point.subject} · {point.date} · {point.score}/{point.total}
                        {point.attemptNumber != null ? ` · Attempt ${point.attemptNumber}` : ''}
                      </p>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      <span
                        className={`rounded-full px-2.5 py-1 text-xs font-semibold ${
                          passed ? 'bg-emerald-500/10 text-emerald-700' : 'bg-red-500/10 text-red-700'
                        }`}
                      >
                        {passed ? 'Passed' : 'Not passed'}
                      </span>
                      <span className="font-semibold">{formatPercent(point.percentage)}%</span>
                      <button
                        type="button"
                        onClick={() => navigate(`/student/exams/${point.examId}/result`)}
                        className="rounded-lg border border-border px-3 py-2 text-xs transition hover:bg-muted active:scale-[.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        View result
                      </button>
                      <button
                        type="button"
                        onClick={() => navigate(`/student/ai-analysis?exam=${point.examId}`)}
                        className="flex items-center gap-1 rounded-lg bg-primary px-3 py-2 text-xs font-medium text-primary-foreground transition hover:opacity-90 active:scale-[.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      >
                        <Sparkles size={12} /> Review with AI
                      </button>
                    </div>
                  </li>
                )
              })}
            </ul>
          ) : (
            <p className="mt-4 text-sm text-muted-foreground">
              No completed exams yet. Submit an exam to see it here and unlock the AI coach review.
            </p>
          )}
        </Card>
      </section>
    </>
  )
}

function throwAwayUnused(cards: typeof Card[]) {
  return cards[0]
}

const unusedSkeleton = Skeleton
function Skeleton() {
  return (
    <>
      <Header
        title="Student learning center"
        description="Loading your learning data..."
      />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" aria-busy="true">
        {[1, 2, 3, 4].map(item => (
          <div key={item} className="h-24 animate-pulse rounded-2xl bg-muted" />
        ))}
      </div>
    </>
  )
}
void throwAwayUnused
void unusedSkeleton

function truncateTitle(value: string, max: number): string {
  return value.length > max ? `${value.slice(0, max - 1)}…` : value
}
