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

        boolean[] invalid = ResearchSampleValidity.markInvalid(samples,
                    id -> examAttemptRepository.findById(id).orElse(null));

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
     * Per-sample baseline-v1 and fusion-v1 prediction for a captured research sample,
     * reusing the exact engine inputs (same widened signal query and precise in-window
     * filtering as the study-level evaluation). Pure and read-only.
     */
    public ResearchEvaluationEngine.PerSamplePrediction predict(ResearchSample sample) {
        List<FusionSignal> signals = windowSignals(sample);
        SampleEvaluation eval = new SampleEvaluation(sample.id(),
                ResearchValidation.parseTimestamp(sample.windowStart(), "windowStart"),
                ResearchValidation.parseTimestamp(sample.windowEnd(), "windowEnd"),
                null, false, signals, sample.rawMediaBytes(), sample.signalBytes(), null);
        return engine.predict(eval);
    }

    /**
     * Loads the in-window proctor signals for a sample using the same widened query
     * and precise in-window filtering as the engine, so detail and evaluation agree.
     */
    public List<FusionSignal> windowSignals(ResearchSample sample) {
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