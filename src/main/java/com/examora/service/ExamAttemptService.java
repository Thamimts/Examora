package com.examora.service;

import com.examora.dto.ExamDtos.ExamSubmissionRequest;
import com.examora.dto.ExamDtos.ExamSubmissionResponse;
import com.examora.dto.ExamDtos.StartExamResponse;
import com.examora.dto.ExamDtos.SubmittedAnswer;
import com.examora.exception.ApiException;
import com.examora.model.Answer;
import com.examora.model.Exam;
import com.examora.model.ExamAttempt;
import com.examora.model.Question;
import com.examora.model.QuestionOption;
import com.examora.model.Result;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.AnswerRepository;
import com.examora.repository.ExamRepository;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.QuestionRepository;
import com.examora.repository.ResultRepository;
import java.time.LocalDate;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamAttemptService {
    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final ResultRepository resultRepository;
    private final ExamAttemptRepository attemptRepository;
    private final ActivityService activityService;
    private final RetestRequestService retestRequestService;
    private final QuestionGradingService gradingService;
    private final ProctorPublishService proctorPublishService;

    public ExamAttemptService(
            ExamRepository examRepository,
            QuestionRepository questionRepository,
            AnswerRepository answerRepository,
            ResultRepository resultRepository,
            ExamAttemptRepository attemptRepository,
            ActivityService activityService,
            RetestRequestService retestRequestService,
            QuestionGradingService gradingService,
            ProctorPublishService proctorPublishService) {
        this.examRepository = examRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.resultRepository = resultRepository;
        this.attemptRepository = attemptRepository;
        this.activityService = activityService;
        this.retestRequestService = retestRequestService;
        this.gradingService = gradingService;
        this.proctorPublishService = proctorPublishService;
    }

    @Scheduled(fixedDelay = 1000)
    public void expireOverdueAttempts() {
        Instant now = Instant.now();
        List<ExamAttempt> aboutToExpire = attemptRepository.findOverdue(now);
        attemptRepository.expireOverdue(now);
        for (ExamAttempt attempt : aboutToExpire) {
            proctorPublishService.publishExpired(attempt);
        }
    }

    public StartExamResponse start(String examId, User student) {
        requireStudent(student);
        Exam exam = requireAvailableExam(examId);
        ExamAttempt attempt = activeOrCreate(exam, student);
        activityService.student(student, "EXAM_STARTED", "You started “" + exam.title() + "”.");
        activityService.admin("EXAM_STARTED", student.name() + " started exam “" + exam.title() + "”.");
        proctorPublishService.publishLifecycle(exam, attempt, student, "STARTED");
        return new StartExamResponse(exam.id(), student.id(), attempt.status(), exam, attempt.id(),
                attempt.startedAt().toString(), attempt.expiresAt().toString(), attempt.expiresAt().toString());
    }

    @Transactional
    public ExamSubmissionResponse submit(String examId, User student, ExamSubmissionRequest request) {
        requireStudent(student);
        Exam exam = requireAvailableExam(examId);
        Result existingResult = resultRepository.findByUserIdAndExamId(student.id(), exam.id()).orElse(null);
        if (existingResult != null) {
            return new ExamSubmissionResponse(
                    existingResult,
                    existingResult.score(),
                    existingResult.total(),
                    percentage(existingResult.score(), existingResult.total()));
        }
        ExamAttempt attempt = activeOrCreate(exam, student);
        if (!Instant.now().isBefore(attempt.expiresAt())) {
            attemptRepository.markExpired(attempt.id());
            throw new ApiException(HttpStatus.CONFLICT, "This exam attempt has expired.");
        }
        List<Question> questions = questionRepository.findByExamId(exam.id());
        if (questions.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This exam has no questions.");
        }
        Map<String, Question> questionsById = questions.stream()
                .collect(Collectors.toMap(Question::id, Function.identity()));
        List<SubmittedAnswer> submittedAnswers = request == null || request.answers() == null ? List.of() : request.answers();

        Set<String> seenQuestionIds = new HashSet<>();
        int score = 0;
        for (SubmittedAnswer submittedAnswer : submittedAnswers) {
            if (submittedAnswer == null || isBlank(submittedAnswer.questionId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Each answer must include a question id.");
            }
            Question question = questionsById.get(submittedAnswer.questionId().trim());
            if (question == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Submitted question does not belong to this exam.");
            }
            if (!seenQuestionIds.add(question.id())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate answer for question " + question.id() + ".");
            }

            AnswerEvaluation evaluation = evaluate(question, submittedAnswer);
            if (evaluation.correct()) {
                score++;
            }
            answerRepository.createForAttempt(attempt.id(), new Answer(
                    UUID.randomUUID().toString(),
                    student.id(),
                    exam.id(),
                    question.id(),
                    evaluation.optionId(),
                    evaluation.value(),
                    attempt.id()));
        }

        Result result = new Result(
                UUID.randomUUID().toString(),
                student.id(),
                exam.id(),
                exam.title(),
                exam.subject(),
                score,
                LocalDate.now().toString(),
                questions.size());
        resultRepository.create(result);
        if (attemptRepository.markSubmitted(attempt.id(), Instant.now()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "This exam attempt is no longer active.");
        }
        examRepository.updateStats(exam.id());
        activityService.student(student, "EXAM_SUBMITTED", "You completed “" + exam.title() + "” with " + score + "/" + questions.size() + ".");
        activityService.admin("EXAM_SUBMITTED", student.name() + " submitted “" + exam.title() + "” (" + score + "/" + questions.size() + ").");

        proctorPublishService.publishLifecycle(exam, attempt, student, "SUBMITTED");

        return new ExamSubmissionResponse(result, score, questions.size(), percentage(score, questions.size()));
    }

    public ExamAttempt requireOwnedAttempt(String attemptId, User student) {
        requireStudent(student);
        ExamAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam attempt not found."));
        if (!attempt.studentId().equals(student.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This attempt belongs to another student.");
        }
        if (!"STARTED".equals(attempt.status()) || !Instant.now().isBefore(attempt.expiresAt())) {
            if ("STARTED".equals(attempt.status())) attemptRepository.markExpired(attempt.id());
            throw new ApiException(HttpStatus.CONFLICT, "This exam attempt is not active.");
        }
        return attempt;
    }

    public java.util.Optional<ExamAttempt> findAttemptById(String attemptId) {
        return attemptRepository.findById(attemptId);
    }

    public ExamAttempt requireProctorAccess(String attemptId, User actor) {
        if (isBlank(attemptId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Attempt id is required.");
        }
        ExamAttempt attempt = attemptRepository.findById(attemptId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam attempt not found."));
        if (actor == null || actor.role() == Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Teacher or administrator access is required.");
        }
        if (actor.role() == Role.TEACHER) {
            boolean ownsExam = examRepository.findOwnerId(attempt.examId())
                    .map(ownerId -> ownerId.equals(actor.id()))
                    .orElse(false);
            if (!ownsExam) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You do not have access to this exam's proctoring.");
            }
        }
        return attempt;
    }

    private ExamAttempt activeOrCreate(Exam exam, User student) {
        Instant now = Instant.now();
        if (exam.startAt() != null && now.isBefore(exam.startAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "This exam has not started yet.");
        }
        if (exam.endAt() != null && !now.isBefore(exam.endAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "This exam has ended.");
        }
        ExamAttempt active = attemptRepository.findActive(exam.id(), student.id()).orElse(null);
        if (active != null) {
            if (Instant.now().isBefore(active.expiresAt())) return active;
            attemptRepository.markExpired(active.id());
            throw new ApiException(HttpStatus.CONFLICT, "This exam attempt has expired.");
        }
        Instant startedAt = Instant.now();
        Instant authoritativeEnd = exam.endAt() != null ? exam.endAt() : startedAt.plusSeconds(exam.duration() * 60L);
        return attemptRepository.create(new ExamAttempt(UUID.randomUUID().toString(), exam.id(), student.id(),
                1, "STARTED", startedAt, authoritativeEnd, null, 0));
    }

    private AnswerEvaluation evaluate(Question question, SubmittedAnswer submittedAnswer) {
        QuestionGradingService.GradeResult grade = gradingService.grade(question, submittedAnswer.optionId(), submittedAnswer.value());
        return new AnswerEvaluation(grade.optionId(), grade.value(), grade.correct());
    }

    private Exam requireAvailableExam(String examId) {
        if (isBlank(examId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exam id is required.");
        }
        Exam exam = examRepository.findById(examId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found."));
        if ("DRAFT".equalsIgnoreCase(exam.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This exam is not available to students.");
        }
        return exam;
    }

    private void requireStudent(User user) {
        if (user.role() != Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Student access is required.");
        }
    }

    private double percentage(int score, int total) {
        return total == 0 ? 0.0 : Math.round((score * 10000.0) / total) / 100.0;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record AnswerEvaluation(String optionId, String value, boolean correct) {
    }
}
