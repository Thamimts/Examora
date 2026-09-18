package com.examora;

import com.examora.dto.ProctorUpdate;
import com.examora.model.ExamAccessStatus;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.test.web.servlet.MvcResult;
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
        "spring.datasource.url=jdbc:h2:mem:examora_command_center;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-command-center-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip=1000",
        "examora.login.max-per-ip-email=1000"
})
class CommandCenterIntegrationTest {

    private static final String EXAM = "cmd-exam-1";
    private static final String EXAM_OTHER = "cmd-exam-2";
    private static final String QUESTION = "cmd-q-1";

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
        jdbcTemplate.update("delete from exam_access_state");
        jdbcTemplate.update("delete from activity_events");
        jdbcTemplate.update("delete from proctor_events");
        jdbcTemplate.update("delete from exam_room_members");
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from retest_requests");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from exam_rooms");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("cmd-student-1", "Command Student One", "cmd-student@example.com", "student123", Role.STUDENT);
        insertUser("cmd-student-2", "Command Student Two", "cmd-student2@example.com", "student234", Role.STUDENT);
        insertUser("cmd-student-3", "Command Student Three", "cmd-student3@example.com", "student345", Role.STUDENT);
        insertUser("cmd-teacher-1", "Command Teacher One", "cmd-teacher@example.com", "teacher123", Role.TEACHER);
        insertUser("cmd-teacher-2", "Command Teacher Two", "cmd-teacher2@example.com", "teacher234", Role.TEACHER);
        insertUser("cmd-admin-1", "Command Admin One", "cmd-admin@example.com", "admin123", Role.ADMIN);

        insertExam(EXAM, "Command Exam", "cmd-teacher-1");
        insertExam(EXAM_OTHER, "Other Command Exam", "cmd-teacher-2");
        insertQuestion(QUESTION, EXAM, "Pick A");
        insertOption("cmd-opt-a", QUESTION, "A", true);
        insertOption("cmd-opt-b", QUESTION, "B", false);
    }

    @Test
    void anonymousAccessRejected() throws Exception {
        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void studentAccessRejected() throws Exception {
        String studentToken = login("cmd-student@example.com", "student123");

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonOwnerTeacherCannotReadCommandCenter() throws Exception {
        String otherTeacherToken = login("cmd-teacher2@example.com", "teacher234");

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerTeacherCanReadCommandCenter() throws Exception {
        String teacherToken = login("cmd-teacher@example.com", "teacher123");
        insertRoom("room-1a", EXAM, "AAAA1111", "ACTIVE", true);
        joinMember("mem-1", "room-1a", "cmd-student-1");
        insertAttempt("at-1", EXAM, "cmd-student-1", "STARTED", 0);

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exam.examId").value(EXAM))
                .andExpect(jsonPath("$.data.exam.examTitle").value("Command Exam"))
                .andExpect(jsonPath("$.data.exam.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.rooms.length()").value(1))
                .andExpect(jsonPath("$.data.roster.length()").value(1))
                .andExpect(jsonPath("$.data.roster[0].studentId").value("cmd-student-1"))
                .andExpect(jsonPath("$.data.roster[0].roomCode").value("AAAA1111"))
                .andExpect(jsonPath("$.data.roster[0].attemptStatus").value("STARTED"))
                .andExpect(jsonPath("$.data.roster[0].warningLevel").value("NONE"))
                .andExpect(jsonPath("$.data.roster[0].accessStatus").value("ELIGIBLE"));
    }

    @Test
    void commandCenterDoesNotLeakAnswersOrScores() throws Exception {
        String teacherToken = login("cmd-teacher@example.com", "teacher123");
        insertRoom("room-1a", EXAM, "AAAA1111", "ACTIVE", true);
        joinMember("mem-1", "room-1a", "cmd-student-1");
        insertAttempt("at-1", EXAM, "cmd-student-1", "STARTED", 0);

        MvcResult result = mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        assertThat(json).doesNotContain("question", "option", "answer", "score", "result", "correct");
    }

    @Test
    void adminCanReadAnyExam() throws Exception {
        String adminToken = login("cmd-admin@example.com", "admin123");
        insertRoom("room-2", EXAM_OTHER, "BBBB2222", "ACTIVE", true);
        joinMember("mem-2", "room-2", "cmd-student-3");
        insertAttempt("at-2", EXAM_OTHER, "cmd-student-3", "STARTED", 0);

        mockMvc.perform(get("/api/proctor/exams/" + EXAM_OTHER + "/command-center")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exam.examId").value(EXAM_OTHER))
                .andExpect(jsonPath("$.data.roster.length()").value(1))
                .andExpect(jsonPath("$.data.roster[0].studentId").value("cmd-student-3"));
    }

    @Test
    void rosterIsScopedToTheRequestedExam() throws Exception {
        String teacherToken = login("cmd-teacher@example.com", "teacher123");
        insertRoom("room-1a", EXAM, "AAAA1111", "ACTIVE", true);
        insertRoom("room-2", EXAM_OTHER, "BBBB2222", "ACTIVE", true);
        joinMember("mem-1", "room-1a", "cmd-student-1");
        joinMember("mem-2", "room-2", "cmd-student-3");
        insertAttempt("at-1", EXAM, "cmd-student-1", "STARTED", 0);
        insertAttempt("at-2", EXAM_OTHER, "cmd-student-3", "STARTED", 0);

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roster.length()").value(1))
                .andExpect(jsonPath("$.data.roster[0].studentId").value("cmd-student-1"))
                .andExpect(jsonPath("$.data.rooms.length()").value(1))
                .andExpect(jsonPath("$.data.rooms[0].roomId").value("room-1a"));
    }

    @Test
    void joinedStudentWithoutAttemptAppearsInRoster() throws Exception {
        String teacherToken = login("cmd-teacher@example.com", "teacher123");
        insertRoom("room-1a", EXAM, "AAAA1111", "ACTIVE", true);
        joinMember("mem-1", "room-1a", "cmd-student-1");
        joinMember("mem-2", "room-1a", "cmd-student-2");
        insertAttempt("at-1", EXAM, "cmd-student-1", "STARTED", 0);

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roster.length()").value(2))
                .andExpect(jsonPath("$.data.roster[0].studentId").value("cmd-student-1"))
                .andExpect(jsonPath("$.data.roster[1].studentId").value("cmd-student-2"))
                .andExpect(jsonPath("$.data.roster[1].attemptStatus").doesNotExist())
                .andExpect(jsonPath("$.data.roster[1].activeNow").value(false))
                .andExpect(jsonPath("$.data.roster[1].warningLevel").value("NONE"))
                .andExpect(jsonPath("$.data.roster[1].roomCode").value("AAAA1111"));
    }

    @Test
    void metricsReflectRoomAttributionPriority() throws Exception {
        String teacherToken = login("cmd-teacher@example.com", "teacher123");
        insertRoom("room-1a", EXAM, "AAAA1111", "ACTIVE", true);
        insertRoom("room-1b", EXAM, "BBBB1111", "WAITING", false);
        joinMember("mem-1", "room-1b", "cmd-student-1");
        joinMember("mem-1b", "room-1a", "cmd-student-1");
        insertAttempt("at-1", EXAM, "cmd-student-1", "STARTED", 0);

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rooms.length()").value(2))
                .andExpect(jsonPath("$.data.roster[0].roomId").value("room-1a"))
                .andExpect(jsonPath("$.data.roster[0].roomCode").value("AAAA1111"))
                .andExpect(jsonPath("$.data.roster[0].roomStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.exam.totalStudents").value(1))
                .andExpect(jsonPath("$.data.exam.joinedStudents").value(1))
                .andExpect(jsonPath("$.data.exam.activeAttempts").value(1));
    }

    @Test
    void metricsCountActiveSubmittedTerminatedAndWarnings() throws Exception {
        String teacherToken = login("cmd-teacher@example.com", "teacher123");
        insertRoom("room-1a", EXAM, "AAAA1111", "ACTIVE", true);
        joinMember("mem-1", "room-1a", "cmd-student-1");
        joinMember("mem-2", "room-1a", "cmd-student-2");
        joinMember("mem-3", "room-1a", "cmd-student-3");
        insertAttempt("at-1", EXAM, "cmd-student-1", "STARTED", 1);
        insertAttempt("at-2", EXAM, "cmd-student-2", "SUBMITTED", 0);
        insertAttempt("at-3", EXAM, "cmd-student-3", "PROCTOR_TERMINATED", 3);
        insertAccess("cmd-student-3", EXAM, ExamAccessStatus.SUSPENDED);

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exam.totalStudents").value(3))
                .andExpect(jsonPath("$.data.exam.joinedStudents").value(3))
                .andExpect(jsonPath("$.data.exam.activeAttempts").value(1))
                .andExpect(jsonPath("$.data.exam.submittedAttempts").value(1))
                .andExpect(jsonPath("$.data.exam.terminatedAttempts").value(1))
                .andExpect(jsonPath("$.data.exam.offlineParticipants").value(2))
                .andExpect(jsonPath("$.data.exam.totalWarnings").value(4));
    }

    @Test
    void terminatedStudentShowsAuthoritativeState() throws Exception {
        String teacherToken = login("cmd-teacher@example.com", "teacher123");
        insertRoom("room-1a", EXAM, "AAAA1111", "ACTIVE", true);
        joinMember("mem-1", "room-1a", "cmd-student-1");
        insertAttempt("at-1", EXAM, "cmd-student-1", "PROCTOR_TERMINATED", 3);
        insertAccess("cmd-student-1", EXAM, ExamAccessStatus.SUSPENDED);

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roster[0].attemptStatus").value("PROCTOR_TERMINATED"))
                .andExpect(jsonPath("$.data.roster[0].warningCount").value(3))
                .andExpect(jsonPath("$.data.roster[0].warningLevel").value("WARNING_3"))
                .andExpect(jsonPath("$.data.roster[0].accessStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.roster[0].activeNow").value(false))
                .andExpect(jsonPath("$.data.exam.terminatedAttempts").value(1));
    }

    @Test
    void deviceSignalsDerivedFromLatestEvents() throws Exception {
        String teacherToken = login("cmd-teacher@example.com", "teacher123");
        insertRoom("room-1a", EXAM, "AAAA1111", "ACTIVE", true);
        joinMember("mem-1", "room-1a", "cmd-student-1");
        String attemptId = insertAttempt("at-1", EXAM, "cmd-student-1", "STARTED", 1);
        postEvents(login("cmd-student@example.com", "student123"),
                batch(attemptId, "TAB_SWITCH", "AUDIO_DETECTED", "NETWORK_INTERRUPTION", "CAMERA_OFF"));

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roster[0].cameraOff").value(true))
                .andExpect(jsonPath("$.data.roster[0].fullscreenExited").doesNotExist())
                .andExpect(jsonPath("$.data.roster[0].audioSignalCount").value(1))
                .andExpect(jsonPath("$.data.roster[0].networkInterruptionCount").value(1))
                .andExpect(jsonPath("$.data.roster[0].riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.data.roster[0].eventCount").value(4))
                .andExpect(jsonPath("$.data.roster[0].latestProctorEvent.type").value("CAMERA_OFF"));
    }

    @Test
    void fullscreenExitIsReflectedAndCameraSignalClears() throws Exception {
        String teacherToken = login("cmd-teacher@example.com", "teacher123");
        insertRoom("room-1a", EXAM, "AAAA1111", "ACTIVE", true);
        joinMember("mem-1", "room-1a", "cmd-student-1");
        insertAttempt("at-1", EXAM, "cmd-student-1", "STARTED", 1);
        postEvents(login("cmd-student@example.com", "student123"),
                batch("at-1", "CAMERA_OFF", "FULLSCREEN_EXIT"));

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roster[0].fullscreenExited").value(true))
                .andExpect(jsonPath("$.data.roster[0].cameraOff").doesNotExist());
    }

    @Test
    void attemptEventsAreScopedToOwnerAndAdmin() throws Exception {
        String ownerToken = login("cmd-teacher@example.com", "teacher123");
        String otherToken = login("cmd-teacher2@example.com", "teacher234");
        String studentToken = login("cmd-student@example.com", "student123");
        String attemptId = insertAttempt("at-1", EXAM, "cmd-student-1", "STARTED", 0);

        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/events")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/events")
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/events")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void websocketUpdateCarriesAuthoritativeWarningFields() throws Exception {
        String studentToken = login("cmd-student@example.com", "student123");
        String attemptId = insertAttempt("at-1", EXAM, "cmd-student-1", "STARTED", 0);

        StompSession session = connectAs(new User("cmd-teacher-1", "Command Teacher One",
                "cmd-teacher@example.com", Role.TEACHER, null));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);
        try {
            postEvents(studentToken, batch(attemptId, "TAB_SWITCH"));

            ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
            assertThat(update).isNotNull();
            assertThat(update.examId()).isEqualTo(EXAM);
            assertThat(update.attemptId()).isEqualTo(attemptId);
            assertThat(update.warningCount()).isEqualTo(1);
            assertThat(update.warningLevel()).isEqualTo(com.examora.dto.ProctorDtos.WarningLevel.WARNING_1);
            assertThat(update.accessStatus()).isEqualTo(ExamAccessStatus.ELIGIBLE);
            assertThat(update.latestEvent()).isNotNull();
            assertThat(update.sequence()).isPositive();
        } finally {
            disconnectQuietly(session);
        }
    }

    private MvcResult postEvents(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/proctor/events/batch")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String batch(String attemptId, String... types) {
        List<String> events = new ArrayList<>();
        for (String type : types) {
            events.add("{\"eventId\":\"" + UUID.randomUUID() + "\",\"attemptId\":\"" + attemptId
                    + "\",\"type\":\"" + type + "\",\"occurredAt\":\"" + Instant.now() + "\"}");
        }
        return "{\"events\":[" + String.join(",", events) + "]}";
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode token = objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/token");
        assertThat(token.asText()).isNotBlank();
        return token.asText();
    }

    private void insertUser(String id, String name, String email, String password, Role role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role.name());
    }

    private void insertExam(String id, String title, String ownerId) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, 'Math', '2030-06-01', 60, 'PUBLISHED', 0, null, ?)",
                id, title, ownerId);
    }

    private void insertQuestion(String id, String examId, String text) {
        jdbcTemplate.update("insert into questions (id, exam_id, text, difficulty) values (?, ?, ?, 2)", id, examId, text);
    }

    private void insertOption(String id, String questionId, String text, boolean correct) {
        jdbcTemplate.update(
                "insert into question_options (id, question_id, text, display_order, correct_answer) values (?, ?, ?, 1, ?)",
                id, questionId, text, correct);
    }

    private void insertRoom(String roomId, String examId, String roomCode, String status, boolean active) {
        jdbcTemplate.update(
                "insert into exam_rooms (id, exam_id, room_code, status, created_by, started_at, ended_at) "
                        + "values (?, ?, ?, ?, 'cmd-teacher-1', ?, null)",
                roomId, examId, roomCode, status, active ? Timestamp.from(Instant.now()) : null);
    }

    private void joinMember(String memberId, String roomId, String studentId) {
        jdbcTemplate.update(
                "insert into exam_room_members (id, room_id, student_id, status, joined_at) values (?, ?, ?, 'JOINED', current_timestamp)",
                memberId, roomId, studentId);
    }

    private String insertAttempt(String id, String examId, String studentId, String status, int warningCount) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version, warning_count) "
                        + "values (?, ?, ?, 1, ?, ?, ?, 0, ?)",
                id, examId, studentId, status, Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)), warningCount);
        return id;
    }

    private void insertAccess(String studentId, String examId, ExamAccessStatus status) {
        jdbcTemplate.update(
                "insert into exam_access_state (student_id, exam_id, status, suspended_at, suspended_reason) "
                        + "values (?, ?, ?, current_timestamp, 'test-setup')",
                studentId, examId, status.name());
    }

    private StompSession connectAs(User user) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("authorization", "Bearer " + jwtService.generateToken(user));
        return client().connectAsync("ws://localhost:" + port + "/ws",
                        (WebSocketHttpHeaders) null, connectHeaders, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private WebSocketStompClient client() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        client.setMessageConverter(converter);
        return client;
    }

    private StompFrameHandler proctorUpdateHandler(LinkedBlockingQueue<ProctorUpdate> updates) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return ProctorUpdate.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                updates.offer((ProctorUpdate) payload);
            }
        };
    }

    private void disconnectQuietly(StompSession session) {
        if (session.isConnected()) {
            session.disconnect();
        }
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}