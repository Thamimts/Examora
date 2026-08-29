package com.examora.model;

import java.time.Instant;

public record ActivityEvent(String id, String type, String message, Instant createdAt) {}
