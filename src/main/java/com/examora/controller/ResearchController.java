package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.ResearchDtos.ExperimentCreateRequest;
import com.examora.dto.ResearchDtos.ExperimentStatusRequest;
import com.examora.dto.ResearchDtos.ResearchExperimentDto;
import com.examora.dto.ResearchDtos.ResearchSampleDto;
import com.examora.dto.ResearchDtos.ReviewRequest;
import com.examora.dto.ResearchDtos.SampleCreateRequest;
import com.examora.dto.ResearchDtos.StudyEvaluationDto;
import com.examora.model.User;
import com.examora.service.AuthService;
import com.examora.service.ResearchService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proctor/research")
public class ResearchController {

    private final ResearchService researchService;
    private final AuthService authService;

    public ResearchController(ResearchService researchService, AuthService authService) {
        this.researchService = researchService;
        this.authService = authService;
    }

    @PostMapping("/experiments")
    public ApiResponse<ResearchExperimentDto> createExperiment(
            @RequestBody ExperimentCreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchService.createExperiment(admin, request));
    }

    @GetMapping("/experiments")
    public ApiResponse<List<ResearchExperimentDto>> listExperiments(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchService.listExperiments(admin));
    }

    @GetMapping("/experiments/{experimentId}")
    public ApiResponse<ResearchExperimentDto> getExperiment(
            @PathVariable String experimentId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchService.getExperiment(admin, experimentId));
    }

    @PostMapping("/experiments/{experimentId}/status")
    public ApiResponse<ResearchExperimentDto> updateStatus(
            @PathVariable String experimentId,
            @RequestBody ExperimentStatusRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchService.updateStatus(admin, experimentId, request.status()));
    }

    @PostMapping("/experiments/{experimentId}/samples")
    public ApiResponse<ResearchSampleDto> createSample(
            @PathVariable String experimentId,
            @RequestBody SampleCreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchService.createSample(admin, experimentId, request));
    }

    @PostMapping("/samples/{sampleId}/review")
    public ApiResponse<ResearchSampleDto> addReview(
            @PathVariable String sampleId,
            @RequestBody ReviewRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchService.addReview(admin, sampleId, request));
    }

    @GetMapping("/experiments/{experimentId}/evaluation")
    public ApiResponse<StudyEvaluationDto> evaluate(
            @PathVariable String experimentId,
            @RequestParam(required = false) String condition,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchService.evaluate(admin, experimentId, condition));
    }
}