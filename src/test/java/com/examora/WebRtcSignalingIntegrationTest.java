package com.examora;

import com.examora.dto.WebRtcDtos.WebRtcSignalDto;
import com.examora.dto.WebRtcDtos.WebRtcSignalRequest;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_webrtc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-webrtc-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip=1000",
        "examora.login.max-per-ip-email=1000"
})
class WebRtcSignalingIntegrationTest {
    private static final String EXAM_1 = "webrtc-exam-1";
    private static final String EXAM_2 = "webrtc-exam-2";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from exam_room_members");
        jdbcTemplate.update("delete from exam_rooms");
        jdbcTemplate.update("delete from activity_events");
        jdbcTemplate.update("delete from proctor_events");
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("webrtc-student-1", "WebRTC Student One", "webrtc-student@example.com", "student123", Role.STUDENT);
        insertUser("webrtc-student-2", "WebRTC Student Two", "webrtc-student2@example.com", "student234", Role.STUDENT);
        insertUser("webrtc-teacher-1", "WebRTC Teacher One", "webrtc-teacher@example.com", "teacher123", Role.TEACHER);
        insertUser("webrtc-teacher-2", "WebRTC Teacher Two", "webrtc-teacher2@example.com", "teacher234", Role.TEACHER);
        insertUser("webrtc-admin-1", "WebRTC Admin One", "webrtc-admin@example.com", "admin123", Role.ADMIN);

