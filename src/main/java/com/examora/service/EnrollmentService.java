package com.examora.service;

import com.examora.dto.EnrollmentDtos.EnrollRequest;
import com.examora.dto.EnrollmentDtos.EnrollmentDetailDto;
import com.examora.dto.EnrollmentDtos.ExamRosterDto;
import com.examora.dto.EnrollmentDtos.InvigilatorRoomSummaryDto;
import com.examora.dto.EnrollmentDtos.RoomSeatingDto;
import com.examora.dto.EnrollmentDtos.RoomStudentDto;
import com.examora.exception.ApiException;
import com.examora.model.Exam;
import com.examora.model.ExamCentre;
import com.examora.model.ExamCentreRoom;
import com.examora.model.ExamAttempt;
import com.examora.model.ExamEnrollment;
import com.examora.model.Role;
import com.examora.model.RoomAssignment;
import com.examora.model.User;
import com.examora.repository.ExamAttemptRepository;
import com.examora.repository.ExamCentreRepository;
import com.examora.repository.ExamCentreRoomRepository;
import com.examora.repository.ExamEnrollmentRepository;
import com.examora.repository.ExamRepository;
import com.examora.repository.ProctorRepository;
import com.examora.repository.ProctorRepository.ProctorEventRow;
import com.examora.repository.RoomAssignmentRepository;
import com.examora.repository.RoomAssignmentRepository.RoomExamCount;
import com.examora.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student enrolment in exams and the invigilator-facing seating views. Enrolment automatically
 * assigns a persistent room/seat via {@link RoomSeatingService}; assignments survive refreshes,
 * reconnects and restarts so the seated arrangement is stable for an exam.
 *
 * <p>Enrolment status (the {@code exam_enrollments} row) is kept separate from attempt status
 * (the {@code exam_attempts} row). Seating DTOs carry both, plus a proctoring risk level computed
 * from the shared {@link ProctorRisk} scoring so live and pull-based views agree.
 */
@Service
public class EnrollmentService {
    private final ExamRepository examRepository;
    private final ExamEnrollmentRepository enrollmentRepository;
    private final RoomAssignmentRepository assignmentRepository;
    private final RoomSeatingService seatingService;
    private final ExamCentreRepository centreRepository;
    private final ExamCentreRoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ExamAttemptRepository attemptRepository;
    private final ProctorRepository proctorRepository;

    public EnrollmentService(ExamRepository examRepository, ExamEnrollmentRepository enrollmentRepository,
                             RoomAssignmentRepository assignmentRepository, RoomSeatingService seatingService,
                             ExamCentreRepository centreRepository, ExamCentreRoomRepository roomRepository,
                             UserRepository userRepository, ExamAttemptRepository attemptRepository,
                             ProctorRepository proctorRepository) {
        this.examRepository = examRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.assignmentRepository = assignmentRepository;
        this.seatingService = seatingService;
        this.centreRepository = centreRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.attemptRepository = attemptRepository;
        this.proctorRepository = proctorRepository;
    }

    @Transactional
    public EnrollmentDetailDto enroll(User student, EnrollRequest request) {
        requireStudent(student);
        if (request == null || isBlank(request.examId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exam id is required.");
        }
        String examId = request.examId().trim();
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found."));
        if ("DRAFT".equalsIgnoreCase(exam.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This exam is not available to students.");
        }

        Optional<ExamEnrollment> existing = enrollmentRepository.findByStudentAndExam(student.id(), examId);
        ExamEnrollment enrollment;
        if (existing.isPresent()) {
            enrollment = existing.get();
        } else {
            seatingService.assign(examId, student.id());
            try {
                enrollment = new ExamEnrollment(UUID.randomUUID().toString(), examId, student.id(), "ENROLLED", null);
                if (enrollmentRepository.insert(enrollment.id(), examId, student.id()) != 1) {
                    throw new ApiException(HttpStatus.CONFLICT, "You are already enrolled in this exam.");
                }
            } catch (DuplicateKeyException duplicate) {
                enrollment = enrollmentRepository.findByStudentAndExam(student.id(), examId)
                        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "You are already enrolled in this exam."));
            }
        }
        return toDetail(enrollment);
    }

    public List<EnrollmentDetailDto> myEnrollments(User student) {
        requireStudent(student);
        return enrollmentRepository.findByStudentId(student.id()).stream()
                .map(this::toDetail)
                .toList();
    }

    public EnrollmentDetailDto myEnrollment(String examId, User student) {
        requireStudent(student);
        return enrollmentRepository.findByStudentAndExam(student.id(), examId)
                .map(this::toDetail)
                .orElse(null);
    }

