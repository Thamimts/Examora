package com.examora.service;

import com.examora.dto.ResearchDtos.BandwidthStatsDto;
import com.examora.dto.ResearchDtos.ConfusionDto;
import com.examora.dto.ResearchDtos.ConditionEvaluationDto;
import com.examora.dto.ResearchDtos.DataQualityDto;
import com.examora.dto.ResearchDtos.EvaluatorMetricsDto;
import com.examora.dto.ResearchDtos.EvaluatorResultDto;
import com.examora.dto.ResearchDtos.LatencyStatsDto;
import com.examora.dto.ResearchDtos.ScenarioOutcomeDto;
import com.examora.dto.ResearchDtos.StudyEvaluationDto;
import com.examora.model.ExamAttempt;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.ProctorRepository;
import com.examora.repository.ResearchRepository;
import com.examora.repository.ResearchRepository.ResearchExperiment;
import com.examora.repository.ResearchRepository.ResearchSample;
import com.examora.service.ProctorFusionService.FusionSignal;
import com.examora.service.ResearchDataQuality.Report;
import com.examora.service.ResearchEvaluationEngine.ConditionGroup;
import com.examora.service.ResearchEvaluationEngine.EvaluatorResult;
import com.examora.service.ResearchEvaluationEngine.SampleEvaluation;
import com.examora.service.ResearchEvaluationEngine.StudyEvaluation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Controlled research experiment runner. Loads the registered samples, resolves the
 * human-reviewed ground truth, loads the stored in-window proctor signals, and evaluates
 * baseline-v1 vs fusion-v1 through the pure engine. The runner is read-only with respect
 * to enforcement state and deterministic for unchanged data.
 */
@Service
public class ResearchExperimentRunner {

    private final ResearchRepository researchRepository;
    private final ProctorRepository proctorRepository;
    private final ExamAttemptRepository examAttemptRepository;
    private final ResearchEvaluationEngine engine;

    public ResearchExperimentRunner(ResearchRepository researchRepository,
                                    ProctorRepository proctorRepository,
                                    ExamAttemptRepository examAttemptRepository,
                                    ProctorFusionService fusionService) {
        this.researchRepository = researchRepository;
        this.proctorRepository = proctorRepository;
        this.examAttemptRepository = examAttemptRepository;
        this.engine = new ResearchEvaluationEngine(fusionService, ResearchConfig.FUSION_THRESHOLD);
    }

