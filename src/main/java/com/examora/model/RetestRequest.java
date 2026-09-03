package com.examora.model;

import java.time.Instant;

public record RetestRequest(String id, String examId, String studentId, String studentName, String examTitle,
                            String status, Instant requestedAt, Instant reviewedAt, String reviewedBy,
                            String reason) {}
