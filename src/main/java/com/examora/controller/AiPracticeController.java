package com.examora.controller;

import com.examora.dto.AiPracticeDtos.AiPracticeAnswerRequest;
import com.examora.dto.AiPracticeDtos.AiPracticeAnswerResponse;
import com.examora.dto.AiPracticeDtos.AiPracticeCreateRequest;
import com.examora.dto.AiPracticeDtos.AiPracticeExplanationRequest;
import com.examora.dto.AiPracticeDtos.AiPracticeProgressDto;
import com.examora.dto.AiPracticeDtos.AiPracticeReviewDto;
import com.examora.dto.AiPracticeDtos.AiPracticeSessionDto;
import com.examora.dto.AiPracticeDtos.AiPracticeSessionSummaryDto;
import com.examora.dto.AiPracticeDtos.AiPracticeSubmitResponse;
import com.examora.dto.ApiResponse;
import com.examora.model.User;
import com.examora.service.AiPracticeService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/ai-practice")
public class AiPracticeController {
    private final AiPracticeService service;

    public AiPracticeController(AiPracticeService service) {
        this.service = service;
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<AiPracticeSessionDto>> create(@RequestBody AiPracticeCreateRequest request,
                                                                    Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        AiPracticeSessionDto dto = service.createSession(student, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Practice session created.", dto));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AiPracticeSessionSummaryDto>> sessions(
            @RequestParam(defaultValue = "20") int limit, Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(service.listSessions(student, Math.min(Math.max(limit, 1), 100)));
    }

    @GetMapping("/progress")
    public ApiResponse<List<AiPracticeProgressDto>> progress(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(service.progress(student));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<AiPracticeSessionDto> get(@PathVariable String sessionId, Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(service.getSession(sessionId, student));
    }

    @PostMapping("/sessions/{sessionId}/answer")
    public ApiResponse<AiPracticeAnswerResponse> answer(@PathVariable String sessionId,
                                                        @RequestBody AiPracticeAnswerRequest request,
                                                        Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok("Answer recorded.", service.answer(sessionId, student, request));
    }

    @PostMapping("/sessions/{sessionId}/submit")
    public ApiResponse<AiPracticeSubmitResponse> submit(@PathVariable String sessionId, Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok("Practice submitted and graded.", service.submit(sessionId, student));
    }

    @GetMapping("/sessions/{sessionId}/review")
    public ApiResponse<AiPracticeReviewDto> review(@PathVariable String sessionId, Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(service.review(sessionId, student));
    }

    @PostMapping("/sessions/{sessionId}/explanations")
    public ApiResponse<String> explanations(@PathVariable String sessionId,
                                            @RequestBody AiPracticeExplanationRequest request,
                                            Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        service.saveExplanations(sessionId, student, request);
        return ApiResponse.ok("Explanations saved.");
    }
}