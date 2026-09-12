package com.examora.service;

import com.examora.dto.AiPracticeDtos.AiDifficultyProgressDto;
import com.examora.dto.AiPracticeDtos.AiPracticeAnswerRequest;
import com.examora.dto.AiPracticeDtos.AiPracticeAnswerResponse;
import com.examora.dto.AiPracticeDtos.AiPracticeCreateRequest;
import com.examora.dto.AiPracticeDtos.AiPracticeExplanationRequest;
import com.examora.dto.AiPracticeDtos.AiPracticeOptionDto;
import com.examora.dto.AiPracticeDtos.AiPracticeProgressDto;
import com.examora.dto.AiPracticeDtos.AiPracticeQuestionDto;
import com.examora.dto.AiPracticeDtos.AiPracticeQuestionInput;
import com.examora.dto.AiPracticeDtos.AiPracticeReviewDto;
import com.examora.dto.AiPracticeDtos.AiPracticeReviewQuestionDto;
import com.examora.dto.AiPracticeDtos.AiPracticeSessionDto;
import com.examora.dto.AiPracticeDtos.AiPracticeSessionSummaryDto;
import com.examora.dto.AiPracticeDtos.AiPracticeSubmitResponse;
import com.examora.exception.ApiException;
import com.examora.model.AiGeneratedQuestion;
import com.examora.model.AiPracticeAnswer;
import com.examora.model.AiPracticeReview;
import com.examora.model.AiPracticeSession;
import com.examora.model.AiQuestionOption;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.AiGeneratedQuestionRepository;
import com.examora.repository.AiPracticeAnswerRepository;
import com.examora.repository.AiPracticeReviewRepository;
import com.examora.repository.AiPracticeSessionRepository;
import com.examora.repository.AiPracticeSessionRepository.ProgressRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AiPracticeService {
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private static final List<String> DIFFICULTY_ORDER = List.of("EASY", "MEDIUM", "HARD");
    private static final int MAX_QUESTIONS = 20;
    private static final int MIN_QUESTIONS = 1;
    private static final int MAX_TOPIC_LENGTH = 100;
    private static final int MAX_QUESTION_LENGTH = 1000;
    private static final int MAX_OPTION_LENGTH = 300;
    private static final int MAX_EXPLANATION_LENGTH = 2000;
    private static final int REQUIRED_OPTION_COUNT = 4;
    private static final double MAX_ACCURACY = 100.0;

    private final AiPracticeSessionRepository sessionRepo;
    private final AiGeneratedQuestionRepository questionRepo;
    private final AiPracticeAnswerRepository answerRepo;
    private final AiPracticeReviewRepository reviewRepo;

    public AiPracticeService(AiPracticeSessionRepository sessionRepo, AiGeneratedQuestionRepository questionRepo,
                             AiPracticeAnswerRepository answerRepo, AiPracticeReviewRepository reviewRepo) {
        this.sessionRepo = sessionRepo;
        this.questionRepo = questionRepo;
        this.answerRepo = answerRepo;
        this.reviewRepo = reviewRepo;
    }

    private void requireStudent(User user) {
        if (user == null || user.role() != Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Student access is required.");
        }
    }

    private AiPracticeSession requireOwnedSession(String sessionId, User student) {
        AiPracticeSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Practice session not found."));
        if (!session.studentId().equals(student.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This practice session belongs to another student.");
        }
        return session;
    }

    private Map<String, List<AiQuestionOption>> optionsByQuestion(List<AiGeneratedQuestion> questions) {
        if (questions.isEmpty()) return java.util.Map.of();
        List<AiQuestionOption> options = questionRepo.findOptionsByQuestionIds(
                questions.stream().map(AiGeneratedQuestion::id).toList());
        return options.stream().collect(Collectors.groupingBy(AiQuestionOption::questionId));
    }

    public AiPracticeSessionDto createSession(User student, AiPracticeCreateRequest request) {
        requireStudent(student);
        validateCreateRequest(request);

        String topic = request.topic().trim();
        String difficulty = request.difficulty().toUpperCase();
        requireUnlockedDifficulty(student, topic, difficulty);
        int count = request.questions().size();
        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Integer minQuestions = request.minimumQuestions();
        BigDecimal minAccuracy = request.minimumAccuracy() == null
                ? null : BigDecimal.valueOf(request.minimumAccuracy()).setScale(2, RoundingMode.HALF_UP);

        sessionRepo.create(new AiPracticeSession(sessionId, student.id(), topic, difficulty, "ACTIVE",
                count, 0, 0, null, null, minQuestions, minAccuracy, false, now, null));

        for (int i = 0; i < count; i++) {
            AiPracticeQuestionInput input = request.questions().get(i);
            String questionId = UUID.randomUUID().toString();
            questionRepo.create(new AiGeneratedQuestion(questionId, sessionId,
                    input.question().trim(), input.correctOption().trim(), input.explanation().trim(),
                    topic, difficulty, i + 1, now));
            List<String> options = input.options().stream().map(String::trim).toList();
            for (int j = 0; j < options.size(); j++) {
                questionRepo.createOption(new AiQuestionOption(UUID.randomUUID().toString(), questionId,
                        options.get(j), j + 1));
            }
        }

        List<AiGeneratedQuestion> created = questionRepo.findBySessionId(sessionId);
        return toSessionDto(sessionRepo.findById(sessionId).orElseThrow(), created,
                optionsByQuestion(created), java.util.Map.of());
    }

    private void validateCreateRequest(AiPracticeCreateRequest request) {
        if (request.topic() == null || request.topic().trim().isEmpty() || request.topic().trim().length() > MAX_TOPIC_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Practice topic is required and must be at most " + MAX_TOPIC_LENGTH + " characters.");
        }
        if (request.difficulty() == null || !DIFFICULTIES.contains(request.difficulty().toUpperCase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Difficulty must be one of EASY, MEDIUM or HARD.");
        }
        if (request.questions() == null || request.questions().isEmpty()
                || request.questions().size() > MAX_QUESTIONS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A practice must contain between " + MIN_QUESTIONS + " and " + MAX_QUESTIONS + " questions.");
        }
        if (request.minimumQuestions() != null
                && (request.minimumQuestions() < MIN_QUESTIONS || request.minimumQuestions() > MAX_QUESTIONS)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Completion requires between " + MIN_QUESTIONS + " and " + MAX_QUESTIONS + " questions.");
        }
        if (request.minimumAccuracy() != null
                && (request.minimumAccuracy() <= 0 || request.minimumAccuracy() > MAX_ACCURACY)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Completion accuracy must be greater than 0 and at most " + MAX_ACCURACY + "%.");
        }
        for (AiPracticeQuestionInput input : request.questions()) {
            validateQuestionInput(input);
        }
    }

    private void requireUnlockedDifficulty(User student, String topic, String difficulty) {
        List<ProgressRow> rows = sessionRepo.findProgressForStudentAndTopic(student.id(), topic);
        Set<String> completed = rows.stream()
                .filter(row -> row.completed() > 0)
                .map(ProgressRow::difficulty)
                .collect(Collectors.toSet());
        if (completed.size() == DIFFICULTY_ORDER.size()) {
            return;
        }
        Set<String> allowed = new HashSet<>(completed);
        DIFFICULTY_ORDER.stream()
                .filter(level -> !completed.contains(level))
                .findFirst()
                .ifPresent(allowed::add);
        if (!allowed.contains(difficulty)) {
            String next = DIFFICULTY_ORDER.stream()
                    .filter(level -> !completed.contains(level))
                    .findFirst()
                    .orElse("EASY");
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Difficulty " + difficulty + " is not unlocked yet for " + topic + ". Practice " + next + " to unlock it.");
        }
    }

    private void validateQuestionInput(AiPracticeQuestionInput input) {
        if (input.question() == null || input.question().trim().isEmpty()
                || input.question().trim().length() > MAX_QUESTION_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Each question must be provided and at most " + MAX_QUESTION_LENGTH + " characters.");
        }
        if (input.options() == null || input.options().size() != REQUIRED_OPTION_COUNT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Each question requires exactly " + REQUIRED_OPTION_COUNT + " options.");
        }
        Set<String> unique = new HashSet<>();
        for (String option : input.options()) {
            if (option == null || option.trim().isEmpty() || option.trim().length() > MAX_OPTION_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Each option must be provided and at most " + MAX_OPTION_LENGTH + " characters.");
            }
            if (!unique.add(option.trim())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Options must be unique within a question.");
            }
        }
        if (input.correctOption() == null || !unique.contains(input.correctOption().trim())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The correct answer must match one of the options.");
        }
        if (input.explanation() == null || input.explanation().trim().isEmpty()
                || input.explanation().trim().length() > MAX_EXPLANATION_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Each question requires an explanation of at most " + MAX_EXPLANATION_LENGTH + " characters.");
        }
    }

    public List<AiPracticeSessionSummaryDto> listSessions(User student, int limit) {
        requireStudent(student);
        return sessionRepo.findRecentForStudent(student.id(), limit).stream()
                .map(this::toSummaryDto)
                .toList();
    }

    public AiPracticeSessionDto getSession(String sessionId, User student) {
        requireStudent(student);
        AiPracticeSession session = requireOwnedSession(sessionId, student);
        List<AiGeneratedQuestion> questions = questionRepo.findBySessionId(sessionId);
        Map<String, String> selectedByQuestion = answerRepo.findBySessionId(sessionId).stream()
                .filter(a -> a.optionId() != null)
                .collect(Collectors.toMap(AiPracticeAnswer::questionId, AiPracticeAnswer::optionId, (a, b) -> a));
        return toSessionDto(session, questions, optionsByQuestion(questions), selectedByQuestion);
    }

    public AiPracticeAnswerResponse answer(String sessionId, User student, AiPracticeAnswerRequest request) {
        requireStudent(student);
        AiPracticeSession session = requireOwnedSession(sessionId, student);
        if (!"ACTIVE".equals(session.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "This practice session is no longer active.");
        }
        if (request.questionId() == null || request.optionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A question id and an option id are required.");
        }
        AiGeneratedQuestion question = questionRepo.findBySessionId(sessionId).stream()
                .filter(q -> q.id().equals(request.questionId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Question does not belong to this session."));
        List<AiQuestionOption> options = questionRepo.findOptionsByQuestionId(question.id());
        AiQuestionOption selected = options.stream()
                .filter(o -> o.id().equals(request.optionId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Selected option does not belong to the question."));
        if (answerRepo.findBySessionAndQuestion(sessionId, question.id()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "This question has already been answered.");
        }
        boolean correct = selected.text().trim().equals(question.correctOption().trim());
        answerRepo.create(new AiPracticeAnswer(UUID.randomUUID().toString(), sessionId, question.id(),
                selected.id(), selected.text(), correct, Instant.now()));
        sessionRepo.incrementAnswered(sessionId, correct);
        return new AiPracticeAnswerResponse(session.answeredCount() + 1, session.questionCount());
    }

    public AiPracticeSubmitResponse submit(String sessionId, User student) {
        requireStudent(student);
        AiPracticeSession session = requireOwnedSession(sessionId, student);
        if (!"ACTIVE".equals(session.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "This practice session is no longer active.");
        }
        List<AiPracticeAnswer> answers = answerRepo.findBySessionId(sessionId);
        int correctCount = (int) answers.stream().filter(a -> Boolean.TRUE.equals(a.correct())).count();
        int answeredCount = answers.size();
        int unansweredCount = session.questionCount() - answeredCount;
        double percentage = session.questionCount() > 0
                ? Math.round(correctCount * 10000.0 / session.questionCount()) / 100.0
                : 0.0;
        boolean completionMet = session.minQuestionCount() != null && session.minAccuracy() != null
                && session.questionCount() >= session.minQuestionCount()
                && BigDecimal.valueOf(percentage).setScale(2, RoundingMode.HALF_UP)
                        .compareTo(session.minAccuracy().setScale(2, RoundingMode.HALF_UP)) >= 0;
        int updated = sessionRepo.complete(sessionId, correctCount, answeredCount,
                BigDecimal.valueOf(percentage), BigDecimal.valueOf(percentage), completionMet, Instant.now());
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This practice session has already been submitted.");
        }
        AiPracticeSession completed = sessionRepo.findById(sessionId).orElseThrow();
        return new AiPracticeSubmitResponse(sessionId, completed.status(), correctCount,
                answeredCount - correctCount, unansweredCount, session.questionCount(), percentage, completionMet);
    }

    public AiPracticeReviewDto review(String sessionId, User student) {
        requireStudent(student);
        AiPracticeSession session = requireOwnedSession(sessionId, student);
        if (!"COMPLETED".equals(session.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "The review is available only after the practice is submitted.");
        }
        List<AiGeneratedQuestion> questions = questionRepo.findBySessionId(sessionId);
        Map<String, List<AiQuestionOption>> optionsByQuestion = optionsByQuestion(questions);
        Map<String, AiPracticeAnswer> answersByQuestion = answerRepo.findBySessionId(sessionId).stream()
                .collect(Collectors.toMap(AiPracticeAnswer::questionId, a -> a));
        Map<String, String> explanationsByQuestion = reviewRepo.findBySessionId(sessionId).stream()
                .collect(Collectors.toMap(AiPracticeReview::questionId, AiPracticeReview::explanation));

        int correctCount = (int) answersByQuestion.values().stream()
                .filter(a -> Boolean.TRUE.equals(a.correct())).count();
        int answeredCount = answersByQuestion.size();
        int unansweredCount = session.questionCount() - answeredCount;
        double percentage = session.questionCount() > 0
                ? Math.round(correctCount * 10000.0 / session.questionCount()) / 100.0
                : 0.0;
        boolean reviewCompleted = explanationsByQuestion.size() == questions.size();

        List<AiPracticeReviewQuestionDto> reviewQuestions = questions.stream()
                .map(q -> {
                    AiPracticeAnswer answer = answersByQuestion.get(q.id());
                    boolean answered = answer != null;
                    boolean correct = answered && Boolean.TRUE.equals(answer.correct());
                    String correctText = optionsByQuestion.getOrDefault(q.id(), List.of()).stream()
                            .filter(o -> o.text().trim().equals(q.correctOption().trim()))
                            .map(AiQuestionOption::text)
                            .findFirst().orElse(q.correctOption());
                    return new AiPracticeReviewQuestionDto(q.id(), q.questionText(),
                            answered ? answer.selectedOption() : null,
                            correctText, correct, answered, explanationsByQuestion.get(q.id()));
                })
                .toList();

        AiPracticeProgressDto topicProgress = toTopicProgress(session.topic(),
                sessionRepo.findProgressForStudentAndTopic(session.studentId(), session.topic()));

        return new AiPracticeReviewDto(sessionId, session.topic(), session.difficulty(),
                correctCount, answeredCount - correctCount, unansweredCount, session.questionCount(),
                percentage, reviewCompleted,
                completionMet(session, percentage), topicProgress.status(), topicProgress.currentDifficulty(),
                topicProgress.unlockedDifficulty(),
                session.minQuestionCount(), session.minAccuracy() == null ? null : session.minAccuracy().doubleValue(),
                reviewQuestions);
    }

    public void saveExplanations(String sessionId, User student, AiPracticeExplanationRequest request) {
        requireStudent(student);
        AiPracticeSession session = requireOwnedSession(sessionId, student);
        if (!"COMPLETED".equals(session.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Explanations can only be saved after the practice is submitted.");
        }
        if (request.explanations() == null || request.explanations().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "At least one explanation is required.");
        }
        Set<String> sessionQuestionIds = questionRepo.findBySessionId(sessionId).stream()
                .map(AiGeneratedQuestion::id).collect(Collectors.toSet());
        for (var input : request.explanations()) {
            if (input.questionId() == null || !sessionQuestionIds.contains(input.questionId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Explanation refers to a question outside this session.");
            }
            if (input.explanation() == null || input.explanation().trim().isEmpty()
                    || input.explanation().trim().length() > MAX_EXPLANATION_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Each explanation must be provided and at most " + MAX_EXPLANATION_LENGTH + " characters.");
            }
        }
        for (var input : request.explanations()) {
            reviewRepo.replace(new AiPracticeReview(UUID.randomUUID().toString(), sessionId,
                    input.questionId(), input.explanation().trim(), Instant.now()));
        }
    }

    public List<AiPracticeProgressDto> progress(User student) {
        requireStudent(student);
        Map<String, List<ProgressRow>> byTopic = sessionRepo.findProgressForStudent(student.id()).stream()
                .collect(Collectors.groupingBy(ProgressRow::topic, LinkedHashMap::new, Collectors.toList()));
        return byTopic.entrySet().stream()
                .map(entry -> toTopicProgress(entry.getKey(), entry.getValue()))
                .toList();
    }

    private boolean completionMet(AiPracticeSession session, double percentage) {
        return session.minQuestionCount() != null && session.minAccuracy() != null
                && session.questionCount() >= session.minQuestionCount()
                && BigDecimal.valueOf(percentage).setScale(2, RoundingMode.HALF_UP)
                        .compareTo(session.minAccuracy().setScale(2, RoundingMode.HALF_UP)) >= 0;
    }

    private AiPracticeProgressDto toTopicProgress(String topic, List<ProgressRow> rows) {
        Map<String, ProgressRow> byDifficulty = rows.stream()
                .collect(Collectors.toMap(ProgressRow::difficulty, row -> row, (a, b) -> a));
        Set<String> completed = rows.stream()
                .filter(row -> row.completed() > 0)
                .map(ProgressRow::difficulty)
                .collect(Collectors.toSet());

        String unlockedDifficulty = DIFFICULTY_ORDER.stream()
                .filter(level -> !completed.contains(level))
                .findFirst()
                .orElse("MASTERED");
        boolean started = rows.stream().anyMatch(row -> row.attempted() > 0);

        String status;
        if (completed.size() == DIFFICULTY_ORDER.size()) {
            status = "MASTERED";
        } else if (completed.isEmpty()) {
            status = started ? "IN_PROGRESS" : "NOT_STARTED";
        } else {
            status = "READY_FOR_NEXT";
        }
        if ("MASTERED".equals(unlockedDifficulty)) {
            status = "MASTERED";
        }

        return new AiPracticeProgressDto(topic, unlockedDifficulty, unlockedDifficulty, status,
                levelProgress(byDifficulty, "EASY"),
                levelProgress(byDifficulty, "MEDIUM"),
                levelProgress(byDifficulty, "HARD"));
    }

    private AiDifficultyProgressDto levelProgress(Map<String, ProgressRow> byDifficulty, String difficulty) {
        ProgressRow row = byDifficulty.get(difficulty);
        if (row == null) {
            return new AiDifficultyProgressDto(0, 0, null);
        }
        Double best = row.bestAccuracy() == null
                ? null : row.bestAccuracy().setScale(2, RoundingMode.HALF_UP).doubleValue();
        return new AiDifficultyProgressDto(row.attempted(), row.completed(), best);
    }

    private AiPracticeSessionDto toSessionDto(AiPracticeSession session, List<AiGeneratedQuestion> questions,
                                              Map<String, List<AiQuestionOption>> optionsByQuestion,
                                              Map<String, String> selectedByQuestion) {
        List<AiPracticeQuestionDto> questionDtos = questions.stream()
                .map(q -> new AiPracticeQuestionDto(q.id(), q.questionText(),
                        optionsByQuestion.getOrDefault(q.id(), List.of()).stream()
                                .map(o -> new AiPracticeOptionDto(o.id(), o.text())).toList(),
                        q.difficulty(), selectedByQuestion.get(q.id())))
                .toList();
        return new AiPracticeSessionDto(session.id(), session.topic(), session.difficulty(), session.status(),
                session.questionCount(), session.answeredCount(),
                session.startedAt() == null ? null : session.startedAt().toString(),
                session.completedAt() == null ? null : session.completedAt().toString(),
                questionDtos);
    }

    private AiPracticeSessionSummaryDto toSummaryDto(AiPracticeSession session) {
        return new AiPracticeSessionSummaryDto(session.id(), session.topic(), session.difficulty(), session.status(),
                session.questionCount(), session.answeredCount(), session.correctCount(),
                session.percentage() == null ? null : session.percentage().setScale(2, RoundingMode.HALF_UP).doubleValue(),
                session.startedAt() == null ? null : session.startedAt().toString(),
                session.completedAt() == null ? null : session.completedAt().toString());
    }
}