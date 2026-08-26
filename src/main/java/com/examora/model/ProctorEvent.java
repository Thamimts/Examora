package com.examora.model;

import java.util.Map;

public record ProctorEvent(String attemptId, String type, String occurredAt, Map<String, Object> metadata) {
}
