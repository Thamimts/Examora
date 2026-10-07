package com.examora.model;

import java.time.Instant;

/**
 * Persistent room/seat assignment for an enrolled student. Unique per (exam, student) and
 * per (exam, room, seat), which makes the seated arrangement deterministic across refreshes,
 * reconnects and restarts.
 */
public record RoomAssignment(
        String id,
        String examId,
        String studentId,
        String roomId,
        int seatNumber,
        Instant assignedAt
) {
}