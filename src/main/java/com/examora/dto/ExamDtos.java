package com.examora.dto;

import com.examora.model.Exam;
import com.examora.model.Result;
import java.util.List;
import java.util.Map;

public final class ExamDtos {
    private ExamDtos() {
    }

    public record StartExamResponse(String examId, String studentId, String status, Exam exam,
                                    String attemptId, String startedAt, String expiresAt, String endAt) {
    }

    public record SaveAnswerRequest(String value) {
    }

    public record AttemptProgressDto(String attemptId, String examId, String status, String startedAt,
                                     String expiresAt, long remainingSeconds, Map<String, String> answers) {
    }

    public record ExamSubmissionRequest(List<SubmittedAnswer> answers) {
    }

    public record SubmittedAnswer(String questionId, String optionId, String value) {
    }

    public record ExamSubmissionResponse(Result result, int score, int total, double percentage) {
    }

    public record ActiveAttemptDto(String attemptId, String examId, String examTitle,
                                   String subject, int duration, String status,
                                   String startedAt, String expiresAt, long remainingSeconds) {
    }

    public record ExamResultReviewDto(String resultId, String examId, String examTitle, String subject,
                                      String date, int score, int total, double percentage,
                                      int correctCount, int incorrectCount, int unansweredCount,
                                      List<QuestionReviewDto> questions) {
    }

    public record QuestionReviewDto(int number, String questionId, String questionText, List<String> options,
                                    String selectedOptionText, String correctOptionText,
                                    boolean answered, boolean correct) {
    }
}
