package com.examora.service;

import com.examora.dto.ProctorUpdate;
import com.examora.dto.ProctorDtos.ProctorEventDto;
import com.examora.dto.ProctorDtos.ProctorStudentDto;
import com.examora.dto.ProctorDtos.RiskLevel;
import com.examora.dto.ProctorDtos.WarningLevel;
import com.examora.model.Exam;
import com.examora.model.ExamAccessStatus;
import com.examora.model.ExamAttempt;
import com.examora.model.User;
import com.examora.repository.ExamAccessRepository;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.ExamRepository;
import com.examora.repository.ProctorRepository;
import com.examora.repository.ProctorRepository.ProctorEventRow;
import com.examora.repository.RoomAssignmentRepository;
import com.examora.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProctorPublishService {
    private final SimpMessagingTemplate messaging;
    private final ExamRepository examRepository;
    private final ExamAttemptRepository attemptRepository;
    private final ProctorRepository proctorRepository;
    private final UserRepository userRepository;
    private final ExamAccessRepository accessRepository;
    private final RoomAssignmentRepository roomAssignmentRepository;
    private final AtomicLong sequenceGenerator = new AtomicLong(0);

    public ProctorPublishService(SimpMessagingTemplate messaging, ExamRepository examRepository,
                                  ExamAttemptRepository attemptRepository, ProctorRepository proctorRepository,
                                  UserRepository userRepository, ExamAccessRepository accessRepository,
                                  RoomAssignmentRepository roomAssignmentRepository) {
        this.messaging = messaging;
        this.examRepository = examRepository;
        this.attemptRepository = attemptRepository;
        this.proctorRepository = proctorRepository;
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.roomAssignmentRepository = roomAssignmentRepository;
    }

    public void publishLifecycle(Exam exam, ExamAttempt attempt, User student, String status) {
        ProctorUpdate update = buildUpdate(exam.id(), attempt.id(), status, student);
        dispatch(update);
    }

    public void publishAfterEventBatch(String attemptId) {
        ExamAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null) return;
        Exam exam = examRepository.findById(attempt.examId()).orElse(null);
        if (exam == null) return;
        User student = userRepository.findById(attempt.studentId()).orElse(null);
        if (student == null) return;
        ProctorUpdate update = buildUpdate(exam.id(), attempt.id(), attempt.status(), student);
        dispatch(update);
    }

    public void publishExpired(ExamAttempt attempt) {
        Exam exam = examRepository.findById(attempt.examId()).orElse(null);
        if (exam == null) return;
        User student = userRepository.findById(attempt.studentId()).orElse(null);
        if (student == null) return;
        ProctorUpdate update = buildUpdate(exam.id(), attempt.id(), "EXPIRED", student);
        dispatch(update);
    }

    private void dispatch(ProctorUpdate update) {
        messaging.convertAndSend("/topic/exams/" + update.examId() + "/activity", update);
        if (update.roomId() != null && !update.roomId().isBlank()) {
            messaging.convertAndSend("/topic/rooms/" + update.roomId() + "/activity", update);
        }
        if (update.student() != null && update.student().id() != null) {
            messaging.convertAndSendToUser(update.student().id(), "/queue/proctor", update);
        }
    }

    private ProctorUpdate buildUpdate(String examId, String attemptId, String status, User student) {
        List<ProctorEventRow> events = proctorRepository.findByAttemptId(attemptId, 200);
        RiskScore risk = riskOf(events);
        ProctorEventDto latestEvent = events.isEmpty() ? null : toDto(events.getFirst());
        ProctorStudentDto studentDto = new ProctorStudentDto(student.id(), student.name(), student.email());
        int warningCount = attemptRepository.warningCount(attemptId).orElse(0);
        WarningLevel warningLevel = WarningLevel.fromWarningCount(warningCount);
        ExamAccessStatus accessStatus = accessRepository.findStatus(student.id(), examId)
                .orElse(ExamAccessStatus.ELIGIBLE);
        String roomId = roomAssignmentRepository.findByStudentAndExam(student.id(), examId)
                .map(assignment -> assignment.roomId())
                .orElse(null);
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
                Instant.now(),
                warningCount,
                warningLevel,
                accessStatus,
                roomId);
    }

    private RiskScore riskOf(List<ProctorEventRow> events) {
        ProctorRisk.Risk risk = ProctorRisk.of(events);
        return new RiskScore(risk.level(), risk.score());
    }

    private ProctorEventDto toDto(ProctorEventRow row) {
        return new ProctorEventDto(row.id(), row.eventId(), row.attemptId(), row.type(), row.occurredAt(),
                row.serverReceivedAt(), row.metadata(),
                row.source() == null ? "BROWSER" : row.source(), row.confidence(), row.durationMs());
    }

    private record RiskScore(RiskLevel level, double score) {
    }
}
