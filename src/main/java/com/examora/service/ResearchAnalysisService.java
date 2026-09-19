package com.examora.service;

import com.examora.dto.ResearchDtos.AnalysisBandwidthDto;
import com.examora.dto.ResearchDtos.AnalysisConfidenceDto;
import com.examora.dto.ResearchDtos.AnalysisConfusionDto;
import com.examora.dto.ResearchDtos.AnalysisDescriptiveDto;
import com.examora.dto.ResearchDtos.AnalysisDisagreementCategoryDto;
import com.examora.dto.ResearchDtos.AnalysisDisagreementSampleDto;
import com.examora.dto.ResearchDtos.AnalysisEvaluatorDto;
import com.examora.dto.ResearchDtos.AnalysisEvaluatorMetricsDto;
import com.examora.dto.ResearchDtos.AnalysisIntervalDto;
import com.examora.dto.ResearchDtos.AnalysisLatencyDto;
import com.examora.dto.ResearchDtos.AnalysisMetricDeltaDto;
import com.examora.dto.ResearchDtos.AnalysisMetricIntervalDto;
import com.examora.dto.ResearchDtos.AnalysisReportDto;
import com.examora.dto.ResearchDtos.AnalysisReviewAgreementDto;
import com.examora.dto.ResearchDtos.AnalysisStratumDto;
import com.examora.dto.ResearchDtos.AnalysisStratumLatencyDto;
import com.examora.dto.ResearchDtos.AnalysisTransitionsDto;
import com.examora.exception.ApiException;
import com.examora.model.User;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.ResearchRepository;
import com.examora.repository.ResearchRepository.ResearchExperiment;
import com.examora.repository.ResearchRepository.ResearchReview;
import com.examora.repository.ResearchRepository.ResearchRun;
import com.examora.repository.ResearchRepository.ResearchRunSample;
import com.examora.repository.ResearchRepository.ResearchSample;
import com.examora.service.ProctorFusionService.FusionSignal;
import com.examora.service.ProctorFusionService.FusionResult;
import com.examora.service.ResearchEvaluationEngine.PerSamplePrediction;
import com.examora.service.ResearchStatisticalAnalysis.AnalysisSample;
import com.examora.service.ResearchStatisticalAnalysis.Confidence;
import com.examora.service.ResearchStatisticalAnalysis.ConfidenceInterval;
import com.examora.service.ResearchStatisticalAnalysis.DataQualityCounts;
import com.examora.service.ResearchStatisticalAnalysis.Descriptive;
import com.examora.service.ResearchStatisticalAnalysis.DisagreementSample;
import com.examora.service.ResearchStatisticalAnalysis.DisagreementSummary;
import com.examora.service.ResearchStatisticalAnalysis.EvaluatorResult;
import com.examora.service.ResearchStatisticalAnalysis.Latency;
import com.examora.service.ResearchStatisticalAnalysis.MetricDelta;
import com.examora.service.ResearchStatisticalAnalysis.PredictionTransitions;
import com.examora.service.ResearchStatisticalAnalysis.Report;
import com.examora.service.ResearchStatisticalAnalysis.ReviewAgreement;
import com.examora.service.ResearchStatisticalAnalysis.ReviewObservation;
import com.examora.service.ResearchStatisticalAnalysis.Stratum;
import com.examora.service.ResearchStatisticalAnalysis.StratumLatency;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Read-only statistical analysis service. It selects the analysis scope (an experiment, or a
 * single run of it), loads only persisted captured research samples with their stored in-window
 * signals and human reviews, derives predictions and confidence scores through the existing
 * deterministic fusion engine, computes data-quality counts, and delegates every derivation to
 * the pure {@link ResearchStatisticalAnalysis} class.
 *
 * <p>The service never persists, never alters enforcement state, never fabricates data, and never
 * exports raw media or student identity. All operations require an administrator.
 */
@Service
public class ResearchAnalysisService {

    private final ResearchRepository researchRepository;
    private final ExamAttemptRepository examAttemptRepository;
    private final ResearchExperimentRunner runner;
    private final ProctorFusionService fusionService;

    public ResearchAnalysisService(ResearchRepository researchRepository,
                                   ExamAttemptRepository examAttemptRepository,
                                   ResearchExperimentRunner runner,
                                   ProctorFusionService fusionService) {
        this.researchRepository = researchRepository;
        this.examAttemptRepository = examAttemptRepository;
        this.runner = runner;
        this.fusionService = fusionService;
    }

