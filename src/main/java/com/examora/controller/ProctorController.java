package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.ProctorDtos.EventBatchRequest;
import com.examora.dto.ProctorDtos.ProctorEventDto;
import com.examora.dto.ProctorDtos.ProctorMonitorData;
import com.examora.service.ProctorService;
import com.examora.service.AuthService;
import com.examora.service.ExamAttemptService;
import com.examora.model.User;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
        examAttemptService.requireProctorAccess(attemptId, user);
        return ApiResponse.ok(Map.of("attemptId", attemptId, "status", "started"));
    }

    @PostMapping("/attempts/{attemptId}/stop")
    public ApiResponse<Map<String, String>> stop(@PathVariable String attemptId, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        examAttemptService.requireProctorAccess(attemptId, user);
        return ApiResponse.ok(Map.of("attemptId", attemptId, "status", "stopped"));
    }

    @GetMapping("/exams/{examId}/monitor")
    public ApiResponse<ProctorMonitorData> monitor(@PathVariable String examId, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(proctorService.monitor(examId, user));
    }

    @GetMapping("/attempts/{attemptId}/events")
    public ApiResponse<List<ProctorEventDto>> events(@PathVariable String attemptId, @RequestParam(defaultValue = "50") int limit, @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(proctorService.events(attemptId, user, limit));
    }
}