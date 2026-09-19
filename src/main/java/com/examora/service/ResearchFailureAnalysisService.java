package com.examora.service;

import com.examora.dto.ResearchDtos.AnalysisDescriptiveDto;
import com.examora.dto.ResearchDtos.FailureCaseDto;
import com.examora.dto.ResearchDtos.FailureConfidenceBandDto;
import com.examora.dto.ResearchDtos.FailureCountedDto;
import com.examora.dto.ResearchDtos.FailureDistributionDto;
import com.examora.dto.ResearchDtos.FailureGroupDto;
import com.examora.dto.ResearchDtos.FailurePatternDto;
import com.examora.dto.ResearchDtos.FailureRangeDto;
import com.examora.dto.ResearchDtos.FailureReportDto;
import com.examora.dto.ResearchDtos.FailureSamplesPageDto;
import com.examora.dto.ResearchDtos.FailureSignalAssociationDto;
import com.examora.dto.ResearchDtos.FailureSignalProfileRowDto;
import com.examora.dto.ResearchDtos.FailureTransitionDto;
import com.examora.model.User;
import com.examora.service.ResearchAnalysisService.AnalysisScope;
import com.examora.service.ResearchFailureAnalysis.ConfidenceBand;
import com.examora.service.ResearchFailureAnalysis.Counted;
import com.examora.service.ResearchFailureAnalysis.Distribution;
import com.examora.service.ResearchFailureAnalysis.EnvironmentalFailure;
import com.examora.service.ResearchFailureAnalysis.FailureCase;
import com.examora.service.ResearchFailureAnalysis.FailurePattern;
import com.examora.service.ResearchFailureAnalysis.FailureReport;
import com.examora.service.ResearchFailureAnalysis.Range;
import com.examora.service.ResearchFailureAnalysis.ScenarioFailure;
import com.examora.service.ResearchFailureAnalysis.SignalAssociation;
import com.examora.service.ResearchFailureAnalysis.SignalProfileRow;
import com.examora.service.ResearchFailureAnalysis.TransitionSummary;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Read-only failure-case analysis service. It reuses the exact analysis scope of
 * {@link ResearchAnalysisService} (admin-only, persisted, evaluated samples only) and delegates
 * every derivation to the pure {@link ResearchFailureAnalysis} class. Nothing is persisted and no
 * enforcement state is ever touched.
 */
@Service
public class ResearchFailureAnalysisService {

    private final ResearchAnalysisService analysisService;

