package com.examora.controller;

import com.examora.dto.AiTutorDtos.AiTutorContextDto;
import com.examora.dto.AiTutorDtos.AiTutorQuestionAnswerRequest;
import com.examora.dto.AiTutorDtos.AiTutorQuestionAnswerResponse;
import com.examora.dto.AiTutorDtos.AiTutorQuestionCreateRequest;
import com.examora.dto.AiTutorDtos.AiTutorQuestionDto;
import com.examora.dto.AiTutorDtos.AiTutorRequest;
import com.examora.dto.ApiResponse;
import com.examora.model.User;
import com.examora.service.AiTutorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/ai-tutor")
public class AiTutorController {
    private final AiTutorService service;

    public AiTutorController(AiTutorService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<AiTutorContextDto> tutor(@RequestBody AiTutorRequest request, Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(service.context(student, request));
    }

    @PostMapping("/questions")
    public ResponseEntity<ApiResponse<AiTutorQuestionDto>> question(@RequestBody AiTutorQuestionCreateRequest request,
                                                                    Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        AiTutorQuestionDto dto = service.createQuestion(student, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Tutor question created.", dto));
    }

    @PostMapping("/questions/{questionId}/answer")
    public ApiResponse<AiTutorQuestionAnswerResponse> answer(@PathVariable String questionId,
                                                             @RequestBody AiTutorQuestionAnswerRequest request,
                                                             Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(service.answerQuestion(student, questionId, request));
    }
}