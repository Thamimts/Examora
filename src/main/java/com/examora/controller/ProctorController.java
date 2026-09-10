package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.ProctorDtos.EventBatchRequest;
import com.examora.service.ProctorService;
import com.examora.service.AuthService;
import com.examora.service.ExamAttemptService;
import com.examora.model.User;
import com.examora.model.Role;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proctor")
public class ProctorController {
    private final ProctorService proctorService;
    private final AuthService authService;
    private final ExamAttemptService examAttemptService;

    public ProctorController(ProctorService proctorService, AuthService authService,
                             ExamAttemptService examAttemptService) {
        this.proctorService = proctorService;
        this.authService = authService;
        this.examAttemptService = examAttemptService;
    }

    @PostMapping("/events/batch")
    public ApiResponse<Map<String, Integer>> events(@RequestBody EventBatchRequest request, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        int saved = proctorService.saveBatch(request == null ? null : request.events(), user);
        return ApiResponse.ok(Map.of("saved", saved));
    }

    @PostMapping("/attempts/{attemptId}/start")
    public ApiResponse<Map<String, String>> start(@PathVariable String attemptId, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        requireProctorAccess(attemptId, user);
        return ApiResponse.ok(Map.of("attemptId", attemptId, "status", "started"));
    }

    @PostMapping("/attempts/{attemptId}/stop")
    public ApiResponse<Map<String, String>> stop(@PathVariable String attemptId, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        requireProctorAccess(attemptId, user);
        return ApiResponse.ok(Map.of("attemptId", attemptId, "status", "stopped"));
    }

    private void requireProctorAccess(String attemptId, User user) {
        if (attemptId == null || attemptId.isBlank()) {
            throw new com.examora.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Attempt id is required.");
        }
        if (user.role() == Role.STUDENT) {
            examAttemptService.requireOwnedAttempt(attemptId, user);
        } else if (examAttemptService.findAttemptById(attemptId).isEmpty()) {
            throw new com.examora.exception.ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "Exam attempt not found.");
        }
    }
}
