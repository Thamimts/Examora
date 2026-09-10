package com.examora.service;

import com.examora.dto.AdaptivePracticeDtos.PracticeAnsweredItem;
import com.examora.dto.AdaptivePracticeDtos.PracticeAnswerRequest;
import com.examora.dto.AdaptivePracticeDtos.PracticeAnswerResponse;
import com.examora.dto.AdaptivePracticeDtos.PracticeOptionDto;
import com.examora.dto.AdaptivePracticeDtos.PracticeQuestionDto;
import com.examora.dto.AdaptivePracticeDtos.PracticeSessionDto;
import com.examora.dto.AdaptivePracticeDtos.PracticeSummaryDto;
import com.examora.dto.StudentPerformanceAnalytics;
import com.examora.dto.StudentPerformanceAnalytics.SubjectPerformance;
import com.examora.exception.ApiException;
import com.examora.model.Exam;
import com.examora.model.PracticeAnswer;
import com.examora.model.PracticeSession;
import com.examora.model.Question;
import com.examora.model.QuestionOption;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ExamRepository;
import com.examora.repository.PracticeAnswerRepository;
import com.examora.repository.PracticeSessionRepository;
import com.examora.repository.QuestionOptionRepository;
import com.examora.repository.QuestionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdaptivePracticeService {

    private static final int DEFAULT_TARGET_QUESTION_COUNT = 10;
    private static final int MIN_TARGET_QUESTION_COUNT = 5;
    private static final int MAX_TARGET_QUESTION_COUNT = 20;
    private static final long IDLE_MILLIS = 24L * 60 * 60 * 1000;

    private final PracticeSessionRepository sessionRepository;
    private final PracticeAnswerRepository answerRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final ExamRepository examRepository;
    private final StudentAnalyticsService analyticsService;
    private final AdaptiveSelectionService selection;
    private final QuestionGradingService gradingService;
    private final ActivityService activityService;

    public AdaptivePracticeService(PracticeSessionRepository sessionRepository,
                                   PracticeAnswerRepository answerRepository,
                                   QuestionRepository questionRepository,
                                   QuestionOptionRepository optionRepository,
                                   ExamRepository examRepository,
                                   StudentAnalyticsService analyticsService,
                                   AdaptiveSelectionService selection,
                                   QuestionGradingService gradingService,
                                   ActivityService activityService) {
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.examRepository = examRepository;
        this.analyticsService = analyticsService;
        this.selection = selection;
        this.gradingService = gradingService;
        this.activityService = activityService;
    }

    public record SessionResult(PracticeSessionDto dto, boolean created) {
    }

    public SessionResult resumeOrCreate(String examId, User student) {
        requireStudent(student);
        Exam exam = requireAvailableExam(examId);
        requireNonEmptyBank(exam);
        Instant now = Instant.now();
        PracticeSession active = sessionRepository.findActive(student.id(), exam.id()).orElse(null);
        if (active != null) {
            Instant lastActivity = active.lastActivityAt();
            boolean fresh = lastActivity != null && now.toEpochMilli() - lastActivity.toEpochMilli() <= IDLE_MILLIS;
            if (fresh) {
                return new SessionResult(toDto(active, exam), false);
            }
            sessionRepository.expire(active.id(), now);
        }
        PracticeSession created = createSession(exam, student, requireNonEmptyBank(exam), DEFAULT_TARGET_QUESTION_COUNT, now);
        return new SessionResult(toDto(created, exam), true);
    }

    public PracticeSessionDto startNew(String examId, User student, Integer requestedTarget) {
        requireStudent(student);
        Exam exam = requireAvailableExam(examId);
        List<Question> bank = requireNonEmptyBank(exam);
        Instant now = Instant.now();
        sessionRepository.expireActiveForExam(student.id(), exam.id(), now);
        int target = requestedTarget == null
                ? DEFAULT_TARGET_QUESTION_COUNT
                : Math.max(MIN_TARGET_QUESTION_COUNT, Math.min(MAX_TARGET_QUESTION_COUNT, requestedTarget));
        PracticeSession created = createSession(exam, student, bank, target, now);
        return toDto(created, exam);
    }

    public PracticeSessionDto getSession(String sessionId, User student) {
        requireStudent(student);
        PracticeSession session = requireOwned(sessionId, student);
        Exam exam = requireAvailableExam(session.examId());
        return toDto(session, exam);
    }

    @Transactional
    public PracticeAnswerResponse submitAnswer(String sessionId, User student, PracticeAnswerRequest request) {
        requireStudent(student);
        PracticeSession session = requireOwned(sessionId, student);
        if (!"STARTED".equals(session.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "This practice session is not active.");
        }
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Question id is required.");
        }
        String questionId = request.questionId();
        if (questionId == null || questionId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Question id is required.");
        }
        if (!questionId.equals(session.inProgressQuestionId())) {
            throw new ApiException(HttpStatus.CONFLICT, "You can only answer the current question.");
        }
        String optionId = blankToNull(request.optionId());
        if (optionId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "An option selection is required.");
        }

        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Question not found."));
        QuestionGradingService.GradeResult grade = gradingService.grade(question, optionId, null);
        String correctOptionText = optionRepository.findByQuestionId(question.id()).stream()
                .filter(QuestionOption::correctAnswer)
                .map(QuestionOption::text)
                .findFirst()
                .orElse(null);

        Instant now = Instant.now();
        PracticeAnswer answer = new PracticeAnswer(UUID.randomUUID().toString(), session.id(), question.id(),
                optionId, grade.value(), grade.correct(), question.difficulty(), session.answeredCount(), now);
        try {
            answerRepository.create(answer);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "This question has already been answered.");
        }

        int answeredCount = session.answeredCount() + 1;
        int correctCount = session.correctCount() + (grade.correct() ? 1 : 0);
        double workingLevel = selection.updateWorkingLevel(session.workingDifficulty(), grade.correct());
        int level = selection.level(workingLevel);
        boolean completed = answeredCount >= session.targetQuestionCount();

        PracticeQuestionDto next = null;
        if (!completed) {
            Set<String> answeredIds = answerRepository.findBySessionId(session.id()).stream()
                    .map(PracticeAnswer::questionId)
                    .collect(Collectors.toSet());
            List<Question> remaining = questionRepository.findByExamId(session.examId()).stream()
                    .filter(candidate -> !answeredIds.contains(candidate.id()))
                    .toList();
            if (remaining.isEmpty()) {
                completed = true;
            } else {
                next = selection.selectNext(remaining, workingLevel).map(this::toQuestionDto).orElse(null);
            }
        }

        if (completed) {
            sessionRepository.complete(session.id(), answeredCount, correctCount, now);
            activityService.student(student, "PRACTICE_COMPLETED",
                    "You finished a " + answeredCount + "-question practice session and got " + correctCount + " right.");
            PracticeSummaryDto summary = buildSummary(session, correctCount, answeredCount);
            return new PracticeAnswerResponse(grade.correct(), correctOptionText, level, workingLevel,
                    answeredCount, correctCount, null, true, summary);
        }

        sessionRepository.advanceProgress(session.id(), next.id(), answeredCount, correctCount, workingLevel, now);
        return new PracticeAnswerResponse(grade.correct(), correctOptionText, level, workingLevel,
                answeredCount, correctCount, next, false, null);
    }

    private PracticeSession createSession(Exam exam, User student, List<Question> bank, int target, Instant now) {
        StudentPerformanceAnalytics analytics = analyticsService.getPerformance(student.id());
        Double subjectAccuracy = analytics.subjectPerformance().stream()
                .filter(perf -> exam.subject() != null && exam.subject().equalsIgnoreCase(perf.subject()))
                .map(SubjectPerformance::accuracy)
                .findFirst()
                .orElse(null);
        Double overallAccuracy = analytics.completedExamCount() > 0 ? analytics.accuracy() : null;
        double workingLevel = selection.seedWorkingLevel(subjectAccuracy, overallAccuracy);
        Question first = selection.selectNext(bank, workingLevel).orElse(null);
        PracticeSession session = new PracticeSession(UUID.randomUUID().toString(), student.id(), exam.id(),
                "STARTED", target, workingLevel, first == null ? null : first.id(),
                0, 0, now, now, null);
        return sessionRepository.create(session);
    }

    private PracticeSummaryDto buildSummary(PracticeSession session, int correctCount, int answeredCount) {
        List<PracticeAnswer> answers = answerRepository.findBySessionId(session.id());
        List<SubjectPerformance> subjectPerformance = analyticsService.getPerformance(session.studentId()).subjectPerformance();
        Exam exam = requireAvailableExam(session.examId());
        return selection.summarize(correctCount, answeredCount, answers, exam.subject(), subjectPerformance);
    }

    private PracticeSessionDto toDto(PracticeSession session, Exam exam) {
        List<PracticeAnswer> answers = answerRepository.findBySessionId(session.id());
        boolean completed = "COMPLETED".equals(session.status());
        PracticeQuestionDto current = null;
        if (!completed && session.inProgressQuestionId() != null) {
            current = questionRepository.findById(session.inProgressQuestionId()).map(this::toQuestionDto).orElse(null);
        }
        PracticeSummaryDto summary = completed ? buildSummary(session, session.correctCount(), session.answeredCount()) : null;
        return new PracticeSessionDto(session.id(), exam.id(), exam.title(), exam.subject(), session.status(),
                session.targetQuestionCount(), session.answeredCount(), session.correctCount(),
                session.workingDifficulty(), selection.level(session.workingDifficulty()), current,
                buildHistory(answers), summary);
    }

    private PracticeQuestionDto toQuestionDto(Question question) {
        List<PracticeOptionDto> options = optionRepository.findByQuestionId(question.id()).stream()
                .sorted(Comparator.comparingInt(QuestionOption::displayOrder).thenComparing(QuestionOption::id))
                .map(option -> new PracticeOptionDto(option.id(), option.text()))
                .toList();
        return new PracticeQuestionDto(question.id(), question.text(), options, question.difficulty());
    }

    private List<PracticeAnsweredItem> buildHistory(List<PracticeAnswer> answers) {
        List<PracticeAnsweredItem> items = new ArrayList<>();
        if (answers.isEmpty()) {
            return items;
        }
        for (PracticeAnswer answer : answers) {
            Question question = questionRepository.findById(answer.questionId()).orElse(null);
            if (question == null) {
                continue;
            }
            List<QuestionOption> options = optionRepository.findByQuestionId(answer.questionId());
            String yourOption = options.stream()
                    .filter(option -> answer.optionId() != null && answer.optionId().equals(option.id()))
                    .map(QuestionOption::text)
                    .findFirst()
                    .orElse(answer.answerValue());
            String correctOptionText = options.stream()
                    .filter(QuestionOption::correctAnswer)
                    .map(QuestionOption::text)
                    .findFirst()
                    .orElse(null);
            items.add(new PracticeAnsweredItem(answer.questionId(), question.text(), yourOption, answer.correct(),
                    correctOptionText));
        }
        return items;
    }

    private PracticeSession requireOwned(String sessionId, User student) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Session id is required.");
        }
        PracticeSession session = sessionRepository.findById(sessionId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Practice session not found."));
        if (!session.studentId().equals(student.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This session belongs to another student.");
        }
        return session;
    }

    private Exam requireAvailableExam(String examId) {
        if (examId == null || examId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exam id is required.");
        }
        Exam exam = examRepository.findById(examId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found."));
        if ("DRAFT".equalsIgnoreCase(exam.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This exam is not available to students.");
        }
        return exam;
    }

    private List<Question> requireNonEmptyBank(Exam exam) {
        List<Question> bank = questionRepository.findByExamId(exam.id());
        if (bank.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "This exam has no questions.");
        }
        return bank;
    }

    private void requireStudent(User user) {
        if (user == null || user.role() != Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Student access is required.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}