    /**
     * The analysis scope resolved as a pure input bundle: captured evaluable candidates, data-quality
     * counts, experiment versions and review observations. Shared unchanged by every read-only analysis
     * endpoint so statistical and failure analyses always describe exactly the same persisted dataset.
     */
    public record AnalysisScope(Map<String, AnalysisSample> captured, DataQualityCounts dataQuality,
                                String datasetVersion, String baselineVersion, String fusionVersion,
                                List<ReviewObservation> observations) {
    }

    public AnalysisReportDto analyze(User actor, String experimentId, String runId) {
        AnalysisScope scope = loadScope(actor, experimentId, runId);
        Report report = ResearchStatisticalAnalysis.analyze(
                new ArrayList<>(scope.captured().values()), scope.observations(), scope.dataQuality(),
                scope.datasetVersion(), scope.baselineVersion(), scope.fusionVersion());
        return toDto(experimentId, runId, report);
    }

    /**
     * Read-only resolution of the analysis scope for an experiment or a single run of it. Only
     * persisted captured samples with their stored in-window signals and human reviews are loaded;
     * nothing is written and no enforcement state is touched. Administrators only.
     */
    public AnalysisScope loadScope(User actor, String experimentId, String runId) {
        requireAdmin(actor);
        ResearchExperiment experiment = requireExperiment(experimentId);

        List<ResearchRun> scopeRuns = new ArrayList<>();
        if (runId != null && !runId.isBlank()) {
            ResearchRun run = researchRepository.findRunById(runId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "run not found"));
            if (!experiment.id().equals(run.experimentId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "run does not belong to this experiment");
            }
            scopeRuns.add(run);
        } else {
            for (ResearchRun run : researchRepository.listRunsByExperiment(experiment.id())) {
                if ("COMPLETED".equals(run.status()) || "RUNNING".equals(run.status())) {
                    scopeRuns.add(run);
                }
            }
        }

        List<ResearchSample> experimentSamples = researchRepository.findSamplesByExperiment(
                experiment.id(), ResearchConfig.MAX_SAMPLES_PER_EXPERIMENT);
        Map<String, ResearchSample> sampleById = new LinkedHashMap<>();
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

        LinkedHashMap<String, AnalysisSample> captured = new LinkedHashMap<>();
        Map<String, ResearchRun> runBySample = new LinkedHashMap<>();
        int invalidInScope = 0;
        int missingConditionSamples = 0;
        for (ResearchRun run : scopeRuns) {
            for (ResearchRunSample runSample : researchRepository.listRunSamplesByRun(
                    run.id(), ResearchConfig.MAX_RUN_SAMPLES)) {
                if (!"CAPTURED".equals(runSample.status()) || runSample.researchSampleId() == null) {
                    continue;
                }
                ResearchSample researchSample = sampleById.get(runSample.researchSampleId());
                if (researchSample == null) {
                    continue;
                }
                if (captured.putIfAbsent(researchSample.id(), null) != null) {
                    continue;
                }
                List<String> labels = reviewLabels.getOrDefault(researchSample.id(), List.of());
                boolean reviewed = !labels.isEmpty();
                String resolvedLabel = ResearchValidation.majorityLabel(labels);
                boolean invalid = invalidBySampleId.contains(researchSample.id());
                boolean evaluable = reviewed && resolvedLabel != null && !invalid;
                AnalysisSample analysisSample = toAnalysisSample(runSample, researchSample,
                        evaluable ? resolvedLabel : null);
                captured.put(researchSample.id(), analysisSample);
                runBySample.put(researchSample.id(), run);
                if (invalid) {
                    invalidInScope++;
                }
                if (!hasAllControlledConditions(researchSample.metadata())) {
                    missingConditionSamples++;
                }
            }
        }

