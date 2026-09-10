package com.examora.model;

import java.time.Instant;

public record PracticeSession(String id, String studentId, String examId, String status,
                              int targetQuestionCount, double workingDifficulty, String inProgressQuestionId,
                              int answeredCount, int correctCount, Instant startedAt,
                              Instant lastActivityAt, Instant completedAt) {
}