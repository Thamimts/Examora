package com.examora.service;

import com.examora.dto.ResearchDtos.ExperimentCreateRequest;
import com.examora.dto.ResearchDtos.ResearchExperimentDto;
import com.examora.dto.ResearchDtos.ResearchSampleDto;
import com.examora.dto.ResearchDtos.ReviewRequest;
import com.examora.dto.ResearchDtos.SampleCreateRequest;
import com.examora.dto.ResearchDtos.StudyEvaluationDto;
import com.examora.exception.ApiException;
import com.examora.model.ExamAttempt;
import com.examora.model.User;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.ExamRepository;
import com.examora.repository.ResearchRepository;
import com.examora.repository.ResearchRepository.ResearchExperiment;
import com.examora.repository.ResearchRepository.ResearchReview;
import com.examora.repository.ResearchRepository.ResearchSample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the controlled research dataset and its experiment runner:
 * experiments, staged samples, human reviews, data-quality accounting, and
 * pure baseline/fusion evaluation. It never alters enforcement state and never
 * fabricates data — every metric derives from stored signals and
 * human-reviewed labels. All operations require an administrator.
 */
@Service
public class ResearchService {

    private final ResearchRepository researchRepository;
    private final ExamAttemptRepository examAttemptRepository;
    private final ExamRepository examRepository;
    private final ResearchExperimentRunner runner;

    public ResearchService(ResearchRepository researchRepository,
                           ExamAttemptRepository examAttemptRepository,
                           ExamRepository examRepository,
                           ResearchExperimentRunner runner) {
        this.researchRepository = researchRepository;
        this.examAttemptRepository = examAttemptRepository;
        this.examRepository = examRepository;
        this.runner = runner;
    }

    public ResearchExperimentDto createExperiment(User actor, ExperimentCreateRequest request) {
        requireAdmin(actor);
        String name = requireName(request.name());
        String description = trimToNull(request.description());
        if (description != null && description.length() > 500) {
            throw badRequest("description must be at most 500 characters");
        }
        String algorithmVersion = request.algorithmVersion() == null || request.algorithmVersion().isBlank()
                ? ResearchConfig.ALGORITHM_VERSION : request.algorithmVersion().trim();
        String baselineVersion = request.baselineVersion() == null || request.baselineVersion().isBlank()
                ? ResearchConfig.BASELINE_VERSION : request.baselineVersion().trim();
        String datasetVersion = request.datasetVersion() == null || request.datasetVersion().isBlank()
                ? ResearchConfig.DATASET_VERSION : request.datasetVersion().trim();
        ResearchValidation.validateExperimentVersions(algorithmVersion, baselineVersion);
        ResearchValidation.validateDatasetVersion(datasetVersion);
        String examId = trimToNull(request.examId());
        if (examId != null && examRepository.findById(examId).isEmpty()) {
            throw badRequest("examId does not reference a known exam");
        }
        ResearchExperiment experiment = researchRepository.insertExperiment(
                name, description, algorithmVersion, baselineVersion, datasetVersion, "DRAFT", examId, actor.id());
        return toDto(experiment);
    }

    public List<ResearchExperimentDto> listExperiments(User actor) {
        requireAdmin(actor);
        List<ResearchExperimentDto> result = new ArrayList<>();
        for (ResearchExperiment experiment : researchRepository.listExperiments()) {
            result.add(toDto(experiment));
        }
        return result;
    }

    public ResearchExperimentDto getExperiment(User actor, String experimentId) {
        requireAdmin(actor);
        return toDto(requireExperiment(experimentId));
    }

    public ResearchExperimentDto updateStatus(User actor, String experimentId, String status) {
        requireAdmin(actor);
        ResearchValidation.validateStatus(status);
        ResearchExperiment experiment = requireExperiment(experimentId);
        researchRepository.updateExperimentStatus(experiment.id(), status);
        return toDto(new ResearchExperiment(experiment.id(), experiment.name(), experiment.description(),
                experiment.algorithmVersion(), experiment.baselineVersion(), experiment.datasetVersion(),
                status, experiment.examId(), experiment.createdBy(), experiment.createdAt()));
    }

