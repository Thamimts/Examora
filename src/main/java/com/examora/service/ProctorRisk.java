package com.examora.service;

import com.examora.dto.ProctorDtos.RiskLevel;
import com.examora.repository.ProctorRepository.ProctorEventRow;
import java.util.List;
import java.util.Map;

/**
 * Shared proctoring risk scoring, used by both the live publisher and the pull-based
 * dashboards so a student's risk never diverges between the two views.
 */
public final class ProctorRisk {
    public static final Map<String, Integer> TYPE_SEVERITY = Map.ofEntries(
            Map.entry("WINDOW_BLUR", 10),
            Map.entry("TAB_SWITCH", 15),
            Map.entry("CAMERA_OFF", 20),
            Map.entry("MULTIPLE_FACES", 30),
            Map.entry("AUDIO_DETECTED", 20),
            Map.entry("NETWORK_INTERRUPTION", 5));
    public static final int UNKNOWN_TYPE_SEVERITY = 5;
    public static final int MEDIUM_THRESHOLD = 30;
    public static final int HIGH_THRESHOLD = 60;
    public static final int MAX_RISK = 100;

    private ProctorRisk() {
    }

    public static Risk of(List<ProctorEventRow> events) {
        int total = 0;
        for (ProctorEventRow event : events) {
            if (event.type() == null || event.type().isBlank()) {
                continue;
            }
            if (ProctorSignalTypes.FUTURE_AI_SIGNAL_TYPES.contains(event.type().trim().toUpperCase())) {
                continue;
            }
            total += TYPE_SEVERITY.getOrDefault(event.type().trim().toUpperCase(), UNKNOWN_TYPE_SEVERITY);
        }
        int capped = Math.min(MAX_RISK, total);
        RiskLevel level = capped >= HIGH_THRESHOLD ? RiskLevel.HIGH
                : capped >= MEDIUM_THRESHOLD ? RiskLevel.MEDIUM
                : RiskLevel.LOW;
        return new Risk(level, capped);
    }

    public record Risk(RiskLevel level, double score) {
    }
}