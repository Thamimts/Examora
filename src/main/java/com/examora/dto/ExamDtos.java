package com.examora.dto;

import com.examora.model.Exam;
import com.examora.model.Result;
import java.util.List;

public final class ExamDtos {
    private ExamDtos() {
    }

    public record StartExamResponse(String examId, String studentId, String status, Exam exam,
                                    String attemptId, String startedAt, String expiresAt, String endAt) {
    }

    public record ExamSubmissionRequest(List<SubmittedAnswer> answers) {
    }

    public record SubmittedAnswer(String questionId, String optionId, String value) {
    }

    public record ExamSubmissionResponse(Result result, int score, int total, double percentage) {
    }
}
