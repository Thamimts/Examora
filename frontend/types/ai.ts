export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'
export type ProctorEventType = 'TAB_SWITCH' | 'WINDOW_BLUR' | 'CAMERA_OFF' | 'MULTIPLE_FACES' | 'AUDIO_DETECTED' | 'NETWORK_INTERRUPTION'
export interface AIAnalysis { id: string; studentId: string; examId: string; score: number; strengths: string[]; weaknesses: string[]; recommendations: string[]; riskLevel: RiskLevel; generatedAt: string }
export interface AdaptiveQuestion { id: string; text: string; difficulty: number; options?: string[]; type: 'MCQ' | 'DESCRIPTIVE' }
export interface AdaptiveSession { id: string; examId: string; currentDifficulty: number; questionIndex: number; questions: AdaptiveQuestion[]; completed: boolean }
export interface ProctorEvent { id?: string; attemptId: string; type: ProctorEventType; timestamp: string; metadata?: Record<string, string | number | boolean> }
export interface WebcamStatus { supported: boolean; permission: 'granted' | 'denied' | 'prompt' | 'unknown'; active: boolean; stream?: MediaStream }
export interface AnalyticsPoint { label: string; value: number; secondary?: number }
export interface TopicPerformance { topic: string; score: number; questions: number }
export interface DifficultyAnalysis { level: string; score: number; attempts: number }
export interface RecentExamPerformance { exam: string; score: number; date: string }
export interface StudentAnalytics { readiness: number; strongestArea: string; nextFocus: string; recommendations: string[]; strengths: string[]; weakAreas: string[]; performanceTrend: AnalyticsPoint[]; topicPerformance: TopicPerformance[]; difficultyAnalysis: DifficultyAnalysis[]; recentExams: RecentExamPerformance[] }
export interface AnalyticsOverview { completionRate: number; averageScore: number; flaggedAttempts: number; activeSessions: number; scoreTrend: AnalyticsPoint[]; difficultyTrend: AnalyticsPoint[]; riskDistribution: AnalyticsPoint[] }
export interface ExamGenerationRequest { topic: string; difficulty: number; questionCount: number; questionTypes: Array<'MCQ' | 'DESCRIPTIVE'>; duration: number }
export interface GeneratedExam { title: string; subject: string; questions: AdaptiveQuestion[]; estimatedDifficulty: number }
