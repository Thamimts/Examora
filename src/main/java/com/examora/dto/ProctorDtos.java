package com.examora.dto;

import com.examora.model.ExamAccessStatus;
import com.examora.model.ExamRoomStatus;
import com.examora.model.ProctorEvent;
import java.util.List;
import java.util.Map;

public final class ProctorDtos {
    private ProctorDtos() {
    }

    public record EventBatchRequest(List<ProctorEvent> events) {
    }

    public record SubmitSignalRequest(String signalId, String signalType, String source, String occurredAt,
                                      Double confidence, Long durationMs, Map<String, Object> metadata) {
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
                                  String serverReceivedAt, Map<String, Object> metadata,
                                  String source, Double confidence, Long durationMs) {
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

    public record CommandCenterExam(
            String examId,
            String examTitle,
            String status,
            int duration,
            int totalStudents,
            int joinedStudents,
            int activeAttempts,
            int submittedAttempts,
            int offlineParticipants,
            int totalWarnings,
            int terminatedAttempts
    ) {
    }

    public record CommandCenterRoom(
            String roomId,
            String roomCode,
            ExamRoomStatus status,
            int memberCount,
            String startedAt,
            String endedAt
    ) {
    }

    public record CommandCenterStudent(
            String studentId,
            String studentName,
            String studentEmail,
            String roomId,
            String roomCode,
            ExamRoomStatus roomStatus,
            String attemptId,
            Integer attemptNumber,
            String attemptStatus,
            boolean activeNow,
            String startedAt,
            String expiresAt,
            int warningCount,
            WarningLevel warningLevel,
            ExamAccessStatus accessStatus,
            RiskLevel riskLevel,
            double riskScore,
            int eventCount,
            ProctorEventDto latestProctorEvent,
            String lastActivityAt,
            Boolean cameraOff,
            Boolean fullscreenExited,
            int audioSignalCount,
            int networkInterruptionCount,
            ShadowFusionDto shadowFusion
    ) {
    }

    public record ProctorFusionContributionDto(String id, String type, String source,
                                               double baseWeight, double sourceWeight,
                                               double confidence, double contribution) {
    }

    public record ProctorFusionResultDto(String attemptId, String algorithmVersion, int evidenceCount,
                                         String windowStart, String windowEnd,
                                         double baselineScore, double fusedScore, double fusedConfidence,
                                         List<ProctorFusionContributionDto> contributingSignals) {
    }

    public record ShadowFusionDto(Double baselineScore, Double fusedScore, Integer evidenceCount,
                                  String algorithmVersion) {
    }

    public record CommandCenterData(
            CommandCenterExam exam,
            List<CommandCenterRoom> rooms,
            List<CommandCenterStudent> roster
    ) {
    }
}