        int scopeSize = captured.size();
        int reviewedSamples = 0;
        int tiedSamples = 0;
        int missingSignalSamples = 0;
        int missingLatencySamples = 0;
        int missingBandwidthSamples = 0;
        for (AnalysisSample sample : captured.values()) {
            List<String> labels = reviewLabels.getOrDefault(sample.sampleId(), List.of());
            if (!labels.isEmpty()) {
                reviewedSamples++;
                if (ResearchValidation.majorityLabel(labels) == null) {
                    tiedSamples++;
                }
            }
            if (sample.signals().isEmpty()) {
                missingSignalSamples++;
            }
            if (sample.measuredLatencyMs() == null) {
                missingLatencySamples++;
            }
            if (sample.rawMediaBytes() == null || sample.signalBytes() == null) {
                missingBandwidthSamples++;
            }
        }
        int evaluableSamples = (int) captured.values().stream()
                .filter(s -> s.groundTruthLabel() != null).count();
        int registeredSamples = runId != null && !runId.isBlank()
                ? scopeSize : experimentSamples.size();

        DataQualityCounts dataQuality = new DataQualityCounts(registeredSamples, scopeSize,
                reviewedSamples, evaluableSamples, scopeSize - reviewedSamples,
                tiedSamples, invalidInScope, missingSignalSamples, missingConditionSamples,
                missingLatencySamples, missingBandwidthSamples);

        String datasetVersion = runId != null && !runId.isBlank()
                ? scopeRuns.getFirst().datasetVersion() : experiment.datasetVersion();
        String baselineVersion = experiment.baselineVersion();
        String fusionVersion = experiment.algorithmVersion();

        List<ReviewObservation> observations = new ArrayList<>();
        Set<String> scopeIds = new HashSet<>(captured.keySet());
        for (ResearchReview review : researchRepository.findReviewsByExperiment(experiment.id())) {
            if (scopeIds.contains(review.sampleId())) {
                observations.add(new ReviewObservation(review.sampleId(), review.reviewerId(), review.label()));
            }
        }

