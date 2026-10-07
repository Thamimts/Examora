package com.examora.model;

import java.time.Instant;

/**
 * A physical location where exams are held. Centres host {@link ExamCentreRoom}s.
 */
public record ExamCentre(
        String id,
        String name,
        String code,
        String address,
        String contactPhone,
        String contactEmail,
        String status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}