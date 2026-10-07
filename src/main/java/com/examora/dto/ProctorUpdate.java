package com.examora.dto;

import com.examora.dto.ProctorDtos.ProctorEventDto;
import com.examora.dto.ProctorDtos.ProctorStudentDto;
import com.examora.dto.ProctorDtos.RiskLevel;
import com.examora.dto.ProctorDtos.WarningLevel;
import com.examora.model.ExamAccessStatus;
import java.time.Instant;

public record ProctorUpdate(
        String examId,
        String attemptId,
        String status,
        ProctorStudentDto student,
        RiskLevel riskLevel,
        double riskScore,
        ProctorEventDto latestEvent,
        int eventCount,
        long sequence,
        Instant occurredAt,
        int warningCount,
        WarningLevel warningLevel,
        ExamAccessStatus accessStatus,
        String roomId
) {
}
