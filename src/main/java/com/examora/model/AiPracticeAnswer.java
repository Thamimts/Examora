package com.examora.model;

import java.time.Instant;

public record AiPracticeAnswer(String id, String sessionId, String questionId, String optionId,
                                String selectedOption, Boolean correct, Instant answeredAt) {
}
