package com.examora.service;

import com.examora.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ResearchValidation {

    private ResearchValidation() {}

    public static String normalizeTimestamp(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required");
        }
        try {
            return ProctorFusionService.parseInstant(value).toString();
        } catch (DateTimeParseException ex) {
            throw badRequest(field + " must be a valid ISO-8601 instant");
        }
    }

    public static Instant parseTimestamp(String value, String field) {
        try {
            return ProctorFusionService.parseInstant(value);
        } catch (DateTimeParseException ex) {
            throw badRequest(field + " must be a valid ISO-8601 instant");
        }
    }

    public static void validateWindow(Instant windowStart, Instant windowEnd, Instant now) {
        if (windowStart == null || windowEnd == null) {
            throw badRequest("windowStart and windowEnd are required");
        }
        if (!windowEnd.isAfter(windowStart)) {
            throw badRequest("windowEnd must be after windowStart");
        }
        long durationMs = windowEnd.toEpochMilli() - windowStart.toEpochMilli();
        if (durationMs < ResearchConfig.MIN_WINDOW_MS || durationMs > ResearchConfig.MAX_WINDOW_MS) {
            throw badRequest("window duration must be between " + ResearchConfig.MIN_WINDOW_MS
                    + " and " + ResearchConfig.MAX_WINDOW_MS + " ms");
        }
        long futureToleranceMs = now.toEpochMilli() + ResearchConfig.CLOCK_TOLERANCE_MS;
        if (windowEnd.toEpochMilli() > futureToleranceMs) {
            throw badRequest("windowEnd must not be in the future beyond " + ResearchConfig.CLOCK_TOLERANCE_MS + " ms");
        }
    }

    public static void validateAttemptWindow(Instant windowStart, Instant windowEnd, Instant attemptStart, Instant attemptExpires) {
        long tolerance = ResearchConfig.CLOCK_TOLERANCE_MS;
        long attemptFrom = attemptStart.toEpochMilli() - tolerance;
        long attemptTo = attemptExpires.toEpochMilli() + tolerance;
        if (windowEnd.toEpochMilli() < attemptFrom || windowStart.toEpochMilli() > attemptTo) {
            throw badRequest("window must overlap the referenced attempt span");
        }
    }

    public static void validateLabel(String label) {
        if (label == null) {
            return;
        }
        if (!ResearchConfig.ALLOWED_LABELS.contains(label)) {
            throw badRequest("label must be one of: " + String.join(", ", ResearchConfig.ALLOWED_LABELS));
        }
    }

    public static boolean isNegativeLabel(String label) {
        return label != null && ResearchConfig.NEGATIVE_LABELS.contains(label);
    }

    public static void validateConfidence(Double confidence) {
        if (confidence == null) {
            return;
        }
        if (Double.isNaN(confidence) || Double.isInfinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw badRequest("confidence must be null or in [0, 1]");
        }
    }

    public static void validateNotes(String notes) {
        if (notes != null && notes.length() > 500) {
            throw badRequest("notes must be at most 500 characters");
        }
    }

    public static void validateConditionMetadata(Map<String, String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            Set<String> allowed = ResearchConfig.CONDITION_KEYS.get(key);
            if (allowed == null) {
                throw badRequest("conditions key '" + key + "' is not a supported condition");
            }
            if (value == null || !allowed.contains(value)) {
                throw badRequest("conditions value for '" + key + "' must be one of: " + String.join(", ", allowed));
            }
        }
    }

    public static void validateByteCounts(Long rawMediaBytes, Long signalBytes) {
        if (rawMediaBytes == null && signalBytes == null) {
            return;
        }
        if (rawMediaBytes == null || signalBytes == null) {
            throw badRequest("rawMediaBytes and signalBytes must be provided together");
        }
        if (rawMediaBytes < 0 || signalBytes < 0) {
            throw badRequest("byte counts must be non-negative");
        }
    }

    public static void validateStatus(String status) {
        if (status == null || !ResearchConfig.EXPERIMENT_STATUSES.contains(status)) {
            throw badRequest("status must be one of: " + String.join(", ", ResearchConfig.EXPERIMENT_STATUSES));
        }
    }

    public static void validateScenario(String scenario) {
        if (scenario == null) {
            return;
        }
        if (!ResearchConfig.SCENARIOS.contains(scenario)) {
            throw badRequest("scenario must be one of: " + String.join(", ", ResearchConfig.SCENARIOS));
        }
    }

    public static String scenarioLabel(String scenario) {
        return scenario == null ? null : ResearchConfig.SCENARIO_LABELS.get(scenario);
    }

    public static String scenarioExpectedLabel(String scenario) {
        return scenario;
    }

    public static boolean scenarioAgrees(String scenario, String resolvedLabel) {
        return scenario != null && scenario.equals(resolvedLabel);
    }

    public static void validateMeasuredLatency(Long measuredLatencyMs) {
        if (measuredLatencyMs != null && measuredLatencyMs < 0) {
            throw badRequest("measuredLatencyMs must be non-negative");
        }
    }

    public static void validateDatasetVersion(String datasetVersion) {
        if (datasetVersion == null) {
            return;
        }
        if (datasetVersion.isBlank()) {
            throw badRequest("dataset version must not be blank");
        }
    }

    public static void validateRunCode(String runCode) {
        if (runCode == null || runCode.isBlank()) {
            throw badRequest("runCode is required");
        }
        String code = runCode.trim();
        if (code.length() > ResearchConfig.MAX_RUN_CODE_LENGTH) {
            throw badRequest("runCode must be at most " + ResearchConfig.MAX_RUN_CODE_LENGTH + " characters");
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_')) {
                throw badRequest("runCode may contain only letters, digits, '.', '-' and '_'");
            }
        }
    }

    public static String normalizeRunCode(String runCode) {
        validateRunCode(runCode);
        return runCode.trim();
    }

    public static void validateRunNotes(String notes) {
        if (notes != null && notes.length() > ResearchConfig.MAX_RUN_NOTES_LENGTH) {
            throw badRequest("notes must be at most " + ResearchConfig.MAX_RUN_NOTES_LENGTH + " characters");
        }
    }

    public static void validateRunStatus(String status) {
        if (status == null || !ResearchConfig.RUN_STATUSES.contains(status)) {
            throw badRequest("run status must be one of: " + String.join(", ", ResearchConfig.RUN_STATUSES));
        }
    }

    public static void validateRunSampleStatus(String status) {
        if (status == null || !ResearchConfig.RUN_SAMPLE_STATUSES.contains(status)) {
            throw badRequest("run sample status must be one of: " + String.join(", ", ResearchConfig.RUN_SAMPLE_STATUSES));
        }
    }

    /**
     * Validates a controlled-run condition payload strictly against CONTROLLED_CONDITIONS.
     * Unknown keys and values are rejected up-front so only the allowed environmental
     * vocabulary can ever be recorded. Demographic keys are structurally impossible to pass.
     */
    public static void validateControlledConditions(Map<String, String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            throw badRequest("condition is required");
        }
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            Set<String> allowed = ResearchConfig.CONTROLLED_CONDITIONS.get(key);
            if (allowed == null) {
                throw badRequest("condition '" + key + "' is not a supported controlled condition");
            }
            if (value == null || !allowed.contains(value)) {
                throw badRequest("condition value for '" + key + "' must be one of: " + String.join(", ", allowed));
            }
        }
    }

    /**
     * Canonical, deterministic condition key used for the run-sample uniqueness constraint.
     * Keys are sorted so equivalent payloads always map to the same key.
     */
    public static String conditionsKey(Map<String, String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            throw badRequest("condition is required");
        }
        ArrayList<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        java.util.Collections.sort(parts);
        return String.join(";", parts);
    }

    /**
     * Whether an attempt belongs to the experiment's exam. A missing experiment exam id
     * means the experiment is not tied to a specific exam, so any valid attempt belongs.
     */
    public static boolean attemptBelongsToExperiment(String attemptExamId, String experimentExamId) {
        return experimentExamId == null || experimentExamId.equals(attemptExamId);
    }

    public static void validateCaptureRequest(Long measuredLatencyMs, Long rawMediaBytes, Long signalBytes) {
        validateMeasuredLatency(measuredLatencyMs);
        validateByteCounts(rawMediaBytes, signalBytes);
    }

    /**
     * Pure overlap check for two bounded windows: window A overlaps window B when
     * A.start < B.end and A.end > B.start. Used both directly (validations) and by the
     * repository query so semantics stay in one place.
     */
    public static boolean hasOverlap(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return aStart != null && aEnd != null && bStart != null && bEnd != null
                && aStart.isBefore(bEnd) && aEnd.isAfter(bStart);
    }

    /**
     * Effective label for a set of review labels: the strict majority, or null when there
     * is no resolved majority (empty reviews or a tie). A tie is never silently converted.
     */
    public static String majorityLabel(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String label : labels) {
            if (label == null) {
                continue;
            }
            counts.merge(label, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return null;
        }
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> leaders = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == max) {
                leaders.add(entry.getKey());
            }
        }
        return leaders.size() == 1 ? leaders.getFirst() : null;
    }

    public static void validateExperimentVersions(String algorithmVersion, String baselineVersion) {
        if (algorithmVersion != null && algorithmVersion.isBlank()) {
            throw badRequest("algorithm version must not be blank");
        }
        if (baselineVersion != null && baselineVersion.isBlank()) {
            throw badRequest("baseline version must not be blank");
        }
    }

    /**
     * Human-review agreement state for a single sample:
     * UNREVIEWED - no reviews recorded; AGREED - resolved by a unanimous majority (a single
     * review counts as unanimous); TIED - reviews recorded but no resolved majority; RESOLVED -
     * a non-unanimous majority resolved the label. TIED is never evaluated and never silently
     * converted.
     */
    public static String agreementState(int reviewCount, String resolvedLabel, boolean unanimous) {
        if (reviewCount <= 0) {
            return "UNREVIEWED";
        }
        if (resolvedLabel == null) {
            return "TIED";
        }
        return unanimous ? "AGREED" : "RESOLVED";
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}