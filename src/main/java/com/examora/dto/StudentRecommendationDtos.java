package com.examora.dto;

import com.examora.dto.LearningIntelligenceDtos.Confidence;
import com.examora.dto.LearningIntelligenceDtos.Dimension;
import com.examora.dto.LearningIntelligenceDtos.PriorityLevel;
import java.util.List;

public final class StudentRecommendationDtos {
    private StudentRecommendationDtos() {
    }

    public enum RecommendationCode {
        PRACTICE_EASY_QUESTIONS,
        PRACTICE_MEDIUM_QUESTIONS,
        PRACTICE_HARD_QUESTIONS,
        REVIEW_RECENT_EXAMS,
        INCREASE_PRACTICE,
        MAINTAIN_STRENGTH,
        BUILD_BASELINE
    }

    public enum Action {
        PRACTICE,
        REVIEW,
        MAINTENANCE,
        BASELINE
    }

    public record StudentRecommendations(
            List<Recommendation> recommendations,
            RecommendationSummary summary,
            String generatedAt) {
    }

    public record Recommendation(
            RecommendationCode code,
            PriorityLevel priority,
            Action action,
            Dimension dimension,
            String difficulty,
            String reasonCode,
            Confidence confidence,
            Integer targetQuestionCount,
            SupportingMetrics supportingMetrics) {
    }

    public record RecommendationSummary(
            String primaryRecommendation,
            int recommendationCount,
            boolean dataSufficient) {
    }

    public record SupportingMetrics(
            Double accuracy,
            Integer observations,
            Double recentAccuracy,
            Double previousAccuracy,
            Boolean recentlyPracticed) {
    }
}