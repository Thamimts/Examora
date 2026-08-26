package com.examora.model;

public record Answer(String id, String userId, String examId, String questionId, String optionId, String value) {
}
