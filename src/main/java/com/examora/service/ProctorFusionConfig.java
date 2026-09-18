package com.examora.service;

import java.util.Map;

/**
 * Single source of truth for the shadow-mode confidence-aware proctoring fusion
 * experiment. All weights, fallbacks, the temporal window and the algorithm
 * version live here. No magic numbers are scattered across services.
 *
 * <p>This configuration is EXPERIMENTAL and research-only. It does not influence
 * the deterministic warning risk or termination enforcement pipeline.
 */
public final class ProctorFusionConfig {

    /** Algorithm version; changed whenever the formula changes. */
    public static final String ALGORITHM_VERSION = "fusion-v1";

    /** Bounded temporal window (ms): only signals within [now - window, now] participate. */
    public static final long TEMPORAL_WINDOW_MS = 30_000L;

    /**
     * Baseline normalizer. The baseline is a fixed binary trigger: signal present = 1,
     * absent = 0. baselineScore = min(1, evidenceCount / BASELINE_FULL_EVIDENCE_COUNT),
     * so three distinct in-window signals saturate the baseline to 1.0. Pure research
     * reference, never used for enforcement.
     */
    public static final double BASELINE_FULL_EVIDENCE_COUNT = 3.0;

    /** Defensive upper bound on window rows loaded per attempt (bounded history). */
    public static final int MAX_WINDOW_SIGNALS = 200;

    /** Deterministic fallback confidence when an AI signal omits confidence (it normally must carry one). */
    public static final double AI_NO_CONFIDENCE_FALLBACK = 0.7;

    /**
     * Per-type base evidence weights. AI evidence types are experimental and share a
     * modest base weight; browser violation types use the deterministic severity scale.
     */
    public static final Map<String, Double> BASE_WEIGHTS = Map.ofEntries(
            Map.entry("WINDOW_BLUR", 0.7),
            Map.entry("TAB_SWITCH", 0.6),
            Map.entry("CAMERA_OFF", 0.8),
            Map.entry("MULTIPLE_FACES", 0.9),
            Map.entry("AUDIO_DETECTED", 0.7),
            Map.entry("NETWORK_INTERRUPTION", 0.4),
            Map.entry("FULLSCREEN_EXIT", 0.8),
            Map.entry("FACE_COUNT_ANOMALY", 0.7),
            Map.entry("PHONE_DETECTED", 0.8),
            Map.entry("UNKNOWN_OBJECT", 0.7),
            Map.entry("GAZE_ANOMALY", 0.6),
            Map.entry("HEAD_POSE_ANOMALY", 0.6));
    public static final double UNKNOWN_TYPE_BASE_WEIGHT = 0.5;

    /** Per-source trust weights (evidence trust, not identity trust). */
    public static final Map<String, Double> SOURCE_WEIGHTS = Map.of(
            "BROWSER", 0.8,
            "CLIENT_AI", 1.0,
            "SERVER_AI", 1.0,
            "HUMAN_INVIGILATOR", 1.0,
            "SYSTEM", 0.6);
    public static final double UNKNOWN_SOURCE_WEIGHT = 0.7;

    /**
     * Deterministic fallback confidences for signals that legitimately have no confidence
     * (non-AI sources). AI sources fall back to {@link #AI_NO_CONFIDENCE_FALLBACK}.
     */
    public static final Map<String, Double> NO_CONFIDENCE_FALLBACKS = Map.of(
            "BROWSER", 0.9,
            "SYSTEM", 0.5,
            "HUMAN_INVIGILATOR", 1.0);
    public static final double UNKNOWN_SOURCE_NO_CONFIDENCE_FALLBACK = 0.6;

    private ProctorFusionConfig() {
    }
}