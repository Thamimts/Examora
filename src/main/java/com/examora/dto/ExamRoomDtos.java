package com.examora.dto;

import com.examora.model.ExamRoomMemberStatus;
import com.examora.model.ExamRoomStatus;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

public final class ExamRoomDtos {
    private ExamRoomDtos() {
    }

    public record CreateRoomRequest(
            @NotBlank(message = "Exam id is required.") String examId
    ) {
    }

    public record JoinRoomRequest(
            @NotBlank(message = "Room code is required.") String roomCode
    ) {
    }

    public record ExamRoomDto(
            String roomId,
            String examId,
            String examTitle,
            String roomCode,
            ExamRoomStatus status,
            String createdBy,
            String startedAt,
            String endedAt,
            String createdAt,
            long memberCount
    ) {
    }

    public record RoomMemberDto(
            String memberId,
            String roomId,
            String studentId,
            String studentName,
            String studentEmail,
            ExamRoomMemberStatus status,
            String joinedAt,
            String leftAt
    ) {
    }

    public record RoomActivityDto(
            String roomId,
            String examId,
            String roomCode,
            ExamRoomStatus status,
            String type,
            String message,
            String at
    ) {
    }

    public record RoomDetailDto(
            ExamRoomDto room,
            List<RoomMemberDto> members
    ) {
    }

    public record JoinRoomResponse(
            String roomId,
            String examId,
            String roomCode,
            ExamRoomStatus status,
            String message
    ) {
    }
}