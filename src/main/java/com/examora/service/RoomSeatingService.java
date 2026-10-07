package com.examora.service;

import com.examora.exception.ApiException;
import com.examora.model.ExamCentreRoom;
import com.examora.model.RoomAssignment;
import com.examora.repository.ExamCentreRoomRepository;
import com.examora.repository.ExamRepository;
import com.examora.repository.RoomAssignmentRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Deterministic, persistent and concurrency-safe room/seat assignment.
 *
 * <p>Assignment is deterministic: for a given exam, rooms are considered in a fixed order and
 * the student is seated in the room with the fewest occupied seats (ties broken by room name),
 * taking the next free seat number. The result is persisted in {@code room_assignments}, so the
 * seating arrangement is identical across refreshes, logouts, reconnects and restarts.
 *
 * <p>Concurrency safety: concurrent enrollers for the same exam race for the same next seat.
 * The DB unique constraint {@code (exam_id, room_id, seat_number)} arbitrates, and a bounded
 * retry loop re-reads occupancy and picks the next free seat until the insert settles.
 */
@Service
public class RoomSeatingService {
    private static final int MAX_INSERT_ATTEMPTS = 6;

    private final ExamRepository examRepository;
    private final ExamCentreRoomRepository roomRepository;
    private final RoomAssignmentRepository assignmentRepository;

    public RoomSeatingService(ExamRepository examRepository, ExamCentreRoomRepository roomRepository,
                              RoomAssignmentRepository assignmentRepository) {
        this.examRepository = examRepository;
        this.roomRepository = roomRepository;
        this.assignmentRepository = assignmentRepository;
    }

    public Optional<RoomAssignment> findForStudent(String examId, String studentId) {
        return assignmentRepository.findByStudentAndExam(studentId, examId);
    }

    public RoomAssignment requireOrAssign(String examId, String studentId) {
        return assignmentRepository.findByStudentAndExam(studentId, examId).orElseGet(() -> assign(examId, studentId));
    }

    /**
     * Picks the next available seat for the student. Returns an existing assignment when one
     * already exists for this (exam, student).
     *
     * <p>Concurrency: the chosen room row is locked with {@code SELECT ... FOR UPDATE} so that
     * count-then-insert for the same (exam, room) is serialized; losers of the room lock re-read
     * occupancy under the lock and take the next free seat. The DB unique constraints
     * {@code (exam_id, student_id)} and {@code (exam_id, room_id, seat_number)} remain the final
     * arbiters, and the bounded loop below backs them: after acquiring the lock the student's
     * existing assignment is re-checked, so duplicate submissions by the same student converge to
     * the single committed assignment instead of consuming capacity.
     */
    public RoomAssignment assign(String examId, String studentId) {
        String centreId = examRepository.centreIdFor(examId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "This exam has no exam centre configured, so no seat can be assigned."));
        List<ExamCentreRoom> activeRooms = roomRepository.findActiveByCentreId(centreId);
        if (activeRooms.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "No exam rooms are available for this exam.");
        }

        // Lock-free pre-checks then lock-based seat selection with bounded retry as backstop.
        for (int attempt = 0; attempt < MAX_INSERT_ATTEMPTS; attempt++) {
            Optional<RoomAssignment> existing = assignmentRepository.findByStudentAndExam(studentId, examId);
            if (existing.isPresent()) {
                return existing.get();
            }
            Optional<ExamCentreRoom> target = pickRoom(activeRooms, examId);
            if (target.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "No seats are available for this exam.");
            }
            ExamCentreRoom room = target.get();
            // Serialise count-then-insert for this (exam, room); blocks concurrent enrollers.
            roomRepository.lockForUpdate(room.id());
            // Under the lock a competing same-student enrolment may already have settled.
            existing = assignmentRepository.findByStudentAndExam(studentId, examId);
            if (existing.isPresent()) {
                return existing.get();
            }
            int nextSeat = assignmentRepository.countForRoom(examId, room.id()) + 1;
            if (nextSeat > room.capacity()) {
                continue;
            }
            try {
                assignmentRepository.insert(UUID.randomUUID().toString(), examId, studentId, room.id(), nextSeat);
                return assignmentRepository.findByStudentAndExam(studentId, examId)
                        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "The seat could not be allocated."));
            } catch (DuplicateKeyException duplicate) {
                // Another enrolment landed on this seat (or this student) first; re-read and retry.
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "The seat could not be allocated. Please try again.");
    }

    public RoomAssignment manualAssign(String examId, String studentId, String roomId, int seatNumber) {
        ExamCentreRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam room not found."));
        if (assignmentRepository.findByStudentAndExam(studentId, examId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "This student already has a room assignment for the exam.");
        }
        if (seatNumber < 1 || seatNumber > room.capacity()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Seat number must be between 1 and " + room.capacity() + ".");
        }
        try {
            assignmentRepository.insert(UUID.randomUUID().toString(), examId, studentId, room.id(), seatNumber);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "That seat is already taken for this exam.");
        }
        return assignmentRepository.findByStudentAndExam(studentId, examId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "The seat could not be allocated."));
    }

    private Optional<ExamCentreRoom> pickRoom(List<ExamCentreRoom> activeRooms, String examId) {
        return activeRooms.stream()
                .map(room -> new RoomCapacity(room, assignmentRepository.countForRoom(examId, room.id())))
                .filter(candidate -> candidate.occupied() < candidate.room().capacity())
                .min(Comparator
                        .comparingInt(RoomCapacity::occupied)
                        .thenComparing(candidate -> candidate.room().roomName()))
                .map(RoomCapacity::room);
    }

    private record RoomCapacity(ExamCentreRoom room, int occupied) {
    }
}