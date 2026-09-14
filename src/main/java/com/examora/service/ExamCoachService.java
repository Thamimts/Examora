package com.examora.service;

import com.examora.dto.ExamCoachDtos.CoachExamSummaryDto;
import com.examora.dto.ExamCoachDtos.CoachQuestionDto;
import com.examora.dto.ExamCoachDtos.CoachStatsDto;
import com.examora.dto.ExamCoachDtos.ExamCoachContextDto;
import com.examora.dto.ExamCoachDtos.ExamCoachRequestDto;
import com.examora.dto.ExamDtos.ExamResultReviewDto;
import com.examora.dto.ExamDtos.QuestionReviewDto;
import com.examora.exception.ApiException;
import com.examora.model.ExamAttempt;
import com.examora.model.Question;
import com.examora.model.Result;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.ExamAttemptRepository.SubmittedAttemptRow;
import com.examora.repository.QuestionRepository;
import com.examora.repository.ResultRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * P5D.5: Exam-Specific AI Coach.
 *
 * <p>Serves the completed-exam picker and builds a server-verified, per-action pruned
 * {@link ExamCoachContextDto} for EXACTLY ONE completed exam belonging to the authenticated
 * student. The context never crosses exam boundaries, never contains another student's data and
 * never includes answer keys except for GENERATE_SIMILAR_QUESTION. No AI happens here — the LLM
 * runs on the frontend behind the strict exam-scoped prompt.
 */
@Service
public class ExamCoachService {

    private static final Set<String> ACTIONS = Set.of(
            "EXPLAIN_PERFORMANCE", "EXPLAIN_MISTAKES", "EXPLAIN_QUESTION", "REVIEW_WEAK_AREAS",
            "SUGGEST_REVISION", "GENERATE_SIMILAR_QUESTION", "CHAT");

    /** Actions that only target one question of the selected exam. */
    private static final Set<String> QUESTION_ACTIONS = Set.of("EXPLAIN_QUESTION", "GENERATE_SIMILAR_QUESTION");
    /** Actions that receive the aggregated, exam-scoped statistics. */
    private static final Set<String> STATS_ACTIONS = Set.of(
            "EXPLAIN_PERFORMANCE", "EXPLAIN_MISTAKES", "REVIEW_WEAK_AREAS", "SUGGEST_REVISION", "CHAT");
    /** Actions that receive the wrongly-answered questions (never with answer keys). */
    private static final Set<String> MISTAKE_ACTIONS = Set.of("EXPLAIN_MISTAKES", "REVIEW_WEAK_AREAS");
    /** Actions that receive every question with its correctness (never with answer keys). */
    private static final Set<String> QUESTION_LIST_ACTIONS = Set.of("SUGGEST_REVISION", "CHAT");
    /** The only action allowed to carry the correct-answer text (mirrors the AI tutor's similar-question flow). */
    private static final Set<String> ANSWER_KEY_ACTIONS = Set.of("GENERATE_SIMILAR_QUESTION");

    private static final int EASY_MIN = 1;
    private static final int EASY_MAX = 2;
    private static final int MEDIUM_VALUE = 3;

    private final ResultRepository resultRepository;
    private final ExamAttemptRepository attemptRepository;
    private final QuestionRepository questionRepository;
    private final ExamAttemptService examAttemptService;

    public ExamCoachService(ResultRepository resultRepository, ExamAttemptRepository attemptRepository,
                            QuestionRepository questionRepository, ExamAttemptService examAttemptService) {
        this.resultRepository = resultRepository;
        this.attemptRepository = attemptRepository;
        this.questionRepository = questionRepository;
        this.examAttemptService = examAttemptService;
    }

    public void requireStudent(User user) {
        if (user == null || user.role() != Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Student access is required.");
        }
    }

    /** Completed exams of the authenticated student: latest result + latest attempt per exam, newest first. */
    public List<CoachExamSummaryDto> completedExams(User student) {
        requireStudent(student);
        Map<String, Result> latestResultByExam = new LinkedHashMap<>();
        for (Result result : resultRepository.findByUserId(student.id())) {
            latestResultByExam.putIfAbsent(result.examId(), result);
        }
        Map<String, SubmittedAttemptRow> latestAttemptByExam = new LinkedHashMap<>();
        for (SubmittedAttemptRow row : attemptRepository.findSubmittedByStudent(student.id())) {
            latestAttemptByExam.putIfAbsent(row.examId(), row);
        }
        List<CoachExamSummaryDto> summaries = new ArrayList<>();
        for (Map.Entry<String, Result> entry : latestResultByExam.entrySet()) {
            Result result = entry.getValue();
            SubmittedAttemptRow attempt = latestAttemptByExam.get(entry.getKey());
            String completedAt = attempt != null
                    ? attempt.submittedAt().toString()
                    : result.date() + "T00:00:00Z";
            int attemptNumber = attempt != null ? attempt.attemptNumber() : 1;
            summaries.add(new CoachExamSummaryDto(
                    result.examId(), result.examTitle(), result.subject(), result.score(),
                    percentage(result.score(), result.total()), completedAt, attemptNumber));
        }
        summaries.sort(Comparator.comparing(CoachExamSummaryDto::completedAt)
                .reversed()
                .thenComparing(Comparator.comparing(CoachExamSummaryDto::examId).reversed()));
        return summaries;
    }

