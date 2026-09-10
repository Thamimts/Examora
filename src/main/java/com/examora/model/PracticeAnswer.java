package com.examora.model;

import java.time.Instant;

public record PracticeAnswer(String id, String sessionId, String questionId, String optionId,
                             String answerValue, boolean correct, int difficulty, int sequenceIndex,
                             Instant answeredAt) {
}