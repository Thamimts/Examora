package com.examora;

import com.examora.dto.ProctorDtos.EventBatchRequest;
import com.examora.dto.ProctorUpdate;
import com.examora.model.ExamAttempt;
import com.examora.model.ProctorEvent;
import com.examora.model.User;
import com.examora.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_proctor_ws;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-proctor-ws-monitor-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class ProctorMonitorWebSocketTest {
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

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @LocalServerPort
    private int port;

    private static final String EXAM_ID = "exam-proctor-ws-1";
    private static final String EXAM_ID_OTHER = "exam-proctor-ws-2";
    private static final String QUESTION_ID = "q-ws-1";
    private static final String OPTION_ID = "opt-ws-1";

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from activity_events");
        jdbcTemplate.update("delete from proctor_events");
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("student-ws-1", "Student WS One", "student-ws@example.com", "student123", "STUDENT");
        insertUser("teacher-ws-1", "Teacher WS One", "teacher-ws@example.com", "teacher123", "TEACHER");
        insertUser("teacher-ws-2", "Teacher WS Two", "teacher-ws2@example.com", "teacher123", "TEACHER");
        insertUser("admin-ws-1", "Admin WS One", "admin-ws@example.com", "admin123", "ADMIN");

        insertExam(EXAM_ID, "Proctor WS Exam", "Math", "UPCOMING", "teacher-ws-1");
        insertExam(EXAM_ID_OTHER, "Other Exam", "Physics", "UPCOMING", "teacher-ws-2");
        insertQuestion(QUESTION_ID, EXAM_ID, "What is 2+2?");
        insertOption(OPTION_ID, QUESTION_ID, "4", true);
    }

    @Test
    void ownerTeacherCanSubscribeToExamTopic() throws Exception {
        User teacher = new User("teacher-ws-1", "Teacher WS One", "teacher-ws@example.com", com.examora.model.Role.TEACHER, null);
        StompSession session = connect(jwtService.generateToken(teacher));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM_ID + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);

        messagingTemplate.convertAndSend("/topic/exams/" + EXAM_ID + "/activity",
                new ProctorUpdate(EXAM_ID, "att-1", "STARTED",
                        new com.examora.dto.ProctorDtos.ProctorStudentDto("s1", "S", "s@e.com"),
                        com.examora.dto.ProctorDtos.RiskLevel.LOW, 0.0, null, 0, 1, Instant.now()));
        assertThat(updates.poll(5, TimeUnit.SECONDS)).isNotNull();
        disconnectQuietly(session);
    }

    @Test
    void wrongTeacherDeniedFromExamTopic() throws Exception {
        User wrongTeacher = new User("teacher-ws-2", "Teacher WS Two", "teacher-ws2@example.com", com.examora.model.Role.TEACHER, null);
        StompSession session = connect(jwtService.generateToken(wrongTeacher));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM_ID + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);

        messagingTemplate.convertAndSend("/topic/exams/" + EXAM_ID + "/activity",
                new ProctorUpdate(EXAM_ID, "att-1", "STARTED",
                        new com.examora.dto.ProctorDtos.ProctorStudentDto("s1", "S", "s@e.com"),
                        com.examora.dto.ProctorDtos.RiskLevel.LOW, 0.0, null, 0, 1, Instant.now()));
        assertThat(updates.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(session);
    }

    @Test
    void studentDeniedFromExamTopic() throws Exception {
        User student = new User("student-ws-1", "Student WS One", "student-ws@example.com", com.examora.model.Role.STUDENT, null);
        StompSession session = connect(jwtService.generateToken(student));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM_ID + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);

        messagingTemplate.convertAndSend("/topic/exams/" + EXAM_ID + "/activity",
                new ProctorUpdate(EXAM_ID, "att-1", "STARTED",
                        new com.examora.dto.ProctorDtos.ProctorStudentDto("s1", "S", "s@e.com"),
                        com.examora.dto.ProctorDtos.RiskLevel.LOW, 0.0, null, 0, 1, Instant.now()));
        assertThat(updates.poll(2, TimeUnit.SECONDS)).isNull();
        disconnectQuietly(session);
    }

    @Test
    void adminCanSubscribeToAnyExamTopic() throws Exception {
        User admin = new User("admin-ws-1", "Admin WS One", "admin-ws@example.com", com.examora.model.Role.ADMIN, null);
        StompSession session = connect(jwtService.generateToken(admin));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM_ID + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);

        messagingTemplate.convertAndSend("/topic/exams/" + EXAM_ID + "/activity",
                new ProctorUpdate(EXAM_ID, "att-1", "STARTED",
                        new com.examora.dto.ProctorDtos.ProctorStudentDto("s1", "S", "s@e.com"),
                        com.examora.dto.ProctorDtos.RiskLevel.LOW, 0.0, null, 0, 1, Instant.now()));
        assertThat(updates.poll(5, TimeUnit.SECONDS)).isNotNull();
        disconnectQuietly(session);
    }

    @Test
    void adminCanSubscribeToOtherTeachersExamTopic() throws Exception {
        User admin = new User("admin-ws-1", "Admin WS One", "admin-ws@example.com", com.examora.model.Role.ADMIN, null);
        StompSession session = connect(jwtService.generateToken(admin));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM_ID_OTHER + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);

        messagingTemplate.convertAndSend("/topic/exams/" + EXAM_ID_OTHER + "/activity",
                new ProctorUpdate(EXAM_ID_OTHER, "att-2", "STARTED",
                        new com.examora.dto.ProctorDtos.ProctorStudentDto("s1", "S", "s@e.com"),
                        com.examora.dto.ProctorDtos.RiskLevel.LOW, 0.0, null, 0, 1, Instant.now()));
        assertThat(updates.poll(5, TimeUnit.SECONDS)).isNotNull();
        disconnectQuietly(session);
    }

    @Test
    void eventBatchProducesProctorUpdate() throws Exception {
        User teacher = new User("teacher-ws-1", "Teacher WS One", "teacher-ws@example.com", com.examora.model.Role.TEACHER, null);
        StompSession session = connect(jwtService.generateToken(teacher));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM_ID + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);

        String attemptId = insertAttempt(EXAM_ID, "student-ws-1", "STARTED");

        User teacherUser = new User("teacher-ws-1", "Teacher WS One", "teacher-ws@example.com", com.examora.model.Role.TEACHER, null);
        String teacherToken = jwtService.generateToken(teacherUser);

        List<ProctorEvent> events = List.of(
                new ProctorEvent("evt-1", attemptId, "TAB_SWITCH", Instant.now().toString(), Map.of()),
                new ProctorEvent("evt-2", attemptId, "WINDOW_BLUR", Instant.now().toString(), Map.of()));

        mockMvc.perform(post("/api/proctor/events/batch")
                        .header("Authorization", "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EventBatchRequest(events))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(2));

        ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
        assertThat(update).isNotNull();
        assertThat(update.examId()).isEqualTo(EXAM_ID);
        assertThat(update.attemptId()).isEqualTo(attemptId);
        assertThat(update.status()).isEqualTo("STARTED");
        assertThat(update.student().id()).isEqualTo("student-ws-1");
        assertThat(update.eventCount()).isEqualTo(2);
        assertThat(update.riskScore()).isGreaterThan(0);
        assertThat(update.latestEvent()).isNotNull();
        disconnectQuietly(session);
    }

    @Test
    void attemptStartProducesProctorUpdate() throws Exception {
        User teacher = new User("teacher-ws-1", "Teacher WS One", "teacher-ws@example.com", com.examora.model.Role.TEACHER, null);
        StompSession session = connect(jwtService.generateToken(teacher));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM_ID + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);

        User student = new User("student-ws-1", "Student WS One", "student-ws@example.com", com.examora.model.Role.STUDENT, null);
        String studentToken = jwtService.generateToken(student);

        mockMvc.perform(post("/api/exams/" + EXAM_ID + "/start")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk());

        ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
        assertThat(update).isNotNull();
        assertThat(update.examId()).isEqualTo(EXAM_ID);
        assertThat(update.status()).isEqualTo("STARTED");
        assertThat(update.student().id()).isEqualTo("student-ws-1");
        assertThat(update.eventCount()).isEqualTo(0);
        assertThat(update.riskLevel()).isEqualTo(com.examora.dto.ProctorDtos.RiskLevel.LOW);
        assertThat(update.riskScore()).isEqualTo(0.0);
        assertThat(update.latestEvent()).isNull();
        disconnectQuietly(session);
    }

    @Test
    void attemptSubmitProducesProctorUpdate() throws Exception {
        User teacher = new User("teacher-ws-1", "Teacher WS One", "teacher-ws@example.com", com.examora.model.Role.TEACHER, null);
        StompSession session = connect(jwtService.generateToken(teacher));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM_ID + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);

        User student = new User("student-ws-1", "Student WS One", "student-ws@example.com", com.examora.model.Role.STUDENT, null);
        String studentToken = jwtService.generateToken(student);

        mockMvc.perform(post("/api/exams/" + EXAM_ID + "/start")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk());
        updates.poll(3, TimeUnit.SECONDS);

        String submitBody = "{\"answers\":[{\"questionId\":\"" + QUESTION_ID + "\",\"optionId\":\"" + OPTION_ID + "\"}]}";
        mockMvc.perform(post("/api/exams/" + EXAM_ID + "/submit")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody))
                .andExpect(status().isOk());

        ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
        assertThat(update).isNotNull();
        assertThat(update.examId()).isEqualTo(EXAM_ID);
        assertThat(update.status()).isEqualTo("SUBMITTED");
        assertThat(update.student().id()).isEqualTo("student-ws-1");
        disconnectQuietly(session);
    }

    @Test
    void attemptExpiryProducesProctorUpdate() throws Exception {
        User teacher = new User("teacher-ws-1", "Teacher WS One", "teacher-ws@example.com", com.examora.model.Role.TEACHER, null);
        StompSession session = connect(jwtService.generateToken(teacher));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM_ID + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);

        String attemptId = insertAttemptWithPastExpiry(EXAM_ID, "student-ws-1");

        Thread.sleep(1500);

        ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
        assertThat(update).isNotNull();
        assertThat(update.examId()).isEqualTo(EXAM_ID);
        assertThat(update.attemptId()).isEqualTo(attemptId);
        assertThat(update.status()).isEqualTo("EXPIRED");
        assertThat(update.student().id()).isEqualTo("student-ws-1");
        disconnectQuietly(session);
    }

    @Test
    void destinationContainsCorrectExamId() throws Exception {
        User teacher = new User("teacher-ws-1", "Teacher WS One", "teacher-ws@example.com", com.examora.model.Role.TEACHER, null);
        StompSession session = connect(jwtService.generateToken(teacher));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM_ID + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);

        User student = new User("student-ws-1", "Student WS One", "student-ws@example.com", com.examora.model.Role.STUDENT, null);
        String studentToken = jwtService.generateToken(student);

        mockMvc.perform(post("/api/exams/" + EXAM_ID + "/start")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk());

        ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
        assertThat(update).isNotNull();
        assertThat(update.examId()).isEqualTo(EXAM_ID);
        disconnectQuietly(session);
    }

    @Test
    void jwtDoesNotAppearInWebSocketUrl() throws Exception {
        User teacher = new User("teacher-ws-1", "Teacher WS One", "teacher-ws@example.com", com.examora.model.Role.TEACHER, null);
        String token = jwtService.generateToken(teacher);

        StompHeaders headers = new StompHeaders();
        assertThatThrownBy(() -> client().connectAsync(
                        "ws://localhost:" + port + "/ws?token=" + token,
                        (WebSocketHttpHeaders) null, headers,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS))
                .isInstanceOf(Exception.class);
    }

    @Test
    void existingWebSocketTestsRemainGreen() throws Exception {
        User student = new User("student-ws-1", "Student WS One", "student-ws@example.com", com.examora.model.Role.STUDENT, null);
        StompSession session = connect(jwtService.generateToken(student));
        LinkedBlockingQueue<com.examora.model.ActivityEvent> events = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/activity", activityHandler(events));
        Thread.sleep(200);
        messagingTemplate.convertAndSendToUser("student-ws-1", "/queue/activity",
                new com.examora.model.ActivityEvent("live-ws-1", "EXAM_STARTED", "Live activity", Instant.now()));
        assertThat(events.poll(5, TimeUnit.SECONDS)).isNotNull();
        disconnectQuietly(session);
    }

    @Test
    void noQuestionsOptionsOrAnswersInProctorUpdate() throws Exception {
        User teacher = new User("teacher-ws-1", "Teacher WS One", "teacher-ws@example.com", com.examora.model.Role.TEACHER, null);
        StompSession session = connect(jwtService.generateToken(teacher));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM_ID + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);

        String attemptId = insertAttempt(EXAM_ID, "student-ws-1", "STARTED");

        User teacherUser = new User("teacher-ws-1", "Teacher WS One", "teacher-ws@example.com", com.examora.model.Role.TEACHER, null);
        String teacherToken = jwtService.generateToken(teacherUser);

        List<ProctorEvent> events = List.of(
                new ProctorEvent("evt-safe-1", attemptId, "TAB_SWITCH", Instant.now().toString(), Map.of()));
        mockMvc.perform(post("/api/proctor/events/batch")
                        .header("Authorization", "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EventBatchRequest(events))))
                .andExpect(status().isOk());

        ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
        assertThat(update).isNotNull();
        String json = objectMapper.writeValueAsString(update);
        assertThat(json).doesNotContain("question", "option", "answer", "score", "result");
        disconnectQuietly(session);
    }

    private WebSocketStompClient client() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        client.setMessageConverter(converter);
        return client;
    }

    private void disconnectQuietly(StompSession session) {
        if (session.isConnected()) {
            session.disconnect();
        }
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("authorization", "Bearer " + token);
        return client().connectAsync("ws://localhost:" + port + "/ws",
                        (WebSocketHttpHeaders) null, connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
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

    private StompFrameHandler activityHandler(LinkedBlockingQueue<com.examora.model.ActivityEvent> events) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return com.examora.model.ActivityEvent.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                events.offer((com.examora.model.ActivityEvent) payload);
            }
        };
    }

    private String insertAttempt(String examId, String studentId, String status) {
        String id = "attempt-ws-" + System.nanoTime();
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) "
                        + "values (?, ?, ?, 1, ?, ?, ?, 0)",
                id, examId, studentId, status,
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        return id;
    }

    private String insertAttemptWithPastExpiry(String examId, String studentId) {
        String id = "attempt-ws-exp-" + System.nanoTime();
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) "
                        + "values (?, ?, ?, 1, 'STARTED', ?, ?, 0)",
                id, examId, studentId,
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)),
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
        return id;
    }

    private void insertUser(String id, String name, String email, String password, String role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role);
    }

    private void insertExam(String id, String title, String subject, String status, String createdBy) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, ?, '2026-09-15', 60, ?, 0, null, ?)",
                id, title, subject, status, createdBy);
    }

    private void insertQuestion(String id, String examId, String text) {
        jdbcTemplate.update(
                "insert into questions (id, exam_id, text, difficulty) values (?, ?, ?, 2)",
                id, examId, text);
    }

    private void insertOption(String id, String questionId, String text, boolean correct) {
        jdbcTemplate.update(
                "insert into question_options (id, question_id, text, display_order, correct_answer) values (?, ?, ?, 1, ?)",
                id, questionId, text, correct);
    }

    private String login(String email, String password) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        com.fasterxml.jackson.databind.JsonNode token = objectMapper.readTree(response).path("data").path("token");
        assertThat(token.asText()).isNotBlank();
        return token.asText();
    }
}
