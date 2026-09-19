package com.examora.service;

import com.examora.dto.ResearchDtos.BandwidthStatsDto;
import com.examora.dto.ResearchDtos.ConditionCatalogDto;
import com.examora.dto.ResearchDtos.ConditionDistributionDto;
import com.examora.dto.ResearchDtos.ConditionGroupEvaluationDto;
import com.examora.dto.ResearchDtos.ConditionOptionDto;
import com.examora.dto.ResearchDtos.ConditionValueCountDto;
import com.examora.dto.ResearchDtos.CompletionSummaryDto;
import com.examora.dto.ResearchDtos.ConfusionDto;
import com.examora.dto.ResearchDtos.DisagreementDto;
import com.examora.dto.ResearchDtos.EvaluationSnapshotDto;
import com.examora.dto.ResearchDtos.EvaluatorMetricsDto;
import com.examora.dto.ResearchDtos.EvaluatorResultDto;
import com.examora.dto.ResearchDtos.LatencyStatsDto;
import com.examora.dto.ResearchDtos.ReviewStatusDto;
import com.examora.dto.ResearchDtos.RunCreateRequest;
import com.examora.dto.ResearchDtos.RunDataQualityDto;
import com.examora.dto.ResearchDtos.RunDetailDto;
import com.examora.dto.ResearchDtos.RunEvaluationDto;
import com.examora.dto.ResearchDtos.RunMatrixSummaryDto;
import com.examora.dto.ResearchDtos.RunProgressDto;
import com.examora.dto.ResearchDtos.RunSampleCreateRequest;
import com.examora.dto.ResearchDtos.RunSampleDetailDto;
import com.examora.dto.ResearchDtos.RunSampleDto;
import com.examora.dto.ResearchDtos.ResearchRunDto;
import com.examora.dto.ResearchDtos.SampleCaptureRequest;
import com.examora.dto.ResearchDtos.ScenarioInstructionDto;
import com.examora.dto.ResearchDtos.ScenarioOutcomeDto;
import com.examora.dto.ResearchDtos.ScenarioProgressDto;
import com.examora.dto.ResearchDtos.SignalSourceCountDto;
import com.examora.dto.ResearchDtos.SignalTypeCountDto;
import com.examora.exception.ApiException;
import com.examora.model.ExamAttempt;
import com.examora.model.User;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.ProctorRepository;
import com.examora.repository.ResearchRepository;
import com.examora.repository.ResearchRepository.ResearchExperiment;
import com.examora.repository.ResearchRepository.ResearchRun;
import com.examora.repository.ResearchRepository.ResearchRunSample;
import com.examora.repository.ResearchRepository.ResearchSample;
import com.examora.service.ProctorFusionService.FusionSignal;
import com.examora.service.ResearchEvaluationEngine.ConditionGroup;
import com.examora.service.ResearchEvaluationEngine.EvaluatorResult;
import com.examora.service.ResearchEvaluationEngine.PerSamplePrediction;
import com.examora.service.ResearchEvaluationEngine.SampleEvaluation;
import com.examora.service.ResearchEvaluationEngine.StudyEvaluation;
import com.examora.service.ResearchRunDataQuality.Entry;
import com.examora.service.ResearchRunDataQuality.MatrixRow;
import com.examora.service.ResearchRunDataQuality.Report;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Controlled research data collection: research runs, the planned-sample matrix (scenario x
 * controlled condition), server-timed observation capture driven by the existing proctor
 * signal pipeline, human review, and run-level data quality. The service never fabricates
 * data: observation start is stamped by the server, windows are validated, and completion is
 * guarded while any sample is mid-capture. All operations require an administrator.
 */
@Service
public class ResearchRunService {

    private static final Map<String, String> CONDITION_LABELS = Map.of(
            "lighting", "Lighting",
            "cameraQuality", "Camera quality",
            "network", "Network",
            "cameraAngle", "Camera angle");

    private static final Map<String, String> CONDITION_VALUE_DESCRIPTIONS = Map.of(
            "GOOD", "well-lit environment",
            "LOW", "dimly lit environment",
            "HD", "high-definition camera",
            "STABLE", "reliable connectivity",
            "INTERRUPTED", "interrupted connectivity",
            "FRONT", "camera facing the student",
            "OFF_ANGLE", "camera at an angle");

    private final ResearchRepository researchRepository;
    private final ProctorRepository proctorRepository;
    private final ExamAttemptRepository examAttemptRepository;
    private final ResearchExperimentRunner runner;

    public ResearchRunService(ResearchRepository researchRepository,
                              ProctorRepository proctorRepository,
                              ExamAttemptRepository examAttemptRepository,
                              ResearchExperimentRunner runner) {
        this.researchRepository = researchRepository;
        this.proctorRepository = proctorRepository;
        this.examAttemptRepository = examAttemptRepository;
        this.runner = runner;
    }

    private static final String[] CONTROLLED_CONDITION_KEYS =
            {"lighting", "cameraQuality", "network", "cameraAngle"};

