package com.examora.dto;

import com.examora.model.ProctorEvent;
import java.util.List;
import java.util.Map;

public final class ProctorDtos {
    private ProctorDtos() {
    }

    public record EventBatchRequest(List<ProctorEvent> events) {
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH
    }

    public enum WarningLevel {
        NONE, WARNING_1, WARNING_2, WARNING_3;

        public static WarningLevel fromWarningCount(int warningCount) {
            if (warningCount >= 3) return WARNING_3;
            if (warningCount == 2) return WARNING_2;
            if (warningCount == 1) return WARNING_1;
            return NONE;
        }
    }

    public record ProctorStudentDto(String id, String name, String email) {
    }

    public record ProctorEventDto(String id, String eventId, String attemptId, String type, String occurredAt,
                                  String serverReceivedAt, Map<String, Object> metadata) {
    }

    public record ProctorAttemptMonitor(String attemptId, int attemptNumber, String status, String startedAt,
                                        String expiresAt, ProctorStudentDto student, boolean activeNow,
                                        RiskLevel riskLevel, double riskScore, int eventCount,
                                        ProctorEventDto latestProctorEvent, String lastActivityAt) {
    }

    public record ProctorMonitorData(String examId, String examTitle, List<ProctorAttemptMonitor> attempts) {
    }

    public record ProctorSummary(String examId, String examTitle, boolean hasAttempts,
                                 int totalAttempts, int activeAttempts, int eventCount,
                                 RiskLevel riskLevel, Integer riskScore) {
    }
}