package com.examora.service;

import com.examora.exception.ApiException;
import com.examora.model.Question;
import com.examora.model.QuestionOption;
import com.examora.repository.QuestionOptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class QuestionGradingService {
    private final QuestionOptionRepository optionRepository;

    public QuestionGradingService(QuestionOptionRepository optionRepository) {
        this.optionRepository = optionRepository;
    }

    public GradeResult grade(Question question, String submittedOptionId, String submittedValue) {
        String selectedOptionId = blankToNull(submittedOptionId);
        String submittedAnswerValue = blankToNull(submittedValue);
        boolean correct = false;

        if (selectedOptionId != null) {
            QuestionOption selectedOption = optionRepository.findById(selectedOptionId)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Selected option was not found."));
            if (!question.id().equals(selectedOption.questionId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Selected option does not belong to the submitted question.");
            }
            submittedAnswerValue = selectedOption.text();
            correct = selectedOption.correctAnswer() || matches(question.answer(), selectedOption.text());
        } else if (submittedAnswerValue != null) {
            correct = matches(question.answer(), submittedAnswerValue) || matchesCorrectOption(question.id(), submittedAnswerValue);
        }

        return new GradeResult(selectedOptionId, submittedAnswerValue, correct);
    }

    private boolean matchesCorrectOption(String questionId, String submittedValue) {
        return optionRepository.findByQuestionId(questionId).stream()
                .anyMatch(option -> option.correctAnswer() && matches(option.text(), submittedValue));
    }

    private boolean matches(String expected, String actual) {
        return expected != null && actual != null && expected.trim().equalsIgnoreCase(actual.trim());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record GradeResult(String optionId, String value, boolean correct) {
    }
}