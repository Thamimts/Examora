package com.examora.model;

import java.time.Instant;

public record AiGeneratedQuestion(String id, String sessionId, String questionText, String correctOption,
                                   String explanation, String topic, String difficulty, int orderIndex,
                                   Instant createdAt) {
}
