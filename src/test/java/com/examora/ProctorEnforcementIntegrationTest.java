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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
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
        "spring.datasource.url=jdbc:h2:mem:examora_proctor_enforcement;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-proctor-enforcement-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip=1000",
        "examora.login.max-per-ip-email=1000"
})
class ProctorEnforcementIntegrationTest {
    private static final String EXAM = "enf-exam-1";
    private static final String EXAM_OTHER = "enf-exam-2";
    private static final String QUESTION = "enf-q-1";

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
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from retest_requests");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("enf-student-1", "Enforcement Student One", "enf-student@example.com", "student123", Role.STUDENT);
        insertUser("enf-student-2", "Enforcement Student Two", "enf-student2@example.com", "student234", Role.STUDENT);
        insertUser("enf-teacher-1", "Enforcement Teacher One", "enf-teacher@example.com", "teacher123", Role.TEACHER);
        insertUser("enf-teacher-2", "Enforcement Teacher Two", "enf-teacher2@example.com", "teacher234", Role.TEACHER);
        insertUser("enf-admin-1", "Enforcement Admin One", "enf-admin@example.com", "admin123", Role.ADMIN);

        insertExam(EXAM, "Enforcement Exam", "enf-teacher-1");
        insertExam(EXAM_OTHER, "Other Enforcement Exam", "enf-teacher-2");
        insertQuestion(QUESTION, EXAM, "Pick A");
        insertOption("enf-opt-a", QUESTION, "A", true);
        insertOption("enf-opt-b", QUESTION, "B", false);
    }

    @Test
    void qualifyingViolationIncrementsWarningCount() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        assertThat(postEvents(studentToken, batch(attemptId, "TAB_SWITCH")).getResponse().getStatus()).isEqualTo(200);

        assertThat(warningCount(attemptId)).isEqualTo(1);
        assertThat(attemptStatus(attemptId)).isEqualTo("STARTED");
        assertThat(accessStatus("enf-student-1", EXAM)).isNull();
    }

    @Test
    void networkInterruptionIsNotCounted() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        MvcResult result = postEvents(studentToken, batch(attemptId, "NETWORK_INTERRUPTION"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/saved").asInt()).isEqualTo(1);
        assertThat(warningCount(attemptId)).isZero();
        assertThat(attemptStatus(attemptId)).isEqualTo("STARTED");
    }

    @Test
    void fullscreenExitIsCounted() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        assertThat(postEvents(studentToken, batch(attemptId, "FULLSCREEN_EXIT")).getResponse().getStatus()).isEqualTo(200);

        assertThat(warningCount(attemptId)).isEqualTo(1);
    }

    @Test
    void thirdQualifyingViolationTerminatesAndSuspends() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        postEvents(studentToken, batch(attemptId, "TAB_SWITCH"));
        postEvents(studentToken, batch(attemptId, "WINDOW_BLUR"));
        postEvents(studentToken, batch(attemptId, "CAMERA_OFF"));

        assertThat(warningCount(attemptId)).isEqualTo(3);
        assertThat(attemptStatus(attemptId)).isEqualTo("PROCTOR_TERMINATED");
        assertThat(jdbcTemplate.queryForObject(
                "select terminated_reason from exam_attempts where id = ?", String.class, attemptId))
                .isEqualTo("PROCTOR_WARNING_LIMIT_REACHED");
        assertThat(accessStatus("enf-student-1", EXAM)).isEqualTo("SUSPENDED");
        assertThat(jdbcTemplate.queryForObject(
                "select suspended_reason from exam_access_state where student_id = ? and exam_id = ?",
                String.class, "enf-student-1", EXAM)).isEqualTo("PROCTOR_WARNING_LIMIT_REACHED");
    }

    @Test
    void warningCountNeverExceedsThree() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String teacherToken = login("enf-teacher@example.com", "teacher123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF"));
        assertThat(attemptStatus(attemptId)).isEqualTo("PROCTOR_TERMINATED");

        MvcResult result = postEvents(teacherToken, batch(attemptId, "MULTIPLE_FACES"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(warningCount(attemptId)).isEqualTo(3);
        assertThat(attemptStatus(attemptId)).isEqualTo("PROCTOR_TERMINATED");
    }

    @Test
    void studentEventsAfterTerminationAreRejected() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF"));

        MvcResult result = postEvents(studentToken, batch(attemptId, "AUDIO_DETECTED"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(warningCount(attemptId)).isEqualTo(3);
    }

    @Test
    void terminatedAttemptCannotBeSubmitted() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF"));

        MvcResult result = mockMvc.perform(post("/api/exams/" + EXAM + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + QUESTION + "\",\"value\":\"A\"}]}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(attemptStatus(attemptId)).isEqualTo("PROCTOR_TERMINATED");
        assertThat(count("select count(*) from results where exam_id = ? and user_id = ?", EXAM, "enf-student-1")).isZero();
        assertThat(count("select count(*) from exam_attempts where exam_id = ? and student_id = ?", EXAM, "enf-student-1")).isEqualTo(1);
    }

    @Test
    void startBlockedWhileSuspended() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF"));

        MvcResult result = mockMvc.perform(post("/api/exams/" + EXAM + "/start")
                        .header("Authorization", bearer(studentToken)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("select count(*) from exam_attempts where exam_id = ? and student_id = ?", EXAM, "enf-student-1")).isEqualTo(1);
    }

    @Test
    void suspendedStudentCanRequestRetestWithoutResult() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF"));

        mockMvc.perform(post("/api/retest-requests/" + EXAM)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        assertThat(accessStatus("enf-student-1", EXAM)).isEqualTo("RETEST_PENDING");
    }

    @Test
    void startBlockedWhileRetestPending() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF"));
        String requestId = requestRetest(studentToken);

        MvcResult result = mockMvc.perform(post("/api/exams/" + EXAM + "/start")
                        .header("Authorization", bearer(studentToken)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(accessStatus("enf-student-1", EXAM)).isEqualTo("RETEST_PENDING");
    }

    @Test
    void retestApprovalRestoresStartAccess() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String adminToken = login("enf-admin@example.com", "admin123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF"));
        String requestId = requestRetest(studentToken);

        reviewRetest(adminToken, requestId, "APPROVED");
        assertThat(accessStatus("enf-student-1", EXAM)).isEqualTo("RETEST_APPROVED");

        String teacherToken = login("enf-teacher@example.com", "teacher123");
        RoomTestSupport.createJoinAndStartRoom(mockMvc, objectMapper, teacherToken, EXAM, studentToken);

        MvcResult result = mockMvc.perform(post("/api/exams/" + EXAM + "/start")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andReturn();

        String newAttemptId = objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/attemptId").asText();
        assertThat(newAttemptId).isNotBlank().isNotEqualTo(attemptId);
        assertThat(attemptNumber(newAttemptId)).isEqualTo(2);
        assertThat(attemptStatus(newAttemptId)).isEqualTo("STARTED");
        assertThat(accessStatus("enf-student-1", EXAM)).isEqualTo("ELIGIBLE");
    }

    @Test
    void retestRejectionKeepsExamBlocked() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String adminToken = login("enf-admin@example.com", "admin123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF"));
        String requestId = requestRetest(studentToken);

        reviewRetest(adminToken, requestId, "REJECTED");
        assertThat(accessStatus("enf-student-1", EXAM)).isEqualTo("RETEST_REJECTED");

        MvcResult result = mockMvc.perform(post("/api/exams/" + EXAM + "/start")
                        .header("Authorization", bearer(studentToken)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void rejectedStudentMayRequestRetestAgain() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String adminToken = login("enf-admin@example.com", "admin123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF"));
        String requestId = requestRetest(studentToken);
        reviewRetest(adminToken, requestId, "REJECTED");

        mockMvc.perform(post("/api/retest-requests/" + EXAM)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        assertThat(accessStatus("enf-student-1", EXAM)).isEqualTo("RETEST_PENDING");
    }

    @Test
    void normalCompletedExamRetestUnaffected() throws Exception {
        String studentToken = login("enf-student2@example.com", "student234");
        String adminToken = login("enf-admin@example.com", "admin123");

        String firstAttemptId = startExam(studentToken);
        mockMvc.perform(post("/api/exams/" + EXAM + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + QUESTION + "\",\"value\":\"A\"}]}"))
                .andExpect(status().isOk());

        String requestId = requestRetest(studentToken);
        assertThat(accessStatus("enf-student-2", EXAM)).isNull();

        reviewRetest(adminToken, requestId, "APPROVED");
        assertThat(accessStatus("enf-student-2", EXAM)).isNull();

        String secondAttemptId = startExam(studentToken);
        assertThat(secondAttemptId).isNotEqualTo(firstAttemptId);
        assertThat(attemptNumber(secondAttemptId)).isEqualTo(2);
        assertThat(count("select count(*) from exam_attempts where exam_id = ? and student_id = ?", EXAM, "enf-student-2")).isEqualTo(2);
    }

    @Test
    void freshStudentHasNoAccessRestriction() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");

        String attemptId = startExam(studentToken);

        assertThat(accessStatus("enf-student-1", EXAM)).isNull();
        assertThat(warningCount(attemptId)).isZero();
        assertThat(attemptStatus(attemptId)).isEqualTo("STARTED");
    }

    @Test
    void concurrentWarningsAtLimitProduceSingleTermination() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR"));
        assertThat(warningCount(attemptId)).isEqualTo(2);

        String first = singleEventBatch("race-a", attemptId, "CAMERA_OFF");
        String second = singleEventBatch("race-b", attemptId, "MULTIPLE_FACES");
        List<MvcResult> results = runConcurrently(2, index -> postEvents(studentToken, index == 0 ? first : second));

        Set<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).collect(Collectors.toSet());
        assertThat(statuses).contains(200).doesNotContain(500);

        assertThat(warningCount(attemptId))
                .as("bodies=" + bodies(results))
                .isEqualTo(3);
        assertThat(count("select count(*) from exam_attempts where id = ? and status = 'PROCTOR_TERMINATED'", attemptId)).isEqualTo(1);
        assertThat(accessStatus("enf-student-1", EXAM)).isEqualTo("SUSPENDED");
    }

    @Test
    void concurrentDuplicateEventIncrementsOnce() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        String body = singleEventBatch("dup-race-1", attemptId, "TAB_SWITCH");

        List<MvcResult> results = runConcurrently(2, index -> postEvents(studentToken, body));

        Set<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).collect(Collectors.toSet());
        assertThat(statuses).containsExactly(200);

        int saved = 0;
        for (MvcResult result : results) {
            saved += objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/saved").asInt();
        }
        assertThat(saved).as("bodies=" + bodies(results)).isEqualTo(1);
        assertThat(warningCount(attemptId)).isEqualTo(1);
        assertThat(count("select count(*) from proctor_events where attempt_id = ? and event_id = 'dup-race-1'", attemptId)).isEqualTo(1);
    }

    @Test
    void oversizedBatchStopsExactlyAtLimit() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        MvcResult result = postEvents(studentToken,
                batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF", "MULTIPLE_FACES"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(warningCount(attemptId)).isEqualTo(3);
        assertThat(attemptStatus(attemptId)).isEqualTo("PROCTOR_TERMINATED");
        assertThat(accessStatus("enf-student-1", EXAM)).isEqualTo("SUSPENDED");
    }

    @Test
    void warningUpdateCarriesAuthoritativeState() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        StompSession session = connectTeacher();
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);
        try {
            postEvents(studentToken, batch(attemptId, "TAB_SWITCH"));

            ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
            assertThat(update).isNotNull();
            assertThat(update.examId()).isEqualTo(EXAM);
            assertThat(update.attemptId()).isEqualTo(attemptId);
            assertThat(update.status()).isEqualTo("STARTED");
            assertThat(update.warningCount()).isEqualTo(1);
            assertThat(update.warningLevel()).isEqualTo(com.examora.dto.ProctorDtos.WarningLevel.WARNING_1);
            assertThat(update.accessStatus()).isEqualTo(ExamAccessStatus.ELIGIBLE);
            assertThat(update.latestEvent()).isNotNull();
            assertThat(update.sequence()).isPositive();
        } finally {
            disconnectQuietly(session);
        }
    }

    @Test
    void terminationUpdateCarriesSuspendedState() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        StompSession session = connectTeacher();
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);
        try {
            postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF"));

            ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
            assertThat(update).isNotNull();
            assertThat(update.status()).isEqualTo("PROCTOR_TERMINATED");
            assertThat(update.warningCount()).isEqualTo(3);
            assertThat(update.warningLevel()).isEqualTo(com.examora.dto.ProctorDtos.WarningLevel.WARNING_3);
            assertThat(update.accessStatus()).isEqualTo(ExamAccessStatus.SUSPENDED);
        } finally {
            disconnectQuietly(session);
        }
    }

    @Test
    void updateDoesNotLeakQuestionsAnswersOrScores() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        StompSession session = connectTeacher();
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);
        try {
            postEvents(studentToken, batch(attemptId, "TAB_SWITCH"));

            ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
            assertThat(update).isNotNull();
            String json = objectMapper.writeValueAsString(update);
            assertThat(json).doesNotContain("question", "option", "answer", "score", "result", "correct");
        } finally {
            disconnectQuietly(session);
        }
    }

    @Test
    void nonOwnerTeacherCannotTriggerEnforcement() throws Exception {
        String otherTeacherToken = login("enf-teacher2@example.com", "teacher234");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        MvcResult result = postEvents(otherTeacherToken, batch(attemptId, "TAB_SWITCH"));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(warningCount(attemptId)).isZero();
    }

    @Test
    void anonymousEventSubmissionRejected() throws Exception {
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        MvcResult result = mockMvc.perform(post("/api/proctor/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch(attemptId, "TAB_SWITCH")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(warningCount(attemptId)).isZero();
    }

    @Test
    void studentCannotReportForAnotherStudent() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-2", "STARTED");

        MvcResult result = postEvents(studentToken, batch(attemptId, "TAB_SWITCH"));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(warningCount(attemptId)).isZero();
    }

    @Test
    void studentStatusEndpointReflectsWarnings() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR"));

        mockMvc.perform(get("/api/exams/" + EXAM + "/attempt/status")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.examId").value(EXAM))
                .andExpect(jsonPath("$.data.attemptId").value(attemptId))
                .andExpect(jsonPath("$.data.attemptStatus").value("STARTED"))
                .andExpect(jsonPath("$.data.warningCount").value(2))
                .andExpect(jsonPath("$.data.warningLevel").value("WARNING_2"))
                .andExpect(jsonPath("$.data.accessStatus").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data.hasResult").value(false));
    }

    @Test
    void studentStatusEndpointReportsTerminationAndSuspension() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");
        postEvents(studentToken, batch(attemptId, "TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF"));

        mockMvc.perform(get("/api/exams/" + EXAM + "/attempt/status")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptStatus").value("PROCTOR_TERMINATED"))
                .andExpect(jsonPath("$.data.warningCount").value(3))
                .andExpect(jsonPath("$.data.warningLevel").value("WARNING_3"))
                .andExpect(jsonPath("$.data.accessStatus").value("SUSPENDED"));
    }

    @Test
    void studentStatusEndpointIsNeutralForFreshStudent() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");

        mockMvc.perform(get("/api/exams/" + EXAM + "/attempt/status")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptId").doesNotExist())
                .andExpect(jsonPath("$.data.attemptStatus").doesNotExist())
                .andExpect(jsonPath("$.data.warningCount").value(0))
                .andExpect(jsonPath("$.data.warningLevel").value("NONE"))
                .andExpect(jsonPath("$.data.accessStatus").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data.hasResult").value(false));
    }

    @Test
    void teacherCannotReadStudentAttemptStatus() throws Exception {
        String teacherToken = login("enf-teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/exams/" + EXAM + "/attempt/status")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentReceivesOwnProctorUpdateOnUserQueue() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        StompSession session = connectStudent1();
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/proctor", proctorUpdateHandler(updates));
        Thread.sleep(300);
        try {
            postEvents(studentToken, batch(attemptId, "TAB_SWITCH"));

            ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
            assertThat(update).isNotNull();
            assertThat(update.examId()).isEqualTo(EXAM);
            assertThat(update.attemptId()).isEqualTo(attemptId);
            assertThat(update.warningCount()).isEqualTo(1);
            assertThat(update.student().id()).isEqualTo("enf-student-1");
        } finally {
            disconnectQuietly(session);
        }
    }

    @Test
    void studentDoesNotReceiveAnotherStudentsUpdates() throws Exception {
        String studentToken = login("enf-student@example.com", "student123");
        String attemptId = insertAttempt(EXAM, "enf-student-1", "STARTED");

        StompSession session = connectStudent2();
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/proctor", proctorUpdateHandler(updates));
        Thread.sleep(300);
        try {
            postEvents(studentToken, batch(attemptId, "TAB_SWITCH"));

            assertThat(updates.poll(1500, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            disconnectQuietly(session);
        }
    }

    private List<String> bodies(List<MvcResult> results) {
        List<String> bodies = new ArrayList<>();
        for (MvcResult result : results) {
            try {
                bodies.add(result.getResponse().getStatus() + ":" + result.getResponse().getContentAsString());
            } catch (Exception exception) {
                bodies.add(result.getResponse().getStatus() + ":<unreadable>");
            }
        }
        return bodies;
    }

    private List<MvcResult> runConcurrently(int count, ConcurrentCall call) throws Exception {        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger();
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return call.perform(counter.incrementAndGet() - 1);
            }));
        }
        ready.await();
        go.countDown();
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdownNow();
        return results;
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
            events.add(singleEvent(java.util.UUID.randomUUID().toString(), attemptId, type));
        }
        return wrap(events);
    }

    private String singleEventBatch(String eventId, String attemptId, String type) {
        return wrap(List.of(singleEvent(eventId, attemptId, type)));
    }

    private String wrap(List<String> events) {
        return "{\"events\":[" + String.join(",", events) + "]}";
    }

    private String singleEvent(String eventId, String attemptId, String type) {
        return "{\"eventId\":\"" + eventId + "\",\"attemptId\":\"" + attemptId
                + "\",\"type\":\"" + type + "\",\"occurredAt\":\"" + Instant.now() + "\"}";
    }

    private String startExam(String token) throws Exception {
        RoomTestSupport.createJoinAndStartRoom(mockMvc, objectMapper, login("enf-teacher@example.com", "teacher123"),
                EXAM, token);
        MvcResult result = mockMvc.perform(post("/api/exams/" + EXAM + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        String attemptId = objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/attemptId").asText();
        assertThat(attemptId).isNotBlank();
        return attemptId;
    }

    private String requestRetest(String studentToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/retest-requests/" + EXAM)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andReturn();
        String requestId = objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/id").asText();
        assertThat(requestId).isNotBlank();
        return requestId;
    }

    private void reviewRetest(String adminToken, String requestId, String status) throws Exception {
        mockMvc.perform(post("/api/retest-requests/admin/" + requestId + "/review")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(status));
    }

    private int warningCount(String attemptId) {
        return jdbcTemplate.queryForObject("select warning_count from exam_attempts where id = ?", Integer.class, attemptId);
    }

    private String attemptStatus(String attemptId) {
        return jdbcTemplate.queryForObject("select status from exam_attempts where id = ?", String.class, attemptId);
    }

    private int attemptNumber(String attemptId) {
        return jdbcTemplate.queryForObject("select attempt_number from exam_attempts where id = ?", Integer.class, attemptId);
    }

    private String accessStatus(String studentId, String examId) {
        List<String> statuses = jdbcTemplate.query(
                "select status from exam_access_state where student_id = ? and exam_id = ?",
                (rs, row) -> rs.getString("status"), studentId, examId);
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
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

    private String insertAttempt(String examId, String studentId, String status) {
        String id = "enf-attempt-" + System.nanoTime();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) "
                        + "values (?, ?, ?, 1, ?, ?, ?, 0)",
                id, examId, studentId, status, Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)));
        return id;
    }

    private StompSession connectTeacher() throws Exception {
        return connectAs(new User("enf-teacher-1", "Enforcement Teacher One", "enf-teacher@example.com", Role.TEACHER, null));
    }

    private StompSession connectStudent1() throws Exception {
        return connectAs(new User("enf-student-1", "Enforcement Student One", "enf-student@example.com", Role.STUDENT, null));
    }

    private StompSession connectStudent2() throws Exception {
        return connectAs(new User("enf-student-2", "Enforcement Student Two", "enf-student2@example.com", Role.STUDENT, null));
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

    private interface ConcurrentCall {
        MvcResult perform(int index) throws Exception;
    }
}