package com.examora.service;

import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Condition vocabulary for captures made through a research run. This is deliberately a
     * distinct, narrower allow-list than the general CONDITION_KEYS so that a controlled run
     * can only record unambiguous environmental conditions. Only environmental characteristics
     * are allowed — demographic attributes are never accepted.
     */
    public static final Map<String, Set<String>> CONTROLLED_CONDITIONS = Map.of(
            "lighting", Set.of("GOOD", "LOW"),
            "cameraQuality", Set.of("HD", "LOW"),
            "network", Set.of("STABLE", "INTERRUPTED"),
            "cameraAngle", Set.of("FRONT", "OFF_ANGLE"));

    public static final Set<String> RUN_STATUSES = Set.of(
            "PLANNED", "RUNNING", "COMPLETED", "CANCELLED");
    public static final Set<String> RUN_SAMPLE_STATUSES = Set.of(
            "PLANNED", "CAPTURING", "CAPTURED");

    public static final int MAX_RUN_SAMPLES = 500;
    public static final int MAX_RUN_CODE_LENGTH = 40;
    public static final int MAX_RUN_NOTES_LENGTH = 1000;

    /**
     * Deterministic ordering of scenarios for matrix rendering and summaries.
     */
    public static final List<String> SCENARIO_ORDER = List.of(
            "NORMAL", "WINDOW_BLUR", "TAB_SWITCH", "CAMERA_OFF", "MULTIPLE_FACES",
            "AUDIO_DETECTED", "FULLSCREEN_EXIT", "NETWORK_INTERRUPTION");

    /**
     * The signal types legitimately expected from each controlled scenario. The expected set
     * is metadata used for data-quality reporting (unexpected signals), never a substitute for
     * human review. NORMAL expects no signals, so any in-window signal is flagged as unexpected.
     */
    public static final Map<String, Set<String>> SCENARIO_EXPECTED_SIGNALS = Map.of(
            "NORMAL", Set.of(),
            "WINDOW_BLUR", Set.of("WINDOW_BLUR"),
            "TAB_SWITCH", Set.of("TAB_SWITCH"),
            "CAMERA_OFF", Set.of("CAMERA_OFF"),
            "MULTIPLE_FACES", Set.of("MULTIPLE_FACES", "FACE_COUNT_ANOMALY", "MULTIPLE_PERSONS"),
            "AUDIO_DETECTED", Set.of("AUDIO_DETECTED"),
            "FULLSCREEN_EXIT", Set.of("FULLSCREEN_EXIT"),
            "NETWORK_INTERRUPTION", Set.of("NETWORK_INTERRUPTION"));

    public record ScenarioInstruction(String description, String expectedAction,
                                      String durationGuidance, String reviewerObservation) {
    }

    public static final Map<String, ScenarioInstruction> SCENARIO_INSTRUCTIONS;

    static {
        Map<String, ScenarioInstruction> instructions = new LinkedHashMap<>();
        instructions.put("NORMAL", new ScenarioInstruction(
                "Student studies and answers normally without any anomaly.",
                "No anomalous proctoring title is expected; detection should produce nothing.",
                "20-30 seconds of continuous normal activity.",
                "Note any noise, screen activity, or camera movement that produced an unexpected signal."));
        instructions.put("WINDOW_BLUR", new ScenarioInstruction(
                "The camera is briefly blurred, for example by finger smudging or lens obstruction.",
                "A WINDOW_BLUR anomaly should fire while the blur is present.",
                "15-25 seconds with blur present.",
                "Confirm the blur was incidental and limited to the window, not a deliberate concealment."));
        instructions.put("TAB_SWITCH", new ScenarioInstruction(
                "The student switches to another tab or application and stays there briefly.",
                "A TAB_SWITCH anomaly should fire during the switch.",
                "10-20 seconds of focus on another tab.",
                "Confirm the switch was deliberate and outside the exam context."));
        instructions.put("CAMERA_OFF", new ScenarioInstruction(
                "The student turns the camera off (hardware control or OS permission denial).",
                "A CAMERA_OFF anomaly should fire while the camera is disabled.",
                "10-20 seconds with the camera disabled.",
                "Confirm the lens was reliably disabled; note any mandatory-camera window the app enforced."));
        instructions.put("MULTIPLE_FACES", new ScenarioInstruction(
                "A second person enters the camera frame while the student remains present.",
                "A face-count anomaly should fire (MULTIPLE_FACES, FACE_COUNT_ANOMALY, or MULTIPLE_PERSONS).",
                "15-25 seconds with the second face visible.",
                "Confirm both faces stayed in frame and the second person was not on-screen media only."));
        instructions.put("AUDIO_DETECTED", new ScenarioInstruction(
                "Unexpected audio (speech, music, or alert) is played from a device near the student.",
                "An AUDIO_DETECTED anomaly should fire while the audio plays.",
                "15-25 seconds of continuous unexpected audio.",
                "Confirm the audio originated from the testing environment."));
        instructions.put("FULLSCREEN_EXIT", new ScenarioInstruction(
                "The student exits fullscreen mode and stays in a windowed browser layout.",
                "A FULLSCREEN_EXIT anomaly should fire after leaving fullscreen.",
                "10-20 seconds outside fullscreen.",
                "Confirm fullscreen was left intentionally and the exam window remained visible."));
        instructions.put("NETWORK_INTERRUPTION", new ScenarioInstruction(
                "Network connectivity drops or becomes unusable for a short interval.",
                "A NETWORK_INTERRUPTION anomaly should fire while connectivity is lost.",
                "15-30 seconds of failed connectivity.",
                "Confirm the disconnection was genuine; note that this scenario is excluded from the "
                        + "production three-warning enforcement rule."));
        SCENARIO_INSTRUCTIONS = Map.copyOf(instructions);
    }
}