    public ResearchRunDto createRun(User actor, String experimentId, RunCreateRequest request) {
        requireAdmin(actor);
        ResearchExperiment experiment = requireExperiment(experimentId);
        if ("ARCHIVED".equals(experiment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "experiment is archived and read-only");
        }
        String runCode = ResearchValidation.normalizeRunCode(request.runCode());
        String datasetVersion = request.datasetVersion() == null || request.datasetVersion().isBlank()
                ? ResearchConfig.DATASET_VERSION : request.datasetVersion().trim();
        ResearchValidation.validateDatasetVersion(datasetVersion);
        String notes = trimToNull(request.notes());
        ResearchValidation.validateRunNotes(notes);
        ResearchRun run = researchRepository.insertRun(experiment.id(), runCode,
                datasetVersion, "PLANNED", notes, actor.id());
        return toRunDto(run);
    }

    public List<ResearchRunDto> listRuns(User actor, String experimentId) {
        requireAdmin(actor);
        requireExperiment(experimentId);
        List<ResearchRunDto> result = new ArrayList<>();
        for (ResearchRun run : researchRepository.listRunsByExperiment(experimentId)) {
            result.add(toRunDto(run));
        }
        return result;
    }

    public RunDetailDto getRun(User actor, String runId) {
        requireAdmin(actor);
        ResearchRun run = requireRun(runId);
        ResearchExperiment experiment = requireExperiment(run.experimentId());
        List<ResearchRunSample> runSamples = researchRepository.listRunSamplesByRun(
                runId, ResearchConfig.MAX_RUN_SAMPLES);

        List<ResearchSample> experimentSamples = researchRepository.findSamplesByExperiment(
                experiment.id(), ResearchConfig.MAX_SAMPLES_PER_EXPERIMENT);
        Map<String, ResearchSample> sampleById = new HashMap<>();
        boolean[] invalidMask = ResearchSampleValidity.markInvalid(experimentSamples,
                id -> examAttemptRepository.findById(id).orElse(null));
        for (int i = 0; i < experimentSamples.size(); i++) {
            sampleById.put(experimentSamples.get(i).id(), experimentSamples.get(i));
        }
        Map<String, Integer> invalidBySampleId = new HashMap<>();
        for (int i = 0; i < experimentSamples.size(); i++) {
            if (invalidMask[i]) {
                invalidBySampleId.put(experimentSamples.get(i).id(), 1);
            }
        }
        Map<String, List<String>> reviewLabels = researchRepository.reviewLabelsByExperiment(experiment.id());

        List<RunSampleDto> samples = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        for (ResearchRunSample runSample : runSamples) {
            String researchSampleId = runSample.researchSampleId();
            ResearchSample researchSample = researchSampleId == null ? null : sampleById.get(researchSampleId);
            boolean captured = "CAPTURED".equals(runSample.status());
            boolean linked = researchSample != null;
            List<String> labels = researchSampleId == null ? List.of()
                    : reviewLabels.getOrDefault(researchSampleId, List.of());
            boolean reviewed = !labels.isEmpty();
            String resolvedLabel = ResearchValidation.majorityLabel(labels);
            boolean invalid = researchSampleId != null && invalidBySampleId.containsKey(researchSampleId);
            long signalCount = captured && runSample.startedAt() != null && runSample.endedAt() != null
                    ? proctorRepository.countByAttemptIdWithin(runSample.attemptId(), runSample.startedAt(),
                    runSample.endedAt())
                    : 0L;
            Set<String> unexpected = captured
                    ? unexpectedSignalTypes(runSample, runSample.startedAt(), runSample.endedAt()) : Set.of();
            boolean hasSignals = captured && signalCount > 0;
            boolean evaluable = reviewed && resolvedLabel != null && !invalid;
            boolean tied = reviewed && resolvedLabel == null;
            boolean measuredLatencyPresent = runSample.measuredLatencyMs() != null;
            boolean bandwidthPresent = runSample.rawMediaBytes() != null && runSample.signalBytes() != null;
            entries.add(new Entry(runSample.id(), runSample.status(), linked, reviewed, evaluable,
                    resolvedLabel, runSample.scenario(), invalid, hasSignals, unexpected,
                    measuredLatencyPresent, bandwidthPresent, runSample.conditionsKey()));
            samples.add(new RunSampleDto(runSample.id(), runSample.runId(), runSample.attemptId(),
                    runSample.scenario(), runSample.conditions(), runSample.status(),
                    runSample.startedAt(), runSample.endedAt(), runSample.measuredLatencyMs(),
                    runSample.rawMediaBytes(), runSample.signalBytes(), researchSampleId,
                    signalCount, reviewed, evaluable, tied, runSample.createdAt()));
        }

        Report report = ResearchRunDataQuality.compute(entries);
        List<MatrixRow> matrixRows = ResearchRunDataQuality.matrix(entries);
        List<RunMatrixSummaryDto> matrix = new ArrayList<>();
        for (MatrixRow row : matrixRows) {
            matrix.add(new RunMatrixSummaryDto(row.scenario(),
                    ResearchValidation.scenarioLabel(row.scenario()), row.planned(),
                    row.captured(), row.reviewed(), row.evaluable(), row.conditionsKey()));
        }
        List<ScenarioOutcomeDto> scenarios = runScenarios(entries);

        return new RunDetailDto(toRunDto(run),
                new RunDataQualityDto(report.planned(), report.capturing(), report.captured(),
                        report.reviewed(), report.evaluable(), report.unreviewed(), report.tied(),
                        report.invalid(), report.missingSignals(), report.unexpectedSignals(),
                        report.scenarioGroundTruthAgreement(), report.scenarioGroundTruthDisagreement(),
                        report.missingMeasuredLatency(), report.missingBandwidth()),
                samples, matrix, scenarios);
    }

