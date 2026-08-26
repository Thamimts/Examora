package com.examora.service;

import com.examora.exception.ApiException;
import com.examora.model.Answer;
import com.examora.repository.AnswerRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AnswerService {
    private final AnswerRepository answerRepository;

    public AnswerService(AnswerRepository answerRepository) {
        this.answerRepository = answerRepository;
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

    public Answer create(Answer answer) {
        Answer normalized = normalize(answer.id(), answer);
        return answerRepository.create(normalized);
    }

    public Answer update(String id, Answer answer) {
        findById(id);
        answerRepository.update(id, normalize(id, answer));
        return findById(id);
    }

    public void delete(String id) {
        if (answerRepository.delete(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Answer not found.");
        }
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
                answer.value());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
