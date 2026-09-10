package com.examora.service;

import com.examora.dto.ProctorDtos.ProctorAttemptMonitor;
import com.examora.dto.ProctorDtos.ProctorEventDto;
import com.examora.dto.ProctorDtos.ProctorMonitorData;
import com.examora.dto.ProctorDtos.ProctorStudentDto;
import com.examora.dto.ProctorDtos.RiskLevel;
import com.examora.exception.ApiException;
import com.examora.model.Exam;
import com.examora.model.ExamAttempt;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.ExamRepository;
import com.examora.repository.ProctorRepository;
import com.examora.repository.ProctorRepository.ProctorEventRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProctorMonitorService {
    private static final Map<String, Integer> TYPE_SEVERITY = Map.ofEntries(
            Map.entry("WINDOW_BLUR", 10),
            Map.entry("TAB_SWITCH", 15),
            Map.entry("CAMERA_OFF", 20),
            Map.entry("MULTIPLE_FACES", 30),
            Map.entry("AUDIO_DETECTED", 20),
            Map.entry("NETWORK_INTERRUPTION", 5));
    private static final int UNKNOWN_TYPE_SEVERITY = 5;
    private static final int MEDIUM_THRESHOLD = 30;
    private static final int HIGH_THRESHOLD = 60;
    private static final int MAX_RISK = 100;

    private final ExamRepository examRepository;
    private final ExamAttemptRepository attemptRepository;
    private final ProctorRepository proctorRepository;
    private final ExamAttemptService examAttemptService;

    public ProctorMonitorService(ExamRepository examRepository, ExamAttemptRepository attemptRepository,
                                 ProctorRepository proctorRepository, ExamAttemptService examAttemptService) {
        this.examRepository = examRepository;
        this.attemptRepository = attemptRepository;
        this.proctorRepository = proctorRepository;
        this.examAttemptService = examAttemptService;
    }

    public ProctorMonitorData monitor(String examId, User actor) {
        Exam exam = requireMonitoredExam(examId, actor);
        List<ExamAttemptRepository.AttemptWithStudent> attempts = attemptRepository.findByExamWithStudent(exam.id());
        Map<String, List<ProctorEventRow>> eventsByAttempt = proctorRepository.findByExamId(exam.id()).stream()
                .collect(Collectors.groupingBy(ProctorEventRow::attemptId));
        Instant now = Instant.now();
        List<ProctorAttemptMonitor> items = attempts.stream()
                .map(row -> build(row, eventsByAttempt.getOrDefault(row.attempt().id(), List.of()), now))
                .toList();
        return new ProctorMonitorData(exam.id(), exam.title(), items);
    }

    public List<ProctorEventDto> events(String attemptId, User actor, int limit) {
        if (limit < 1 || limit > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Limit must be between 1 and 200.");
        }
        ExamAttempt attempt = examAttemptService.requireProctorAccess(attemptId, actor);
        return proctorRepository.findByAttemptId(attempt.id(), limit).stream()
                .map(this::toDto)
                .toList();
    }

    private ProctorAttemptMonitor build(ExamAttemptRepository.AttemptWithStudent row,
                                        List<ProctorEventRow> events, Instant now) {
        ExamAttempt attempt = row.attempt();
        boolean activeNow = "STARTED".equals(attempt.status()) && now.isBefore(attempt.expiresAt());
        RiskScore risk = riskOf(events);
        ProctorEventRow latest = events.isEmpty() ? null : events.getFirst();
        return new ProctorAttemptMonitor(
                attempt.id(),
                attempt.attemptNumber(),
                attempt.status(),
                attempt.startedAt().toString(),
                attempt.expiresAt().toString(),
                new ProctorStudentDto(attempt.studentId(), row.studentName(), row.studentEmail()),
                activeNow,
                risk.level(),
                risk.score(),
                events.size(),
                latest == null ? null : toDto(latest),
                latest == null ? null : latest.occurredAt());
    }

    private RiskScore riskOf(List<ProctorEventRow> events) {
        int total = 0;
        for (ProctorEventRow event : events) {
            if (event.type() == null || event.type().isBlank()) {
                continue;
            }
            total += TYPE_SEVERITY.getOrDefault(event.type().trim().toUpperCase(), UNKNOWN_TYPE_SEVERITY);
        }
        int capped = Math.min(MAX_RISK, total);
        RiskLevel level = capped >= HIGH_THRESHOLD ? RiskLevel.HIGH
                : capped >= MEDIUM_THRESHOLD ? RiskLevel.MEDIUM
                : RiskLevel.LOW;
        return new RiskScore(level, capped);
    }

    private ProctorEventDto toDto(ProctorEventRow row) {
        return new ProctorEventDto(row.id(), row.eventId(), row.attemptId(), row.type(), row.occurredAt(),
                row.serverReceivedAt(), row.metadata());
    }

    private Exam requireMonitoredExam(String examId, User actor) {
        if (examId == null || examId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exam id is required.");
        }
        Exam exam = examRepository.findById(examId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found."));
        if (actor == null || actor.role() == Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Teacher or administrator access is required.");
        }
        if (actor.role() == Role.TEACHER) {
            boolean ownsExam = examRepository.findOwnerId(exam.id())
                    .map(ownerId -> ownerId.equals(actor.id()))
                    .orElse(false);
            if (!ownsExam) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You do not have access to this exam's proctoring.");
            }
        }
        return exam;
    }

    private record RiskScore(RiskLevel level, double score) {
    }
}