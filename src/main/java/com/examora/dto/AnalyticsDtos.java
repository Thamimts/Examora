package com.examora.dto;

import java.util.List;

public final class AnalyticsDtos {
    private AnalyticsDtos() {
    }

    public record ExamAnalyticsSummary(
            String examId,
            String title,
            String subject,
            String status,
            int participants,
            int submissions,
            Double averageScore,
            Double medianScore,
            Integer highestScore,
            Integer lowestScore,
            Double completionRate,
            Double passRate) {
    }

    public record ScoreDistributionBucket(String range, int count) {
    }

    public record OptionDistribution(String optionId, int count) {
    }

    public record QuestionAnalytics(
            int number,
            String questionId,
            int difficulty,
            int eligibleAttempts,
            int gradedAttempts,
            int correct,
            int incorrect,
            int ungradedAnswers,
            int unanswered,
            Double accuracy,
            Double attemptRate,
            List<OptionDistribution> optionDistribution,
            List<String> flags) {
    }

    public record ExamAnalyticsDetail(
            String examId,
            String title,
            String subject,
            String status,
            int participants,
            int submissions,
            Double averageScore,
            Double medianScore,
            Integer highestScore,
            Integer lowestScore,
            Double completionRate,
            int attemptsStarted,
            int attemptsSubmitted,
            List<ScoreDistributionBucket> scoreDistribution,
            List<QuestionAnalytics> questionAnalytics) {
    }

    public record RecentExamPoint(String examId, String examTitle, String subject, String date,
                                  int score, int total, double percentage) {
    }

    public record PerExamStudentResult(String examId, String examTitle, String subject, String date,
                                       int score, int total, double percentage) {
    }

    public record ExamAttemptInfo(int attemptNumber, int durationSeconds, String submittedAt) {
    }

    public record StudentDrilldownAnalytics(
            String studentId,
            String studentName,
            int completedExams,
            Double averagePercentage,
            Integer highestScore,
            Integer lowestScore,
            Double trueAccuracy,
            List<RecentExamPoint> recentTrend,
            List<PerExamStudentResult> examHistory) {
    }

    public record SubjectPerformanceSummary(String subject, int completedExamCount, double averagePercentage) {
    }

    public record DifficultyPerformance(int difficulty, int gradedAttempts, int correct, Double accuracy) {
    }

    public record PracticeDifficultyAnalytics(int difficulty, int questions, int correct, Double accuracy) {
    }

    public record PracticeAnalytics(
            int totalSessions,
            int sessionsCompleted,
            int totalQuestions,
            int correctAnswers,
            Double accuracy,
            List<PracticeDifficultyAnalytics> accuracyByDifficulty,
            String mostRecentActivity) {
        public static PracticeAnalytics empty() {
            return new PracticeAnalytics(0, 0, 0, 0, null, List.of(), null);
        }
    }

    public record StudentAnalyticsSummary(
            int examCount,
            Double averagePercentage,
            Integer highestScore,
            Integer lowestScore,
            Integer gradedQuestions,
            Integer correctGradedQuestions,
            Double trueAccuracy,
            List<RecentExamPoint> examTrend,
            List<SubjectPerformanceSummary> subjectPerformance,
            List<DifficultyPerformance> difficultyPerformance,
            PracticeAnalytics practice) {
    }

    public record StudentExamAnalytics(
            String examId,
            String examTitle,
            String subject,
            String date,
            int score,
            int total,
            double percentage,
            ExamAttemptInfo attempt,
            PracticeAnalytics practice) {
    }

    public record OptionLabelRow(String questionId, String optionId, String text, int displayOrder) {
    }

    public record StudentPerformanceRow(
            String studentId,
            String studentName,
            boolean hasResult,
            Integer score,
            Integer total,
            Double percentage,
            String submittedAt,
            Integer durationSeconds,
            Integer attemptNumber,
            String attemptStatus,
            Boolean activeNow,
            int practiceQuestions,
            int practiceCorrect,
            Double practiceAccuracy) {
    }
}