export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'
export type ProctorEventType = 'TAB_SWITCH' | 'WINDOW_BLUR' | 'CAMERA_OFF' | 'MULTIPLE_FACES' | 'AUDIO_DETECTED' | 'NETWORK_INTERRUPTION'
export interface AIAnalysis { id: string; studentId: string; examId: string; score: number; strengths: string[]; weaknesses: string[]; recommendations: string[]; riskLevel: RiskLevel; generatedAt: string }
export interface AdaptiveQuestion { id: string; text: string; difficulty: number; options?: string[]; type: 'MCQ' | 'DESCRIPTIVE' }
export interface ProctorEvent { id?: string; attemptId: string; type: ProctorEventType; timestamp: string; metadata?: Record<string, string | number | boolean> }
export interface WebcamStatus { supported: boolean; permission: 'granted' | 'denied' | 'prompt' | 'unknown'; active: boolean; stream?: MediaStream }
export interface AnalyticsPoint { label: string; value: number; secondary?: number }
export interface TopicPerformance { topic: string; score: number; questions: number }
export interface DifficultyAnalysis { level: string; score: number; attempts: number }
export interface RecentExamPerformance { exam: string; score: number; date: string }
export interface StudentAnalytics { readiness: number; strongestArea: string; nextFocus: string; recommendations: string[]; strengths: string[]; weakAreas: string[]; performanceTrend: AnalyticsPoint[]; topicPerformance: TopicPerformance[]; difficultyAnalysis: DifficultyAnalysis[]; recentExams: RecentExamPerformance[] }
export type StudyPriority = { topic: string; reason: string; priority: 'HIGH' | 'MEDIUM' | 'LOW' }
export interface StudentAiAnalysis { summary: string; strengths: string[]; weaknesses: string[]; recommendations: string[]; priorityTopics: string[]; studyPriorities: StudyPriority[] }
export type RoadmapPriority = 'HIGH' | 'MEDIUM' | 'LOW'
export type RoadmapDifficulty = 'EASY' | 'MEDIUM' | 'HARD'
export interface DifficultyCompletionCriteria { minimumQuestions: number; minimumAccuracy: number }
export interface TopicCompletionCriteria { easy: DifficultyCompletionCriteria; medium: DifficultyCompletionCriteria; hard: DifficultyCompletionCriteria }
export type TopicProgressStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'READY_FOR_NEXT' | 'MASTERED'
export interface AIDifficultyProgress { attempted: number; completed: number; bestAccuracy: number | null }
export interface AITopicProgress { topic: string; currentDifficulty: RoadmapDifficulty | 'MASTERED'; unlockedDifficulty: RoadmapDifficulty | 'MASTERED'; status: TopicProgressStatus; easy: AIDifficultyProgress; medium: AIDifficultyProgress; hard: AIDifficultyProgress }
export interface RoadmapTopic { name: string; reason: string; priority: RoadmapPriority; prerequisites: string[]; recommendedDifficulty: RoadmapDifficulty; estimatedStudyTime: string; recommendedPracticeCount: number; completionCriteria: TopicCompletionCriteria; progress: AITopicProgress | null; startDifficulty: RoadmapDifficulty | null }
export interface RoadmapPhase { order: number; title: string; objective: string; topics: RoadmapTopic[] }
export interface StudyRoadmap { title: string; summary: string; estimatedDuration: string; phases: RoadmapPhase[] }
export interface AnalyticsOverview { completionRate: number; averageScore: number; flaggedAttempts: number; activeSessions: number; scoreTrend: AnalyticsPoint[]; difficultyTrend: AnalyticsPoint[]; riskDistribution: AnalyticsPoint[] }
export interface ExamGenerationRequest { topic: string; difficulty: number; questionCount: number; questionTypes: Array<'MCQ' | 'DESCRIPTIVE'>; duration: number }
export interface GeneratedExam { title: string; subject: string; questions: AdaptiveQuestion[]; estimatedDifficulty: number }
export interface AIPracticeOption { id: string; text: string }
export interface AIPracticeQuestion { id: string; question: string; options: AIPracticeOption[]; difficulty: RoadmapDifficulty; selectedOptionId: string | null }
export interface AIPracticeSession { sessionId: string; topic: string; difficulty: RoadmapDifficulty; status: 'ACTIVE' | 'COMPLETED' | 'EXPIRED'; questionCount: number; answeredCount: number; startedAt: string; completedAt: string | null; questions: AIPracticeQuestion[] }
export interface AIPracticeAnswerResponse { answeredCount: number; questionCount: number }
export interface AIPracticeSubmitResponse { sessionId: string; status: string; correctCount: number; incorrectCount: number; unansweredCount: number; totalCount: number; percentage: number; completionMet: boolean }
export interface AIPracticeReviewQuestion { id: string; question: string; yourAnswer: string | null; correctAnswer: string; correct: boolean; answered: boolean; explanation: string | null }
export interface AIPracticeReview { sessionId: string; topic: string; difficulty: RoadmapDifficulty; correctCount: number; incorrectCount: number; unansweredCount: number; totalCount: number; percentage: number; completionMet: boolean; topicStatus: TopicProgressStatus | null; currentDifficulty: RoadmapDifficulty | 'MASTERED' | null; unlockedDifficulty: RoadmapDifficulty | 'MASTERED' | null; minimumQuestions: number | null; minimumAccuracy: number | null; reviewCompleted: boolean; questions: AIPracticeReviewQuestion[] }
export interface AIPracticeSessionSummary { sessionId: string; topic: string; difficulty: RoadmapDifficulty; status: 'ACTIVE' | 'COMPLETED' | 'EXPIRED'; questionCount: number; answeredCount: number; correctCount: number; percentage: number | null; startedAt: string; completedAt: string | null }
