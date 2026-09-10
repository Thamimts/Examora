package com.examora.service;

import com.examora.exception.ApiException;
import com.examora.model.Question;
import com.examora.model.QuestionOption;
import com.examora.model.User;
import com.examora.repository.ExamRepository;
import com.examora.repository.QuestionOptionRepository;
import com.examora.repository.QuestionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class QuestionOptionService {
    private final QuestionOptionRepository optionRepository;
    private final QuestionRepository questionRepository;
    private final ExamService examService;

    public QuestionOptionService(QuestionOptionRepository optionRepository, QuestionRepository questionRepository,
                                 ExamService examService) {
        this.optionRepository = optionRepository;
        this.questionRepository = questionRepository;
        this.examService = examService;
    }

    public List<QuestionOption> findAll() {
        return optionRepository.findAll();
    }

    public List<QuestionOption> findByQuestionId(String questionId) {
        requireQuestion(questionId);
        return optionRepository.findByQuestionId(questionId);
    }

    public QuestionOption findById(String id) {
        return optionRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Question option not found."));
    }

    public QuestionOption create(String questionId, QuestionOption option, User actor) {
        requireAuthoring(questionId, actor);
        QuestionOption normalized = normalize(option.id(), questionId, option);
        return optionRepository.create(normalized);
    }

    public QuestionOption create(QuestionOption option, User actor) {
        requireAuthoring(option.questionId(), actor);
        QuestionOption normalized = normalize(option.id(), option.questionId(), option);
        return optionRepository.create(normalized);
    }

    public QuestionOption update(String id, QuestionOption option, User actor) {
        QuestionOption existing = findById(id);
        requireAuthoring(existing.questionId(), actor);
        QuestionOption normalized = normalize(id, existing.questionId(), option);
        optionRepository.update(id, normalized);
        return findById(id);
    }

    public void delete(String id, User actor) {
        QuestionOption existing = findById(id);
        requireAuthoring(existing.questionId(), actor);
        if (optionRepository.delete(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Question option not found.");
        }
    }

    private void requireAuthoring(String questionId, User actor) {
        Question question = questionRepository.findById(questionId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Question not found."));
        examService.requireOwner(question.examId(), actor);
    }

    private QuestionOption normalize(String id, String questionId, QuestionOption option) {
        requireQuestion(questionId);
        if (option.text() == null || option.text().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Option text is required.");
        }
        return new QuestionOption(
                id == null || id.isBlank() ? UUID.randomUUID().toString() : id,
                questionId.trim(),
                option.text().trim(),
                Math.max(option.displayOrder(), 0),
                option.correctAnswer());
    }

    private void requireQuestion(String questionId) {
        if (questionId == null || questionId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Question id is required.");
        }
        questionRepository.findById(questionId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Question not found."));
    }
}