        insertExam(EXAM_1, "WebRTC Math Exam", "UPCOMING", "webrtc-teacher-1");
        insertExam(EXAM_2, "WebRTC Physics Exam", "UPCOMING", "webrtc-teacher-2");
    }

    @Test
    void studentOfferRelaysToOwnerTeacher() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);

        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> signals = subscribe(teacherSession,
                "/topic/exam-rooms/" + room.id() + "/webrtc");
        Thread.sleep(300);

        relay(studentSession, room.id(), "webrtc-student-1", "offer", sdpNode());

        WebRtcSignalDto signal = signals.poll(5, TimeUnit.SECONDS);
        assertThat(signal).isNotNull();
        assertThat(signal.roomId()).isEqualTo(room.id());
        assertThat(signal.senderId()).isEqualTo("webrtc-student-1");
        assertThat(signal.senderRole()).isEqualTo("STUDENT");
        assertThat(signal.peerId()).isEqualTo("webrtc-student-1");
        assertThat(signal.type()).isEqualTo("offer");
        assertThat(signal.sdp()).isNotNull();
        assertThat(signal.sdp().path("sdp").asText()).contains("v=0 fake");
        disconnectQuietly(studentSession);
        disconnectQuietly(teacherSession);
    }

    @Test
    void teacherAnswerRelaysToTargetStudentOnly() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        String secondStudentToken = login("webrtc-student2@example.com", "student234");
        join(room.code(), studentToken);
        join(room.code(), secondStudentToken);

        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        StompSession secondStudentSession = connect(jwtService.generateToken(new User("webrtc-student-2",
                "WebRTC Student Two", "webrtc-student2@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> targeted = subscribe(studentSession, "/user/queue/webrtc");
        LinkedBlockingQueue<WebRtcSignalDto> bystander = subscribe(secondStudentSession, "/user/queue/webrtc");
        Thread.sleep(300);

        relay(teacherSession, room.id(), "webrtc-student-1", "answer", sdpNode());

        WebRtcSignalDto signal = targeted.poll(5, TimeUnit.SECONDS);
        assertThat(signal).isNotNull();
        assertThat(signal.senderId()).isEqualTo("webrtc-teacher-1");
        assertThat(signal.senderRole()).isEqualTo("TEACHER");
        assertThat(signal.peerId()).isEqualTo("webrtc-student-1");
        assertThat(signal.type()).isEqualTo("answer");
        assertThat(bystander.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(secondStudentSession);
        disconnectQuietly(studentSession);
        disconnectQuietly(teacherSession);
    }

    @Test
    void adminReceivesStudentOffersAndCanRelayBack() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);

        StompSession adminSession = connect(jwtService.generateToken(new User("webrtc-admin-1", "WebRTC Admin One",
                "webrtc-admin@example.com", Role.ADMIN, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> adminSignals = subscribe(adminSession,
                "/topic/exam-rooms/" + room.id() + "/webrtc");
        LinkedBlockingQueue<WebRtcSignalDto> studentSignals = subscribe(studentSession, "/user/queue/webrtc");
        Thread.sleep(300);

        relay(studentSession, room.id(), "webrtc-student-1", "offer", sdpNode());

        WebRtcSignalDto offer = adminSignals.poll(5, TimeUnit.SECONDS);
        assertThat(offer).isNotNull();
        assertThat(offer.senderRole()).isEqualTo("STUDENT");

        relay(adminSession, room.id(), "webrtc-student-1", "bye", null);

        WebRtcSignalDto bye = studentSignals.poll(5, TimeUnit.SECONDS);
        assertThat(bye).isNotNull();
        assertThat(bye.senderRole()).isEqualTo("ADMIN");
        assertThat(bye.type()).isEqualTo("bye");
        disconnectQuietly(studentSession);
        disconnectQuietly(adminSession);
    }

    @Test
    void unjoinedStudentCannotRelaySignals() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);

        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession stowawaySession = connect(jwtService.generateToken(new User("webrtc-student-2",
                "WebRTC Student Two", "webrtc-student2@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> signals = subscribe(teacherSession,
                "/topic/exam-rooms/" + room.id() + "/webrtc");
        Thread.sleep(300);

        relay(stowawaySession, room.id(), "webrtc-student-2", "offer", sdpNode());

        assertThat(signals.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(stowawaySession);
        disconnectQuietly(teacherSession);
    }

    @Test
    void studentPeerIdentityCannotBeSpoofed() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);

        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> signals = subscribe(teacherSession,
                "/topic/exam-rooms/" + room.id() + "/webrtc");
        Thread.sleep(300);

        relay(studentSession, room.id(), "webrtc-student-2", "offer", sdpNode());

        WebRtcSignalDto signal = signals.poll(5, TimeUnit.SECONDS);
        assertThat(signal).isNotNull();
        assertThat(signal.senderId()).isEqualTo("webrtc-student-1");
        assertThat(signal.peerId()).isEqualTo("webrtc-student-1");
        disconnectQuietly(studentSession);
        disconnectQuietly(teacherSession);
    }

    @Test
    void wrongTeacherCannotRelayForAnotherTeachersRoom() throws Exception {
        String teacherOneToken = login("webrtc-teacher@example.com", "teacher123");
        String teacherTwoToken = login("webrtc-teacher2@example.com", "teacher234");
        Room room = createRoom(teacherOneToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);

        StompSession ownerTeacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1",
                "WebRTC Teacher One", "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession wrongTeacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-2",
                "WebRTC Teacher Two", "webrtc-teacher2@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> ownerSignals = subscribe(ownerTeacherSession,
                "/topic/exam-rooms/" + room.id() + "/webrtc");
        LinkedBlockingQueue<WebRtcSignalDto> studentSignals = subscribe(studentSession, "/user/queue/webrtc");
        Thread.sleep(300);

        relay(wrongTeacherSession, room.id(), "webrtc-student-1", "answer", sdpNode());

        assertThat(ownerSignals.poll(2, TimeUnit.SECONDS)).isNull();
        assertThat(studentSignals.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(studentSession);
        disconnectQuietly(wrongTeacherSession);
        disconnectQuietly(ownerTeacherSession);
    }

    @Test
    void invalidSignalTypeIsIgnored() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);

        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> signals = subscribe(teacherSession,
                "/topic/exam-rooms/" + room.id() + "/webrtc");
        Thread.sleep(300);

        relay(studentSession, room.id(), "webrtc-student-1", "exploit", null);

        assertThat(signals.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(studentSession);
        disconnectQuietly(teacherSession);
    }

    @Test
    void joinedStudentCannotSubscribeToWebrtcTopic() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);

        User student = new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null);
        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession snoopingSession = connect(jwtService.generateToken(student));
        LinkedBlockingQueue<WebRtcSignalDto> snooped = subscribe(snoopingSession,
                "/topic/exam-rooms/" + room.id() + "/webrtc");

        LinkedBlockingQueue<WebRtcSignalDto> teacherSignals = subscribe(teacherSession,
                "/topic/exam-rooms/" + room.id() + "/webrtc");
        StompSession senderSession = connect(jwtService.generateToken(student));
        Thread.sleep(300);

        relay(senderSession, room.id(), "webrtc-student-1", "offer", sdpNode());

        assertThat(teacherSignals.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(snooped.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(senderSession);
        disconnectQuietly(snoopingSession);
        disconnectQuietly(teacherSession);
    }

    @Test
    void endedRoomRejectsSignaling() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);
        startRoom(room.id(), teacherToken);
        endRoom(room.id(), teacherToken);

        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> signals = subscribe(teacherSession,
                "/topic/exam-rooms/" + room.id() + "/webrtc");
        Thread.sleep(300);

        relay(studentSession, room.id(), "webrtc-student-1", "offer", sdpNode());

        assertThat(signals.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(studentSession);
        disconnectQuietly(teacherSession);
    }

    @Test
    void authorizedTeacherViewerReadyRelaysToJoinedStudent() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);

        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> studentSignals = subscribe(studentSession, "/user/queue/webrtc");
        Thread.sleep(300);

        relay(teacherSession, room.id(), "webrtc-student-1", "viewer_ready", null);

        WebRtcSignalDto signal = studentSignals.poll(5, TimeUnit.SECONDS);
        assertThat(signal).isNotNull();
        assertThat(signal.senderId()).isEqualTo("webrtc-teacher-1");
        assertThat(signal.senderRole()).isEqualTo("TEACHER");
        assertThat(signal.peerId()).isEqualTo("webrtc-student-1");
        assertThat(signal.type()).isEqualTo("viewer_ready");
        assertThat(signal.sdp() == null || signal.sdp().isNull()).isTrue();
        disconnectQuietly(studentSession);
        disconnectQuietly(teacherSession);
    }

    @Test
    void unauthorizedTeacherViewerReadyIsRejected() throws Exception {
        String teacherOneToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherOneToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);

        StompSession wrongTeacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-2",
                "WebRTC Teacher Two", "webrtc-teacher2@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> studentSignals = subscribe(studentSession, "/user/queue/webrtc");
        Thread.sleep(300);

        relay(wrongTeacherSession, room.id(), "webrtc-student-1", "viewer_ready", null);

        assertThat(studentSignals.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(studentSession);
        disconnectQuietly(wrongTeacherSession);
    }

    @Test
    void studentCannotIssueViewerReady() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);

        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> signals = subscribe(teacherSession,
                "/topic/exam-rooms/" + room.id() + "/webrtc");
        Thread.sleep(300);

        relay(studentSession, room.id(), "webrtc-student-2", "viewer_ready", null);

        assertThat(signals.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(studentSession);
        disconnectQuietly(teacherSession);
    }

    @Test
    void teacherCannotSendUnavailableToStudent() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);

        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> studentSignals = subscribe(studentSession, "/user/queue/webrtc");
        Thread.sleep(300);

        relay(teacherSession, room.id(), "webrtc-student-1", "unavailable", null);

        assertThat(studentSignals.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(studentSession);
        disconnectQuietly(teacherSession);
    }

    @Test
    void studentUnavailableRelaysToTeacherTopic() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);

        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> signals = subscribe(teacherSession,
                "/topic/exam-rooms/" + room.id() + "/webrtc");
        Thread.sleep(300);

        relay(studentSession, room.id(), "webrtc-student-1", "unavailable", null);

        WebRtcSignalDto signal = signals.poll(5, TimeUnit.SECONDS);
        assertThat(signal).isNotNull();
        assertThat(signal.senderId()).isEqualTo("webrtc-student-1");
        assertThat(signal.senderRole()).isEqualTo("STUDENT");
        assertThat(signal.peerId()).isEqualTo("webrtc-student-1");
        assertThat(signal.type()).isEqualTo("unavailable");
        disconnectQuietly(studentSession);
        disconnectQuietly(teacherSession);
    }

    @Test
    void endedRoomRejectsViewerReady() throws Exception {
        String teacherToken = login("webrtc-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("webrtc-student@example.com", "student123");
        join(room.code(), studentToken);
        startRoom(room.id(), teacherToken);
        endRoom(room.id(), teacherToken);

        StompSession teacherSession = connect(jwtService.generateToken(new User("webrtc-teacher-1", "WebRTC Teacher One",
                "webrtc-teacher@example.com", Role.TEACHER, null)));
        StompSession studentSession = connect(jwtService.generateToken(new User("webrtc-student-1", "WebRTC Student One",
                "webrtc-student@example.com", Role.STUDENT, null)));
        LinkedBlockingQueue<WebRtcSignalDto> studentSignals = subscribe(studentSession, "/user/queue/webrtc");
        Thread.sleep(300);

        relay(teacherSession, room.id(), "webrtc-student-1", "viewer_ready", null);

        assertThat(studentSignals.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(studentSession);
        disconnectQuietly(teacherSession);
    }

    private NodeData sdpNode() {
        JsonNode sdp = objectMapper.createObjectNode().put("type", "offer").put("sdp", "v=0 fake");
        return new NodeData(sdp);
    }

    private void relay(StompSession session, String roomId, String peerId, String type, NodeData node) {
        session.send("/app/exam-rooms/" + roomId + "/webrtc",
                new WebRtcSignalRequest(roomId, peerId, type, node == null ? null : node.sdp(), null));
    }

    private LinkedBlockingQueue<WebRtcSignalDto> subscribe(StompSession session, String destination) {
        LinkedBlockingQueue<WebRtcSignalDto> signals = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return WebRtcSignalDto.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                signals.offer((WebRtcSignalDto) payload);
            }
        });
        return signals;
    }

    private Room createRoom(String token, String examId) throws Exception {
        String response = mockMvc.perform(post("/api/exam-rooms")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":\"" + examId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        String id = data.path("roomId").asText();
        String code = data.path("roomCode").asText();
        assertThat(id).isNotBlank();
        assertThat(code).isNotBlank();
        return new Room(id, code);
    }

    private void join(String roomCode, String studentToken) throws Exception {
        mockMvc.perform(post("/api/exam-rooms/join")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomCode\":\"" + roomCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomCode").value(roomCode));
    }

    private void startRoom(String roomId, String token) throws Exception {
        mockMvc.perform(post("/api/exam-rooms/" + roomId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private void endRoom(String roomId, String token) throws Exception {
        mockMvc.perform(post("/api/exam-rooms/" + roomId + "/end")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode token = objectMapper.readTree(response).path("data").path("token");
        assertThat(token.asText()).isNotBlank();
        return token.asText();
    }

    private void insertUser(String id, String name, String email, String password, Role role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role.name());
    }

    private void insertExam(String id, String title, String status, String createdBy) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, 'Math', '2026-09-15', 60, ?, 0, null, ?)",
                id, title, status, createdBy);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Room(String id, String code) {
    }

    private record NodeData(JsonNode sdp) {
    }

    private WebSocketStompClient client() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        client.setMessageConverter(converter);
        return client;
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("authorization", "Bearer " + token);
        return client().connectAsync("ws://localhost:" + port + "/ws",
                        (WebSocketHttpHeaders) null, connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private void disconnectQuietly(StompSession session) {
        if (session.isConnected()) {
            session.disconnect();
        }
    }
}