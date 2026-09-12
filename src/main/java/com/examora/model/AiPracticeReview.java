package com.examora.model;

import java.time.Instant;

public record AiPracticeReview(String id, String sessionId, String questionId, String explanation,
                                Instant createdAt) {
}
