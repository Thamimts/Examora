package com.examora.dto;

import java.util.List;

/**
 * P5D.5: Exam-Specific AI Coach DTOs. Every payload is strictly scoped to ONE selected completed
 * exam of the authenticated student. No cross-exam or global analytics are ever included.
 */
public final class ExamCoachDtos {
    private ExamCoachDtos() {
    }

    /** Safe summary of one completed exam, as shown in the coach's exam picker. */
    public record CoachExamSummaryDto(String examId, String title, String subject,
                                      int score, double percentage, String completedAt, int attemptNumber) {
    }

    public record ExamCoachRequestDto(String action, String questionId) {
    }

    /** Exam-scoped aggregate stats (no question text, no answer keys). */
    public record CoachStatsDto(int correct, int incorrect, int unanswered, double accuracy,
                                int easyCorrect, int easyTotal,
                                int mediumCorrect, int mediumTotal,
                                int hardCorrect, int hardTotal) {
    }

    /** One exam question with correctness, but never the correct-answer text. */
    public record CoachQuestionDto(int number, String questionId, String text, List<String> options,
                                   String difficultyName, boolean correct, String selectedOptionText) {
    }

    /**
     * The server-verified, per-action pruned context handed to the LLM for a single exam.
     * Only the fields relevant to {@code action} are populated; answer keys are never included
     * except for GENERATE_SIMILAR_QUESTION ({@code correctAnswer}).
     */
    public record ExamCoachContextDto(String examId, String examTitle, String subject, String completedAt,
                                      int attemptNumber, int score, int total, double percentage,
                                      String action, CoachStatsDto stats,
                                      List<CoachQuestionDto> mistakes,
                                      List<CoachQuestionDto> questions,
                                      CoachQuestionDto question,
                                      String correctAnswer) {
    }
}