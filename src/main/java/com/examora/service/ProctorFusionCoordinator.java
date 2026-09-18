package com.examora.service;

import com.examora.dto.ProctorDtos.ProctorFusionContributionDto;
import com.examora.dto.ProctorDtos.ProctorFusionResultDto;
import com.examora.dto.ProctorDtos.ShadowFusionDto;
import com.examora.model.ExamAttempt;
import com.examora.model.User;
import com.examora.repository.ProctorFusionRepository;
import com.examora.repository.ProctorRepository;
import com.examora.repository.ProctorRepository.ProctorEventRow;
import com.examora.service.ProctorFusionService.Contribution;
import com.examora.service.ProctorFusionService.FusionResult;
import com.examora.service.ProctorFusionService.FusionSignal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Read-side orchestration of the shadow-mode fusion experiment.
 *
 * <p>The per-attempt endpoint is read-only for enforcement purposes: it computes
 * the experimental score, persists it for research, and never modifies warnings,
 * risk, access status, termination or retest decisions.
 */
@Service
public class ProctorFusionCoordinator {
    private final ProctorFusionService fusionService;
    private final ProctorFusionRepository fusionRepository;
    private final ProctorRepository proctorRepository;
    private final ExamAttemptService examAttemptService;

    public ProctorFusionCoordinator(ProctorFusionService fusionService, ProctorFusionRepository fusionRepository,
                                    ProctorRepository proctorRepository, ExamAttemptService examAttemptService) {
        this.fusionService = fusionService;
        this.fusionRepository = fusionRepository;
        this.proctorRepository = proctorRepository;
        this.examAttemptService = examAttemptService;
    }

    public ProctorFusionResultDto fusionForAttempt(String attemptId, User actor) {
        ExamAttempt attempt = examAttemptService.requireProctorAccess(attemptId, actor);
        Instant windowEnd = Instant.now();
        long windowMs = ProctorFusionConfig.TEMPORAL_WINDOW_MS;
        String from = windowEnd.minusMillis(windowMs + 1_000).toString();
        String to = windowEnd.plusMillis(1_000).toString();
        List<ProctorEventRow> rows = proctorRepository.findByAttemptIdWithin(
                attempt.id(), from, to, ProctorFusionConfig.MAX_WINDOW_SIGNALS);
        FusionResult result = fusionService.fuse(toFusionSignals(rows), windowEnd, windowMs);
        fusionRepository.upsert(attempt.id(), result);
        return toResultDto(attempt.id(), result);
    }

    public ShadowFusionDto shadowFor(List<ProctorEventRow> events, Instant now) {
        FusionResult result = fusionService.fuse(toFusionSignals(events), now == null ? Instant.now() : now);
        return toShadowDto(result);
    }

    public static List<FusionSignal> toFusionSignals(List<ProctorEventRow> rows) {
        return rows.stream().map(ProctorFusionCoordinator::toFusionSignal).toList();
    }

    private static FusionSignal toFusionSignal(ProctorEventRow row) {
        String eventId = row.eventId();
        return new FusionSignal(
                eventId == null || eventId.isBlank() ? row.id() : eventId.trim(),
                row.type(),
                row.source() == null ? "BROWSER" : row.source(),
                row.occurredAt(),
                row.confidence(),
                row.durationMs());
    }

    private static ProctorFusionResultDto toResultDto(String attemptId, FusionResult result) {
        return new ProctorFusionResultDto(
                attemptId,
                result.algorithmVersion(),
                result.evidenceCount(),
                result.windowStart(),
                result.windowEnd(),
                result.baselineScore(),
                result.fusedScore(),
                result.fusedConfidence(),
                result.contributingSignals().stream()
                        .map(ProctorFusionCoordinator::toContributionDto).toList());
    }

    private static ShadowFusionDto toShadowDto(FusionResult result) {
        return new ShadowFusionDto(
                result.baselineScore(),
                result.fusedScore(),
                result.evidenceCount(),
                result.algorithmVersion());
    }

    private static ProctorFusionContributionDto toContributionDto(Contribution contribution) {
        return new ProctorFusionContributionDto(
                contribution.id(),
                contribution.type(),
                contribution.source(),
                contribution.baseWeight(),
                contribution.sourceWeight(),
                contribution.confidence(),
                contribution.contribution());
    }
}