package com.examora.model;

public record QuestionOption(String id, String questionId, String text, int displayOrder, boolean correctAnswer) {
}
