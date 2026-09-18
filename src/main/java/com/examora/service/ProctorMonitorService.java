package com.examora.service;

import com.examora.dto.ProctorDtos.CommandCenterData;
import com.examora.dto.ProctorDtos.CommandCenterExam;
import com.examora.dto.ProctorDtos.CommandCenterRoom;
import com.examora.dto.ProctorDtos.CommandCenterStudent;
import com.examora.dto.ProctorDtos.ProctorAttemptMonitor;
import com.examora.dto.ProctorDtos.ProctorEventDto;
import com.examora.dto.ProctorDtos.ProctorMonitorData;
import com.examora.dto.ProctorDtos.ProctorStudentDto;
import com.examora.dto.ProctorDtos.ProctorSummary;
import com.examora.dto.ProctorDtos.RiskLevel;
import com.examora.dto.ProctorDtos.WarningLevel;
import com.examora.exception.ApiException;
import com.examora.model.Exam;
import com.examora.model.ExamAccessStatus;
import com.examora.model.ExamAttempt;
import com.examora.model.ExamRoom;
import com.examora.model.ExamRoomStatus;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ExamAccessRepository;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.ExamRepository;
import com.examora.repository.ExamRoomMemberRepository;
import com.examora.repository.ExamRoomRepository;
import com.examora.repository.ProctorRepository;
import com.examora.repository.ProctorRepository.ProctorEventRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final ExamRoomRepository roomRepository;
    private final ExamRoomMemberRepository memberRepository;
    private final ExamAccessRepository accessRepository;

    public ProctorMonitorService(ExamRepository examRepository, ExamAttemptRepository attemptRepository,
                                 ProctorRepository proctorRepository, ExamAttemptService examAttemptService,
                                 ExamRoomRepository roomRepository, ExamRoomMemberRepository memberRepository,
                                 ExamAccessRepository accessRepository) {
        this.examRepository = examRepository;
        this.attemptRepository = attemptRepository;
        this.proctorRepository = proctorRepository;
        this.examAttemptService = examAttemptService;
        this.roomRepository = roomRepository;
        this.memberRepository = memberRepository;
        this.accessRepository = accessRepository;
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

    public ProctorSummary summary(String examId, User actor) {
        ProctorMonitorData data = monitor(examId, actor);
        int totalAttempts = data.attempts().size();
        int activeAttempts = (int) data.attempts().stream()
                .filter(ProctorAttemptMonitor::activeNow).count();
        int eventCount = data.attempts().stream()
                .mapToInt(ProctorAttemptMonitor::eventCount).sum();
        RiskScore worst = data.attempts().stream()
                .map(attempt -> new RiskScore(attempt.riskLevel(), attempt.riskScore()))
                .max(Comparator.comparingInt(r -> r.level().ordinal()))
                .orElse(new RiskScore(RiskLevel.LOW, 0.0));
        return new ProctorSummary(data.examId(), data.examTitle(), totalAttempts > 0,
                totalAttempts, activeAttempts, eventCount, worst.level(),
                (int) Math.round(worst.score()));
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

    public CommandCenterData commandCenter(String examId, User actor) {
        Exam exam = requireMonitoredExam(examId, actor);
        Instant now = Instant.now();

        List<ExamRoom> rooms = roomRepository.findByExamId(exam.id());
        List<ExamRoomMemberRepository.JoinedForExam> members = memberRepository.findJoinedForExam(exam.id());
        List<ExamAttemptRepository.AttemptWithWarning> attempts =
                attemptRepository.findByExamWithStudentAndWarnings(exam.id());
        Map<String, ExamAccessStatus> accessByStudent = accessRepository.findByExamId(exam.id());
        Map<String, List<ProctorEventRow>> eventsByAttempt = proctorRepository.findByExamId(exam.id()).stream()
                .collect(Collectors.groupingBy(ProctorEventRow::attemptId));

        Map<String, List<ExamRoomMemberRepository.JoinedForExam>> membershipsByStudent = members.stream()
                .collect(Collectors.groupingBy(ExamRoomMemberRepository.JoinedForExam::studentId));
        Map<String, ExamRoomStatus> roomStatusById = rooms.stream()
                .collect(Collectors.toMap(ExamRoom::id, ExamRoom::status));
        Map<String, String> roomCodeById = rooms.stream()
                .collect(Collectors.toMap(ExamRoom::id, ExamRoom::roomCode));

        Map<String, RoomInfo> roomByStudent = new HashMap<>();
        for (Map.Entry<String, List<ExamRoomMemberRepository.JoinedForExam>> entry : membershipsByStudent.entrySet()) {
            entry.getValue().stream()
                    .sorted(Comparator
                            .comparing((ExamRoomMemberRepository.JoinedForExam m) -> roomPriority(roomStatusById.get(m.roomId())))
                            .thenComparing(m -> m.joinedAt() == null ? Instant.MIN : m.joinedAt()))
                    .findFirst()
                    .ifPresent(m -> roomByStudent.put(entry.getKey(),
                            new RoomInfo(m.roomId(), roomCodeById.get(m.roomId()),
                                    roomStatusById.getOrDefault(m.roomId(), ExamRoomStatus.ENDED))));
        }

        Map<String, CommandCenterStudent> studentsByAttempt = new HashMap<>();
        for (ExamAttemptRepository.AttemptWithWarning row : attempts) {
            CommandCenterStudent student = buildCommandCenterStudent(row, eventsByAttempt.getOrDefault(
                    row.attempt().id(), List.of()), now, roomByStudent.get(row.attempt().studentId()),
                    accessByStudent.get(row.attempt().studentId()));
            studentsByAttempt.put(row.attempt().studentId(), student);
        }

        List<CommandCenterStudent> roster = new ArrayList<>(studentsByAttempt.values());
        for (ExamRoomMemberRepository.JoinedForExam member : members) {
            if (studentsByAttempt.containsKey(member.studentId())) {
                continue;
            }
            RoomInfo room = roomByStudent.get(member.studentId());
            if (room == null) {
                continue;
            }
            roster.add(new CommandCenterStudent(
                    member.studentId(), member.studentName(), member.studentEmail(),
                    room.roomId(), room.roomCode(), room.status(),
                    null, null, null, false, null, null,
                    0, WarningLevel.NONE,
                    accessByStudent.getOrDefault(member.studentId(), ExamAccessStatus.ELIGIBLE),
                    RiskLevel.LOW, 0.0, 0, null, null,
                    null, null, 0, 0));
        }
        roster.sort(Comparator.comparing(CommandCenterStudent::studentName,
                Comparator.nullsLast(String::compareTo)).thenComparing(CommandCenterStudent::studentId));

        List<CommandCenterRoom> commandRooms = rooms.stream()
                .map(room -> new CommandCenterRoom(room.id(), room.roomCode(), room.status(),
                        (int) members.stream().filter(m -> m.roomId().equals(room.id())).count(),
                        room.startedAt() == null ? null : room.startedAt().toString(),
                        room.endedAt() == null ? null : room.endedAt().toString()))
                .toList();

        int totalStudents = roster.size();
        int joinedStudents = (int) roster.stream().filter(s -> s.roomId() != null).count();
        int activeAttempts = (int) roster.stream().filter(CommandCenterStudent::activeNow).count();
        int submittedAttempts = (int) roster.stream()
                .filter(s -> "SUBMITTED".equals(s.attemptStatus())).count();
        int terminatedAttempts = (int) roster.stream()
                .filter(s -> "PROCTOR_TERMINATED".equals(s.attemptStatus())).count();
        int totalWarnings = roster.stream().mapToInt(CommandCenterStudent::warningCount).sum();
        CommandCenterExam commandExam = new CommandCenterExam(
                exam.id(), exam.title(), exam.status(), exam.duration(),
                totalStudents, joinedStudents, activeAttempts, submittedAttempts,
                Math.max(0, joinedStudents - activeAttempts), totalWarnings, terminatedAttempts);

        return new CommandCenterData(commandExam, commandRooms, roster);
    }

    private CommandCenterStudent buildCommandCenterStudent(ExamAttemptRepository.AttemptWithWarning row,
                                                           List<ProctorEventRow> events, Instant now, RoomInfo room,
                                                           ExamAccessStatus explicitAccess) {
        ExamAttempt attempt = row.attempt();
        boolean activeNow = "STARTED".equals(attempt.status()) && now.isBefore(attempt.expiresAt());
        RiskScore risk = riskOf(events);
        ProctorEventRow latest = events.isEmpty() ? null : events.getFirst();
        ProctorEventDto latestDto = latest == null ? null : toDto(latest);
        int audioCount = 0;
        int networkCount = 0;
        Boolean cameraOff = null;
        Boolean fullscreenExited = null;
        if (latest != null) {
            String latestType = latest.type() == null ? null : latest.type().trim().toUpperCase();
            cameraOff = "CAMERA_OFF".equals(latestType) ? Boolean.TRUE : null;
            fullscreenExited = "FULLSCREEN_EXIT".equals(latestType) ? Boolean.TRUE : null;
        }
        for (ProctorEventRow event : events) {
            String type = event.type() == null ? null : event.type().trim().toUpperCase();
            if ("AUDIO_DETECTED".equals(type)) {
                audioCount++;
            } else if ("NETWORK_INTERRUPTION".equals(type)) {
                networkCount++;
            }
        }
        ExamAccessStatus accessStatus = explicitAccess == null ? ExamAccessStatus.ELIGIBLE : explicitAccess;
        return new CommandCenterStudent(
                attempt.studentId(), row.studentName(), row.studentEmail(),
                room == null ? null : room.roomId(),
                room == null ? null : room.roomCode(),
                room == null ? null : room.status(),
                attempt.id(),
                attempt.attemptNumber(),
                attempt.status(),
                activeNow,
                attempt.startedAt().toString(),
                attempt.expiresAt().toString(),
                row.warningCount(),
                WarningLevel.fromWarningCount(row.warningCount()),
                accessStatus,
                risk.level(),
                risk.score(),
                events.size(),
                latestDto,
                latest == null ? null : latest.occurredAt(),
                cameraOff,
                fullscreenExited,
                audioCount,
                networkCount);
    }

    private int roomPriority(ExamRoomStatus status) {
        if (status == ExamRoomStatus.ACTIVE) return 0;
        if (status == ExamRoomStatus.WAITING) return 1;
        return 2;
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
            if (ProctorSignalTypes.FUTURE_AI_SIGNAL_TYPES.contains(event.type().trim().toUpperCase())) {
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
                row.serverReceivedAt(), row.metadata(),
                row.source() == null ? "BROWSER" : row.source(), row.confidence(), row.durationMs());
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

    private record RoomInfo(String roomId, String roomCode, ExamRoomStatus status) {
    }
}