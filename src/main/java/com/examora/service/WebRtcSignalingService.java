package com.examora.service;

import com.examora.dto.WebRtcDtos.WebRtcSignalDto;
import com.examora.dto.WebRtcDtos.WebRtcSignalRequest;
import com.examora.model.ExamRoom;
import com.examora.model.ExamRoomMemberStatus;
import com.examora.model.ExamRoomStatus;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ExamRepository;
import com.examora.repository.ExamRoomMemberRepository;
import com.examora.repository.ExamRoomRepository;
import java.util.Set;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class WebRtcSignalingService {
    private static final Set<String> ALLOWED_TYPES = Set.of("offer", "answer", "ice", "bye", "viewer_ready", "unavailable");
    private static final Set<String> PROCTOR_ONLY_TYPES = Set.of("viewer_ready");
    private static final Set<String> STUDENT_ONLY_TYPES = Set.of("unavailable");

    private final ExamRoomRepository roomRepository;
    private final ExamRoomMemberRepository memberRepository;
    private final ExamRepository examRepository;
    private final SimpMessagingTemplate messaging;

    public WebRtcSignalingService(ExamRoomRepository roomRepository,
                                  ExamRoomMemberRepository memberRepository,
                                  ExamRepository examRepository,
                                  SimpMessagingTemplate messaging) {
        this.roomRepository = roomRepository;
        this.memberRepository = memberRepository;
        this.examRepository = examRepository;
        this.messaging = messaging;
    }

    public void handle(String roomId, WebRtcSignalRequest signal, User actor) {
        if (actor == null) {
            throw new AccessDeniedException("Authentication is required.");
        }
        if (signal == null || signal.type() == null || !ALLOWED_TYPES.contains(signal.type())) {
            return;
        }
        if (roomId == null || roomId.isBlank()) {
            throw new AccessDeniedException("Invalid exam room destination.");
        }
        ExamRoom room = roomRepository.findById(roomId.trim())
                .orElseThrow(() -> new AccessDeniedException("Exam room not found."));
        if (room.status() == ExamRoomStatus.ENDED) {
            throw new AccessDeniedException("This exam room has ended.");
        }
        if (actor.role() == Role.STUDENT) {
            if (PROCTOR_ONLY_TYPES.contains(signal.type())) {
                return;
            }
            requireJoined(room.id(), actor.id());
            publish(new WebRtcSignalDto(room.id(), actor.id(), Role.STUDENT.name(), actor.id(),
                    signal.type(), signal.sdp(), signal.candidate()));
            return;
        }
        if (actor.role() == Role.TEACHER || actor.role() == Role.ADMIN) {
            if (STUDENT_ONLY_TYPES.contains(signal.type())) {
                return;
            }
            requireExamAccess(room.examId(), actor);
            if (signal.peerId() == null || signal.peerId().isBlank()) {
                throw new AccessDeniedException("A target student is required.");
            }
            requireJoined(room.id(), signal.peerId().trim());
            messaging.convertAndSendToUser(signal.peerId().trim(), "/queue/webrtc",
                    new WebRtcSignalDto(room.id(), actor.id(), actor.role().name(), signal.peerId().trim(),
                            signal.type(), signal.sdp(), signal.candidate()));
            return;
        }
        throw new AccessDeniedException("Access is denied.");
    }

    private void publish(WebRtcSignalDto signal) {
        messaging.convertAndSend("/topic/exam-rooms/" + signal.roomId() + "/webrtc", signal);
    }

    private void requireJoined(String roomId, String studentId) {
        boolean joined = memberRepository
                .findForStudentByRoom(roomId, studentId, ExamRoomMemberStatus.JOINED)
                .isPresent();
        if (!joined) {
            throw new AccessDeniedException("You are not a participant in this exam room.");
        }
    }

    private void requireExamAccess(String examId, User actor) {
        if (actor.role() == Role.ADMIN) {
            return;
        }
        boolean ownsExam = examRepository.findOwnerId(examId)
                .map(ownerId -> ownerId.equals(actor.id()))
                .orElse(false);
        if (!ownsExam) {
            throw new AccessDeniedException("You do not have access to this exam room.");
        }
    }
}