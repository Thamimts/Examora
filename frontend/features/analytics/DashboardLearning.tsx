'use client'
import { useNavigate } from 'react-router-dom'
import { ArrowRight, Minus, Target, TrendingDown, TrendingUp } from 'lucide-react'
import { Card } from '@/components/shared'
import { useStudentLearning } from '@/features/analytics/useStudentLearning'
import {
  dimensionLabel,
  formatDelta,
  formatPercent,
  recommendationLabel,
  trendLabel,
  trendTone,
} from '@/lib/student-learning'

function CardSkeleton() {
  return (
    <Card>
      <div className="h-[104px] animate-pulse rounded-xl bg-muted" />
    </Card>
  )
}

function TrendCard({ hidden, isError, direction, delta }: { hidden: boolean; isError: boolean; direction?: string; delta?: string | null }) {
  if (hidden) return null
  const tone = direction ? trendTone(direction as Parameters<typeof trendTone>[0]) : 'none'
  return (
    <Card className="p-4">
      <p className="text-sm font-medium text-muted-foreground">Trend</p>
      <div className="mt-2 flex items-center justify-between gap-2">
        <p className="text-lg font-semibold leading-tight">{isError || !direction ? '—' : trendLabel(direction as Parameters<typeof trendLabel>[0])}</p>
        {!isError && direction && tone === 'up' && <TrendingUp size={16} className="text-emerald-600" />}
        {!isError && direction && tone === 'down' && <TrendingDown size={16} className="text-amber-600" />}
        {!isError && direction && tone === 'flat' && <Minus size={16} className="text-muted-foreground" />}
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        {isError || !direction ? 'No data yet' : delta ? `${delta} vs previous exams` : 'from your completed exams'}
      </p>
    </Card>
  )
}

function FocusCard({ hidden, isError, label, accuracy }: { hidden: boolean; isError: boolean; label?: string | null; accuracy?: number | null }) {
  if (hidden) return null
  return (
    <Card className="p-4">
      <p className="text-sm font-medium text-muted-foreground">Weakest area</p>
      <p className="mt-2 flex items-center gap-2 text-lg font-semibold leading-tight">
        <Target size={16} className="shrink-0 text-amber-600" />
        {isError || !label ? 'Complete an exam' : label}
      </p>
      <p className="mt-1 text-xs text-muted-foreground">
        {isError || accuracy == null ? 'Start studying to unlock insights' : `${formatPercent(accuracy)}% accuracy`}
      </p>
    </Card>
  )
}

export function DashboardLearning() {
  const navigate = useNavigate()
  const { profileQuery, intelligenceQuery, recommendationsQuery } = useStudentLearning()

  const profile = profileQuery.data
  const intelligence = intelligenceQuery.data
  const recommendations = recommendationsQuery.data

  const weakest = (intelligence?.weaknesses ?? [])[0]
  const topRecommendation = (recommendations?.recommendations ?? []).find(
    recommendation => recommendation.code !== 'BUILD_BASELINE',
  )
  const hasEnoughData = intelligence?.dataQuality.sufficientData ?? false

  return (
    <section aria-label="Your learning at a glance">
      <div className="grid gap-4 sm:grid-cols-3">
        {profileQuery.isPending ? (
          <>
            <CardSkeleton />
            <CardSkeleton />
            <CardSkeleton />
          </>
        ) : (
          <>
            <TrendCard
              hidden={false}
              isError={profileQuery.isError}
              direction={profile?.trend.direction}
              delta={profile ? formatDelta(profile.trend.delta) : null}
            />
            <FocusCard
              hidden={false}
              isError={intelligenceQuery.isError}
              label={hasEnoughData && weakest ? dimensionLabel(weakest.dimension) : null}
              accuracy={hasEnoughData && weakest ? weakest.accuracy : null}
            />
            {topRecommendation ? (
              <Card className="flex flex-col justify-between p-4">
                <p className="text-sm font-medium text-muted-foreground">Next step</p>
                <span className="mt-2 flex items-center gap-2 text-lg font-semibold leading-tight">
                  <span className="line-clamp-2">{recommendationLabel(topRecommendation.code)}</span>
                </span>
                <button
                  type="button"
                  onClick={() =>
                    navigate(topRecommendation.action === 'REVIEW' ? '/student/history' : '/student/practice')
                  }
                  className="mt-2 flex items-center justify-center gap-1.5 rounded-xl bg-primary px-3 py-2 text-sm font-medium text-primary-foreground transition hover:opacity-90 active:scale-[.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  {topRecommendation.action === 'REVIEW' ? 'Review exams' : 'Practice now'} <ArrowRight size={14} />
                </button>
              </Card>
            ) : recommendationsQuery.isError ? (
              <Card className="p-4">
                <p className="text-sm font-medium text-muted-foreground">Next step</p>
                <p className="mt-2 text-lg font-semibold leading-tight">Complete an exam</p>
                <p className="mt-1 text-xs text-muted-foreground">Recommendations appear after a few exams</p>
              </Card>
            ) : (
              <Card className="p-4">
                <p className="text-sm font-medium text-muted-foreground">Next step</p>
                <p className="mt-2 text-lg font-semibold leading-tight">Build your baseline</p>
                <p className="mt-1 text-xs text-muted-foreground">Recommendations appear after a few exams</p>
              </Card>
            )}
          </>
        )}
      </div>
    </section>
  )
}