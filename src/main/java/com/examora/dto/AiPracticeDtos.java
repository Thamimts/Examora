package com.examora.dto;

import java.util.List;

public final class AiPracticeDtos {
    private AiPracticeDtos() {
    }

    public record AiPracticeQuestionInput(String question, List<String> options, String correctOption,
                                          String explanation) {
    }

    public record AiPracticeCreateRequest(String topic, String difficulty,
                                          List<AiPracticeQuestionInput> questions,
                                          Integer minimumQuestions, Double minimumAccuracy) {
    }

    public record AiDifficultyProgressDto(int attempted, int completed, Double bestAccuracy) {
    }

    public record AiPracticeProgressDto(String topic, String currentDifficulty, String unlockedDifficulty, String status,
                                        AiDifficultyProgressDto easy, AiDifficultyProgressDto medium,
                                        AiDifficultyProgressDto hard) {
    }

    public record AiPracticeOptionDto(String id, String text) {
    }

    public record AiPracticeQuestionDto(String id, String question, List<AiPracticeOptionDto> options,
                                        String difficulty, String selectedOptionId) {
    }

    public record AiPracticeSessionDto(String sessionId, String topic, String difficulty, String status,
                                       int questionCount, int answeredCount,
                                       String startedAt, String completedAt,
                                       List<AiPracticeQuestionDto> questions) {
    }

    public record AiPracticeSessionSummaryDto(String sessionId, String topic, String difficulty, String status,
                                              int questionCount, int answeredCount, int correctCount,
                                              Double percentage, String startedAt, String completedAt) {
    }

    public record AiPracticeAnswerRequest(String questionId, String optionId) {
    }

    public record AiPracticeAnswerResponse(int answeredCount, int questionCount) {
    }

    public record AiPracticeSubmitResponse(String sessionId, String status, int correctCount, int incorrectCount,
                                           int unansweredCount, int totalCount, double percentage,
                                           boolean completionMet) {
    }

    public record AiPracticeReviewQuestionDto(String id, String question, String yourAnswer, String correctAnswer,
                                              boolean correct, boolean answered, String explanation) {
    }

    public record AiPracticeReviewDto(String sessionId, String topic, String difficulty,
                                      int correctCount, int incorrectCount, int unansweredCount, int totalCount,
                                      double percentage, boolean reviewCompleted,
                                      boolean completionMet, String topicStatus, String currentDifficulty,
                                      String unlockedDifficulty, Integer minimumQuestions, Double minimumAccuracy,
                                      List<AiPracticeReviewQuestionDto> questions) {
    }

    public record AiPracticeExplanationInput(String questionId, String explanation) {
    }

    public record AiPracticeExplanationRequest(List<AiPracticeExplanationInput> explanations) {
    }
}