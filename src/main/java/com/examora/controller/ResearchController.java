package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.ResearchDtos.ConditionCatalogDto;
import com.examora.dto.ResearchDtos.ExperimentCreateRequest;
import com.examora.dto.ResearchDtos.ExperimentStatusRequest;
import com.examora.dto.ResearchDtos.ResearchExperimentDto;
import com.examora.dto.ResearchDtos.ResearchRunDto;
import com.examora.dto.ResearchDtos.ResearchSampleDto;
import com.examora.dto.ResearchDtos.ReviewRequest;
import com.examora.dto.ResearchDtos.RunCreateRequest;
import com.examora.dto.ResearchDtos.RunDetailDto;
import com.examora.dto.ResearchDtos.RunSampleCreateRequest;
import com.examora.dto.ResearchDtos.RunSampleDetailDto;
import com.examora.dto.ResearchDtos.RunSampleDto;
import com.examora.dto.ResearchDtos.SampleCaptureRequest;
import com.examora.dto.ResearchDtos.SampleCreateRequest;
import com.examora.dto.ResearchDtos.ScenarioInstructionDto;
import com.examora.dto.ResearchDtos.StudyEvaluationDto;
import com.examora.model.User;
import com.examora.service.AuthService;
import com.examora.service.ResearchRunService;
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
    private final ResearchRunService researchRunService;
    private final AuthService authService;

    public ResearchController(ResearchService researchService,
                              ResearchRunService researchRunService,
                              AuthService authService) {
        this.researchService = researchService;
        this.researchRunService = researchRunService;
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

    @GetMapping("/experiments/{experimentId}/evaluate")
    public ApiResponse<StudyEvaluationDto> runEvaluationGet(
            @PathVariable String experimentId,
            @RequestParam(required = false) String condition,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchService.evaluate(admin, experimentId, condition));
    }

    @PostMapping("/experiments/{experimentId}/evaluate")
    public ApiResponse<StudyEvaluationDto> runEvaluationPost(
            @PathVariable String experimentId,
            @RequestBody(required = false) String ignored,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchService.evaluate(admin, experimentId, null));
    }

    @PostMapping("/experiments/{experimentId}/runs")
    public ApiResponse<ResearchRunDto> createRun(
            @PathVariable String experimentId,
            @RequestBody RunCreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.createRun(admin, experimentId, request));
    }

    @GetMapping("/experiments/{experimentId}/runs")
    public ApiResponse<List<ResearchRunDto>> listRuns(
            @PathVariable String experimentId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.listRuns(admin, experimentId));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<RunDetailDto> getRun(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.getRun(admin, runId));
    }

    @PostMapping("/runs/{runId}/start")
    public ApiResponse<ResearchRunDto> startRun(
            @PathVariable String runId,
            @RequestBody(required = false) String ignored,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.startRun(admin, runId));
    }

    @PostMapping("/runs/{runId}/complete")
    public ApiResponse<ResearchRunDto> completeRun(
            @PathVariable String runId,
            @RequestBody(required = false) String ignored,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.completeRun(admin, runId));
    }

    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<ResearchRunDto> cancelRun(
            @PathVariable String runId,
            @RequestBody(required = false) String ignored,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.cancelRun(admin, runId));
    }

    @PostMapping("/runs/{runId}/samples")
    public ApiResponse<RunSampleDto> createPlannedSample(
            @PathVariable String runId,
            @RequestBody RunSampleCreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.createPlannedSample(admin, runId, request));
    }

    @GetMapping("/runs/{runId}/samples")
    public ApiResponse<RunDetailDto> listRunSamples(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.getRun(admin, runId));
    }

    @PostMapping("/runs/{runId}/samples/{runSampleId}/observe")
    public ApiResponse<RunSampleDto> observeSample(
            @PathVariable String runId,
            @PathVariable String runSampleId,
            @RequestBody(required = false) String ignored,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.observeSample(admin, runId, runSampleId));
    }

    @PostMapping("/runs/{runId}/samples/{runSampleId}/capture")
    public ApiResponse<RunSampleDto> captureSample(
            @PathVariable String runId,
            @PathVariable String runSampleId,
            @RequestBody SampleCaptureRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.captureSample(admin, runId, runSampleId, request));
    }

    @GetMapping("/run-samples/{runSampleId}/detail")
    public ApiResponse<RunSampleDetailDto> getRunSampleDetail(
            @PathVariable String runSampleId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.getRunSampleDetail(admin, runSampleId));
    }

    @GetMapping("/scenarios")
    public ApiResponse<List<ScenarioInstructionDto>> scenarioInstructions(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.scenarioInstructions(admin));
    }

    @GetMapping("/conditions")
    public ApiResponse<List<ConditionCatalogDto>> conditionCatalog(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User admin = authService.requireAdmin(authorizationHeader);
        return ApiResponse.ok(researchRunService.conditionCatalog(admin));
    }
}