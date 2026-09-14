package com.examora.dto;

import java.util.List;

public final class LearningIntelligenceDtos {
    private LearningIntelligenceDtos() {
    }

    public enum Dimension {
        EASY_DIFFICULTY,
        MEDIUM_DIFFICULTY,
        HARD_DIFFICULTY,
        EXAM_PERFORMANCE,
        PRACTICE_PERFORMANCE,
        RECENT_PERFORMANCE,
        CONSISTENCY
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum Confidence {
        INSUFFICIENT_DATA,
        LOW_CONFIDENCE,
        MEDIUM_CONFIDENCE,
        HIGH_CONFIDENCE
    }

    public enum PriorityLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum RecommendationCode {
        REINFORCE_BASICS,
        PRACTICE_MEDIUM_QUESTIONS,
        PRACTICE_HARD_QUESTIONS,
        REVIEW_RECENT_EXAMS,
        INCREASE_PRACTICE,
        COMPLETE_REGULAR_PRACTICE
    }

    public record LearningIntelligence(
            List<Strength> strengths,
            List<Weakness> weaknesses,
            List<Priority> priorities,
            List<Recommendation> recommendations,
            DataQuality dataQuality) {
    }

    public record Strength(Dimension dimension, double accuracy, int observations,
                           Confidence confidence, String signal) {
    }

    public record Weakness(Dimension dimension, double accuracy, int observations,
                           Severity severity, Confidence confidence, String signal) {
    }

    public record Priority(Dimension dimension, PriorityLevel priorityLevel,
                           String reason, RecommendationCode recommendedAction) {
    }

    public record Recommendation(RecommendationCode code) {
    }

    public record DataQuality(Double overallAccuracy, int observationCount,
                              int strengthCount, int weaknessCount, boolean sufficientData) {
    }
}