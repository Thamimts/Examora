package com.examora.dto;

import com.examora.dto.LearningIntelligenceDtos.Dimension;
import java.util.List;

public final class StudentProgressDtos {
    private StudentProgressDtos() {
    }

    public record StudentProgress(
            ProgressTrend overall,
            ExamProgress examProgress,
            PracticeProgress practiceProgress,
            DifficultyProgress difficultyProgress,
            List<IntelligenceChange> intelligenceChanges,
            List<Milestone> milestones,
            ProgressDataQuality dataQuality) {
    }

    public record ProgressTrend(String direction, Double recentAverage, Double previousAverage, Double delta) {
    }

    public record ExamProgress(String direction, Double recentAverage, Double previousAverage, Double delta,
                               List<ExamHistoryPoint> history) {
    }

    public record ExamHistoryPoint(String examId, String examTitle, String subject, String date,
                                   int score, int total, Double percentage, Double accuracy, Integer attemptNumber) {
    }

    public record PracticeProgress(String direction, Double recentAccuracy, Double previousAccuracy, Double delta,
                                   int recentQuestions, int previousQuestions) {
    }

    public record DifficultyProgress(DifficultyProgression easy, DifficultyProgression medium, DifficultyProgression hard) {
    }

    public record DifficultyProgression(String direction, Double recentAccuracy, Double previousAccuracy,
                                        Double delta, int recentObservations, int previousObservations) {
    }

    public record IntelligenceChange(String type, Dimension dimension, Double previousAccuracy,
                                     Double currentAccuracy, String detail) {
    }

    public record Milestone(String code, String occurredAt, Double metric) {
    }

    public record ProgressDataQuality(boolean sufficientForTrend, int completedExamCount, int practiceObservationCount) {
    }
}