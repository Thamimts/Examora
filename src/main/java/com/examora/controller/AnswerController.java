package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.model.Answer;
import com.examora.model.User;
import com.examora.service.AnswerService;
import com.examora.service.AuthService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/answers")
public class AnswerController {
    private final AnswerService answerService;
    private final AuthService authService;

    public AnswerController(AnswerService answerService, AuthService authService) {
        this.answerService = answerService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse<List<Answer>> list(@RequestParam(required = false) String userId, @RequestParam(required = false) String examId) {
        if (userId != null && !userId.isBlank()) {
            return ApiResponse.ok(answerService.findByUserId(userId));
        }
        if (examId != null && !examId.isBlank()) {
            return ApiResponse.ok(answerService.findByExamId(examId));
        }
        return ApiResponse.ok(answerService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<Answer> get(@PathVariable String id) {
        return ApiResponse.ok(answerService.findById(id));
    }

    @PostMapping
    public ApiResponse<Answer> create(@RequestBody Answer answer,
                                      @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        return ApiResponse.ok("Created", answerService.create(actor, answer));
    }

    @PutMapping("/{id}")
    public ApiResponse<Answer> update(@PathVariable String id, @RequestBody Answer answer,
                                      @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        return ApiResponse.ok("Updated", answerService.update(id, actor, answer));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        answerService.delete(id);
        return ApiResponse.ok("Deleted", null);
    }
}
