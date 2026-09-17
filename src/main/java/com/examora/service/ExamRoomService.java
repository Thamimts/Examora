package com.examora.service;

import com.examora.dto.ExamRoomDtos.CreateRoomRequest;
import com.examora.dto.ExamRoomDtos.ExamRoomDto;
import com.examora.dto.ExamRoomDtos.JoinRoomRequest;
import com.examora.dto.ExamRoomDtos.JoinRoomResponse;
import com.examora.dto.ExamRoomDtos.RoomActivityDto;
import com.examora.dto.ExamRoomDtos.RoomDetailDto;
import com.examora.dto.ExamRoomDtos.RoomMemberDto;
import com.examora.exception.ApiException;
import com.examora.model.Exam;
import com.examora.model.ExamRoom;
import com.examora.model.ExamRoomMember;
import com.examora.model.ExamRoomMemberStatus;
import com.examora.model.ExamRoomStatus;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ExamRepository;
import com.examora.repository.ExamRoomMemberRepository;
import com.examora.repository.ExamRoomRepository;
import com.examora.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamRoomService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ROOM_CODE_LENGTH = 8;
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final ExamRoomRepository roomRepository;
    private final ExamRoomMemberRepository memberRepository;
    private final ExamRepository examRepository;
    private final UserRepository userRepository;
    private final ActivityService activityService;
    private final SimpMessagingTemplate messaging;

    public ExamRoomService(ExamRoomRepository roomRepository,
                           ExamRoomMemberRepository memberRepository,
                           ExamRepository examRepository,
                           UserRepository userRepository,
                           ActivityService activityService,
                           SimpMessagingTemplate messaging) {
        this.roomRepository = roomRepository;
        this.memberRepository = memberRepository;
        this.examRepository = examRepository;
        this.userRepository = userRepository;
        this.activityService = activityService;
        this.messaging = messaging;
    }

    public ExamRoomDto create(CreateRoomRequest request, User actor) {
        requireTeacherOrAdmin(actor);
        if (request == null || isBlank(request.examId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exam id is required.");
        }
        Exam exam = examRepository.findById(request.examId().trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found."));
        requireExamAccess(exam.id(), actor, "You do not own this exam.");

        ExamRoom created = null;
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS && created == null; attempt++) {
            try {
                created = roomRepository.create(new ExamRoom(
                        UUID.randomUUID().toString(),
                        exam.id(),
                        generateRoomCode(),
                        ExamRoomStatus.WAITING,
                        actor.id(),
                        null, null, null, null));
            } catch (DuplicateKeyException collision) {
                created = null;
            }
        }
        if (created == null) {
            throw new ApiException(HttpStatus.CONFLICT, "A unique room code could not be allocated.");
        }
        activityService.admin(actor, "EXAM_ROOM_CREATED", actor.name() + " created exam room “" + created.roomCode() + "”.");
        publishActivity(created, "ROOM_CREATED", "Exam room " + created.roomCode() + " is waiting for students.");
        return toDto(created);
    }

    public RoomDetailDto get(String roomId, User actor) {
        if (isBlank(roomId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Room id is required.");
        }
        ExamRoom room = roomRepository.findById(roomId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam room not found."));
        if (actor.role() == Role.ADMIN) {
            return detail(room);
        }
        if (actor.role() == Role.TEACHER) {
            requireExamAccess(room.examId(), actor, "You do not have access to this room's exam.");
            return detail(room);
        }
        if (actor.role() == Role.STUDENT) {
            if (memberRepository.findForStudentByRoom(room.id(), actor.id(), ExamRoomMemberStatus.JOINED).isEmpty()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You have not joined this exam room.");
            }
            return detail(room);
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "Access is denied.");
    }

    public ExamRoomDto start(String roomId, User actor) {
        ExamRoom room = requireManageable(roomId, actor);
        Instant now = Instant.now();
        if (roomRepository.markActive(room.id(), now) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This room is not in WAITING state and cannot be started.");
        }
        ExamRoom started = roomRepository.findById(room.id())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "This room could not be started."));
        activityService.admin(actor, "EXAM_ROOM_STARTED", actor.name() + " started exam room “" + started.roomCode() + "”.");
        publishActivity(started, "ROOM_STARTED", "Exam room " + started.roomCode() + " has started.");
        return toDto(started);
    }

    public ExamRoomDto end(String roomId, User actor) {
        ExamRoom room = requireManageable(roomId, actor);
        Instant now = Instant.now();
        if (roomRepository.markEnded(room.id(), now) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This room is not in ACTIVE state and cannot be ended.");
        }
        ExamRoom ended = roomRepository.findById(room.id())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "This room could not be ended."));
        activityService.admin(actor, "EXAM_ROOM_ENDED", actor.name() + " ended exam room “" + ended.roomCode() + "”.");
        publishActivity(ended, "ROOM_ENDED", "Exam room " + ended.roomCode() + " has ended.");
        return toDto(ended);
    }

    @Transactional
    public JoinRoomResponse join(JoinRoomRequest request, User student) {
        if (student == null || student.role() != Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Student access is required.");
        }
        if (request == null || isBlank(request.roomCode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Room code is required.");
        }
        String code = request.roomCode().trim().toUpperCase();
        ExamRoom room = roomRepository.findByCode(code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam room not found."));
        if (room.status() == ExamRoomStatus.ENDED) {
            throw new ApiException(HttpStatus.CONFLICT, "This exam room has ended.");
        }
        boolean created;
        try {
            created = memberRepository.joinIfAllowed(UUID.randomUUID().toString(), room.id(), student.id()) == 1;
        } catch (DuplicateKeyException duplicate) {
            created = false;
        }
        if (!created) {
            throw new ApiException(HttpStatus.CONFLICT, "You have already joined this exam room.");
        }
        activityService.student(student, "EXAM_ROOM_JOINED", "You joined exam room “" + room.roomCode() + "”.");
        activityService.admin("EXAM_ROOM_JOINED", student.name() + " joined exam room “" + room.roomCode() + "”.");
        publishActivity(room, "MEMBER_JOINED", student.name() + " joined exam room " + room.roomCode() + ".");
        return new JoinRoomResponse(room.id(), room.examId(), room.roomCode(), room.status(),
                "Joined exam room " + room.roomCode() + ".");
    }

    public List<ExamRoomDto> myRooms(User actor) {
        List<ExamRoom> rooms;
        if (actor.role() == Role.STUDENT) {
            rooms = roomRepository.findRoomsJoinedByStudent(actor.id());
        } else if (actor.role() == Role.TEACHER) {
            rooms = roomRepository.findByCreator(actor.id());
        } else {
            rooms = roomRepository.findAll();
        }
        return rooms.stream().map(this::toDto).toList();
    }

    private ExamRoom requireManageable(String roomId, User actor) {
        if (isBlank(roomId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Room id is required.");
        }
        ExamRoom room = roomRepository.findById(roomId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam room not found."));
        requireTeacherOrAdmin(actor);
        requireExamAccess(room.examId(), actor, "You do not have access to this room's exam.");
        return room;
    }

    private void requireTeacherOrAdmin(User actor) {
        if (actor == null || (actor.role() != Role.TEACHER && actor.role() != Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Teacher or administrator access is required.");
        }
    }

    private void requireExamAccess(String examId, User actor, String forbiddenMessage) {
        if (actor.role() == Role.ADMIN) {
            return;
        }
        String ownerId = examRepository.findOwnerId(examId).orElse(null);
        if (ownerId == null || !ownerId.equals(actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, forbiddenMessage);
        }
    }

    private RoomDetailDto detail(ExamRoom room) {
        List<RoomMemberDto> members = memberRepository.findByRoomId(room.id(), ExamRoomMemberStatus.JOINED).stream()
                .map(this::toMemberDto)
                .toList();
        return new RoomDetailDto(toDto(room), members);
    }

    private RoomMemberDto toMemberDto(ExamRoomMember member) {
        User student = userRepository.findById(member.studentId()).orElse(null);
        return new RoomMemberDto(
                member.id(),
                member.roomId(),
                member.studentId(),
                student == null ? null : student.name(),
                student == null ? null : student.email(),
                member.status(),
                member.joinedAt() == null ? null : member.joinedAt().toString(),
                member.leftAt() == null ? null : member.leftAt().toString());
    }

    private ExamRoomDto toDto(ExamRoom room) {
        Exam exam = examRepository.findById(room.examId()).orElse(null);
        return new ExamRoomDto(
                room.id(),
                room.examId(),
                exam == null ? null : exam.title(),
                room.roomCode(),
                room.status(),
                room.createdBy(),
                room.startedAt() == null ? null : room.startedAt().toString(),
                room.endedAt() == null ? null : room.endedAt().toString(),
                room.createdAt() == null ? null : room.createdAt().toString(),
                memberRepository.countJoined(room.id()));
    }

    private void publishActivity(ExamRoom room, String type, String message) {
        messaging.convertAndSend("/topic/exam-rooms/" + room.id() + "/activity",
                new RoomActivityDto(room.id(), room.examId(), room.roomCode(), room.status(), type, message,
                        Instant.now().toString()));
    }

    private String generateRoomCode() {
        StringBuilder code = new StringBuilder(ROOM_CODE_LENGTH);
        for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
            code.append(ROOM_CODE_ALPHABET.charAt(SECURE_RANDOM.nextInt(ROOM_CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}