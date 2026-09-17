package com.examora.model;

import java.time.Instant;

public record ExamRoomMember(
        String id,
        String roomId,
        String studentId,
        ExamRoomMemberStatus status,
        Instant joinedAt,
        Instant leftAt
) {
}