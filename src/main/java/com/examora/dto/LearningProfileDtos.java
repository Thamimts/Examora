package com.examora.dto;

import java.util.List;

public final class LearningProfileDtos {
    private LearningProfileDtos() {
    }

    public record LearningProfile(
            Overall overall,
            ExamPerformance examPerformance,
            Practice practice,
            AiPractice aiPractice,
            Difficulty difficulty,
            Trend trend,
            LearningSignals learningSignals) {
    }

    public record Overall(
            Double averageScore,
            Integer highestScore,
            Integer lowestScore,
            int completedExams,
            int submittedAttempts) {
    }

    public record ExamPerformance(
            Double averageAccuracy,
            Double recentAccuracy,
            Double improvementDelta) {
    }

    public record Practice(
            int sessionsCompleted,
            int questionsAnswered,
            int correctAnswers,
            Double accuracy,
            Double recentAccuracy) {
    }

    public record AiPractice(
            int sessionsCompleted,
            int questionsAnswered,
            int correctAnswers,
            Double accuracy) {
    }

    public record Difficulty(DifficultyBucket exam, DifficultyBucket practice) {
    }

    public record DifficultyBucket(DifficultyLevel easy, DifficultyLevel medium, DifficultyLevel hard) {
    }

    public record DifficultyLevel(int attempted, int correct, Double accuracy) {
    }

    public record Trend(String direction, Double delta) {
    }

    public record LearningSignals(List<String> strengths, List<String> weaknesses, String consistency) {
    }
}