    public ResearchRunDto startRun(User actor, String runId) {
        requireAdmin(actor);
        ResearchRun run = requireRun(runId);
        if ("RUNNING".equals(run.status())) {
            return toRunDto(run);
        }
        if (!ResearchRunStateMachine.canStart(run.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "run cannot be started from status " + run.status());
        }
        int updated = researchRepository.transitionRun(runId, "'PLANNED','RUNNING'",
                "RUNNING", "started_at", Timestamp.from(Instant.now()));
        if (updated == 0) {
            ResearchRun current = requireRun(runId);
            if ("RUNNING".equals(current.status())) {
                return toRunDto(current);
            }
            throw new ApiException(HttpStatus.CONFLICT, "run could not be started");
        }
        return toRunDto(researchRepository.findRunById(runId).orElseThrow());
    }

    public ResearchRunDto completeRun(User actor, String runId) {
        requireAdmin(actor);
        ResearchRun run = requireRun(runId);
        if ("COMPLETED".equals(run.status())) {
            return toRunDto(run);
        }
        if (!ResearchRunStateMachine.canComplete(run.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "run cannot be completed from status " + run.status());
        }
        if (researchRepository.countRunSamplesWithStatus(runId, "CAPTURING") > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "run cannot be completed while a sample is still mid-capture");
        }
        int updated = researchRepository.transitionRun(runId, "'RUNNING'",
                "COMPLETED", "ended_at", Timestamp.from(Instant.now()));
        if (updated == 0) {
            ResearchRun current = requireRun(runId);
            if ("COMPLETED".equals(current.status())) {
                return toRunDto(current);
            }
            throw new ApiException(HttpStatus.CONFLICT, "run could not be completed");
        }
        return toRunDto(researchRepository.findRunById(runId).orElseThrow());
    }

    public ResearchRunDto cancelRun(User actor, String runId) {
        requireAdmin(actor);
        ResearchRun run = requireRun(runId);
        if ("CANCELLED".equals(run.status())) {
            return toRunDto(run);
        }
        if (!ResearchRunStateMachine.canCancel(run.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "run cannot be cancelled from status " + run.status());
        }
        int updated = researchRepository.transitionRun(runId, "'PLANNED','RUNNING'",
                "CANCELLED", "ended_at", Timestamp.from(Instant.now()));
        if (updated == 0) {
            ResearchRun current = requireRun(runId);
            if ("CANCELLED".equals(current.status())) {
                return toRunDto(current);
            }
            throw new ApiException(HttpStatus.CONFLICT, "run could not be cancelled");
        }
        return toRunDto(researchRepository.findRunById(runId).orElseThrow());
    }

    public RunSampleDto createPlannedSample(User actor, String runId,
                                                       RunSampleCreateRequest request) {
        requireAdmin(actor);
        ResearchRun run = requireRun(runId);
        if (!ResearchRunStateMachine.canCreatePlannedSample(run.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "run cannot accept planned samples in status " + run.status());
        }
        ResearchExperiment experiment = requireExperiment(run.experimentId());
        if ("ARCHIVED".equals(experiment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "experiment is archived and read-only");
        }
        if (request.attemptId() == null || request.attemptId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "attemptId is required");
        }
        ExamAttempt attempt = examAttemptRepository.findById(request.attemptId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "attempt not found"));
        if (!ResearchValidation.attemptBelongsToExperiment(attempt.examId(), experiment.examId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "attempt does not belong to the experiment's exam");
        }
        ResearchValidation.validateScenario(request.scenario());
        if (request.scenario() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "scenario is required");
        }
        ResearchValidation.validateControlledConditions(request.conditions());
        String conditionsKey = ResearchValidation.conditionsKey(request.conditions());

        ResearchRunSample sample = researchRepository.insertRunSample(run.id(), attempt.id(),
                request.scenario(), conditionsKey, request.conditions());
        return toRunSampleDto(sample, 0L);
    }

    public RunSampleDto observeSample(User actor, String runId, String runSampleId) {
        requireAdmin(actor);
        ResearchRun run = requireRun(runId);
        if (!"RUNNING".equals(run.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "observation requires a running run");
        }
        ResearchRunSample sample = requireRunSample(runSampleId, runId);
        if (!"PLANNED".equals(sample.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "sample is not in PLANNED state (current: " + sample.status() + ")");
        }
        String startedAt = Instant.now().toString();
        int updated = researchRepository.observeRunSample(runSampleId, startedAt);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "sample could not be observed");
        }
        return toRunSampleDto(requireRunSample(runSampleId, runId), 0L);
    }

    @Transactional
    public RunSampleDto captureSample(User actor, String runId, String runSampleId,
                                              SampleCaptureRequest request) {
        requireAdmin(actor);
        ResearchRun run = requireRun(runId);
        if (!"RUNNING".equals(run.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "capture requires a running run");
        }
        ResearchExperiment experiment = requireExperiment(run.experimentId());
        if ("ARCHIVED".equals(experiment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "experiment is archived and read-only");
        }
        ResearchRunSample sample = requireRunSample(runSampleId, runId);
        if (!"CAPTURING".equals(sample.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "sample must be observed first (current: " + sample.status() + ")");
        }
        String startedAt = sample.startedAt();
        if (startedAt == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sample has no observation start");
        }
        ResearchValidation.validateCaptureRequest(request.measuredLatencyMs(),
                request.rawMediaBytes(), request.signalBytes());
        String endedAt = ResearchValidation.normalizeTimestamp(request.endedAt(), "endedAt");
        Instant start = ResearchValidation.parseTimestamp(startedAt, "startedAt");
        Instant end = ResearchValidation.parseTimestamp(endedAt, "endedAt");
        ResearchValidation.validateWindow(start, end, Instant.now());

        ExamAttempt attempt = examAttemptRepository.findById(sample.attemptId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "attempt not found"));
        if (attempt.startedAt() != null && attempt.expiresAt() != null) {
            ResearchValidation.validateAttemptWindow(start, end, attempt.startedAt(), attempt.expiresAt());
        }
        if (researchRepository.hasOverlappingSample(experiment.id(), attempt.id(), startedAt, endedAt, null)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "window overlaps an existing sample of the same experiment and attempt");
        }
        if (researchRepository.hasOverlappingRunSample(run.id(), attempt.id(), startedAt, endedAt, runSampleId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "window overlaps a captured sample of the same run and attempt");
        }

        ResearchSample researchSample = researchRepository.insertSample(experiment.id(), attempt.id(),
                startedAt, endedAt, sample.scenario(), null, sample.conditions(),
                request.rawMediaBytes(), request.signalBytes(), request.measuredLatencyMs());
        int updated = researchRepository.captureRunSample(runSampleId, startedAt, endedAt,
                request.measuredLatencyMs(), request.rawMediaBytes(), request.signalBytes());
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "sample could not be captured");
        }
        researchRepository.linkRunSampleToResearchSample(runSampleId, researchSample.id());
        ResearchRunSample captured = requireRunSample(runSampleId, runId);
        return toRunSampleDto(captured, 0L);
    }

    public RunSampleDetailDto getRunSampleDetail(User actor, String runSampleId) {
        requireAdmin(actor);
        ResearchRunSample runSample = researchRepository.findRunSampleById(runSampleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "run sample not found"));
        ResearchSample researchSample = runSample.researchSampleId() == null ? null
                : researchRepository.findSampleById(runSample.researchSampleId()).orElse(null);

        long signalCount = 0L;
        List<SignalTypeCountDto> types = new ArrayList<>();
        List<SignalSourceCountDto> sources = new ArrayList<>();
        Double minConfidence = null;
        Double maxConfidence = null;
        Double meanConfidence = null;
        Long maxDurationMs = null;
        Boolean baselinePositive = null;
        Boolean fusionPositive = null;
        boolean scenarioAgreement = false;
        String groundTruthLabel = researchSample == null ? null : researchSample.label();

        if (researchSample != null) {
            signalCount = proctorRepository.countByAttemptIdWithin(runSample.attemptId(),
                    runSample.startedAt(), runSample.endedAt());
            Map<String, Integer> typeCounts = proctorRepository.signalTypeCountsByAttemptIdWithin(
                    runSample.attemptId(), runSample.startedAt(), runSample.endedAt());
            List<FusionSignal> signals = runner.windowSignals(researchSample);
            Map<String, Integer> sourceCounts = new LinkedHashMap<>();
            double confidenceSum = 0.0;
            int confidenceCount = 0;
            for (FusionSignal signal : signals) {
                sourceCounts.merge(signal.source(), 1, Integer::sum);
                if (signal.confidence() != null) {
                    confidenceSum += signal.confidence();
                    confidenceCount++;
                    if (minConfidence == null || signal.confidence() < minConfidence) {
                        minConfidence = signal.confidence();
                    }
                    if (maxConfidence == null || signal.confidence() > maxConfidence) {
                        maxConfidence = signal.confidence();
                    }
                }
                if (signal.durationMs() != null
                        && (maxDurationMs == null || signal.durationMs() > maxDurationMs)) {
                    maxDurationMs = signal.durationMs();
                }
            }
            if (confidenceCount > 0) {
                meanConfidence = confidenceSum / confidenceCount;
            }
            for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
                types.add(new SignalTypeCountDto(entry.getKey(), entry.getValue()));
            }
            for (Map.Entry<String, Integer> entry : sourceCounts.entrySet()) {
                sources.add(new SignalSourceCountDto(entry.getKey(), entry.getValue()));
            }
            PerSamplePrediction prediction = runner.predict(researchSample);
            baselinePositive = prediction.baselinePositive();
            fusionPositive = prediction.fusionPositive();
            scenarioAgreement = researchSample.scenario() != null
                    && researchSample.scenario().equals(researchSample.label());
        }

        return new RunSampleDetailDto(runSample.id(), runSample.runId(), runSample.attemptId(),
                runSample.scenario(), runSample.conditions(), runSample.status(),
                runSample.startedAt(), runSample.endedAt(), runSample.measuredLatencyMs(),
                runSample.rawMediaBytes(), runSample.signalBytes(), signalCount, types, sources,
                minConfidence, maxConfidence, meanConfidence, maxDurationMs,
                groundTruthLabel, baselinePositive, fusionPositive, scenarioAgreement);
    }

    public List<ScenarioInstructionDto> scenarioInstructions(User actor) {
        requireAdmin(actor);
        List<ScenarioInstructionDto> result = new ArrayList<>();
        for (String scenario : ResearchConfig.SCENARIO_ORDER) {
            ResearchConfig.ScenarioInstruction instruction = ResearchConfig.SCENARIO_INSTRUCTIONS.get(scenario);
            List<String> expectedSignals = new ArrayList<>(ResearchConfig.SCENARIO_EXPECTED_SIGNALS.get(scenario));
            java.util.Collections.sort(expectedSignals);
            result.add(new ScenarioInstructionDto(scenario, ResearchValidation.scenarioLabel(scenario),
                    instruction.description(), instruction.expectedAction(),
                    instruction.durationGuidance(), instruction.reviewerObservation(), expectedSignals));
        }
        return result;
    }

    public List<ConditionCatalogDto> conditionCatalog(User actor) {
        requireAdmin(actor);
        List<ConditionCatalogDto> result = new ArrayList<>();
        for (String key : CONTROLLED_CONDITION_KEYS) {
            List<String> values = new ArrayList<>(ResearchConfig.CONTROLLED_CONDITIONS.get(key));
            java.util.Collections.sort(values);
            List<ConditionOptionDto> options = new ArrayList<>();
            for (String value : values) {
                options.add(new ConditionOptionDto(value,
                        CONDITION_VALUE_DESCRIPTIONS.getOrDefault(value, "")));
            }
            result.add(new ConditionCatalogDto(key, CONDITION_LABELS.getOrDefault(key, key),
                    values, options));
        }
        return result;
    }

    /**
     * Run-scoped evaluation for the first controlled experiment: real progress versus the
     * informational pilot target (80 = 10 x 8 scenarios), scenario progress, per-condition
     * distribution from stored records, human-review agreement, baseline-v1 vs fusion-v1
     * metrics (including FDR) for the run's captured samples, disagreement analysis, per-dimension
     * condition breakdown, latency/bandwidth, completion summary, and a reproducibility snapshot.
     * Everything derives from persisted rows and pure functions; only {@code evaluatedAt} in the
     * snapshot varies between invocations. Tied, unreviewed, and invalid samples are never
     * silently resolved or evaluated.
     */
    public RunEvaluationDto evaluateRun(User actor, String runId) {
        requireAdmin(actor);
        ResearchRun run = requireRun(runId);
        ResearchExperiment experiment = requireExperiment(run.experimentId());
        List<ResearchRunSample> runSamples = researchRepository.listRunSamplesByRun(
                runId, ResearchConfig.MAX_RUN_SAMPLES);
        List<ResearchSample> experimentSamples = researchRepository.findSamplesByExperiment(
                experiment.id(), ResearchConfig.MAX_SAMPLES_PER_EXPERIMENT);
        Map<String, ResearchSample> sampleById = new HashMap<>();
        for (ResearchSample sample : experimentSamples) {
            sampleById.put(sample.id(), sample);
        }
        boolean[] invalidMask = ResearchSampleValidity.markInvalid(experimentSamples,
                id -> examAttemptRepository.findById(id).orElse(null));
        Set<String> invalidBySampleId = new HashSet<>();
        for (int i = 0; i < experimentSamples.size(); i++) {
            if (invalidMask[i]) {
                invalidBySampleId.add(experimentSamples.get(i).id());
            }
        }
        Map<String, List<String>> reviewLabels = researchRepository.reviewLabelsByExperiment(experiment.id());

        List<Entry> entries = new ArrayList<>();
        List<RunSampleContext> contexts = new ArrayList<>();
        for (ResearchRunSample runSample : runSamples) {
            boolean captured = "CAPTURED".equals(runSample.status());
            String researchSampleId = runSample.researchSampleId();
            ResearchSample researchSample = researchSampleId == null ? null : sampleById.get(researchSampleId);
            boolean linked = researchSample != null;
            List<String> labels = researchSampleId == null ? List.of()
                    : reviewLabels.getOrDefault(researchSampleId, List.of());
            boolean reviewed = !labels.isEmpty();
            String resolvedLabel = ResearchValidation.majorityLabel(labels);
            boolean invalid = researchSampleId != null && invalidBySampleId.contains(researchSampleId);
            long signalCount = captured && runSample.startedAt() != null && runSample.endedAt() != null
                    ? proctorRepository.countByAttemptIdWithin(runSample.attemptId(), runSample.startedAt(),
                    runSample.endedAt())
                    : 0L;
            Set<String> unexpected = captured
                    ? unexpectedSignalTypes(runSample, runSample.startedAt(), runSample.endedAt()) : Set.of();
            boolean hasSignals = captured && signalCount > 0;
            boolean evaluable = reviewed && resolvedLabel != null && !invalid;
            boolean latencyPresent = runSample.measuredLatencyMs() != null;
            boolean bandwidthPresent = runSample.rawMediaBytes() != null && runSample.signalBytes() != null;
            entries.add(new Entry(runSample.id(), runSample.status(), linked, reviewed, evaluable,
                    resolvedLabel, runSample.scenario(), invalid, hasSignals, unexpected,
                    latencyPresent, bandwidthPresent, runSample.conditionsKey()));
            if (captured && linked) {
                Boolean actualPositive = evaluable ? !ResearchValidation.isNegativeLabel(resolvedLabel) : null;
                SampleEvaluation evaluation = new SampleEvaluation(researchSample.id(),
                        ResearchValidation.parseTimestamp(runSample.startedAt(), "startedAt"),
                        ResearchValidation.parseTimestamp(runSample.endedAt(), "endedAt"),
                        actualPositive, reviewed, runner.windowSignals(researchSample),
                        researchSample.rawMediaBytes(), researchSample.signalBytes(), null);
                contexts.add(new RunSampleContext(runSample, researchSample, labels, resolvedLabel,
                        invalid, reviewed, evaluable, evaluation));
            }
        }
        contexts.sort((a, b) -> {
            int scenarioA = ResearchConfig.SCENARIO_ORDER.indexOf(a.sample().scenario());
            int scenarioB = ResearchConfig.SCENARIO_ORDER.indexOf(b.sample().scenario());
            if (scenarioA != scenarioB) {
                return Integer.compare(scenarioA, scenarioB);
            }
            return a.sample().id().compareTo(b.sample().id());
        });

        StudyEvaluation study = runner.evaluateInputs(
                contexts.stream().map(RunSampleContext::evaluation).toList(),
                experiment.baselineVersion(), experiment.algorithmVersion());
        Report report = ResearchRunDataQuality.compute(entries);

        EvaluationSnapshotDto snapshot = new EvaluationSnapshotDto(run.datasetVersion(),
                experiment.baselineVersion(), experiment.algorithmVersion(),
                Instant.now().toString(), report.evaluable());
        RunProgressDto progress = new RunProgressDto(ResearchConfig.TARGET_EXPERIMENT_SAMPLES,
                report.planned(), report.capturing(), report.captured(), report.reviewed(), report.evaluable());
        CompletionSummaryDto completion = completionSummary(contexts, report);

        return new RunEvaluationDto(run.id(), experiment.id(), run.runCode(), progress,
                scenarioProgress(entries), conditionDistribution(contexts),
                toDataQualityDto(report), toDto(study.baseline()), toDto(study.fusion()),
                reviewAgreement(contexts), disagreements(contexts),
                conditionGroups(contexts, experiment.baselineVersion(), experiment.algorithmVersion()),
                toDto(study.latency()), toDto(study.bandwidth()), completion, snapshot);
    }

    private RunDataQualityDto toDataQualityDto(Report report) {
        return new RunDataQualityDto(report.planned(), report.capturing(), report.captured(),
                report.reviewed(), report.evaluable(), report.unreviewed(), report.tied(),
                report.invalid(), report.missingSignals(), report.unexpectedSignals(),
                report.scenarioGroundTruthAgreement(), report.scenarioGroundTruthDisagreement(),
                report.missingMeasuredLatency(), report.missingBandwidth());
    }

    private List<ScenarioProgressDto> scenarioProgress(List<Entry> entries) {
        Map<String, ScenarioProgressAccumulator> byScenario = new LinkedHashMap<>();
        for (String scenario : ResearchConfig.SCENARIO_ORDER) {
            byScenario.put(scenario, new ScenarioProgressAccumulator());
        }
        for (Entry entry : entries) {
            ScenarioProgressAccumulator acc = byScenario.get(entry.scenario());
            if (acc == null) {
                acc = byScenario.computeIfAbsent(entry.scenario(), key -> new ScenarioProgressAccumulator());
            }
            acc.planned++;
            if ("CAPTURED".equals(entry.runSampleStatus())) {
                acc.captured++;
            }
            if (entry.reviewed()) {
                acc.reviewed++;
                if (entry.evaluable()) {
                    acc.evaluable++;
                }
            }
        }
        List<ScenarioProgressDto> result = new ArrayList<>();
        for (String scenario : ResearchConfig.SCENARIO_ORDER) {
            ScenarioProgressAccumulator acc = byScenario.get(scenario);
            result.add(new ScenarioProgressDto(scenario, ResearchValidation.scenarioLabel(scenario),
                    ResearchConfig.TARGET_SAMPLES_PER_SCENARIO, acc.planned, acc.captured,
                    acc.reviewed, acc.evaluable));
        }
        return result;
    }

    private List<ConditionDistributionDto> conditionDistribution(List<RunSampleContext> contexts) {
        List<ConditionDistributionDto> result = new ArrayList<>();
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (RunSampleContext context : contexts) {
                String value = context.sample().conditions().get(key);
                if (value != null) {
                    counts.merge(value, 1, Integer::sum);
                }
            }
            List<ConditionValueCountDto> values = new ArrayList<>();
            for (String value : new java.util.TreeSet<>(counts.keySet())) {
                values.add(new ConditionValueCountDto(value, counts.get(value)));
            }
            result.add(new ConditionDistributionDto(key, CONDITION_LABELS.getOrDefault(key, key), values));
        }
        return result;
    }

    private List<ReviewStatusDto> reviewAgreement(List<RunSampleContext> contexts) {
        List<ReviewStatusDto> result = new ArrayList<>();
        for (RunSampleContext context : contexts) {
            List<String> labels = context.reviewLabels();
            String resolved = context.resolvedLabel();
            boolean unanimous = labels.stream().allMatch(label -> label != null && label.equals(resolved));
            result.add(new ReviewStatusDto(context.sample().id(), context.sample().scenario(),
                    labels.size(), resolved,
                    ResearchValidation.agreementState(labels.size(), resolved, unanimous)));
        }
        return result;
    }

    private List<DisagreementDto> disagreements(List<RunSampleContext> contexts) {
        List<DisagreementDto> result = new ArrayList<>();
        for (RunSampleContext context : contexts) {
            PerSamplePrediction prediction = runner.predict(context.researchSample());
            if (prediction.baselinePositive() == prediction.fusionPositive()) {
                continue;
            }
            SignalProfile profile = signalProfile(context.sample(), context.researchSample());
            result.add(new DisagreementDto(context.researchSample().id(), context.sample().id(),
                    context.sample().scenario(), context.sample().conditionsKey(),
                    context.resolvedLabel(), prediction.baselinePositive(), prediction.fusionPositive(),
                    profile.types(), profile.sources(), profile.minConfidence(),
                    profile.maxConfidence(), profile.meanConfidence(), profile.maxDurationMs()));
        }
        return result;
    }

    private List<ConditionGroupEvaluationDto> conditionGroups(List<RunSampleContext> contexts,
                                                              String baselineVersion, String algorithmVersion) {
        List<ConditionGroupEvaluationDto> result = new ArrayList<>();
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            List<SampleEvaluation> dimensionInputs = new ArrayList<>();
            for (RunSampleContext context : contexts) {
                String value = context.sample().conditions().get(key);
                if (value == null) {
                    continue;
                }
                SampleEvaluation base = context.evaluation();
                dimensionInputs.add(new SampleEvaluation(base.sampleId(), base.windowStart(),
                        base.windowEnd(), base.actualPositive(), base.reviewed(), base.signals(),
                        base.rawMediaBytes(), base.signalBytes(), value));
            }
            List<ConditionGroup> groups = new ArrayList<>(runner.evaluateInputsByCondition(
                    dimensionInputs, baselineVersion, algorithmVersion));
            groups.sort(java.util.Comparator.comparing(ConditionGroup::conditionValue));
            for (ConditionGroup group : groups) {
                result.add(new ConditionGroupEvaluationDto(key, group.conditionValue(),
                        group.sampleCount(), toDto(group.baseline()), toDto(group.fusion())));
            }
        }
        return result;
    }

    private CompletionSummaryDto completionSummary(List<RunSampleContext> contexts, Report report) {
        int target = ResearchConfig.TARGET_EXPERIMENT_SAMPLES;
        Set<String> coveredScenarios = new HashSet<>();
        Map<String, Set<String>> coveredConditionValues = new LinkedHashMap<>();
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            coveredConditionValues.put(key, new HashSet<>());
        }
        for (RunSampleContext context : contexts) {
            coveredScenarios.add(context.sample().scenario());
            for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
                String value = context.sample().conditions().get(key);
                if (value != null) {
                    coveredConditionValues.get(key).add(value);
                }
            }
        }
        int scenarioCells = ResearchConfig.SCENARIO_ORDER.size();
        int conditionCells = 0;
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            conditionCells += ResearchConfig.CONTROLLED_CONDITIONS.get(key).size();
        }
        int conditionCoverage = 0;
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            conditionCoverage += coveredConditionValues.get(key).size();
        }
        return new CompletionSummaryDto(target, report.captured(), report.reviewed(),
                report.evaluable(), coveredScenarios.size(), scenarioCells,
                conditionCoverage, conditionCells, report.evaluable() >= 1);
    }

    private SignalProfile signalProfile(ResearchRunSample runSample, ResearchSample researchSample) {
        Map<String, Integer> typeCounts = proctorRepository.signalTypeCountsByAttemptIdWithin(
                runSample.attemptId(), runSample.startedAt(), runSample.endedAt());
        List<FusionSignal> signals = runner.windowSignals(researchSample);
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        Double minConfidence = null;
        Double maxConfidence = null;
        Double meanConfidence = null;
        Long maxDurationMs = null;
        double confidenceSum = 0.0;
        int confidenceCount = 0;
        for (FusionSignal signal : signals) {
            sourceCounts.merge(signal.source(), 1, Integer::sum);
            if (signal.confidence() != null) {
                confidenceSum += signal.confidence();
                confidenceCount++;
                if (minConfidence == null || signal.confidence() < minConfidence) {
                    minConfidence = signal.confidence();
                }
                if (maxConfidence == null || signal.confidence() > maxConfidence) {
                    maxConfidence = signal.confidence();
                }
            }
            if (signal.durationMs() != null && (maxDurationMs == null || signal.durationMs() > maxDurationMs)) {
                maxDurationMs = signal.durationMs();
            }
        }
        if (confidenceCount > 0) {
            meanConfidence = confidenceSum / confidenceCount;
        }
        List<SignalTypeCountDto> types = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
            types.add(new SignalTypeCountDto(entry.getKey(), entry.getValue()));
        }
        List<SignalSourceCountDto> sources = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : sourceCounts.entrySet()) {
            sources.add(new SignalSourceCountDto(entry.getKey(), entry.getValue()));
        }
        return new SignalProfile(types, sources, minConfidence, maxConfidence, meanConfidence, maxDurationMs);
    }

    private EvaluatorResultDto toDto(EvaluatorResult result) {
        ResearchMetrics.Confusion c = result.confusion();
        ConfusionDto confusion = new ConfusionDto(c.truePositives(), c.trueNegatives(),
                c.falsePositives(), c.falseNegatives());
        ResearchMetrics.EvaluatorMetrics m = result.metrics();
        EvaluatorMetricsDto metrics = new EvaluatorMetricsDto(m.precision(), m.recall(), m.specificity(),
                m.accuracy(), m.falsePositiveRate(), m.falseNegativeRate(), m.f1(),
                m.wrongfulWarningRate(), m.fdr());
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

    private record RunSampleContext(ResearchRunSample sample, ResearchSample researchSample,
                                    List<String> reviewLabels, String resolvedLabel, boolean invalid,
                                    boolean reviewed, boolean evaluable, SampleEvaluation evaluation) {
    }

    private record SignalProfile(List<SignalTypeCountDto> types, List<SignalSourceCountDto> sources,
                                 Double minConfidence, Double maxConfidence, Double meanConfidence,
                                 Long maxDurationMs) {
    }

    private static final class ScenarioProgressAccumulator {
        private int planned;
        private int captured;
        private int reviewed;
        private int evaluable;
    }

    private Set<String> unexpectedSignalTypes(ResearchRunSample runSample,
                                              String from, String to) {
        Set<String> expected = ResearchConfig.SCENARIO_EXPECTED_SIGNALS.getOrDefault(
                runSample.scenario(), Set.of());
        Set<String> unexpected = new HashSet<>();
        Map<String, Integer> typeCounts = proctorRepository.signalTypeCountsByAttemptIdWithin(
                runSample.attemptId(), from, to);
        for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
            if (!expected.contains(entry.getKey())) {
                unexpected.add(entry.getKey());
            }
        }
        return unexpected;
    }

    private List<ScenarioOutcomeDto> runScenarios(List<Entry> entries) {
        Map<String, ScenarioAccumulator> byScenario = new LinkedHashMap<>();
        for (String scenario : ResearchConfig.SCENARIO_ORDER) {
            byScenario.put(scenario, new ScenarioAccumulator());
        }
        for (Entry entry : entries) {
            ScenarioAccumulator acc = byScenario.get(entry.scenario());
            if (acc == null) {
                acc = byScenario.computeIfAbsent(entry.scenario(), key -> new ScenarioAccumulator());
            }
            if ("CAPTURED".equals(entry.runSampleStatus())) {
                acc.captured++;
            }
            if (entry.evaluable()) {
                acc.resolved++;
                if (ResearchValidation.scenarioAgrees(entry.scenario(), entry.resolvedLabel())) {
                    acc.agreement++;
                }
            }
        }
        List<ScenarioOutcomeDto> result = new ArrayList<>();
        for (Map.Entry<String, ScenarioAccumulator> entry : byScenario.entrySet()) {
            ScenarioAccumulator acc = entry.getValue();
            result.add(new ScenarioOutcomeDto(entry.getKey(),
                    ResearchValidation.scenarioLabel(entry.getKey()), entry.getKey(),
                    acc.captured, acc.resolved, acc.agreement));
        }
        return result;
    }

    private static final class ScenarioAccumulator {
        private int captured;
        private int resolved;
        private int agreement;
    }

    private ResearchRunDto toRunDto(ResearchRun run) {
        return new ResearchRunDto(run.id(), run.experimentId(), run.runCode(),
                run.startedAt() == null ? null : run.startedAt().toString(),
                run.endedAt() == null ? null : run.endedAt().toString(),
                run.operatorId(), run.operatorName(), run.status(),
                run.datasetVersion(), run.notes(), run.createdAt());
    }

    private RunSampleDto toRunSampleDto(ResearchRunSample sample, long signalCount) {
        return new RunSampleDto(sample.id(), sample.runId(), sample.attemptId(),
                sample.scenario(), sample.conditions(), sample.status(),
                sample.startedAt(), sample.endedAt(), sample.measuredLatencyMs(),
                sample.rawMediaBytes(), sample.signalBytes(), sample.researchSampleId(),
                signalCount, false, false, false, sample.createdAt());
    }

    private void requireAdmin(User actor) {
        if (actor == null || actor.role() == null || !"ADMIN".equals(actor.role().name())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "administrator role required");
        }
    }

    private ResearchExperiment requireExperiment(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "experiment id is required");
        }
        return researchRepository.findExperimentById(experimentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "experiment not found"));
    }

    private ResearchRun requireRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "run id is required");
        }
        return researchRepository.findRunById(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "run not found"));
    }

    private ResearchRunSample requireRunSample(String runSampleId, String runId) {
        if (runSampleId == null || runSampleId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "run sample id is required");
        }
        ResearchRunSample sample = researchRepository.findRunSampleById(runSampleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "run sample not found"));
        if (!runId.equals(sample.runId())) {
            throw new ApiException(HttpStatus.CONFLICT, "run sample does not belong to this run");
        }
        return sample;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}