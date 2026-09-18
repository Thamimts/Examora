package com.examora.service;

import com.examora.dto.ResearchDtos.BandwidthStatsDto;
import com.examora.dto.ResearchDtos.ConditionEvaluationDto;
import com.examora.dto.ResearchDtos.ConfusionDto;
import com.examora.dto.ResearchDtos.EvaluatorMetricsDto;
import com.examora.dto.ResearchDtos.EvaluatorResultDto;
import com.examora.dto.ResearchDtos.ExperimentCreateRequest;
import com.examora.dto.ResearchDtos.LatencyStatsDto;
import com.examora.dto.ResearchDtos.ResearchExperimentDto;
import com.examora.dto.ResearchDtos.ResearchSampleDto;
import com.examora.dto.ResearchDtos.ReviewRequest;
import com.examora.dto.ResearchDtos.SampleCreateRequest;
import com.examora.dto.ResearchDtos.StudyEvaluationDto;
import com.examora.exception.ApiException;
import com.examora.model.ExamAttempt;
import com.examora.model.User;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.ProctorRepository;
import com.examora.repository.ResearchRepository;
import com.examora.repository.ResearchRepository.ResearchExperiment;
import com.examora.repository.ResearchRepository.ResearchSample;
import com.examora.service.ProctorFusionService.FusionSignal;
import com.examora.service.ResearchEvaluationEngine.ConditionGroup;
import com.examora.service.ResearchEvaluationEngine.EvaluatorResult;
import com.examora.service.ResearchEvaluationEngine.SampleEvaluation;
import com.examora.service.ResearchEvaluationEngine.StudyEvaluation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the research evaluation framework: experiments, staged samples,
 * human reviews, and pure baseline/fusion evaluation. It never alters
 * enforcement state and never fabricates data — every metric derives from
 * stored signals and human-reviewed labels.
 */
@Service
public class ResearchService {

    private final ResearchRepository researchRepository;
    private final ProctorRepository proctorRepository;
    private final ExamAttemptRepository examAttemptRepository;
    private final ResearchEvaluationEngine engine;

