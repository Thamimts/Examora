package com.examora.service;

import com.examora.dto.AiTutorDtos.AiTutorContextDto;
import com.examora.dto.AiTutorDtos.AiTutorOptionDto;
import com.examora.dto.AiTutorDtos.AiTutorQuestionAnswerRequest;
import com.examora.dto.AiTutorDtos.AiTutorQuestionAnswerResponse;
import com.examora.dto.AiTutorDtos.AiTutorQuestionCreateRequest;
import com.examora.dto.AiTutorDtos.AiTutorQuestionDto;
import com.examora.dto.AiTutorDtos.AiTutorRequest;
import com.examora.exception.ApiException;
import com.examora.model.AiGeneratedQuestion;
import com.examora.model.AiPracticeAnswer;
import com.examora.model.AiPracticeSession;
import com.examora.model.AiQuestionOption;
import com.examora.model.AiTutorQuestion;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.AiGeneratedQuestionRepository;
import com.examora.repository.AiPracticeAnswerRepository;
import com.examora.repository.AiPracticeSessionRepository;
import com.examora.repository.AiTutorQuestionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AiTutorService {
    private static final Set<String> ACTIONS = Set.of(
            "EXPLAIN_WRONG", "EXPLAIN_SIMPLE", "GIVE_EXAMPLE", "EXPLAIN_CONCEPT", "SIMILAR_QUESTION", "HARDER_QUESTION");
    private static final Set<String> QUESTION_ACTIONS = Set.of("SIMILAR_QUESTION", "HARDER_QUESTION");
    private static final int REQUIRED_OPTION_COUNT = 4;
    private static final int MAX_QUESTION_LENGTH = 1000;
    private static final int MAX_OPTION_LENGTH = 300;
    private static final int MAX_HINT_LENGTH = 500;
    private static final int MIN_DIFFICULTY = 1;
    private static final int MAX_DIFFICULTY = 5;

    private final AiPracticeSessionRepository sessionRepo;
    private final AiGeneratedQuestionRepository questionRepo;
    private final AiPracticeAnswerRepository answerRepo;
    private final AiTutorQuestionRepository tutorRepo;
    private final ObjectMapper objectMapper;

    public AiTutorService(AiPracticeSessionRepository sessionRepo, AiGeneratedQuestionRepository questionRepo,
                          AiPracticeAnswerRepository answerRepo, AiTutorQuestionRepository tutorRepo,
                          ObjectMapper objectMapper) {
        this.sessionRepo = sessionRepo;
        this.questionRepo = questionRepo;
        this.answerRepo = answerRepo;
        this.tutorRepo = tutorRepo;
        this.objectMapper = objectMapper;
    }

    private void requireStudent(User user) {
        if (user == null || user.role() != Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Student access is required.");
        }
    }

    private AiPracticeSession requireOwnedCompletedSession(String sessionId, User student) {
        AiPracticeSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Practice session not found."));
        if (!session.studentId().equals(student.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This practice session belongs to another student.");
        }
        if (!"COMPLETED".equals(session.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "The AI tutor is available only after the practice is submitted.");
        }
        return session;
    }

    public AiTutorContextDto context(User student, AiTutorRequest request) {
        requireStudent(student);
        validateRequest(request);
        String action = request.action().toUpperCase();
        AiPracticeSession session = requireOwnedCompletedSession(request.sessionId(), student);
        AiGeneratedQuestion question = questionRepo.findBySessionId(request.sessionId()).stream()
                .filter(q -> q.id().equals(request.questionId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Question does not belong to this session."));
        List<AiQuestionOption> options = questionRepo.findOptionsByQuestionId(question.id());
        AiPracticeAnswer answer = answerRepo.findBySessionAndQuestion(request.sessionId(), question.id()).orElse(null);
        boolean answered = answer != null;
        boolean correct = answered && Boolean.TRUE.equals(answer.correct());
        requireActionAppropriate(action, answered, correct);
        String correctText = options.stream()
                .filter(o -> o.text().trim().equals(question.correctOption().trim()))
                .map(AiQuestionOption::text)
                .findFirst().orElse(question.correctOption());
        List<AiTutorOptionDto> optionDtos = options.stream()
                .map(o -> new AiTutorOptionDto(o.id(), o.text()))
                .toList();
        return new AiTutorContextDto(question.id(), question.questionText(), session.topic(), session.difficulty(),
                optionDtos, answered ? answer.selectedOption() : null, correctText, correct, answered);
    }

    private void validateRequest(AiTutorRequest request) {
        if (request.sessionId() == null || request.sessionId().isBlank()
                || request.questionId() == null || request.questionId().isBlank()
                || request.action() == null || !ACTIONS.contains(request.action().toUpperCase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A session id, a question id and a valid tutor action are required.");
        }
    }

    private void requireActionAppropriate(String action, boolean answered, boolean correct) {
        if ("EXPLAIN_WRONG".equals(action) && (!answered || correct)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Explain-why-mistake is only available for questions answered incorrectly.");
        }
        if ("HARDER_QUESTION".equals(action) && !answered) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A harder question is only available after the review question has been answered.");
        }
    }

    public AiTutorQuestionDto createQuestion(User student, AiTutorQuestionCreateRequest request) {
        requireStudent(student);
        AiPracticeSession session = requireOwnedCompletedSession(request.sessionId(), student);
        validateQuestionCreate(request, session);
        List<AiTutorOptionDto> optionDtos = request.options().stream()
                .map(text -> new AiTutorOptionDto(UUID.randomUUID().toString(), text.trim()))
                .toList();
        AiTutorQuestion question = new AiTutorQuestion(UUID.randomUUID().toString(), student.id(),
                request.sessionId(), request.sourceQuestionId(), request.kind(),
                request.question().trim(), toJson(optionDtos), request.difficulty(),
                request.hint().trim(), request.correctAnswer().trim(), false, null, null, Instant.now());
        tutorRepo.create(question);
        return new AiTutorQuestionDto(question.id(), question.questionText(), optionDtos,
                question.difficulty(), question.hint());
    }

    private void validateQuestionCreate(AiTutorQuestionCreateRequest request, AiPracticeSession session) {
        if (request.kind() == null || !QUESTION_ACTIONS.contains(request.kind())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Kind must be SIMILAR_QUESTION or HARDER_QUESTION.");
        }
        if (request.sourceQuestionId() == null || request.sourceQuestionId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A source question id is required.");
        }
        Set<String> sessionQuestionIds = questionRepo.findBySessionId(session.id()).stream()
                .map(AiGeneratedQuestion::id).collect(Collectors.toSet());
        if (!sessionQuestionIds.contains(request.sourceQuestionId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The source question does not belong to this session.");
        }
        if (request.question() == null || request.question().trim().isEmpty()
                || request.question().trim().length() > MAX_QUESTION_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The question must be provided and at most " + MAX_QUESTION_LENGTH + " characters.");
        }
        if (request.options() == null || request.options().size() != REQUIRED_OPTION_COUNT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Each tutor question requires exactly " + REQUIRED_OPTION_COUNT + " options.");
        }
        Set<String> unique = new HashSet<>();
        for (String option : request.options()) {
            if (option == null || option.trim().isEmpty() || option.trim().length() > MAX_OPTION_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Each option must be provided and at most " + MAX_OPTION_LENGTH + " characters.");
            }
            if (!unique.add(option.trim())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Options must be unique within a tutor question.");
            }
        }
        if (request.correctAnswer() == null || !unique.contains(request.correctAnswer().trim())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The correct answer must match one of the options.");
        }
        if (request.difficulty() < MIN_DIFFICULTY || request.difficulty() > MAX_DIFFICULTY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Difficulty must be between " + MIN_DIFFICULTY + " and " + MAX_DIFFICULTY + ".");
        }
        if (request.hint() == null || request.hint().trim().isEmpty()
                || request.hint().trim().length() > MAX_HINT_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A hint of at most " + MAX_HINT_LENGTH + " characters is required.");
        }
        if (request.correctAnswer().trim().length() > MAX_OPTION_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The correct answer must be at most " + MAX_OPTION_LENGTH + " characters.");
        }
    }

    public AiTutorQuestionAnswerResponse answerQuestion(User student, String questionId,
                                                        AiTutorQuestionAnswerRequest request) {
        requireStudent(student);
        if (request.optionId() == null || request.optionId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "An option id is required.");
        }
        AiTutorQuestion question = tutorRepo.findById(questionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Tutor question not found."));
        if (!question.studentId().equals(student.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This tutor question belongs to another student.");
        }
        List<AiTutorOptionDto> options = fromJson(question.options());
        AiTutorOptionDto selected = options.stream()
                .filter(o -> o.id().equals(request.optionId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Selected option does not belong to the tutor question."));
        boolean correct = selected.text().trim().equals(question.correctAnswer().trim());
        int updated = tutorRepo.markAnswered(questionId, correct, Instant.now());
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This tutor question has already been answered.");
        }
        return new AiTutorQuestionAnswerResponse(questionId, correct, question.correctAnswer());
    }

    private String toJson(List<AiTutorOptionDto> options) {
        try {
            return objectMapper.writeValueAsString(options);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not persist the tutor question.");
        }
    }

    private List<AiTutorOptionDto> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<AiTutorOptionDto>>() {
            });
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Tutor question data is unreadable.");
        }
    }
}