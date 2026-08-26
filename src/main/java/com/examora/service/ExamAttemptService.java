package com.examora.service;

import com.examora.dto.ExamDtos.ExamSubmissionRequest;
import com.examora.dto.ExamDtos.ExamSubmissionResponse;
import com.examora.dto.ExamDtos.StartExamResponse;
import com.examora.dto.ExamDtos.SubmittedAnswer;
import com.examora.exception.ApiException;
import com.examora.model.Answer;
import com.examora.model.Exam;
import com.examora.model.Question;
import com.examora.model.QuestionOption;
import com.examora.model.Result;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.AnswerRepository;
import com.examora.repository.ExamRepository;
import com.examora.repository.QuestionOptionRepository;
import com.examora.repository.QuestionRepository;
import com.examora.repository.ResultRepository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamAttemptService {
    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final AnswerRepository answerRepository;
    private final ResultRepository resultRepository;

    public ExamAttemptService(
            ExamRepository examRepository,
            QuestionRepository questionRepository,
            QuestionOptionRepository optionRepository,
            AnswerRepository answerRepository,
            ResultRepository resultRepository) {
        this.examRepository = examRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.answerRepository = answerRepository;
        this.resultRepository = resultRepository;
    }

    public StartExamResponse start(String examId, User student) {
        requireStudent(student);
        Exam exam = requireAvailableExam(examId);
        return new StartExamResponse(exam.id(), student.id(), "STARTED", exam);
    }

    @Transactional
    public ExamSubmissionResponse submit(String examId, User student, ExamSubmissionRequest request) {
        requireStudent(student);
        Exam exam = requireAvailableExam(examId);
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
            answerRepository.create(new Answer(
                    UUID.randomUUID().toString(),
                    student.id(),
                    exam.id(),
                    question.id(),
                    evaluation.optionId(),
                    evaluation.value()));
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
        examRepository.updateStats(exam.id());

        double percentage = questions.isEmpty() ? 0.0 : Math.round((score * 10000.0) / questions.size()) / 100.0;
        return new ExamSubmissionResponse(result, score, questions.size(), percentage);
    }

    private AnswerEvaluation evaluate(Question question, SubmittedAnswer submittedAnswer) {
        String selectedOptionId = blankToNull(submittedAnswer.optionId());
        String submittedValue = blankToNull(submittedAnswer.value());
        boolean correct = false;

        if (selectedOptionId != null) {
            QuestionOption selectedOption = optionRepository.findById(selectedOptionId)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Selected option was not found."));
            if (!question.id().equals(selectedOption.questionId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Selected option does not belong to the submitted question.");
            }
            submittedValue = selectedOption.text();
            correct = selectedOption.correctAnswer() || matches(question.answer(), selectedOption.text());
        } else if (submittedValue != null) {
            correct = matches(question.answer(), submittedValue) || matchesCorrectOption(question.id(), submittedValue);
        }

        return new AnswerEvaluation(selectedOptionId, submittedValue, correct);
    }

    private boolean matchesCorrectOption(String questionId, String submittedValue) {
        return optionRepository.findByQuestionId(questionId).stream()
                .anyMatch(option -> option.correctAnswer() && matches(option.text(), submittedValue));
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

    private boolean matches(String expected, String actual) {
        return expected != null && actual != null && expected.trim().equalsIgnoreCase(actual.trim());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record AnswerEvaluation(String optionId, String value, boolean correct) {
    }
}
