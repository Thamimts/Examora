export interface ScoreTrendPoint {
  resultId: string
  examTitle: string
  date: string
  score: number
}

export interface AnalyticsSubjectPerformance {
  subject: string
  completedExamCount: number
  averageScore: number
  accuracy: number
}

export interface AnalyticsTopicPerformance {
  topic: string
  completedExamCount: number
  averageScore: number
  accuracy: number
}

export interface StudentPerformanceAnalytics {
  completedExamCount: number
  averageScore: number
  highestScore: number | null
  lowestScore: number | null
  accuracy: number
  recentScoreTrend: ScoreTrendPoint[]
  subjectPerformance: AnalyticsSubjectPerformance[]
  topicPerformance: AnalyticsTopicPerformance[]
}