    public ResearchService(ResearchRepository researchRepository,
                           ProctorRepository proctorRepository,
                           ExamAttemptRepository examAttemptRepository,
                           ProctorFusionService fusionService) {
        this.researchRepository = researchRepository;
        this.proctorRepository = proctorRepository;
        this.examAttemptRepository = examAttemptRepository;
        this.engine = new ResearchEvaluationEngine(fusionService, ResearchConfig.FUSION_THRESHOLD);
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
        ResearchValidation.validateExperimentVersions(algorithmVersion, baselineVersion);
        ResearchExperiment experiment = researchRepository.insertExperiment(
                name, description, algorithmVersion, baselineVersion, "DRAFT", actor.id());
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
                experiment.algorithmVersion(), experiment.baselineVersion(), status,
                experiment.createdBy(), experiment.createdAt()));
    }

    public ResearchSampleDto createSample(User actor, String experimentId, SampleCreateRequest request) {
        requireAdmin(actor);
        ResearchExperiment experiment = requireExperiment(experimentId);
        if ("ARCHIVED".equals(experiment.status()) || "COMPLETED".equals(experiment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "experiment is not accepting new samples");
        }
        String windowStart = ResearchValidation.normalizeTimestamp(request.windowStart(), "windowStart");
        String windowEnd = ResearchValidation.normalizeTimestamp(request.windowEnd(), "windowEnd");
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

        ResearchValidation.validateLabel(request.label());
        ResearchValidation.validateConditionMetadata(request.conditions());
        ResearchValidation.validateByteCounts(request.rawMediaBytes(), request.signalBytes());

        ResearchSample sample = researchRepository.insertSample(experiment.id(), attemptId, windowStart,
                windowEnd, request.label(), request.conditions(), request.rawMediaBytes(), request.signalBytes());
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
        List<ResearchRepository.ResearchReview> reviews = researchRepository.findReviewsBySampleId(sampleId);
        researchRepository.updateSampleLabel(sampleId, majorityLabel(reviews));
        return toDto(requireSample(sampleId), reviews.size());
    }

    public StudyEvaluationDto evaluate(User actor, String experimentId, String conditionKey) {
        requireAdmin(actor);
        ResearchExperiment experiment = requireExperiment(experimentId);
        if (conditionKey != null && !ResearchConfig.CONDITION_KEYS.containsKey(conditionKey)) {
            throw badRequest("condition key must be one of: " + String.join(", ", ResearchConfig.CONDITION_KEYS.keySet()));
        }
        List<ResearchSample> samples = researchRepository.findSamplesByExperiment(
                experiment.id(), ResearchConfig.MAX_SAMPLES_PER_EXPERIMENT);
        Map<String, Long> reviewCounts = researchRepository.countReviewsByExperiment(experiment.id());

        List<SampleEvaluation> inputs = new ArrayList<>();
        for (ResearchSample sample : samples) {
            long reviewCount = reviewCounts.getOrDefault(sample.id(), 0L);
            String label = sample.label();
            boolean reviewed = reviewCount > 0;
            Boolean actualPositive = reviewed && label != null
                    ? !ResearchValidation.isNegativeLabel(label) : null;
            String conditionValue = conditionKey == null ? null : sample.metadata().get(conditionKey);
            inputs.add(new SampleEvaluation(sample.id(),
                    ResearchValidation.parseTimestamp(sample.windowStart(), "windowStart"),
                    ResearchValidation.parseTimestamp(sample.windowEnd(), "windowEnd"),
                    actualPositive, reviewed, windowSignals(sample), sample.rawMediaBytes(),
                    sample.signalBytes(), conditionValue));
        }

        StudyEvaluation study = engine.evaluate(inputs, experiment.baselineVersion(), experiment.algorithmVersion());
        List<ConditionEvaluationDto> conditions = null;
        if (conditionKey != null) {
            conditions = new ArrayList<>();
            for (ConditionGroup group : engine.evaluateByCondition(inputs,
                    experiment.baselineVersion(), experiment.algorithmVersion())) {
                conditions.add(new ConditionEvaluationDto(group.conditionValue(), group.sampleCount(),
                        toDto(group.baseline()), toDto(group.fusion())));
            }
        }
        return new StudyEvaluationDto(experiment.id(), study.totalSamples(), study.evaluatedSamples(),
                study.reviewedSamples(), study.unevaluatedSamples(),
                toDto(study.baseline()), toDto(study.fusion()),
                toDto(study.latency()), toDto(study.bandwidth()), conditions);
    }

    private List<FusionSignal> windowSignals(ResearchSample sample) {
        if (sample.attemptId() == null) {
            return List.of();
        }
        Instant start = ResearchValidation.parseTimestamp(sample.windowStart(), "windowStart");
        Instant end = ResearchValidation.parseTimestamp(sample.windowEnd(), "windowEnd");
        String from = start.minusMillis(ResearchConfig.WINDOW_SLACK_MS).toString();
        String to = end.plusMillis(ResearchConfig.WINDOW_SLACK_MS).toString();
        return ProctorFusionCoordinator.toFusionSignals(
                proctorRepository.findByAttemptIdWithin(sample.attemptId(), from, to,
                        ResearchConfig.MAX_WINDOW_SIGNALS));
    }

    private String majorityLabel(List<ResearchRepository.ResearchReview> reviews) {
        if (reviews.isEmpty()) {
            return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ResearchRepository.ResearchReview review : reviews) {
            counts.merge(review.label(), 1, Integer::sum);
        }
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> leaders = counts.entrySet().stream()
                .filter(entry -> entry.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();
        return leaders.size() == 1 ? leaders.getFirst() : null;
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
                experiment.algorithmVersion(), experiment.baselineVersion(), experiment.status(),
                experiment.createdAt(), experiment.createdBy());
    }

    private ResearchSampleDto toDto(ResearchSample sample, long reviewCount) {
        return new ResearchSampleDto(sample.id(), sample.experimentId(), sample.attemptId(),
                sample.windowStart(), sample.windowEnd(), sample.label(), sample.metadata(),
                sample.rawMediaBytes(), sample.signalBytes(), reviewCount, sample.createdAt());
    }

    private EvaluatorResultDto toDto(EvaluatorResult result) {
        ResearchMetrics.Confusion c = result.confusion();
        ConfusionDto confusion = new ConfusionDto(c.truePositives(), c.trueNegatives(),
                c.falsePositives(), c.falseNegatives());
        ResearchMetrics.EvaluatorMetrics m = result.metrics();
        EvaluatorMetricsDto metrics = new EvaluatorMetricsDto(m.precision(), m.recall(), m.specificity(),
                m.accuracy(), m.falsePositiveRate(), m.falseNegativeRate(), m.f1(), m.wrongfulWarningRate());
        return new EvaluatorResultDto(result.version(), confusion, metrics);
    }

    private LatencyStatsDto toDto(ResearchMetrics.LatencyStats stats) {
        return new LatencyStatsDto(stats.measuredCount(), stats.meanMs(), stats.medianMs(),
                stats.minMs(), stats.maxMs());
    }

    private BandwidthStatsDto toDto(ResearchMetrics.BandwidthStats stats) {
        return new BandwidthStatsDto(stats.measuredCount(), stats.meanSignalBytes(),
                stats.meanRawMediaBytes(), stats.meanDataMinimizationRatio());
    }
}