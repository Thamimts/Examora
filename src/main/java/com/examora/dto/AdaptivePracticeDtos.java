package com.examora.dto;

import java.util.List;

public final class AdaptivePracticeDtos {
    private AdaptivePracticeDtos() {
    }

    public record PracticeStartRequest(Integer targetQuestionCount) {
    }

    public record PracticeAnswerRequest(String questionId, String optionId) {
    }

    public record PracticeOptionDto(String id, String text) {
    }

    public record PracticeQuestionDto(String id, String text, List<PracticeOptionDto> options, int difficulty) {
    }

    public record PracticeAnsweredItem(String questionId, String text, String yourOption, boolean correct,
                                       String correctOptionText) {
    }

    public record PracticeDifficultyStat(int difficulty, int answered, int correct, double accuracy) {
    }

    public record PracticeSummaryDto(int correctCount, int totalAnswered, double accuracy,
                                     List<PracticeDifficultyStat> perDifficulty,
                                     List<String> weakAreas, List<String> strongAreas, String subjectFocus) {
    }

    public record PracticeSessionDto(String id, String examId, String examTitle, String subject, String status,
                                     int targetQuestionCount, int answeredCount, int correctCount,
                                     double workingDifficulty, int level,
                                     PracticeQuestionDto currentQuestion,
                                     List<PracticeAnsweredItem> answeredHistory,
                                     PracticeSummaryDto summary) {
    }

    public record PracticeAnswerResponse(boolean correct, String correctOptionText, int level,
                                         double workingDifficulty, int answeredCount, int correctCount,
                                         PracticeQuestionDto next, boolean completed, PracticeSummaryDto summary) {
    }
}