    public ResearchSampleDto createSample(User actor, String experimentId, SampleCreateRequest request) {
        requireAdmin(actor);
        ResearchExperiment experiment = requireExperiment(experimentId);
        if ("ARCHIVED".equals(experiment.status()) || "COMPLETED".equals(experiment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "experiment is not accepting new samples");
        }
        String windowStart = ResearchValidation.normalizeTimestamp(
                nullableFirst(request.windowStart(), request.startedAt()), "windowStart");
        String windowEnd = ResearchValidation.normalizeTimestamp(
                nullableFirst(request.windowEnd(), request.endedAt()), "windowEnd");
        Instant start = ResearchValidation.parseTimestamp(windowStart, "windowStart");
        Instant end = ResearchValidation.parseTimestamp(windowEnd, "windowEnd");
        ResearchValidation.validateWindow(start, end, Instant.now());

        String attemptId = trimToNull(request.attemptId());
        if (attemptId != null) {
            ExamAttempt attempt = examAttemptRepository.findById(attemptId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "attempt not found"));
            if (attempt.startedAt() != null && attempt.expiresAt() != null) {
                ResearchValidation.validateAttemptWindow(start, end, attempt.startedAt(), attempt.expiresAt());
            }
        }

        if (researchRepository.hasOverlappingSample(experiment.id(), attemptId, windowStart, windowEnd, null)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "window overlaps an existing sample of the same experiment and attempt");
        }

        ResearchValidation.validateScenario(request.scenario());
        ResearchValidation.validateLabel(request.label());
        ResearchValidation.validateConditionMetadata(request.conditions());
        ResearchValidation.validateByteCounts(request.rawMediaBytes(), request.signalBytes());
        ResearchValidation.validateMeasuredLatency(request.measuredLatencyMs());

        ResearchSample sample = researchRepository.insertSample(experiment.id(), attemptId, windowStart,
                windowEnd, request.scenario(), request.label(), request.conditions(),
                request.rawMediaBytes(), request.signalBytes(), request.measuredLatencyMs());
        return toDto(sample, 0);
    }

    public ResearchSampleDto addReview(User actor, String sampleId, ReviewRequest request) {
        requireAdmin(actor);
        ResearchSample sample = requireSample(sampleId);
        ResearchExperiment experiment = requireExperiment(sample.experimentId());
        if ("ARCHIVED".equals(experiment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "experiment is archived and read-only");
        }
        if (request.label() == null || request.label().isBlank()) {
            throw badRequest("label is required for a review");
        }
        ResearchValidation.validateLabel(request.label());
        ResearchValidation.validateConfidence(request.confidence());
        ResearchValidation.validateNotes(request.notes());

        researchRepository.upsertReview(sampleId, actor.id(), request.label(),
                request.confidence(), trimToNull(request.notes()));
        List<ResearchReview> reviews = researchRepository.findReviewsBySampleId(sampleId);
        String majority = ResearchValidation.majorityLabel(
                reviews.stream().map(ResearchReview::label).toList());
        researchRepository.updateSampleLabel(sampleId, majority);
        return toDto(requireSample(sampleId), reviews.size());
    }

    public StudyEvaluationDto evaluate(User actor, String experimentId, String conditionKey) {
        requireAdmin(actor);
        ResearchExperiment experiment = requireExperiment(experimentId);
        if (conditionKey != null && !ResearchConfig.CONDITION_KEYS.containsKey(conditionKey)) {
            throw badRequest("condition key must be one of: " + String.join(", ", ResearchConfig.CONDITION_KEYS.keySet()));
        }
        return runner.evaluate(experiment, conditionKey);
    }

    private void requireAdmin(User actor) {
        if (actor == null || actor.role() == null || !"ADMIN".equals(actor.role().name())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "administrator role required");
        }
    }

    private ResearchExperiment requireExperiment(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) {
            throw badRequest("experiment id is required");
        }
        return researchRepository.findExperimentById(experimentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "experiment not found"));
    }

    private ResearchSample requireSample(String sampleId) {
        if (sampleId == null || sampleId.isBlank()) {
            throw badRequest("sample id is required");
        }
        return researchRepository.findSampleById(sampleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "sample not found"));
    }

    private String requireName(String name) {
        String trimmed = trimToNull(name);
        if (trimmed == null) {
            throw badRequest("name is required");
        }
        if (trimmed.length() > 180) {
            throw badRequest("name must be at most 180 characters");
        }
        return trimmed;
    }

    private static String nullableFirst(String primary, String alias) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return alias;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private ResearchExperimentDto toDto(ResearchExperiment experiment) {
        return new ResearchExperimentDto(experiment.id(), experiment.name(), experiment.description(),
                experiment.algorithmVersion(), experiment.baselineVersion(), experiment.datasetVersion(),
                experiment.status(), experiment.examId(), experiment.createdAt(), experiment.createdBy());
    }

    private ResearchSampleDto toDto(ResearchSample sample, long reviewCount) {
        return new ResearchSampleDto(sample.id(), sample.experimentId(), sample.attemptId(),
                sample.windowStart(), sample.windowEnd(), sample.windowStart(), sample.windowEnd(),
                sample.scenario(), sample.label(), sample.metadata(),
                sample.rawMediaBytes(), sample.signalBytes(), sample.measuredLatencyMs(),
                reviewCount, sample.createdAt());
    }
}