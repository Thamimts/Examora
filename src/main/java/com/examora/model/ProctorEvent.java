package com.examora.model;

import java.util.Map;

public record ProctorEvent(String eventId, String attemptId, String type, String occurredAt,
                           Map<String, Object> metadata, String source, Double confidence, Long durationMs) {

    public ProctorEvent {
        if (source == null || source.isBlank()) {
            source = "BROWSER";
        }
    }

    public ProctorEvent(String eventId, String attemptId, String type, String occurredAt, Map<String, Object> metadata) {
        this(eventId, attemptId, type, occurredAt, metadata, "BROWSER", null, null);
    }
}