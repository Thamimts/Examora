package com.examora.service;

import com.examora.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.format.DateTimeParseException;
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

    public static void validateExperimentVersions(String algorithmVersion, String baselineVersion) {
        if (algorithmVersion != null && algorithmVersion.isBlank()) {
            throw badRequest("algorithm version must not be blank");
        }
        if (baselineVersion != null && baselineVersion.isBlank()) {
            throw badRequest("baseline version must not be blank");
        }
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}