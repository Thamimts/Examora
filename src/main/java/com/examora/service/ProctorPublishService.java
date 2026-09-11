package com.examora.service;

import com.examora.dto.ProctorDtos.ProctorEventDto;
import com.examora.dto.ProctorDtos.ProctorStudentDto;
import com.examora.dto.ProctorDtos.RiskLevel;
import com.examora.dto.ProctorUpdate;
import com.examora.model.Exam;
import com.examora.model.ExamAttempt;
import com.examora.model.User;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.ExamRepository;
import com.examora.repository.ProctorRepository;
import com.examora.repository.ProctorRepository.ProctorEventRow;
import com.examora.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProctorPublishService {
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

    private final SimpMessagingTemplate messaging;
    private final ExamRepository examRepository;
    private final ExamAttemptRepository attemptRepository;
    private final ProctorRepository proctorRepository;
    private final UserRepository userRepository;
    private final AtomicLong sequenceGenerator = new AtomicLong(0);

    public ProctorPublishService(SimpMessagingTemplate messaging, ExamRepository examRepository,
                                  ExamAttemptRepository attemptRepository, ProctorRepository proctorRepository,
                                  UserRepository userRepository) {
        this.messaging = messaging;
        this.examRepository = examRepository;
        this.attemptRepository = attemptRepository;
        this.proctorRepository = proctorRepository;
        this.userRepository = userRepository;
    }

    public void publishLifecycle(Exam exam, ExamAttempt attempt, User student, String status) {
        ProctorUpdate update = buildUpdate(exam.id(), attempt.id(), status, student);
        messaging.convertAndSend("/topic/exams/" + exam.id() + "/activity", update);
    }

    public void publishAfterEventBatch(String attemptId) {
        ExamAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null) return;
        Exam exam = examRepository.findById(attempt.examId()).orElse(null);
        if (exam == null) return;
        User student = userRepository.findById(attempt.studentId()).orElse(null);
        if (student == null) return;
        ProctorUpdate update = buildUpdate(exam.id(), attempt.id(), attempt.status(), student);
        messaging.convertAndSend("/topic/exams/" + exam.id() + "/activity", update);
    }

    public void publishExpired(ExamAttempt attempt) {
        Exam exam = examRepository.findById(attempt.examId()).orElse(null);
        if (exam == null) return;
        User student = userRepository.findById(attempt.studentId()).orElse(null);
        if (student == null) return;
        ProctorUpdate update = buildUpdate(exam.id(), attempt.id(), "EXPIRED", student);
        messaging.convertAndSend("/topic/exams/" + exam.id() + "/activity", update);
    }

    private ProctorUpdate buildUpdate(String examId, String attemptId, String status, User student) {
        List<ProctorEventRow> events = proctorRepository.findByAttemptId(attemptId, 200);
        RiskScore risk = riskOf(events);
        ProctorEventDto latestEvent = events.isEmpty() ? null : toDto(events.getFirst());
        ProctorStudentDto studentDto = new ProctorStudentDto(student.id(), student.name(), student.email());
        return new ProctorUpdate(
                examId,
                attemptId,
                status,
                studentDto,
                risk.level(),
                risk.score(),
                latestEvent,
                events.size(),
                sequenceGenerator.incrementAndGet(),
                Instant.now());
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

    private record RiskScore(RiskLevel level, double score) {
    }
}
