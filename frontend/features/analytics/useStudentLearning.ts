'use client'
import { useQuery } from '@tanstack/react-query'
import { useAuthStore } from '@/store/authStore'
import { learningProfileApi } from '@/services/learningProfileApi'
import { learningIntelligenceApi } from '@/services/learningIntelligenceApi'
import { studentProgressApi } from '@/services/studentProgressApi'
import { studentRecommendationApi } from '@/services/studentRecommendationApi'
import { studentLearningQueryKeys } from '@/lib/student-learning'

/**
 * Bundles the four student-learning queries (P5D.1-4) under stable shared keys.
 * Every consumer renders from the same cached data, so the profile/intelligence/
 * progress/recommendations payloads are only fetched once per session.
 */
export function useStudentLearning() {
  const user = useAuthStore(state => state.user)
  const enabled = Boolean(user)

  const profileQuery = useQuery({
    queryKey: [...studentLearningQueryKeys.profile],
    queryFn: async () => (await learningProfileApi.get()).data.data,
    enabled,
    retry: 1,
  })
  const intelligenceQuery = useQuery({
    queryKey: [...studentLearningQueryKeys.intelligence],
    queryFn: async () => (await learningIntelligenceApi.get()).data.data,
    enabled,
    retry: 1,
  })
  const progressQuery = useQuery({
    queryKey: [...studentLearningQueryKeys.progress],
    queryFn: async () => (await studentProgressApi.get()).data.data,
    enabled,
    retry: 1,
  })
  const recommendationsQuery = useQuery({
    queryKey: [...studentLearningQueryKeys.recommendations],
    queryFn: async () => (await studentRecommendationApi.get()).data.data,
    enabled,
    retry: 1,
  })

  const refetchAll = () => {
    void profileQuery.refetch()
    void intelligenceQuery.refetch()
    void progressQuery.refetch()
    void recommendationsQuery.refetch()
  }

  return { profileQuery, intelligenceQuery, progressQuery, recommendationsQuery, refetchAll }
}