        return new AnalysisScope(captured, dataQuality, datasetVersion, baselineVersion,
                fusionVersion, observations);
    }

    private AnalysisSample toAnalysisSample(ResearchRunSample runSample, ResearchSample researchSample,
                                            String resolvedLabel) {
        List<FusionSignal> signals = runner.windowSignals(researchSample);
        PerSamplePrediction prediction = runner.predict(researchSample);
        Instant windowStart = ResearchValidation.parseTimestamp(researchSample.windowStart(), "windowStart");
        Instant windowEnd = ResearchValidation.parseTimestamp(researchSample.windowEnd(), "windowEnd");
        long windowMs = Math.max(0L, windowEnd.toEpochMilli() - windowStart.toEpochMilli());
        FusionResult fusion = fusionService.fuse(signals, windowEnd, windowMs);
        return new AnalysisSample(researchSample.id(), researchSample.scenario(),
                researchSample.metadata(), resolvedLabel,
                prediction.baselinePositive(), prediction.fusionPositive(),
                fusion.baselineScore(), fusion.fusedScore(), signals,
                runSample.measuredLatencyMs(), researchSample.rawMediaBytes(),
                researchSample.signalBytes(), windowStart);
    }

    private boolean hasAllControlledConditions(Map<String, String> conditions) {
        if (conditions == null) {
            return false;
        }
        for (String key : ResearchConfig.CONTROLLED_CONDITION_ORDER) {
            if (conditions.get(key) == null) {
                return false;
            }
        }
        return true;
    }

    private AnalysisReportDto toDto(String experimentId, String runId, Report report) {
        return new AnalysisReportDto(experimentId, runId,
                report.dataQuality().registeredSamples(), report.dataQuality().capturedSamples(),
                report.dataQuality().reviewedSamples(), report.dataQuality().evaluableSamples(),
                report.dataQuality().unreviewedSamples(), report.dataQuality().tiedSamples(),
                report.dataQuality().invalidSamples(), report.dataQuality().missingSignalSamples(),
                report.dataQuality().missingConditionSamples(), report.dataQuality().missingLatencySamples(),
                report.dataQuality().missingBandwidthSamples(), report.evaluableCoverage(),
                toEvaluatorDto(report.baseline()), toEvaluatorDto(report.fusion()),
                report.metricDeltas().stream().map(m -> new AnalysisMetricDeltaDto(m.metric(), m.delta())).toList(),
                report.disagreementSummary().stream()
                        .map(s -> new AnalysisDisagreementCategoryDto(s.category(), s.count(), s.percentage()))
                        .toList(),
                report.disagreementSamples().stream().map(this::toDisagreementDto).toList(),
                report.scenarios().stream().map(this::toStratumDto).toList(),
                report.conditions().stream().map(this::toStratumDto).toList(),
                report.confidence().stream().map(this::toConfidenceDto).toList(),
                toLatencyDto(report.latency()),
                report.latencyByScenario().stream().map(this::toStratumLatencyDto).toList(),
                report.latencyByCondition().stream().map(this::toStratumLatencyDto).toList(),
                new AnalysisBandwidthDto(toDescriptiveDto(report.bandwidth().rawMedia()),
                        toDescriptiveDto(report.bandwidth().signalBytes())),
                toReviewAgreementDto(report.reviewAgreement()),
                report.confidenceIntervals().stream()
                        .map(i -> new AnalysisMetricIntervalDto(i.metric(),
                                new AnalysisIntervalDto(i.estimate(), i.lower(), i.upper(),
                                        i.confidenceLevel(), i.method())))
                        .toList(),
                toTransitionsDto(report.transitions()),
                report.datasetVersion(), report.baselineVersion(), report.fusionVersion(),
                report.analysisVersion(), report.generatedAt().toString());
    }

    private AnalysisDisagreementSampleDto toDisagreementDto(DisagreementSample sample) {
        return new AnalysisDisagreementSampleDto(sample.sampleId(), sample.scenario(),
                sample.conditions(), sample.groundTruthLabel(), sample.baselinePositive(),
                sample.fusionPositive(), sample.signalSummary());
    }

    private AnalysisStratumDto toStratumDto(Stratum stratum) {
        return new AnalysisStratumDto(stratum.value(), stratum.sampleCount(),
                toEvaluatorDto(stratum.baseline()), toEvaluatorDto(stratum.fusion()),
                stratum.deltas().stream()
                        .map(d -> new AnalysisMetricDeltaDto(d.metric(), d.delta())).toList());
    }

    private AnalysisConfidenceDto toConfidenceDto(Confidence confidence) {
        return new AnalysisConfidenceDto(confidence.source(), confidence.sampleCount(),
                confidence.measuredCount(), confidence.min(), confidence.max(),
                confidence.mean(), confidence.median());
    }

    private AnalysisLatencyDto toLatencyDto(Latency latency) {
        return new AnalysisLatencyDto(toDescriptiveDto(latency.measured()),
                toDescriptiveDto(latency.storedWindow()));
    }

    private AnalysisStratumLatencyDto toStratumLatencyDto(StratumLatency stratum) {
        return new AnalysisStratumLatencyDto(stratum.value(), stratum.sampleCount(),
                toLatencyDto(stratum.latency()));
    }

    private AnalysisReviewAgreementDto toReviewAgreementDto(ReviewAgreement agreement) {
        return new AnalysisReviewAgreementDto(agreement.reviewedSamples(), agreement.resolvedSamples(),
                agreement.tiedSamples(), agreement.agreementRate(),
                agreement.singleReviewerDataset(), agreement.pairwiseAgreementRate());
    }

    private AnalysisTransitionsDto toTransitionsDto(PredictionTransitions transitions) {
        return new AnalysisTransitionsDto(transitions.baselineErrorCount(),
                transitions.fusionErrorCount(), transitions.baselineCorrectCount(),
                transitions.fusionCorrectCount(), transitions.changedPredictionCount(),
                transitions.baselineCorrectToFusionWrong(), transitions.baselineWrongToFusionCorrect());
    }

    private AnalysisEvaluatorDto toEvaluatorDto(EvaluatorResult result) {
        ResearchMetrics.Confusion c = result.confusion();
        ResearchMetrics.EvaluatorMetrics m = result.metrics();
        AnalysisConfusionDto confusion = new AnalysisConfusionDto(c.truePositives(), c.trueNegatives(),
                c.falsePositives(), c.falseNegatives());
        AnalysisEvaluatorMetricsDto metrics = new AnalysisEvaluatorMetricsDto(m.precision(), m.recall(),
                m.specificity(), m.accuracy(), m.falsePositiveRate(), m.falseNegativeRate(),
                m.f1(), m.wrongfulWarningRate(), m.fdr());
        return new AnalysisEvaluatorDto(result.version(), confusion, metrics);
    }

    private AnalysisDescriptiveDto toDescriptiveDto(Descriptive descriptive) {
        return new AnalysisDescriptiveDto(descriptive.measuredCount(), descriptive.mean(),
                descriptive.median(), descriptive.min(), descriptive.max());
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
}