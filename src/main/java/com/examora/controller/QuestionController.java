package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.model.Question;
import com.examora.model.User;
import com.examora.service.AuthService;
import com.examora.service.QuestionService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QuestionController {
    private final QuestionService questionService;
    private final AuthService authService;

    public QuestionController(QuestionService questionService, AuthService authService) {
        this.questionService = questionService;
        this.authService = authService;
    }

    @GetMapping("/api/questions")
    public ApiResponse<List<Question>> list(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(questionService.findAllForUser(user));
    }

    @GetMapping("/api/questions/{id}")
    public ApiResponse<Question> get(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(questionService.findByIdForUser(id, user));
    }

    @GetMapping("/api/exams/{examId}/questions")
    public ApiResponse<List<Question>> listByExam(
            @PathVariable String examId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(questionService.findByExamIdForUser(examId, user));
    }

    @PostMapping("/api/questions")
    public ApiResponse<Question> create(@RequestBody Question question) {
        return ApiResponse.ok("Created", questionService.create(question));
    }

    @PostMapping("/api/exams/{examId}/questions")
    public ApiResponse<Question> createForExam(@PathVariable String examId, @RequestBody Question question) {
        return ApiResponse.ok("Created", questionService.create(examId, question));
    }

    @PutMapping("/api/questions/{id}")
    public ApiResponse<Question> update(@PathVariable String id, @RequestBody Question question) {
        return ApiResponse.ok("Updated", questionService.update(id, question));
    }

    @DeleteMapping("/api/questions/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        questionService.delete(id);
        return ApiResponse.ok("Deleted", null);
    }
}
