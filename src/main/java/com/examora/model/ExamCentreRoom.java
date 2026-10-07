package com.examora.model;

import java.time.Instant;

/**
 * A physical room inside an {@link ExamCentre} where enrolled students are seated.
 * Named ExamCentreRoom (rather than ExamRoom) to avoid clashing with the existing
 * live, code-based exam session room model.
 */
public record ExamCentreRoom(
        String id,
        String centreId,
        String roomName,
        String roomCode,
        int capacity,
        String invigilatorId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}