    /** Builds the per-action pruned, server-verified context for ONE selected exam of the student. */
    public ExamCoachContextDto context(User student, String examId, ExamCoachRequestDto request) {
        requireStudent(student);
        if (examId == null || examId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exam id is required.");
        }
        if (request == null || isBlank(request.action()) || !ACTIONS.contains(request.action().toUpperCase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A valid coach action is required.");
        }
        String action = request.action().toUpperCase();
        if (QUESTION_ACTIONS.contains(action) && isBlank(request.questionId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A question id is required for this action.");
        }
        String examIdTrimmed = examId.trim();

        Result result = resultRepository.findLatestByUserIdAndExamId(student.id(), examIdTrimmed)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No completed result found for this exam."));
        ExamResultReviewDto review = examAttemptService.getStudentResultReview(examIdTrimmed, student);
        ExamAttempt attempt = attemptRepository.findLatestSubmitted(examIdTrimmed, student.id()).orElse(null);
        String completedAt = attempt != null ? attempt.submittedAt().toString() : result.date() + "T00:00:00Z";
        int attemptNumber = attempt != null ? attempt.attemptNumber() : 1;

        Map<String, Integer> difficultyByQuestion = questionRepository.findByExamId(examIdTrimmed).stream()
                .collect(Collectors.toMap(Question::id, Question::difficulty, (first, second) -> first,
                        LinkedHashMap::new));

        CoachQuestionDto question = null;
        if (QUESTION_ACTIONS.contains(action)) {
            String questionId = request.questionId().trim();
            question = review.questions().stream()
                    .filter(q -> q.questionId().equals(questionId))
                    .findFirst()
                    .map(q -> new CoachQuestionDto(q.number(), q.questionId(), q.questionText(),
                            safeOptions(q), difficultyName(difficultyByQuestion.getOrDefault(questionId, MEDIUM_VALUE)),
                            q.correct(), q.selectedOptionText()))
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                            "Question does not belong to this exam result."));
        }

        return new ExamCoachContextDto(
                result.examId(), result.examTitle(), result.subject(), completedAt,
                attemptNumber, review.score(), review.total(), review.percentage(),
                action,
                STATS_ACTIONS.contains(action) ? statsOf(review, difficultyByQuestion) : null,
                MISTAKE_ACTIONS.contains(action)
                        ? review.questions().stream()
                                .filter(q -> q.answered() && !q.correct())
                                .map(q -> toDto(q, difficultyByQuestion))
                                .toList()
                        : null,
                QUESTION_LIST_ACTIONS.contains(action)
                        ? review.questions().stream().map(q -> toDto(q, difficultyByQuestion)).toList()
                        : null,
                question,
                ANSWER_KEY_ACTIONS.contains(action) ? correctAnswerOf(review, request.questionId().trim()) : null);
    }

    private CoachStatsDto statsOf(ExamResultReviewDto review, Map<String, Integer> difficultyByQuestion) {
        int correct = 0;
        int incorrect = 0;
        int unanswered = 0;
        int easyCorrect = 0;
        int easyTotal = 0;
        int mediumCorrect = 0;
        int mediumTotal = 0;
        int hardCorrect = 0;
        int hardTotal = 0;
        for (QuestionReviewDto q : review.questions()) {
            String bucket = difficultyName(difficultyByQuestion.getOrDefault(q.questionId(), MEDIUM_VALUE));
            if ("EASY".equals(bucket)) {
                easyTotal++;
                if (q.correct()) {
                    easyCorrect++;
                }
            } else if ("MEDIUM".equals(bucket)) {
                mediumTotal++;
                if (q.correct()) {
                    mediumCorrect++;
                }
            } else {
                hardTotal++;
                if (q.correct()) {
                    hardCorrect++;
                }
            }
            if (q.correct()) {
                correct++;
            } else if (q.answered()) {
                incorrect++;
            } else {
                unanswered++;
            }
        }
        int attempted = review.total() - unanswered;
        double accuracy = attempted > 0 ? round(correct * 100.0 / attempted) : 0.0;
        return new CoachStatsDto(correct, incorrect, unanswered, accuracy,
                easyCorrect, easyTotal, mediumCorrect, mediumTotal, hardCorrect, hardTotal);
    }

    private CoachQuestionDto toDto(QuestionReviewDto q, Map<String, Integer> difficultyByQuestion) {
        return new CoachQuestionDto(q.number(), q.questionId(), q.questionText(), safeOptions(q),
                difficultyName(difficultyByQuestion.getOrDefault(q.questionId(), MEDIUM_VALUE)),
                q.correct(), q.selectedOptionText());
    }

    private List<String> safeOptions(QuestionReviewDto q) {
        return q.options() == null ? List.of() : q.options();
    }

    private String correctAnswerOf(ExamResultReviewDto review, String questionId) {
        return review.questions().stream()
                .filter(q -> q.questionId().equals(questionId))
                .findFirst()
                .map(QuestionReviewDto::correctOptionText)
                .orElse(null);
    }

    private String difficultyName(int difficulty) {
        if (difficulty >= EASY_MIN && difficulty <= EASY_MAX) {
            return "EASY";
        }
        if (difficulty == MEDIUM_VALUE) {
            return "MEDIUM";
        }
        return "HARD";
    }

    private double percentage(int score, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return round(score * 100.0 / total);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}