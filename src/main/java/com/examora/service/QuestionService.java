package com.examora.service;

import com.examora.exception.ApiException;
import com.examora.model.Exam;
import com.examora.model.Question;
import com.examora.model.QuestionOption;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ExamRepository;
import com.examora.repository.QuestionOptionRepository;
import com.examora.repository.QuestionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionService {
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final ExamRepository examRepository;

    public QuestionService(QuestionRepository questionRepository, QuestionOptionRepository optionRepository, ExamRepository examRepository) {
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.examRepository = examRepository;
    }

    public List<Question> findAll() {
        return questionRepository.findAll();
    }

    public List<Question> findAllForUser(User user) {
        if (user.role() == Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Students must select an exam before viewing questions.");
        }
        return findAll();
    }

    public List<Question> findByExamId(String examId) {
        requireExam(examId);
        return questionRepository.findByExamId(examId);
    }

    public List<Question> findByExamIdForUser(String examId, User user) {
        Exam exam = requireExam(examId);
        if (user.role() == Role.STUDENT && "DRAFT".equalsIgnoreCase(exam.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This exam is not available to students.");
        }
        List<Question> questions = questionRepository.findByExamId(examId);
        return user.role() == Role.STUDENT ? stripAnswers(questions) : questions;
    }

    public Question findById(String id) {
        return questionRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Question not found."));
    }

    public Question findByIdForUser(String id, User user) {
        Question question = findById(id);
        Exam exam = requireExam(question.examId());
        if (user.role() == Role.STUDENT && "DRAFT".equalsIgnoreCase(exam.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This question is not available to students.");
        }
        return user.role() == Role.STUDENT ? stripAnswer(question) : question;
    }

    @Transactional
    public Question create(String examId, Question question, User actor) {
        requireAuthoring(examId, actor);
        Question normalized = normalize(question.id(), examId, question);
        questionRepository.create(normalized);
        replaceOptions(normalized.id(), normalized.options(), normalized.answer());
        return findById(normalized.id());
    }

    @Transactional
    public Question create(Question question, User actor) {
        return create(question.examId(), question, actor);
    }

    @Transactional
    public Question update(String id, Question question, User actor) {
        Question existing = findById(id);
        requireAuthoring(existing.examId(), actor);
        String targetExamId = question.examId() == null || question.examId().isBlank() ? existing.examId() : question.examId();
        Question normalized = normalize(id, targetExamId, question);
        questionRepository.update(id, normalized);
        replaceOptions(id, normalized.options(), normalized.answer());
        return findById(id);
    }

    public void delete(String id, User actor) {
        requireAuthoring(findById(id).examId(), actor);
        if (questionRepository.delete(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Question not found.");
        }
    }

    private void requireAuthoring(String examId, User actor) {
        if (actor == null || (actor.role() != Role.TEACHER && actor.role() != Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Teacher or administrator access is required.");
        }
        if (actor.role() != Role.ADMIN) {
            String owner = examRepository.findOwnerId(examId).orElse(null);
            if (owner != null && !owner.equals(actor.id())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You do not own this exam.");
            }
        }
    }

    private Question normalize(String id, String examId, Question question) {
        requireExam(examId);
        List<String> options = question.options() == null ? List.of() : question.options().stream()
                .filter(option -> option != null && !option.isBlank())
                .map(String::trim)
                .toList();
        String answer = blankToNull(question.answer());
        if (options.size() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A multiple-choice question requires at least two options.");
        }
        if (answer == null || options.stream().noneMatch(option -> option.equalsIgnoreCase(answer))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The correct answer must match one of the options.");
        }
        return new Question(
                id == null || id.isBlank() ? UUID.randomUUID().toString() : id,
                examId.trim(),
                required(question.text()),
                options,
                answer);
    }

    private void replaceOptions(String questionId, List<String> options, String answer) {
        optionRepository.deleteByQuestionId(questionId);
        for (int index = 0; index < options.size(); index++) {
            String text = options.get(index);
            if (text != null && !text.isBlank()) {
                String optionText = text.trim();
                boolean correct = answer != null && optionText.equalsIgnoreCase(answer.trim());
                optionRepository.create(new QuestionOption(UUID.randomUUID().toString(), questionId, optionText, index, correct));
            }
        }
    }

    private Exam requireExam(String examId) {
        if (examId == null || examId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exam id is required.");
        }
        return examRepository.findById(examId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found."));
    }

    private List<Question> stripAnswers(List<Question> questions) {
        return questions.stream().map(this::stripAnswer).toList();
    }

    private Question stripAnswer(Question question) {
        return new Question(question.id(), question.examId(), question.text(), question.options(), null);
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Question text is required.");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
