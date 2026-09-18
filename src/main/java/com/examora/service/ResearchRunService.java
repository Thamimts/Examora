package com.examora.service;

import com.examora.dto.ResearchDtos.ConditionCatalogDto;
import com.examora.dto.ResearchDtos.ConditionOptionDto;
import com.examora.dto.ResearchDtos.RunCreateRequest;
import com.examora.dto.ResearchDtos.RunDataQualityDto;
import com.examora.dto.ResearchDtos.RunDetailDto;
import com.examora.dto.ResearchDtos.RunMatrixSummaryDto;
import com.examora.dto.ResearchDtos.RunSampleCreateRequest;
import com.examora.dto.ResearchDtos.RunSampleDetailDto;
import com.examora.dto.ResearchDtos.RunSampleDto;
import com.examora.dto.ResearchDtos.ResearchRunDto;
import com.examora.dto.ResearchDtos.SampleCaptureRequest;
import com.examora.dto.ResearchDtos.ScenarioInstructionDto;
import com.examora.dto.ResearchDtos.ScenarioOutcomeDto;
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
import com.examora.service.ResearchEvaluationEngine.PerSamplePrediction;
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