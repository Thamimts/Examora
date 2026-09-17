package com.examora;

import com.examora.dto.ExamRoomDtos.RoomActivityDto;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_exam_rooms;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-exam-room-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class ExamRoomIntegrationTest {
    private static final String EXAM_1 = "room-exam-1";
    private static final String EXAM_2 = "room-exam-2";

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

        insertUser("room-student-1", "Room Student One", "room-student@example.com", "student123", Role.STUDENT);
        insertUser("room-student-2", "Room Student Two", "room-student2@example.com", "student234", Role.STUDENT);
        insertUser("room-teacher-1", "Room Teacher One", "room-teacher@example.com", "teacher123", Role.TEACHER);
        insertUser("room-teacher-2", "Room Teacher Two", "room-teacher2@example.com", "teacher234", Role.TEACHER);
        insertUser("room-admin-1", "Room Admin One", "room-admin@example.com", "admin123", Role.ADMIN);

        insertExam(EXAM_1, "Room Math Exam", "UPCOMING", "room-teacher-1");
        insertExam(EXAM_2, "Room Physics Exam", "UPCOMING", "room-teacher-2");
    }

    @Test
    void ownerTeacherCanCreateAndManageRoom() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        Room room = createRoom(teacher, EXAM_1);

        mockMvc.perform(get("/api/exam-rooms/" + room.id())
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.room.status").value("WAITING"))
                .andExpect(jsonPath("$.data.room.examId").value(EXAM_1))
                .andExpect(jsonPath("$.data.room.roomCode").value(room.code()));

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/start")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/end")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENDED"));
    }

    @Test
    void anotherTeacherCannotManageUnownedRoom() throws Exception {
        String teacherOne = login("room-teacher@example.com", "teacher123");
        String teacherTwo = login("room-teacher2@example.com", "teacher234");

        mockMvc.perform(post("/api/exam-rooms")
                        .header("Authorization", bearer(teacherTwo))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":\"" + EXAM_1 + "\"}"))
                .andExpect(status().isForbidden());

        Room room = createRoom(teacherOne, EXAM_1);

        mockMvc.perform(get("/api/exam-rooms/" + room.id())
                        .header("Authorization", bearer(teacherTwo)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/start")
                        .header("Authorization", bearer(teacherTwo)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/end")
                        .header("Authorization", bearer(teacherTwo)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanManageAnyRoom() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        String admin = login("room-admin@example.com", "admin123");

        Room room = createRoom(teacher, EXAM_1);

        mockMvc.perform(get("/api/exam-rooms/" + room.id())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.room.examId").value(EXAM_1));

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/start")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/end")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENDED"));
    }

    @Test
    void studentRestrictionsAreEnforced() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        String student = login("room-student@example.com", "student123");

        mockMvc.perform(post("/api/exam-rooms")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":\"" + EXAM_1 + "\"}"))
                .andExpect(status().isForbidden());

        Room room = createRoom(teacher, EXAM_1);

        mockMvc.perform(get("/api/exam-rooms/" + room.id())
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/start")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/end")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createJoinAndListRoomsWithoutCreatingAttempt() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        String student = login("room-student@example.com", "student123");

        Room room = createRoom(teacher, EXAM_1);
        join(room.code(), student);

        mockMvc.perform(get("/api/exam-rooms/" + room.id())
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.room.status").value("WAITING"))
                .andExpect(jsonPath("$.data.members.length()").value(1))
                .andExpect(jsonPath("$.data.members[0].studentId").value("room-student-1"))
                .andExpect(jsonPath("$.data.members[0].studentName").value("Room Student One"));

        mockMvc.perform(get("/api/exam-rooms/my")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomId").value(room.id()));

        mockMvc.perform(get("/api/exam-rooms/my")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomCode").value(room.code()));

        Integer attempts = jdbcTemplate.queryForObject(
                "select count(*) from exam_attempts where exam_id = ? and student_id = ?",
                Integer.class, EXAM_1, "room-student-1");
        assertThat(attempts).isZero();
    }

    @Test
    void joiningInvalidRoomCodeReturns404() throws Exception {
        String student = login("room-student@example.com", "student123");
        mockMvc.perform(post("/api/exam-rooms/join")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomCode\":\"ZZZZZZZZ\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUnknownRoomReturns404() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        mockMvc.perform(get("/api/exam-rooms/does-not-exist")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isNotFound());
    }

    @Test
    void lifecycleTransitionsAreGuarded() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        Room room = createRoom(teacher, EXAM_1);

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/start")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/start")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/end")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENDED"));

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/end")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/start")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isConflict());
    }

    @Test
    void endingWaitingRoomWithoutStartingIsRejected() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        Room room = createRoom(teacher, EXAM_1);

        mockMvc.perform(post("/api/exam-rooms/" + room.id() + "/end")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isConflict());
    }

    @Test
    void endedRoomRejectsJoins() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        String student = login("room-student@example.com", "student123");

        Room room = createRoom(teacher, EXAM_1);
        startRoom(room.id(), teacher);
        endRoom(room.id(), teacher);

        mockMvc.perform(post("/api/exam-rooms/join")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomCode\":\"" + room.code() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void duplicateJoinIsRejected() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        String student = login("room-student@example.com", "student123");

        Room room = createRoom(teacher, EXAM_1);
        join(room.code(), student);

        mockMvc.perform(post("/api/exam-rooms/join")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomCode\":\"" + room.code() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void crossStudentIsolationIsEnforced() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        String studentOne = login("room-student@example.com", "student123");
        String studentTwo = login("room-student2@example.com", "student234");

        Room room = createRoom(teacher, EXAM_1);
        join(room.code(), studentOne);

        mockMvc.perform(get("/api/exam-rooms/" + room.id())
                        .header("Authorization", bearer(studentTwo)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/exam-rooms/my")
                        .header("Authorization", bearer(studentTwo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        Integer membershipRows = jdbcTemplate.queryForObject(
                "select count(*) from exam_room_members where room_id = ? and student_id = ?",
                Integer.class, room.id(), "room-student-2");
        assertThat(membershipRows).isZero();
    }

    @Test
    void crossExamIsolationIsEnforced() throws Exception {
        String teacherOne = login("room-teacher@example.com", "teacher123");
        String teacherTwo = login("room-teacher2@example.com", "teacher234");

        Room roomOne = createRoom(teacherOne, EXAM_1);
        Room roomTwo = createRoom(teacherTwo, EXAM_2);

        mockMvc.perform(get("/api/exam-rooms/" + roomOne.id())
                        .header("Authorization", bearer(teacherTwo)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/exam-rooms/" + roomOne.id() + "/start")
                        .header("Authorization", bearer(teacherTwo)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/exam-rooms/my")
                        .header("Authorization", bearer(teacherOne)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomId").value(roomOne.id()));

        mockMvc.perform(get("/api/exam-rooms/my")
                        .header("Authorization", bearer(teacherTwo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomId").value(roomTwo.id()));
    }

    @Test
    void jwtIdentityCannotBeSpoofed() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        String student = login("room-student@example.com", "student123");

        Room room = createRoom(teacher, EXAM_1);
        join(room.code(), student);

        Integer namedOthers = jdbcTemplate.queryForObject(
                "select count(*) from exam_room_members where room_id = ? and student_id <> ?",
                Integer.class, room.id(), "room-student-1");
        assertThat(namedOthers).isZero();

        Integer membershipCount = jdbcTemplate.queryForObject(
                "select count(*) from exam_room_members where room_id = ? and student_id = ? and status = 'JOINED'",
                Integer.class, room.id(), "room-student-1");
        assertThat(membershipCount).isEqualTo(1);
    }

    @Test
    void roomCodeIsUniqueAcrossRooms() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        Room first = createRoom(teacher, EXAM_1);
        Room second = createRoom(teacher, EXAM_1);

        assertThat(first.code()).isNotEqualTo(second.code());
        Integer collisions = jdbcTemplate.queryForObject(
                "select count(*) from (select room_code from exam_rooms group by room_code having count(*) > 1) duplicates",
                Integer.class);
        assertThat(collisions).isZero();
    }

    @Test
    void ownerTeacherReceivesRoomActivityUpdates() throws Exception {
        User teacher = new User("room-teacher-1", "Room Teacher One", "room-teacher@example.com", Role.TEACHER, null);
        StompSession session = connect(jwtService.generateToken(teacher));
        LinkedBlockingQueue<RoomActivityDto> updates = new LinkedBlockingQueue<>();
        Room room = createRoom(login("room-teacher@example.com", "teacher123"), EXAM_1);
        session.subscribe("/topic/exam-rooms/" + room.id() + "/activity", roomActivityHandler(updates));
        Thread.sleep(300);

        startRoom(room.id(), login("room-teacher@example.com", "teacher123"));

        RoomActivityDto update = updates.poll(5, TimeUnit.SECONDS);
        assertThat(update).isNotNull();
        assertThat(update.roomId()).isEqualTo(room.id());
        assertThat(update.status().name()).isEqualTo("ACTIVE");
        disconnectQuietly(session);
    }

    @Test
    void joinedStudentReceivesRoomActivityUpdates() throws Exception {
        String teacherToken = login("room-teacher@example.com", "teacher123");
        Room room = createRoom(teacherToken, EXAM_1);
        String studentToken = login("room-student@example.com", "student123");
        join(room.code(), studentToken);

        User student = new User("room-student-1", "Room Student One", "room-student@example.com", Role.STUDENT, null);
        StompSession session = connect(jwtService.generateToken(student));
        LinkedBlockingQueue<RoomActivityDto> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exam-rooms/" + room.id() + "/activity", roomActivityHandler(updates));
        Thread.sleep(300);

        startRoom(room.id(), teacherToken);

        RoomActivityDto update = updates.poll(5, TimeUnit.SECONDS);
        assertThat(update).isNotNull();
        assertThat(update.roomId()).isEqualTo(room.id());
        assertThat(update.status().name()).isEqualTo("ACTIVE");
        disconnectQuietly(session);
    }

    @Test
    void wrongTeacherIsDeniedFromRoomTopic() throws Exception {
        String teacherOne = login("room-teacher@example.com", "teacher123");
        Room room = createRoom(teacherOne, EXAM_1);

        User wrongTeacher = new User("room-teacher-2", "Room Teacher Two", "room-teacher2@example.com", Role.TEACHER, null);
        StompSession session = connect(jwtService.generateToken(wrongTeacher));
        LinkedBlockingQueue<RoomActivityDto> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exam-rooms/" + room.id() + "/activity", roomActivityHandler(updates));
        Thread.sleep(300);

        startRoom(room.id(), teacherOne);

        assertThat(updates.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(session);
    }

    @Test
    void adminCanSubscribeToAnyRoomTopic() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        Room room = createRoom(teacher, EXAM_1);

        User admin = new User("room-admin-1", "Room Admin One", "room-admin@example.com", Role.ADMIN, null);
        StompSession session = connect(jwtService.generateToken(admin));
        LinkedBlockingQueue<RoomActivityDto> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exam-rooms/" + room.id() + "/activity", roomActivityHandler(updates));
        Thread.sleep(300);

        startRoom(room.id(), teacher);

        assertThat(updates.poll(5, TimeUnit.SECONDS)).isNotNull();
        disconnectQuietly(session);
    }

    @Test
    void unjoinedStudentIsDeniedFromRoomTopic() throws Exception {
        String teacher = login("room-teacher@example.com", "teacher123");
        Room room = createRoom(teacher, EXAM_1);

        User stowaway = new User("room-student-2", "Room Student Two", "room-student2@example.com", Role.STUDENT, null);
        StompSession session = connect(jwtService.generateToken(stowaway));
        LinkedBlockingQueue<RoomActivityDto> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exam-rooms/" + room.id() + "/activity", roomActivityHandler(updates));
        Thread.sleep(300);

        startRoom(room.id(), teacher);

        assertThat(updates.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(session);
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

    private StompFrameHandler roomActivityHandler(LinkedBlockingQueue<RoomActivityDto> updates) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return RoomActivityDto.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                updates.offer((RoomActivityDto) payload);
            }
        };
    }

    private void disconnectQuietly(StompSession session) {
        if (session.isConnected()) {
            session.disconnect();
        }
    }
}