import { backendUrl } from '@/lib/ai-config'
import type { AITopicProgress, RoadmapDifficulty, RoadmapTopic, StudyRoadmap, TopicCompletionCriteria } from '@/types/ai'

export const DEFAULT_MIN_ACCURACY = 80

export async function fetchRoadmapProgress(authorization: string): Promise<Map<string, AITopicProgress>> {
  try {
    const response = await fetch(`${backendUrl()}/student/ai-practice/progress`, {
      headers: { Authorization: authorization, Accept: 'application/json' },
      cache: 'no-store',
    })
    if (!response.ok) return new Map()
    const body = await response.json().catch(() => null)
    const list = body?.data as AITopicProgress[] | undefined
    if (!Array.isArray(list)) return new Map()
    return new Map(list.map(item => [item.topic, item]))
  } catch {
    return new Map()
  }
}

export function startDifficultyFor(progress: AITopicProgress | null): RoadmapDifficulty | null {
  if (!progress) return 'EASY'
  if (progress.currentDifficulty === 'MASTERED') return null
  return progress.currentDifficulty
}

export function normalizeCompletionCriteria(topic: Pick<RoadmapTopic, 'completionCriteria' | 'recommendedPracticeCount'>): TopicCompletionCriteria {
  const normalized = { minimumQuestions: topic.recommendedPracticeCount, minimumAccuracy: DEFAULT_MIN_ACCURACY }
  return { easy: { ...normalized }, medium: { ...normalized }, hard: { ...normalized } }
}

export async function mergeTopicProgress(authorization: string, roadmap: StudyRoadmap): Promise<StudyRoadmap> {
  const progressByTopic = await fetchRoadmapProgress(authorization)
  return {
    ...roadmap,
    phases: roadmap.phases.map(phase => ({
      ...phase,
      topics: phase.topics.map(topic => {
        const progress = progressByTopic.get(topic.name) ?? null
        return { ...topic, progress, startDifficulty: startDifficultyFor(progress) }
      }),
    })),
  }
}