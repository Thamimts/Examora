export type DifficultyLevel = 'EASY' | 'MEDIUM' | 'HARD'

export interface LearningProfile {
  overall: {
    averageScore: number | null
    highestScore: number | null
    lowestScore: number | null
    completedExams: number
    submittedAttempts: number
  }
  examPerformance: {
    averageAccuracy: number | null
    recentAccuracy: number | null
    improvementDelta: number | null
  }
  practice: {
    sessionsCompleted: number
    questionsAnswered: number
    correctAnswers: number
    accuracy: number | null
    recentAccuracy: number | null
  }
  aiPractice: {
    sessionsCompleted: number
    questionsAnswered: number
    correctAnswers: number
    accuracy: number | null
  }
  difficulty: {
    exam: DifficultyBuckets
    practice: DifficultyBuckets
  }
  trend: {
    direction: 'IMPROVING' | 'STABLE' | 'DECLINING' | 'INSUFFICIENT_DATA'
    delta: number | null
  }
  learningSignals: {
    strengths: string[]
    weaknesses: string[]
    consistency: 'STABLE' | 'VARIABLE' | 'INSUFFICIENT_DATA'
  }
}

export interface DifficultyBuckets {
  easy: DifficultyLevelData
  medium: DifficultyLevelData
  hard: DifficultyLevelData
}

export interface DifficultyLevelData {
  attempted: number
  correct: number
  accuracy: number | null
}