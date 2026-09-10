package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.model.RetestRequest;
import com.examora.service.AuthService;
import com.examora.service.RetestRequestService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/retest-requests")
public class RetestRequestController {
    private final AuthService authService;
    private final RetestRequestService service;

    public RetestRequestController(AuthService authService, RetestRequestService service) {
        this.authService = authService;
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<RetestRequest>> mine(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok(service.mine(authService.requireUser(authorizationHeader)));
    }

    @PostMapping("/{examId}")
    public ApiResponse<RetestRequest> create(@PathVariable String examId,
                                             @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok("Requested", service.create(examId, authService.requireUser(authorizationHeader)));
    }

    @GetMapping("/admin")
    public ApiResponse<List<RetestRequest>> all(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok(service.all(authService.requireAdmin(authorizationHeader)));
    }

    @PostMapping("/admin/{id}/review")
    public ApiResponse<RetestRequest> review(@PathVariable String id,
                                             @RequestBody Map<String, String> body,
                                             @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok("Reviewed",
                service.review(id, body == null ? null : body.get("status"),
                        body == null ? null : body.get("reason"),
                        authService.requireAdmin(authorizationHeader)));
    }
}