    public List<ExamRosterDto> roster(String examId, User actor) {
        requireMonitoredExam(examId, actor);
        List<ExamEnrollment> enrollments = enrollmentRepository.findByExamId(examId);
        Map<String, RoomAssignment> assignmentByStudent = assignmentRepository.findByExamId(examId).stream()
                .collect(Collectors.toMap(RoomAssignment::studentId, assignment -> assignment));
        Map<String, String> userNames = userNames(enrollments.stream().map(ExamEnrollment::studentId).toList());
        Map<String, String> roomNames = roomNames();
        SeatContext context = seatContext(examId);
        return enrollments.stream()
                .map(enrollment -> {
                    String studentId = enrollment.studentId();
                    RoomAssignment assignment = assignmentByStudent.get(studentId);
                    return new ExamRosterDto(
                            enrollment.id(),
                            studentId,
                            userNames.getOrDefault(studentId, "Unknown"),
                            null,
                            rollNumberOf(studentId),
                            assignment == null ? null : assignment.roomId(),
                            assignment == null ? null : roomNames.get(assignment.roomId()),
                            assignment == null ? null : assignment.seatNumber(),
                            attemptStatusOf(context.attemptByStudent.get(studentId), context.now),
                            riskLevelOf(context.riskByStudent.get(studentId)));
                })
                .toList();
    }

    public List<RoomSeatingDto> examRooms(String examId, User actor) {
        requireMonitoredExam(examId, actor);
        Map<String, String> centreNames = centreRepository.findAll().stream()
                .collect(Collectors.toMap(ExamCentre::id, ExamCentre::name));
        String centreId = examRepository.centreIdFor(examId).orElse(null);
        SeatContext context = seatContext(examId);
        return roomRepository.findActiveByCentreId(centreId == null ? "__none__" : centreId).stream()
                .map(room -> seating(room, examId, centreNames.getOrDefault(room.centreId(), null), context))
                .toList();
    }

