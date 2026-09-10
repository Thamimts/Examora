package com.examora.service;

import com.examora.exception.ApiException;
import com.examora.model.Answer;
import com.examora.model.ExamAttempt;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.AnswerRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AnswerService {
    private final AnswerRepository answerRepository;
    private final ExamAttemptService examAttemptService;

    public AnswerService(AnswerRepository answerRepository, ExamAttemptService examAttemptService) {
        this.answerRepository = answerRepository;
        this.examAttemptService = examAttemptService;
    }

    public List<Answer> findAll() {
        return answerRepository.findAll();
    }

    public Answer findById(String id) {
        return answerRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Answer not found."));
    }

    public List<Answer> findByExamId(String examId) {
        return answerRepository.findByExamId(examId);
    }

    public List<Answer> findByUserId(String userId) {
        return answerRepository.findByUserId(userId);
    }

    public Answer create(User actor, Answer answer) {
        if (actor.role() == Role.STUDENT) {
            return createForStudent(actor, answer);
        }
        return answerRepository.create(enforceAttemptLink(normalize(answer)));
    }

    public Answer update(String id, User actor, Answer answer) {
        Answer existing = findById(id);
        if (actor.role() == Role.STUDENT) {
            return updateForStudent(id, actor, existing, answer);
        }
        if (!existing.userId().equals(answer.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Answer user cannot be changed.");
        }
        return answerRepository.update(id, enforceAttemptLink(normalize(id, answer))) == 0
                ? throwUpdateFailed()
                : findById(id);
    }

    public void delete(String id) {
        if (answerRepository.delete(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Answer not found.");
        }
    }

    private Answer createForStudent(User actor, Answer answer) {
        String attemptId = requiredAttemptId(answer.attemptId());
        ExamAttempt attempt = examAttemptService.requireOwnedAttempt(attemptId, actor);
        Answer normalized = normalize(answer);
        rejectExamMismatch(normalized.examId(), attempt);
        Answer owned = new Answer(
                normalized.id(),
                actor.id(),
                attempt.examId(),
                normalized.questionId(),
                normalized.optionId(),
                normalized.value(),
                attempt.id());
        return answerRepository.create(owned);
    }

    private Answer updateForStudent(String id, User actor, Answer existing, Answer answer) {
        if (!actor.id().equals(existing.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This answer belongs to another student.");
        }
        String attemptId = answer.attemptId() == null || answer.attemptId().isBlank()
                ? existing.attemptId()
                : answer.attemptId().trim();
        if (attemptId == null || attemptId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Attempt id is required.");
        }
        if (!attemptId.equals(existing.attemptId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "A student answer cannot be moved to another attempt.");
        }
        ExamAttempt attempt = examAttemptService.requireOwnedAttempt(attemptId, actor);
        Answer normalized = normalize(id, answer);
        rejectExamMismatch(normalized.examId(), attempt);
        Answer owned = new Answer(
                existing.id(),
                actor.id(),
                attempt.examId(),
                normalized.questionId(),
                normalized.optionId(),
                normalized.value(),
                attempt.id());
        return answerRepository.update(id, owned) == 0 ? throwUpdateFailed() : owned;
    }

    private Answer enforceAttemptLink(Answer answer) {
        if (answer.attemptId() == null || answer.attemptId().isBlank()) {
            return answer;
        }
        ExamAttempt attempt = examAttemptService.findAttemptById(answer.attemptId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam attempt not found."));
        rejectExamMismatch(answer.examId(), attempt);
        return new Answer(
                answer.id(),
                answer.userId(),
                attempt.examId(),
                answer.questionId(),
                answer.optionId(),
                answer.value(),
                attempt.id());
    }

    private void rejectExamMismatch(String examId, ExamAttempt attempt) {
        if (examId != null && !examId.isBlank() && !attempt.examId().equals(examId.trim())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The answer exam does not match the attempt's exam.");
        }
    }

    private String requiredAttemptId(String attemptId) {
        if (attemptId == null || attemptId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Attempt id is required.");
        }
        return attemptId.trim();
    }

    private Answer throwUpdateFailed() {
        throw new ApiException(HttpStatus.NOT_FOUND, "Answer not found.");
    }

    private Answer normalize(Answer answer) {
        return normalize(answer.id(), answer);
    }

    private Answer normalize(String id, Answer answer) {
        if (answer.examId() == null || answer.examId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exam id is required.");
        }
        if (answer.questionId() == null || answer.questionId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Question id is required.");
        }
        return new Answer(
                id == null || id.isBlank() ? UUID.randomUUID().toString() : id,
                blankToNull(answer.userId()),
                answer.examId().trim(),
                answer.questionId().trim(),
                blankToNull(answer.optionId()),
                answer.value(),
                blankToNull(answer.attemptId()));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}