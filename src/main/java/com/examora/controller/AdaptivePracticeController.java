package com.examora.controller;

import com.examora.dto.AdaptivePracticeDtos.PracticeAnswerRequest;
import com.examora.dto.AdaptivePracticeDtos.PracticeAnswerResponse;
import com.examora.dto.AdaptivePracticeDtos.PracticeSessionDto;
import com.examora.dto.AdaptivePracticeDtos.PracticeStartRequest;
import com.examora.dto.ApiResponse;
import com.examora.model.User;
import com.examora.service.AdaptivePracticeService;
import com.examora.service.AdaptivePracticeService.SessionResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/adaptive")
public class AdaptivePracticeController {
    private final AdaptivePracticeService service;

    public AdaptivePracticeController(AdaptivePracticeService service) {
        this.service = service;
    }

    @GetMapping("/exams/{examId}/session")
    public ResponseEntity<ApiResponse<PracticeSessionDto>> resumeOrCreate(@PathVariable String examId,
                                                                          Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        SessionResult result = service.resumeOrCreate(examId, student);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.created() ? "Practice session created." : "Practice session resumed.", result.dto()));
    }

    @PostMapping("/exams/{examId}/sessions")
    public ResponseEntity<ApiResponse<PracticeSessionDto>> startNew(@PathVariable String examId,
                                                                     @RequestBody(required = false) PracticeStartRequest request,
                                                                     Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        Integer target = request == null ? null : request.targetQuestionCount();
        PracticeSessionDto dto = service.startNew(examId, student, target);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Practice session created.", dto));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<PracticeSessionDto> get(@PathVariable String sessionId, Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(service.getSession(sessionId, student));
    }

    @PostMapping("/sessions/{sessionId}/answer")
    public ApiResponse<PracticeAnswerResponse> answer(@PathVariable String sessionId,
                                                      @RequestBody PracticeAnswerRequest request,
                                                      Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok("Graded.", service.submitAnswer(sessionId, student, request));
    }
}