    public ResearchFailureAnalysisService(ResearchAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    public FailureReportDto failureAnalysis(User actor, String experimentId, String runId) {
        AnalysisScope scope = analysisService.loadScope(actor, experimentId, runId);
        FailureReport report = analyze(scope);
        return toReportDto(experimentId, runId, report);
    }

    public FailureSamplesPageDto failureSamples(User actor, String experimentId, String runId,
                                                Integer page, Integer pageSize) {
        AnalysisScope scope = analysisService.loadScope(actor, experimentId, runId);
        FailureReport report = analyze(scope);

        int effectivePage = page == null ? 0 : Math.max(0, page);
        int effectivePageSize = pageSize == null
                ? ResearchFailureAnalysis.DEFAULT_PAGE_SIZE
                : Math.min(Math.max(1, pageSize), ResearchFailureAnalysis.MAX_PAGE_SIZE);
        List<FailureCase> cases = report.failureCases();
        int totalCount = cases.size();
        int totalPages = (int) Math.ceil(totalCount / (double) effectivePageSize);
        int from = effectivePage * effectivePageSize;
        List<FailureCase> slice;
        if (from >= totalCount) {
            slice = List.of();
        } else {
            slice = cases.subList(from, Math.min(from + effectivePageSize, totalCount));
        }
        List<FailureCaseDto> samples = new ArrayList<>();
        for (FailureCase failureCase : slice) {
            samples.add(toCaseDto(failureCase));
        }
        return new FailureSamplesPageDto(experimentId, runId, effectivePage, effectivePageSize,
                totalCount, totalPages, samples);
    }

    private static FailureReport analyze(AnalysisScope scope) {
        return ResearchFailureAnalysis.analyze(new ArrayList<>(scope.captured().values()),
                scope.datasetVersion(), scope.baselineVersion(), scope.fusionVersion());
    }

    private FailureReportDto toReportDto(String experimentId, String runId, FailureReport report) {
        return new FailureReportDto(experimentId, runId,
                report.evaluableSampleCount(), report.failureCaseCount(),
                report.disagreementCount(), report.changedPredictionCount(),
                report.categories().categories().stream().map(this::toCountedDto).toList(),
                report.transitionCategories().categories().stream().map(this::toCountedDto).toList(),
                report.disagreement().categories().stream().map(this::toCountedDto).toList(),
                report.signalProfile().rows().stream().map(this::toSignalProfileDto).toList(),
                report.confidenceBands().stream().map(this::toBandDto).toList(),
                report.missingConfidenceSignalCount(),
                report.environmentalFailures().stream().map(this::toEnvironmentalGroupDto).toList(),
                report.missingConditionCount(),
                report.scenarioFailures().stream().map(this::toScenarioGroupDto).toList(),
                new FailureSignalAssociationDto(report.signalAssociation().errorSampleCount(),
                        report.signalAssociation().errorSamplesWithoutSignals(),
                        report.signalAssociation().sources().stream().map(this::toCountedDto).toList()),
                toTransitionDto(report.transitions()),
                report.patterns().stream().map(p -> new FailurePatternDto(p.statement())).toList(),
                report.datasetVersion(), report.baselineVersion(), report.fusionVersion(),
                report.analysisVersion(), report.failureAnalysisVersion(), report.generatedAt().toString());
    }

    private FailureSignalProfileRowDto toSignalProfileDto(SignalProfileRow row) {
        return new FailureSignalProfileRowDto(row.category(), row.source(), row.signalCount(), row.sampleCount(),
                toDescriptiveDto(row.confidence()), toDescriptiveDto(row.duration()));
    }

    private AnalysisDescriptiveDto toDescriptiveDto(ResearchFailureAnalysis.DescriptiveStats stats) {
        return new AnalysisDescriptiveDto(stats.measuredCount(), stats.mean(), stats.median(),
                stats.min(), stats.max());
    }

    private FailureConfidenceBandDto toBandDto(ConfidenceBand band) {
        return new FailureConfidenceBandDto(band.band(), band.signalCount(), band.distinctSampleCount(),
                band.truePositives(), band.trueNegatives(), band.falsePositives(), band.falseNegatives(),
                band.precision(), band.recall(), band.falsePositiveRate(),
                band.falseNegativeRate(), band.falseDiscoveryRate(), band.evaluator());
    }

    private FailureGroupDto toEnvironmentalGroupDto(EnvironmentalFailure group) {
        return new FailureGroupDto(group.condition(), group.sampleCount(), group.smallSample(),
                group.baselineFalsePositives(), group.baselineFalseNegatives(),
                group.fusionFalsePositives(), group.fusionFalseNegatives(),
                group.baselineFalsePositiveRate(), group.baselineFalseNegativeRate(),
                group.fusionFalsePositiveRate(), group.fusionFalseNegativeRate(),
                group.disagreementCount());
    }

    private FailureGroupDto toScenarioGroupDto(ScenarioFailure group) {
        return new FailureGroupDto(group.scenario(), group.sampleCount(), group.smallSample(),
                group.baselineFalsePositives(), group.baselineFalseNegatives(),
                group.fusionFalsePositives(), group.fusionFalseNegatives(),
                group.baselineFalsePositiveRate(), group.baselineFalseNegativeRate(),
                group.fusionFalsePositiveRate(), group.fusionFalseNegativeRate(),
                group.disagreementCount());
    }

    private FailureTransitionDto toTransitionDto(TransitionSummary summary) {
        return new FailureTransitionDto(summary.changedPredictionCount(),
                summary.baselineCorrectToFusionWrong(), summary.baselineWrongToFusionCorrect(),
                summary.baselineCorrectToFusionWrongFraction(), summary.baselineWrongToFusionCorrectFraction(),
                summary.byScenario().stream().map(this::toDistributionDto).toList(),
                summary.byCondition().stream().map(this::toDistributionDto).toList(),
                summary.bySignalSource().stream().map(this::toDistributionDto).toList(),
                summary.byConfidenceBand().stream().map(this::toDistributionDto).toList());
    }

    private FailureDistributionDto toDistributionDto(Distribution distribution) {
        return new FailureDistributionDto(distribution.value(), distribution.count());
    }

    private FailureCountedDto toCountedDto(Counted counted) {
        return new FailureCountedDto(counted.value(), counted.count());
    }

    private FailureCaseDto toCaseDto(FailureCase failureCase) {
        return new FailureCaseDto(failureCase.sampleId(), failureCase.scenario(), failureCase.groundTruth(),
                failureCase.baselinePrediction(), failureCase.fusionPrediction(),
                failureCase.classification(), failureCase.condition(),
                failureCase.signalTypes(), failureCase.signalSources(),
                toRangeDto(failureCase.confidenceRange()), toRangeDto(failureCase.durationRange()));
    }

    private FailureRangeDto toRangeDto(Range range) {
        return range == null ? null : new FailureRangeDto(range.min(), range.max());
    }
}