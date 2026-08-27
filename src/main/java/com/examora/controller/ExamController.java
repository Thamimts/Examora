package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.ExamDtos.ExamSubmissionRequest;
import com.examora.dto.ExamDtos.ExamSubmissionResponse;
import com.examora.dto.ExamDtos.StartExamResponse;
import com.examora.model.Exam;
import com.examora.model.User;
import com.examora.service.AuthService;
import com.examora.service.ExamAttemptService;
import com.examora.service.ExamService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exams")
public class ExamController {
    private final ExamService examService;
    private final ExamAttemptService examAttemptService;
    private final AuthService authService;

    public ExamController(ExamService examService, ExamAttemptService examAttemptService, AuthService authService) {
        this.examService = examService;
        this.examAttemptService = examAttemptService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse<List<Exam>> list(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(examService.findForUser(user));
    }

    @GetMapping("/{id}")
    public ApiResponse<Exam> get(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(examService.findByIdForUser(id, user));
    }

    @PostMapping
    public ApiResponse<Exam> create(@RequestBody Exam exam, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok("Created", examService.create(exam, authService.requireUser(authorizationHeader)));
    }

    @PutMapping("/{id}")
    public ApiResponse<Exam> update(@PathVariable String id, @RequestBody Exam exam, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok("Updated", examService.update(id, exam, authService.requireUser(authorizationHeader)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        examService.delete(id, authService.requireUser(authorizationHeader));
        return ApiResponse.ok("Deleted", null);
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Exam> publish(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok("Published", examService.publish(id, authService.requireUser(authorizationHeader)));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<StartExamResponse> start(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User student = authService.requireUser(authorizationHeader);
        return ApiResponse.ok("Started", examAttemptService.start(id, student));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<ExamSubmissionResponse> submit(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody ExamSubmissionRequest request) {
        User student = authService.requireUser(authorizationHeader);
        return ApiResponse.ok("Submitted", examAttemptService.submit(id, student, request));
    }
}
