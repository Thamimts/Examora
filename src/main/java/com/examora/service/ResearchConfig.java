package com.examora.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ResearchConfig {

    private ResearchConfig() {}

    public static final String ALGORITHM_VERSION = "fusion-v1";
    public static final String BASELINE_VERSION = "baseline-v1";
    public static final String DATASET_VERSION = "dataset-v1";

    public static final double FUSION_THRESHOLD = 0.5;
    public static final long MIN_WINDOW_MS = 1_000L;
    public static final long MAX_WINDOW_MS = 30_000L;
    public static final long CLOCK_TOLERANCE_MS = 60_000L;
    public static final long WINDOW_SLACK_MS = 1_000L;
    public static final int MAX_SAMPLES_PER_EXPERIMENT = 500;
    public static final int MAX_WINDOW_SIGNALS = 200;

    public static final Set<String> EXPERIMENT_STATUSES = Set.of(
            "DRAFT", "ACTIVE", "COMPLETED", "ARCHIVED");

    public static final Set<String> NEGATIVE_LABELS = Set.of("NORMAL", "NO_ANOMALY");

    public static final Set<String> POSITIVE_LABELS = Set.of(
            "ANOMALY", "FACE_COUNT_ANOMALY", "PHONE_PRESENT", "MULTIPLE_PERSONS",
            "UNKNOWN_OBJECT", "GAZE_ANOMALY", "HEAD_POSE_ANOMALY", "TAB_SWITCH",
            "WINDOW_BLUR", "CAMERA_OFF", "AUDIO_DETECTED", "NETWORK_INTERRUPTION",
            "FULLSCREEN_EXIT");

    public static final Set<String> ALLOWED_LABELS = Set.copyOf(
            new java.util.LinkedHashSet<>() {{
                addAll(NEGATIVE_LABELS);
                addAll(POSITIVE_LABELS);
            }});

    /**
     * Fixed allow-list of controlled proctoring scenarios. The scenario describes the
     * intended neutral ground-truth condition — it is experiment metadata, never a
     * substitute for human review. NETWORK_INTERRUPTION is intentionally included even
     * though it is excluded from the production three-warning enforcement rule.
     */
    public static final Set<String> SCENARIOS = Set.of(
            "NORMAL", "WINDOW_BLUR", "TAB_SWITCH", "CAMERA_OFF", "MULTIPLE_FACES",
            "AUDIO_DETECTED", "FULLSCREEN_EXIT", "NETWORK_INTERRUPTION");

    public static final Map<String, String> SCENARIO_LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("NORMAL", "Normal behavior");
        labels.put("WINDOW_BLUR", "Incidental window blur");
        labels.put("TAB_SWITCH", "Tab switch");
        labels.put("CAMERA_OFF", "Camera turned off");
        labels.put("MULTIPLE_FACES", "Multiple faces visible");
        labels.put("AUDIO_DETECTED", "Unexpected audio detected");
        labels.put("FULLSCREEN_EXIT", "Exited fullscreen");
        labels.put("NETWORK_INTERRUPTION", "Network interruption");
        SCENARIO_LABELS = Map.copyOf(labels);
    }

    public static final Map<String, Set<String>> CONDITION_KEYS = Map.of(
            "lighting", Set.of("NORMAL", "LOW", "BRIGHT"),
            "cameraQuality", Set.of("LOW", "MEDIUM", "HIGH"),
            "network", Set.of("GOOD", "DEGRADED"),
            "cameraAngle", Set.of("NORMAL", "OBSTRUCTED"));
}