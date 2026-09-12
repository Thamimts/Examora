package com.examora.dto;

import java.util.List;

public final class AiTutorDtos {
    private AiTutorDtos() {
    }

    public record AiTutorRequest(String sessionId, String questionId, String action) {
    }

    public record AiTutorOptionDto(String id, String text) {
    }

    public record AiTutorContextDto(String questionId, String question, String topic, String difficulty,
                                    List<AiTutorOptionDto> options, String yourAnswer, String correctAnswer,
                                    boolean correct, boolean answered) {
    }

    public record AiTutorQuestionCreateRequest(String sessionId, String sourceQuestionId, String kind,
                                               String question, List<String> options, int difficulty,
                                               String hint, String correctAnswer) {
    }

    public record AiTutorQuestionDto(String questionId, String question, List<AiTutorOptionDto> options,
                                     int difficulty, String hint) {
    }

    public record AiTutorQuestionAnswerRequest(String optionId) {
    }

    public record AiTutorQuestionAnswerResponse(String questionId, boolean correct, String correctAnswer) {
    }
}