    public RoomSeatingDto roomSeating(String roomId, String examId, User actor) {
        if (isBlank(roomId) || isBlank(examId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Room id and exam id are required.");
        }
        ExamCentreRoom room = roomRepository.findById(roomId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam room not found."));
        requireRoomAccess(room, actor);
        return seating(room, examId.trim(), centreName(room.centreId()), seatContext(examId.trim()));
    }

    @Transactional
    public ExamRosterDto manualAssign(String examId, String studentId, String roomId, int seatNumber, User actor) {
        requireAdmin(actor);
        ExamEnrollment enrollment = enrollmentRepository.findByStudentAndExam(studentId, examId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "This student is not enrolled in the exam."));
        RoomAssignment assignment = seatingService.manualAssign(examId, studentId, roomId, seatNumber);
        return new ExamRosterDto(
                enrollment.id(),
                studentId,
                userName(studentId),
                null,
                rollNumberOf(studentId),
                assignment.roomId(),
                roomName(assignment.roomId()),
                assignment.seatNumber(),
                attemptStatusOf(attemptRepository.findLatest(examId, studentId).orElse(null), Instant.now()),
                riskLevelOf(null));
    }

    /**
     * Per-room status summary for the invigilator dashboards. TEACHERs only see rooms they
     * invigilate; ADMINS see every active room. Each summary pairs a room with one exam that
     * actually seats students in it (from {@code room_assignments}), so no fabricated numbers.
     */
    public List<InvigilatorRoomSummaryDto> invigilatorRoomSummaries(User actor) {
        requireTeacherOrAdmin(actor);
        List<ExamCentreRoom> rooms = actor.role() == Role.ADMIN
                ? roomRepository.findActive()
                : roomRepository.findByInvigilatorId(actor.id());
        if (rooms.isEmpty()) {
            return List.of();
        }
        Map<String, String> centreNames = centreRepository.findAll().stream()
                .collect(Collectors.toMap(ExamCentre::id, ExamCentre::name));
        Map<String, Exam> examById = examRepository.findAll().stream()
                .collect(Collectors.toMap(Exam::id, exam -> exam));
        Map<String, List<RoomAssignment>> assignmentsByExam = assignmentRepository.countsByRoom().stream()
                .filter(count -> rooms.stream().anyMatch(room -> room.id().equals(count.roomId())))
                .map(RoomExamCount::examId)
                .distinct()
                .collect(Collectors.toMap(examId -> examId, examId -> assignmentRepository.findByExamId(examId)));

        Map<String, SeatContext> contextByExam = new HashMap<>();
        for (String examId : assignmentsByExam.keySet()) {
            contextByExam.put(examId, seatContext(examId));
        }

        Map<String, String> roomNameByRoom = rooms.stream()
                .collect(Collectors.toMap(ExamCentreRoom::id, ExamCentreRoom::roomName));
        Map<String, String> roomCodeByRoom = rooms.stream()
                .collect(Collectors.toMap(ExamCentreRoom::id, ExamCentreRoom::roomCode));

        List<InvigilatorRoomSummaryDto> results = new ArrayList<>();
        for (ExamCentreRoom room : rooms) {
            List<ExamRoomSummary> summaries = new ArrayList<>();
            for (Map.Entry<String, List<RoomAssignment>> entry : assignmentsByExam.entrySet()) {
                String examId = entry.getKey();
                List<RoomAssignment> inRoom = entry.getValue().stream()
                        .filter(assignment -> assignment.roomId().equals(room.id()))
                        .toList();
                if (inRoom.isEmpty()) {
                    continue;
                }
                SeatContext context = contextByExam.get(examId);
                int active = 0;
                int submitted = 0;
                ProctorRisk.Risk worst = null;
                for (RoomAssignment assignment : inRoom) {
                    ExamAttempt attempt = context.attemptByStudent.get(assignment.studentId());
                    String status = attemptStatusOf(attempt, context.now);
                    if ("STARTED".equals(status)) {
                        active++;
                    } else if ("SUBMITTED".equals(status)) {
                        submitted++;
                    }
                    ProctorRisk.Risk risk = context.riskByStudent.get(assignment.studentId());
                    if (risk != null && (worst == null || risk.score() > worst.score())) {
                        worst = risk;
                    }
                }
                Exam exam = examById.get(examId);
                summaries.add(new ExamRoomSummary(examId,
                        exam == null ? null : exam.title(),
                        exam == null ? null : exam.status(),
                        inRoom.size(), active, submitted, worst));
            }
            if (summaries.isEmpty()) {
                summaries.add(new ExamRoomSummary(null, null, null, 0, 0, 0, null));
            }
            for (ExamRoomSummary summary : summaries) {
                ProctorRisk.Risk risk = summary.worstRisk;
                String roomName = roomNameByRoom.get(room.id());
                results.add(new InvigilatorRoomSummaryDto(
                        room.id(), roomName, roomCodeByRoom.get(room.id()), room.status(),
                        room.centreId(), centreNames.get(room.centreId()), room.capacity(),
                        summary.occupied, summary.examId, summary.examTitle, summary.examStatus,
                        summary.activeCount, summary.submittedCount,
                        risk == null ? null : risk.level().name(),
                        risk == null ? 0 : risk.score(), summary.activeCount > 0));
            }
        }
        results.sort((left, right) -> {
            int byRoom = safeCompare(left.roomName(), right.roomName());
            if (byRoom != 0) {
                return byRoom;
            }
            return safeCompare(left.examTitle(), right.examTitle());
        });
        return results;
    }

    private RoomSeatingDto seating(ExamCentreRoom room, String examId, String centreName, SeatContext context) {
        List<RoomStudentDto> students = assignmentRepository.findByRoomIdAndExam(examId, room.id()).stream()
                .map(assignment -> {
                    ExamAttempt attempt = context.attemptByStudent.get(assignment.studentId());
                    return new RoomStudentDto(
                            assignment.id(),
                            assignment.studentId(),
                            userName(assignment.studentId()),
                            null,
                            rollNumberOf(assignment.studentId()),
                            assignment.seatNumber(),
                            attemptStatusOf(attempt, context.now),
                            riskLevelOf(context.riskByStudent.get(assignment.studentId())));
                })
                .toList();
        return new RoomSeatingDto(
                room.id(),
                room.roomName(),
                room.roomCode(),
                room.capacity(),
                room.centreId(),
                centreName,
                room.invigilatorId(),
                invigilatorName(room),
                room.status(),
                students.size(),
                students);
    }

    private EnrollmentDetailDto toDetail(ExamEnrollment enrollment) {
        Exam exam = examRepository.findById(enrollment.examId()).orElse(null);
        RoomAssignment assignment = assignmentRepository.findByStudentAndExam(enrollment.studentId(), enrollment.examId())
                .orElse(null);
        ExamCentreRoom room = assignment == null ? null
                : roomRepository.findById(assignment.roomId()).orElse(null);
        String centreName = room == null ? null : centreName(room.centreId());
        ExamAttempt attempt = attemptRepository.findLatest(enrollment.examId(), enrollment.studentId()).orElse(null);
        return new EnrollmentDetailDto(
                enrollment.id(),
                enrollment.examId(),
                exam == null ? enrollment.examId() : exam.title(),
                exam == null ? null : exam.subject(),
                enrollment.status(),
                enrollment.enrolledAt() == null ? null : enrollment.enrolledAt().toString(),
                assignment == null ? null : assignment.id(),
                assignment == null ? null : assignment.roomId(),
                room == null ? null : room.roomName(),
                room == null ? null : room.roomCode(),
                centreName,
                room == null ? null : room.centreId(),
                assignment == null ? null : assignment.seatNumber(),
                attemptStatusOf(attempt, Instant.now()));
    }

    private SeatContext seatContext(String examId) {
        Map<String, ExamAttempt> attemptByStudent = new HashMap<>();
        for (ExamAttempt attempt : attemptRepository.findLatestForExam(examId)) {
            attemptByStudent.put(attempt.studentId(), attempt);
        }
        return new SeatContext(attemptByStudent, riskByStudent(examId, attemptByStudent), Instant.now());
    }

    private Map<String, ProctorRisk.Risk> riskByStudent(String examId, Map<String, ExamAttempt> attemptByStudent) {
        Map<String, ProctorRisk.Risk> byAttemptId = proctorRepository.findByExamId(examId).stream()
                .collect(Collectors.groupingBy(ProctorEventRow::attemptId))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> ProctorRisk.of(entry.getValue())));
        Map<String, ProctorRisk.Risk> riskByStudent = new HashMap<>();
        attemptByStudent.forEach((studentId, attempt) -> {
            ProctorRisk.Risk risk = byAttemptId.get(attempt.id());
            if (risk != null) {
                riskByStudent.put(studentId, risk);
            }
        });
        return riskByStudent;
    }

    /**
     * Derives a display attempt status without writing to the DB. A {@code STARTED} attempt whose
     * window has passed reads as {@code EXPIRED}; students with no attempt yet read as
     * {@code null} (enrolled but not started).
     */
    private String attemptStatusOf(ExamAttempt attempt, Instant now) {
        if (attempt == null) {
            return null;
        }
        if ("STARTED".equals(attempt.status()) && !now.isBefore(attempt.expiresAt())) {
            return "EXPIRED";
        }
        return attempt.status();
    }

    private String riskLevelOf(ProctorRisk.Risk risk) {
        return risk == null ? null : risk.level().name();
    }

    private Exam requireMonitoredExam(String examId, User actor) {
        if (isBlank(examId)) {
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
                throw new ApiException(HttpStatus.FORBIDDEN, "You do not have access to this exam's seating.");
            }
        }
        return exam;
    }

    private void requireRoomAccess(ExamCentreRoom room, User actor) {
        if (actor == null || actor.role() == Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Teacher or administrator access is required.");
        }
        if (actor.role() == Role.ADMIN) {
            return;
        }
        if (room.invigilatorId() != null && room.invigilatorId().equals(actor.id())) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "You are not the invigilator of this room.");
    }

    private void requireTeacherOrAdmin(User actor) {
        if (actor == null || (actor.role() != Role.TEACHER && actor.role() != Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Teacher or administrator access is required.");
        }
    }

    private void requireStudent(User user) {
        if (user.role() != Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Student access is required.");
        }
    }

    private void requireAdmin(User actor) {
        if (actor == null || actor.role() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Administrator access is required.");
        }
    }

    private String userName(String userId) {
        return userRepository.findById(userId).map(User::name).orElse("Unknown");
    }

    private String rollNumberOf(String userId) {
        return userRepository.findRollNumber(userId).orElse(null);
    }

    private String invigilatorName(ExamCentreRoom room) {
        if (room.invigilatorId() == null) {
            return null;
        }
        return userRepository.findById(room.invigilatorId()).map(User::name).orElse(null);
    }

    private String roomName(String roomId) {
        return roomRepository.findById(roomId).map(ExamCentreRoom::roomName).orElse(null);
    }

    private Map<String, String> roomNames() {
        Map<String, String> names = new HashMap<>();
        for (ExamCentreRoom room : allRooms()) {
            names.put(room.id(), room.roomName());
        }
        return names;
    }

    private List<ExamCentreRoom> allRooms() {
        List<ExamCentreRoom> rooms = new ArrayList<>();
        for (ExamCentre centre : centreRepository.findAll()) {
            rooms.addAll(roomRepository.findByCentreId(centre.id()));
        }
        return rooms;
    }

    private Map<String, String> userNames(List<String> ids) {
        Map<String, String> names = new HashMap<>();
        for (String id : ids) {
            names.put(id, userName(id));
        }
        return names;
    }

    private String centreName(String centreId) {
        return centreRepository.findById(centreId).map(ExamCentre::name).orElse(null);
    }

    private int safeCompare(String left, String right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SeatContext(Map<String, ExamAttempt> attemptByStudent,
                               Map<String, ProctorRisk.Risk> riskByStudent, Instant now) {
    }

    private record ExamRoomSummary(String examId, String examTitle, String examStatus,
                                   int occupied, int activeCount, int submittedCount,
                                   ProctorRisk.Risk worstRisk) {
    }
}