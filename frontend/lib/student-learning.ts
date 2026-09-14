import type { Dimension, PriorityLevel, Severity } from '@/types/learning-intelligence'
import type { ProgressDirection } from '@/types/student-progress'
import type { RecommendationCode } from '@/types/student-recommendation'

/**
 * Central query keys for the student learning data. Shared by every page that
 * renders learning intelligence (dashboard + learning center) so react-query
 * can serve one network round-trip per API across navigation.
 */
export const studentLearningQueryKeys = {
  profile: ['student-learning-profile'],
  intelligence: ['student-learning-intelligence'],
  progress: ['student-progress'],
  recommendations: ['student-recommendations'],
} as const

export function formatPercent(value: number | null | undefined): string {
  if (typeof value !== 'number' || Number.isNaN(value)) return '—'
  const rounded = Math.round(value * 100) / 100
  return String(Number.isInteger(rounded) ? rounded : rounded.toFixed(2))
}

export function formatDelta(value: number | null | undefined): string | null {
  if (typeof value !== 'number' || Number.isNaN(value)) return null
  const sign = value > 0 ? '+' : ''
  return `${sign}${formatPercent(value)} pts`
}

export function dimensionLabel(dimension: Dimension): string {
  switch (dimension) {
    case 'EASY_DIFFICULTY':
      return 'Easy questions'
    case 'MEDIUM_DIFFICULTY':
      return 'Medium questions'
    case 'HARD_DIFFICULTY':
      return 'Hard questions'
    case 'EXAM_PERFORMANCE':
      return 'Exam performance'
    case 'PRACTICE_PERFORMANCE':
      return 'Practice performance'
    case 'RECENT_PERFORMANCE':
      return 'Recent performance'
    case 'CONSISTENCY':
      return 'Consistency'
  }
}

export function recommendationLabel(code: RecommendationCode): string {
  switch (code) {
    case 'PRACTICE_EASY_QUESTIONS':
      return 'Practice easy questions'
    case 'PRACTICE_MEDIUM_QUESTIONS':
      return 'Practice medium questions'
    case 'PRACTICE_HARD_QUESTIONS':
      return 'Practice hard questions'
    case 'REVIEW_RECENT_EXAMS':
      return 'Review recent exams'
    case 'INCREASE_PRACTICE':
      return 'Practice more consistently'
    case 'MAINTAIN_STRENGTH':
      return 'Maintain your strengths'
    case 'BUILD_BASELINE':
      return 'Build your learning baseline'
  }
}

export function trendLabel(direction: ProgressDirection): string {
  switch (direction) {
    case 'IMPROVING':
      return 'Improving'
    case 'DECLINING':
      return 'Declining'
    case 'STABLE':
      return 'Stable'
    case 'INSUFFICIENT_DATA':
      return 'Not enough data'
  }
}

export type TrendTone = 'up' | 'down' | 'flat' | 'none'

export function trendTone(direction: ProgressDirection): TrendTone {
  if (direction === 'IMPROVING') return 'up'
  if (direction === 'DECLINING') return 'down'
  if (direction === 'STABLE') return 'flat'
  return 'none'
}

export function priorityTone(priority: PriorityLevel): string {
  if (priority === 'HIGH') return 'bg-red-500/10 text-red-700'
  if (priority === 'MEDIUM') return 'bg-amber-500/10 text-amber-700'
  return 'bg-muted text-muted-foreground'
}

export function severityTone(severity: Severity): string {
  if (severity === 'HIGH') return 'bg-red-500/10 text-red-700'
  if (severity === 'MEDIUM') return 'bg-amber-500/10 text-amber-700'
  return 'bg-emerald-500/10 text-emerald-700'
}