    public StudyEvaluationDto evaluate(ResearchExperiment experiment, String conditionKey) {
        String evaluatedAt = Instant.now().toString();
        List<ResearchSample> samples = researchRepository.findSamplesByExperiment(
                experiment.id(), ResearchConfig.MAX_SAMPLES_PER_EXPERIMENT);
        Map<String, Long> reviewCounts = researchRepository.countReviewsByExperiment(experiment.id());

        List<SampleContext> contexts = new ArrayList<>();
        for (ResearchSample sample : samples) {
            contexts.add(new SampleContext(sample, sample.label(),
                    reviewCounts.getOrDefault(sample.id(), 0L) > 0));
        }

        boolean[] invalid = markInvalid(samples);

        List<SampleEvaluation> inputs = new ArrayList<>();
        List<ResearchDataQuality.Entry> qualityEntries = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            SampleContext context = contexts.get(i);
            ResearchSample sample = context.sample();
            boolean reviewed = context.reviewed();
            String label = context.label();
            boolean qualifies = reviewed && label != null && !invalid[i];
            Boolean actualPositive = qualifies ? !ResearchValidation.isNegativeLabel(label) : null;
            String conditionValue = conditionKey == null ? null : sample.metadata().get(conditionKey);
            List<FusionSignal> signals = windowSignals(sample);
            inputs.add(new SampleEvaluation(sample.id(),
                    ResearchValidation.parseTimestamp(sample.windowStart(), "windowStart"),
                    ResearchValidation.parseTimestamp(sample.windowEnd(), "windowEnd"),
                    actualPositive, reviewed, signals, sample.rawMediaBytes(),
                    sample.signalBytes(), conditionValue));
            qualityEntries.add(new ResearchDataQuality.Entry(sample.id(), sample.scenario(),
                    label, reviewed, invalid[i], sample.attemptId() != null,
                    !signals.isEmpty(), !sample.metadata().isEmpty(),
                    sample.measuredLatencyMs() != null,
                    sample.rawMediaBytes() != null && sample.signalBytes() != null));
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

        Report quality = ResearchDataQuality.compute(qualityEntries);
        List<ScenarioOutcomeDto> scenarios = new ArrayList<>();
        for (ResearchDataQuality.ScenarioOutcome outcome : quality.scenarios()) {
            scenarios.add(new ScenarioOutcomeDto(outcome.scenario(), outcome.label(),
                    outcome.expectedLabel(), outcome.sampleCount(), outcome.resolvedCount(),
                    outcome.agreementCount()));
        }
        DataQualityDto dataQuality = new DataQualityDto(quality.registered(), quality.evaluable(),
                quality.unreviewed(), quality.tied(), quality.invalid(),
                quality.scenarioGroundTruthAgreement(), quality.missingSignals(),
                quality.missingCondition(), quality.missingMeasuredLatency(),
                quality.missingBandwidth(), scenarios);

        return new StudyEvaluationDto(experiment.id(), study.totalSamples(), study.evaluatedSamples(),
                study.reviewedSamples(), study.unevaluatedSamples(),
                toDto(study.baseline()), toDto(study.fusion()),
                toDto(study.latency()), toDto(study.bandwidth()), conditions,
                experiment.datasetVersion(), evaluatedAt, dataQuality, scenarios);
    }

    /**
     * Flags samples that fail post-hoc integrity checks: a window outside its attempt's
     * span, or an overlapping window with another sample in the same experiment and same
     * attempt context. Deterministic and bounded (pairwise over the experiment's samples).
     */
    private boolean[] markInvalid(List<ResearchSample> samples) {
        boolean[] invalid = new boolean[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            ResearchSample sample = samples.get(i);
            if (sample.attemptId() != null) {
                ExamAttempt attempt = examAttemptRepository.findById(sample.attemptId()).orElse(null);
                if (attempt == null || attempt.startedAt() == null || attempt.expiresAt() == null
                        || outsideAttemptSpan(sample, attempt)) {
                    invalid[i] = true;
                }
            }
        }
        for (int i = 0; i < samples.size(); i++) {
            for (int j = i + 1; j < samples.size(); j++) {
                ResearchSample a = samples.get(i);
                ResearchSample b = samples.get(j);
                boolean sameContext = a.attemptId() == null
                        ? b.attemptId() == null : a.attemptId().equals(b.attemptId());
                if (sameContext && ResearchValidation.hasOverlap(
                        ResearchValidation.parseTimestamp(a.windowStart(), "windowStart"),
                        ResearchValidation.parseTimestamp(a.windowEnd(), "windowEnd"),
                        ResearchValidation.parseTimestamp(b.windowStart(), "windowStart"),
                        ResearchValidation.parseTimestamp(b.windowEnd(), "windowEnd"))) {
                    invalid[i] = true;
                    invalid[j] = true;
                }
            }
        }
        return invalid;
    }

    private boolean outsideAttemptSpan(ResearchSample sample, ExamAttempt attempt) {
        Instant start = ResearchValidation.parseTimestamp(sample.windowStart(), "windowStart");
        Instant end = ResearchValidation.parseTimestamp(sample.windowEnd(), "windowEnd");
        try {
            ResearchValidation.validateAttemptWindow(start, end, attempt.startedAt(), attempt.expiresAt());
            return false;
        } catch (RuntimeException ex) {
            return true;
        }
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

    private record SampleContext(ResearchSample sample, String label, boolean reviewed) {
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