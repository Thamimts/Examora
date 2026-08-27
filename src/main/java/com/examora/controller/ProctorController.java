package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.ProctorDtos.EventBatchRequest;
import com.examora.service.ProctorService;
import com.examora.service.AuthService;
import com.examora.model.User;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proctor")
public class ProctorController {
    private final ProctorService proctorService;
    private final AuthService authService;

    public ProctorController(ProctorService proctorService, AuthService authService) {
        this.proctorService = proctorService;
        this.authService = authService;
    }

    @PostMapping("/events/batch")
    public ApiResponse<Map<String, Integer>> events(@RequestBody EventBatchRequest request, @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User user = authService.requireUser(authorizationHeader);
        int saved = proctorService.saveBatch(request == null ? null : request.events(), user);
        return ApiResponse.ok(Map.of("saved", saved));
    }

    @PostMapping("/attempts/{attemptId}/start")
    public ApiResponse<Map<String, String>> start(@PathVariable String attemptId) {
        return ApiResponse.ok(Map.of("attemptId", attemptId, "status", "started"));
    }

    @PostMapping("/attempts/{attemptId}/stop")
    public ApiResponse<Map<String, String>> stop(@PathVariable String attemptId) {
        return ApiResponse.ok(Map.of("attemptId", attemptId, "status", "stopped"));
    }
}
