package com.examora.model;

import java.time.Instant;

public record ExamRoom(
        String id,
        String examId,
        String roomCode,
        ExamRoomStatus status,
        String createdBy,
        Instant startedAt,
        Instant endedAt,
        Instant createdAt,
        Instant updatedAt
) {
}