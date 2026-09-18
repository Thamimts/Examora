package com.examora.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Pure, deterministic, side-effect-free confidence-aware fusion of proctoring
 * evidence into an experimental shadow score. It never writes state and never
 * modifies warnings, risk, access status, termination or retest decisions.
 *
 * <p>Repeated calculation on the same input produces the identical result,
 * independent of input ordering: signals are sorted by (occurredAt, id), then
 * deduplicated first-wins by id.
 */
@Service
public final class ProctorFusionService {

    /** Minimal evidence unit for the fusion engine. */
    public record FusionSignal(String id, String type, String source, String occurredAt,
                               Double confidence, Long durationMs) {
        public FusionSignal {
            id = id == null ? "" : id;
            type = type == null ? "" : type;
            source = source == null ? "" : source;
        }
    }

    /** Per-signal contribution used for aggregation and reporting. */
    public record Contribution(String id, String type, String source,
                               double baseWeight, double sourceWeight,
                               double confidence, double contribution) {
    }

    /** Immutable fusion outcome for one attempt and one temporal window. */
    public record FusionResult(String algorithmVersion, int evidenceCount,
                               String windowStart, String windowEnd,
                               double baselineScore, double fusedScore, double fusedConfidence,
                               List<Contribution> contributingSignals) {
    }

    public FusionResult fuse(List<FusionSignal> signals, Instant referenceTime) {
        return fuse(signals, referenceTime, ProctorFusionConfig.TEMPORAL_WINDOW_MS);
    }

    public FusionResult fuse(List<FusionSignal> signals, Instant referenceTime, long windowMs) {
        if (referenceTime == null) {
            referenceTime = Instant.now();
        }
        Instant windowEnd = referenceTime;
        Instant windowStart = referenceTime.minusMillis(Math.max(0, windowMs));

        LinkedHashMap<String, FusionSignal> inWindow = new LinkedHashMap<>();
        for (FusionSignal raw : signals == null ? List.<FusionSignal>of() : signals) {
            Instant occurredAt = parseInstant(raw.occurredAt());
            if (occurredAt == null || occurredAt.isBefore(windowStart) || occurredAt.isAfter(windowEnd)) {
                continue;
            }
            FusionSignal signal = new FusionSignal(
                    raw.id().trim(),
                    normalizeType(raw.type()),
                    normalizeSource(raw.source()),
                    raw.occurredAt(),
                    raw.confidence(),
                    raw.durationMs());
            if (signal.id().isEmpty()) {
                continue;
            }
            // sort happens later; first-wins is decided on the sorted order below
            inWindow.putIfAbsent(signal.id(), signal);
        }

        List<FusionSignal> sorted = new ArrayList<>(inWindow.values());
        sorted.sort(Comparator.comparing((FusionSignal s) -> parseInstant(s.occurredAt()))
                .thenComparing(FusionSignal::id));

        List<Contribution> contributions = new ArrayList<>();
        double fusedTotal = 0.0;
        double confidenceTotal = 0.0;
        for (FusionSignal signal : sorted) {
            double baseWeight = baseWeight(signal.type());
            double sourceWeight = sourceWeight(signal.source());
            double confidence = resolveConfidence(signal.confidence(), signal.source());
            double contribution = clamp01(baseWeight * sourceWeight * confidence);
            contributions.add(new Contribution(
                    signal.id(), signal.type(), signal.source(),
                    baseWeight, sourceWeight, confidence, contribution));
            fusedTotal += contribution;
            confidenceTotal += confidence;
        }

        int evidenceCount = contributions.size();
        double baselineScore = clamp01(evidenceCount / ProctorFusionConfig.BASELINE_FULL_EVIDENCE_COUNT);
        double fusedScore = clamp01(fusedTotal);
        double fusedConfidence = evidenceCount == 0 ? 0.0 : confidenceTotal / evidenceCount;

        return new FusionResult(
                ProctorFusionConfig.ALGORITHM_VERSION,
                evidenceCount,
                windowStart.toString(),
                windowEnd.toString(),
                baselineScore,
                fusedScore,
                fusedConfidence,
                contributions);
    }

    double baseWeight(String type) {
        return ProctorFusionConfig.BASE_WEIGHTS.getOrDefault(type, ProctorFusionConfig.UNKNOWN_TYPE_BASE_WEIGHT);
    }

    double sourceWeight(String source) {
        return ProctorFusionConfig.SOURCE_WEIGHTS.getOrDefault(source, ProctorFusionConfig.UNKNOWN_SOURCE_WEIGHT);
    }

    double resolveConfidence(Double confidence, String source) {
        if (confidence == null || !Double.isFinite(confidence)) {
            if (isAiSource(source)) {
                return ProctorFusionConfig.AI_NO_CONFIDENCE_FALLBACK;
            }
            return ProctorFusionConfig.NO_CONFIDENCE_FALLBACKS
                    .getOrDefault(source, ProctorFusionConfig.UNKNOWN_SOURCE_NO_CONFIDENCE_FALLBACK);
        }
        return clamp01(confidence);
    }

    private boolean isAiSource(String source) {
        return "CLIENT_AI".equals(source) || "SERVER_AI".equals(source);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeSource(String source) {
        return source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
    }

    static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        String normalized = text.replace(' ', 'T');
        try {
            return Instant.parse(normalized);
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(normalized).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"))
                    .toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        return null;
    }
}