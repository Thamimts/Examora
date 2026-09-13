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

export interface OptionDistribution {
  optionId: string | null
  count: number
}

export interface QuestionAnalytics {
  number: number
  questionId: string
  difficulty: number
  eligibleAttempts: number
  gradedAttempts: number
  correct: number
  incorrect: number
  ungradedAnswers: number
  unanswered: number
  accuracy: number | null
  attemptRate: number | null
  optionDistribution: OptionDistribution[]
  flags: string[]
}

export interface ScoreDistributionBucket {
  range: string
  count: number
}

export interface ExamAnalyticsSummary {
  examId: string
  title: string
  subject: string
  status: string
  participants: number
  submissions: number
  averageScore: number | null
  medianScore: number | null
  highestScore: number | null
  lowestScore: number | null
  completionRate: number | null
  passRate: number | null
}

export interface ExamAnalyticsDetail {
  examId: string
  title: string
  subject: string
  status: string
  participants: number
  submissions: number
  averageScore: number | null
  medianScore: number | null
  highestScore: number | null
  lowestScore: number | null
  completionRate: number | null
  attemptsStarted: number
  attemptsSubmitted: number
  scoreDistribution: ScoreDistributionBucket[]
  questionAnalytics: QuestionAnalytics[]
}

export interface PerExamStudentResult {
  examId: string
  examTitle: string
  subject: string
  date: string
  score: number
  total: number
  percentage: number
}

export interface RecentExamPoint {
  examId: string
  examTitle: string
  subject: string
  date: string
  score: number
  total: number
  percentage: number
}

export interface StudentDrilldownAnalytics {
  studentId: string
  studentName: string
  completedExams: number
  averagePercentage: number | null
  highestScore: number | null
  lowestScore: number | null
  trueAccuracy: number | null
  recentTrend: RecentExamPoint[]
  examHistory: PerExamStudentResult[]
}

export interface SubjectPerformanceSummary {
  subject: string
  completedExamCount: number
  averagePercentage: number
}

export interface DifficultyPerformance {
  difficulty: number
  gradedAttempts: number
  correct: number
  accuracy: number | null
}

export interface PracticeDifficultyAnalytics {
  difficulty: number
  questions: number
  correct: number
  accuracy: number | null
}

export interface PracticeAnalytics {
  totalSessions: number
  sessionsCompleted: number
  totalQuestions: number
  correctAnswers: number
  accuracy: number | null
  accuracyByDifficulty: PracticeDifficultyAnalytics[]
  mostRecentActivity: string | null
}

export interface StudentAnalyticsSummary {
  examCount: number
  averagePercentage: number | null
  highestScore: number | null
  lowestScore: number | null
  gradedQuestions: number
  correctGradedQuestions: number
  trueAccuracy: number | null
  examTrend: RecentExamPoint[]
  subjectPerformance: SubjectPerformanceSummary[]
  difficultyPerformance: DifficultyPerformance[]
  practice: PracticeAnalytics
}

export interface ExamAttemptInfo {
  attemptNumber: number
  durationSeconds: number | null
  submittedAt: string | null
}

export interface OptionLabel {
  questionId: string
  optionId: string
  text: string
  displayOrder: number
}

export interface StudentPerformanceRow {
  studentId: string
  studentName: string
  hasResult: boolean
  score: number | null
  total: number | null
  percentage: number | null
  submittedAt: string | null
  durationSeconds: number | null
  attemptNumber: number | null
  attemptStatus: string | null
  activeNow: boolean
  practiceQuestions: number
  practiceCorrect: number
  practiceAccuracy: number | null
}

export interface StudentExamAnalytics {
  examId: string
  examTitle: string
  subject: string
  date: string
  score: number
  total: number
  percentage: number
  attempt: ExamAttemptInfo
  practice: PracticeAnalytics
}