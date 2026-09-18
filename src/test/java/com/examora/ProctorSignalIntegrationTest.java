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
import java.util.UUID;
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
        "spring.datasource.url=jdbc:h2:mem:examora_signal;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-proctor-signal-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24",
        "examora.login.max-per-ip=1000",
        "examora.login.max-per-ip-email=1000"
})
class ProctorSignalIntegrationTest {

    private static final String EXAM = "sig-exam-1";
    private static final String QUESTION = "sig-q-1";
    private static final Set<String> FUTURE_AI_TYPES =
            Set.of("FACE_COUNT_ANOMALY", "PHONE_DETECTED", "UNKNOWN_OBJECT", "GAZE_ANOMALY", "HEAD_POSE_ANOMALY");

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

        insertUser("sig-student-1", "Signal Student One", "sig-student@example.com", "student123", Role.STUDENT);
        insertUser("sig-student-2", "Signal Student Two", "sig-student2@example.com", "student234", Role.STUDENT);
        insertUser("sig-teacher-1", "Signal Teacher One", "sig-teacher@example.com", "teacher123", Role.TEACHER);
        insertUser("sig-admin-1", "Signal Admin One", "sig-admin@example.com", "admin123", Role.ADMIN);

        insertExam(EXAM, "Signal Exam", "sig-teacher-1");
        insertQuestion(QUESTION, EXAM, "Pick A");
        insertOption("sig-opt-a", QUESTION, "A", true);
        insertOption("sig-opt-b", QUESTION, "B", false);
    }

    @Test
    void browserSignalIsAccepted() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");

        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-1", "TAB_SWITCH", "BROWSER", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
    }

    @Test
    void aiSignalWithConfidenceAndDurationIsAccepted() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");

        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-2", "FACE_COUNT_ANOMALY", "CLIENT_AI", "0.92", "1800", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
    }

    @Test
    void aiSignalWithoutConfidenceIsRejected() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");

        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-3", "FACE_COUNT_ANOMALY", "CLIENT_AI", null, null, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confidenceZeroIsAccepted() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-4", "GAZE_ANOMALY", "CLIENT_AI", "0", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
    }

    @Test
    void confidenceOneIsAccepted() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-5", "GAZE_ANOMALY", "CLIENT_AI", "1", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
    }

    @Test
    void confidenceBelowZeroIsRejected() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-6", "GAZE_ANOMALY", "CLIENT_AI", "-0.1", null, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confidenceAboveOneIsRejected() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-7", "GAZE_ANOMALY", "CLIENT_AI", "1.5", null, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confidenceNaNIsRejected() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-8", "GAZE_ANOMALY", "CLIENT_AI", "NaN", null, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confidenceInfinityIsRejected() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-9", "GAZE_ANOMALY", "CLIENT_AI", "1e999", null, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousSignalIsRejected() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-10", "TAB_SWITCH", "BROWSER", null, null, null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void crossStudentSignalIsRejected() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String otherToken = login("sig-student2@example.com", "student234");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-11", "TAB_SWITCH", "BROWSER", null, null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownAttemptSignalIsRejected() throws Exception {
        String token = login("sig-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/attempts/does-not-exist/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-12", "TAB_SWITCH", "BROWSER", null, null, null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void signalForSubmittedAttemptIsRejected() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "SUBMITTED", 0);
        String token = login("sig-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-13", "TAB_SWITCH", "BROWSER", null, null, null)))
                .andExpect(status().isConflict());
    }

    @Test
    void signalForExpiredAttemptIsRejectedAndMarkerPersisted() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "EXPIRED", 0);
        String token = login("sig-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-14", "TAB_SWITCH", "BROWSER", null, null, null)))
                .andExpect(status().isConflict());
        assertThat(attemptStatus(attemptId)).isEqualTo("EXPIRED");
    }

    @Test
    void duplicateSignalIdIsDeduplicated() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        String body = signal("sig-15", "TAB_SWITCH", "BROWSER", null, null, null);

        postSignal(token, attemptId, body).getResponse().getStatus();
        MvcResult second = postSignal(token, attemptId, body);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(second.getResponse().getContentAsString()).at("/data/saved").asInt()).isEqualTo(0);
        assertThat(warningCount(attemptId)).isEqualTo(1);
        assertThat(count("select count(*) from proctor_events where attempt_id = ? and event_id = 'sig-15'", attemptId))
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateIncrementsWarningOnce() throws Exception {
        String token = login("sig-student@example.com", "student123");
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String body = signal("sig-race-1", "TAB_SWITCH", "BROWSER", null, null, null);

        List<MvcResult> results = runConcurrently(2, index -> postSignal(token, attemptId, body));

        Set<Integer> statuses = results.stream()
                .map(result -> result.getResponse().getStatus()).collect(Collectors.toSet());
        assertThat(statuses).containsExactly(200);

        int saved = 0;
        for (MvcResult result : results) {
            saved += objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/saved").asInt();
        }
        assertThat(saved).isEqualTo(1);
        assertThat(warningCount(attemptId)).isEqualTo(1);
        assertThat(count("select count(*) from proctor_events where attempt_id = ? and event_id = 'sig-race-1'", attemptId))
                .isEqualTo(1);
    }

    @Test
    void oversizedMetadataIsRejected() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        String large = "{\"note\":\"" + "X".repeat(3000) + "\"}";
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-16", "TAB_SWITCH", "BROWSER", null, null, large)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mediaPayloadInMetadataIsRejected() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        String media = "{\"visual\":\"data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==\"}";
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-17", "TAB_SWITCH", "BROWSER", null, null, media)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reservedMetadataFieldIsRejected() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        String reserved = "{\"frame\":\"metadata-frame-tag\"}";
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-18", "TAB_SWITCH", "BROWSER", null, null, reserved)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void studentCannotUseServerSideSource() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-19", "TAB_SWITCH", "SERVER_AI", "0.9", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanSubmitAnySource() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String adminToken = login("sig-admin@example.com", "admin123");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-20", "AUDIO_DETECTED", "HUMAN_INVIGILATOR", null, "2500", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(1));
    }

    @Test
    void serverDerivesAttemptIdentityAndPersistsSignalFields() throws Exception {
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        String token = login("sig-student@example.com", "student123");
        mockMvc.perform(post("/api/proctor/attempts/" + attemptId + "/signals")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signal("sig-21", "PHONE_DETECTED", "CLIENT_AI", "0.77", "900", null)))
                .andExpect(status().isOk());

        List<JsonNode> rows = jdbcTemplate.query(
                "select attempt_id, source, confidence, duration_ms from proctor_events where event_id = 'sig-21'",
                (rs, i) -> {
                    double confidence = rs.getDouble("confidence");
                    return objectMapper.createObjectNode()
                            .put("attempt_id", rs.getString("attempt_id"))
                            .put("source", rs.getString("source"))
                            .put("confidence", confidence)
                            .put("duration_ms", rs.getLong("duration_ms"));
                });
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("attempt_id").asText()).isEqualTo(attemptId);
        assertThat(rows.get(0).path("source").asText()).isEqualTo("CLIENT_AI");
        assertThat(rows.get(0).path("confidence").asDouble()).isEqualTo(0.77);
        assertThat(rows.get(0).path("duration_ms").asLong()).isEqualTo(900);
    }

    @Test
    void signalAppearsInCommandCenter() throws Exception {
        String teacherToken = login("sig-teacher@example.com", "teacher123");
        String studentToken = login("sig-student@example.com", "student123");
        insertRoom("room-1a", EXAM, "SIG11111", "ACTIVE", true);
        joinMember("mem-1", "room-1a", "sig-student-1");
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);

        postSignal(studentToken, attemptId, signal("sig-22", "FACE_COUNT_ANOMALY", "CLIENT_AI", "0.88", null, null));

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roster[0].eventCount").value(1))
                .andExpect(jsonPath("$.data.roster[0].latestProctorEvent.type").value("FACE_COUNT_ANOMALY"))
                .andExpect(jsonPath("$.data.roster[0].latestProctorEvent.source").value("CLIENT_AI"))
                .andExpect(jsonPath("$.data.roster[0].latestProctorEvent.confidence").value(0.88))
                .andExpect(jsonPath("$.data.roster[0].riskLevel").value("LOW"));
    }

    @Test
    void futureAiSignalsDoNotChangeRisk() throws Exception {
        String teacherToken = login("sig-teacher@example.com", "teacher123");
        String studentToken = login("sig-student@example.com", "student123");
        insertRoom("room-1a", EXAM, "SIG22222", "ACTIVE", true);
        joinMember("mem-1", "room-1a", "sig-student-1");
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);

        for (String type : FUTURE_AI_TYPES) {
            postSignal(studentToken, attemptId, signal("sig-fai-" + type, type, "CLIENT_AI", "0.5", null, null));
        }

        mockMvc.perform(get("/api/proctor/exams/" + EXAM + "/command-center")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roster[0].eventCount").value(5))
                .andExpect(jsonPath("$.data.roster[0].riskScore").value(0.0))
                .andExpect(jsonPath("$.data.roster[0].riskLevel").value("LOW"))
                .andExpect(jsonPath("$.data.roster[0].warningCount").value(0));
    }

    @Test
    void futureAiSignalsDoNotTerminate() throws Exception {
        String studentToken = login("sig-student@example.com", "student123");
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);

        for (String type : FUTURE_AI_TYPES) {
            postSignal(studentToken, attemptId, signal("sig-fai2-" + type, type, "CLIENT_AI", "0.5", null, null));
        }

        assertThat(attemptStatus(attemptId)).isEqualTo("STARTED");
        assertThat(warningCount(attemptId)).isEqualTo(0);
        assertThat(accessStatus("sig-student-1", EXAM)).isEqualTo("ELIGIBLE");
    }

    @Test
    void qualifyingSignalStillCountsTowardWarningLadder() throws Exception {
        String studentToken = login("sig-student@example.com", "student123");
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);

        postSignal(studentToken, attemptId, signal("sig-23", "TAB_SWITCH", "BROWSER", null, null, null));
        postSignal(studentToken, attemptId, signal("sig-24", "WINDOW_BLUR", "BROWSER", null, null, null));
        postSignal(studentToken, attemptId, signal("sig-25", "CAMERA_OFF", "BROWSER", null, null, null));

        assertThat(attemptStatus(attemptId)).isEqualTo("PROCTOR_TERMINATED");
        assertThat(warningCount(attemptId)).isEqualTo(3);
        assertThat(accessStatus("sig-student-1", EXAM)).isEqualTo("SUSPENDED");
    }

    @Test
    void batchEventsDefaultToBrowserSource() throws Exception {
        String studentToken = login("sig-student@example.com", "student123");
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);
        mockMvc.perform(post("/api/proctor/events/batch")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[{\"eventId\":\"sig-batch-1\",\"attemptId\":\"" + attemptId
                                + "\",\"type\":\"TAB_SWITCH\",\"occurredAt\":\"2030-01-01T00:00:00Z\"}]}"))
                .andExpect(status().isOk());

        String teacherToken = login("sig-teacher@example.com", "teacher123");
        mockMvc.perform(get("/api/proctor/attempts/" + attemptId + "/events")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("TAB_SWITCH"))
                .andExpect(jsonPath("$.data[0].source").value("BROWSER"));
    }

    @Test
    void websocketUpdateCarriesSignalFields() throws Exception {
        String studentToken = login("sig-student@example.com", "student123");
        String attemptId = insertAttempt("at-1", EXAM, "sig-student-1", "STARTED", 0);

        StompSession session = connectAs(new User("sig-teacher-1", "Signal Teacher One",
                "sig-teacher@example.com", Role.TEACHER, null));
        LinkedBlockingQueue<ProctorUpdate> updates = new LinkedBlockingQueue<>();
        session.subscribe("/topic/exams/" + EXAM + "/activity", proctorUpdateHandler(updates));
        Thread.sleep(300);
        try {
            postSignal(studentToken, attemptId, signal("sig-26", "PHONE_DETECTED", "CLIENT_AI", "0.81", "1200", null));

            ProctorUpdate update = updates.poll(5, TimeUnit.SECONDS);
            assertThat(update).isNotNull();
            assertThat(update.examId()).isEqualTo(EXAM);
            assertThat(update.attemptId()).isEqualTo(attemptId);
            assertThat(update.latestEvent()).isNotNull();
            assertThat(update.latestEvent().type()).isEqualTo("PHONE_DETECTED");
            assertThat(update.latestEvent().source()).isEqualTo("CLIENT_AI");
            assertThat(update.latestEvent().confidence()).isEqualTo(0.81);
            assertThat(update.latestEvent().durationMs()).isEqualTo(1200);
            assertThat(update.sequence()).isPositive();
        } finally {
            disconnectQuietly(session);
        }
    }

    private MvcResult postSignal(String token, String attemptId, String body) throws Exception {
        var builder = post("/api/proctor/attempts/" + attemptId + "/signals")
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) builder.header("Authorization", bearer(token));
        return mockMvc.perform(builder.content(body)).andReturn();
    }

    private String signal(String signalId, String type, String source, String confidence,
                          String durationMs, String metadata) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"signalId\":\"").append(signalId).append("\",");
        sb.append("\"signalType\":\"").append(type).append("\",");
        sb.append("\"source\":\"").append(source).append("\",");
        sb.append("\"occurredAt\":\"2030-01-01T00:00:00Z\"");
        if (confidence != null) sb.append(",\"confidence\":").append(confidence);
        if (durationMs != null) sb.append(",\"durationMs\":").append(durationMs);
        if (metadata != null) sb.append(",\"metadata\":").append(metadata);
        sb.append("}");
        return sb.toString();
    }

    private List<MvcResult> runConcurrently(int count, ConcurrentCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
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

    private int warningCount(String attemptId) {
        Integer count = jdbcTemplate.queryForObject(
                "select warning_count from exam_attempts where id = ?", Integer.class, attemptId);
        return count == null ? 0 : count;
    }

    private String attemptStatus(String attemptId) {
        return jdbcTemplate.queryForObject("select status from exam_attempts where id = ?", String.class, attemptId);
    }

    private String accessStatus(String studentId, String examId) {
        List<String> statuses = jdbcTemplate.query(
                "select status from exam_access_state where student_id = ? and exam_id = ?",
                (rs, i) -> rs.getString("status"), studentId, examId);
        return statuses.isEmpty() ? ExamAccessStatus.ELIGIBLE.name() : statuses.getFirst();
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

    private void insertRoom(String roomId, String examId, String roomCode, String status, boolean active) {
        jdbcTemplate.update(
                "insert into exam_rooms (id, exam_id, room_code, status, created_by, started_at, ended_at) "
                        + "values (?, ?, ?, ?, 'sig-teacher-1', ?, null)",
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