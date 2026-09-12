package com.examora.model;

import java.math.BigDecimal;
import java.time.Instant;

public record AiPracticeSession(String id, String studentId, String topic, String difficulty, String status,
                                 int questionCount, int answeredCount, int correctCount,
                                 BigDecimal score, BigDecimal percentage,
                                 Integer minQuestionCount, BigDecimal minAccuracy, boolean completionMet,
                                 Instant startedAt, Instant completedAt) {
}