package com.examora.model;

import java.time.Instant;

public record AiTutorQuestion(String id, String studentId, String sessionId, String sourceQuestionId,
                              String kind, String questionText, String options, int difficulty,
                              String hint, String correctAnswer, boolean answered, Boolean correct,
                              Instant answeredAt, Instant createdAt) {
}