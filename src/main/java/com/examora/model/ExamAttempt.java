package com.examora.model;

import java.time.Instant;

public record ExamAttempt(String id, String examId, String studentId, int attemptNumber, String status,
                          Instant startedAt, Instant expiresAt, Instant submittedAt, int version) {
}
