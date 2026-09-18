package com.examora;

import com.examora.model.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_room_gate;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-room-gate-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class RoomRequiredAttemptIntegrationTest {
    private static final String EXAM_1 = "room-gate-exam-1";
    private static final String EXAM_2 = "room-gate-exam-2";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

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
        jdbcTemplate.update("delete from exam_access_state");
        jdbcTemplate.update("delete from retest_requests");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("gate-student-1", "Gate Student One", "gate-student@example.com", "student123", Role.STUDENT);
        insertUser("gate-student-2", "Gate Student Two", "gate-student2@example.com", "student234", Role.STUDENT);
        insertUser("gate-teacher-1", "Gate Teacher One", "gate-teacher@example.com", "teacher123", Role.TEACHER);
        insertUser("gate-admin-1", "Gate Admin One", "gate-admin@example.com", "admin123", Role.ADMIN);
        insertExam(EXAM_1, "Gate Math Exam", "gate-teacher-1");
        insertExam(EXAM_2, "Gate Physics Exam", "gate-teacher-1");
    }

    @Test
    void startWithoutActiveRoomIsForbidden() throws Exception {
        String studentToken = login("gate-student@example.com", "student123");

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This exam must be started through an active exam room."));

        assertThat(attemptCount(EXAM_1, "gate-student-1")).isZero();
    }

    @Test
    void waitingRoomDoesNotPermitStart() throws Exception {
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentToken = login("gate-student@example.com", "student123");
        RoomTestSupport.Room room = RoomTestSupport.createRoom(mockMvc, objectMapper, teacherToken, EXAM_1);
        RoomTestSupport.joinRoom(mockMvc, studentToken, room.code());

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isForbidden());

        assertThat(attemptCount(EXAM_1, "gate-student-1")).isZero();
    }

    @Test
    void activeRoomPermitsStart() throws Exception {
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentToken = login("gate-student@example.com", "student123");
        RoomTestSupport.createJoinAndStartRoom(mockMvc, objectMapper, teacherToken, EXAM_1, studentToken);

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptId").isNotEmpty());

        assertThat(attemptCount(EXAM_1, "gate-student-1")).isEqualTo(1);
    }

    @Test
    void endedRoomDoesNotPermitStart() throws Exception {
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentToken = login("gate-student@example.com", "student123");
        RoomTestSupport.Room room = RoomTestSupport.createRoom(mockMvc, objectMapper, teacherToken, EXAM_1);
        RoomTestSupport.joinRoom(mockMvc, studentToken, room.code());
        RoomTestSupport.startRoom(mockMvc, teacherToken, room.id());
        RoomTestSupport.endRoom(mockMvc, teacherToken, room.id());

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isForbidden());

        assertThat(attemptCount(EXAM_1, "gate-student-1")).isZero();
    }

    @Test
    void activeRoomForAnotherExamDoesNotPermitStart() throws Exception {
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentToken = login("gate-student@example.com", "student123");
        RoomTestSupport.createJoinAndStartRoom(mockMvc, objectMapper, teacherToken, EXAM_2, studentToken);

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isForbidden());

        assertThat(attemptCount(EXAM_1, "gate-student-1")).isZero();
    }

    @Test
    void activeRoomJoinedByAnotherStudentDoesNotPermitStart() throws Exception {
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentOneToken = login("gate-student@example.com", "student123");
        String studentTwoToken = login("gate-student2@example.com", "student234");
        RoomTestSupport.createJoinAndStartRoom(mockMvc, objectMapper, teacherToken, EXAM_1, studentTwoToken);

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentOneToken)))
                .andExpect(status().isForbidden());

        assertThat(attemptCount(EXAM_1, "gate-student-1")).isZero();
    }

    @Test
    void resumeOfExistingActiveAttemptDoesNotRequireRoom() throws Exception {
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentToken = login("gate-student@example.com", "student123");
        RoomTestSupport.Room room = RoomTestSupport.createRoom(mockMvc, objectMapper, teacherToken, EXAM_1);
        RoomTestSupport.joinRoom(mockMvc, studentToken, room.code());
        RoomTestSupport.startRoom(mockMvc, teacherToken, room.id());

        String firstResponse = mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String attemptId = objectMapper.readTree(firstResponse).path("data").path("attemptId").asText();
        assertThat(attemptId).isNotBlank();

        RoomTestSupport.endRoom(mockMvc, teacherToken, room.id());

        String secondResponse = mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String resumedAttemptId = objectMapper.readTree(secondResponse).path("data").path("attemptId").asText();
        assertThat(resumedAttemptId).isEqualTo(attemptId);
        assertThat(attemptCount(EXAM_1, "gate-student-1")).isEqualTo(1);
    }

    @Test
    void suspendedStudentIsRejectedEvenWithActiveRoom() throws Exception {
        setAccess(EXAM_1, "gate-student-1", "SUSPENDED");
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentToken = login("gate-student@example.com", "student123");
        RoomTestSupport.createJoinAndStartRoom(mockMvc, objectMapper, teacherToken, EXAM_1, studentToken);

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isConflict());

        assertThat(attemptCount(EXAM_1, "gate-student-1")).isZero();
    }

    @Test
    void retestPendingIsRejectedEvenWithActiveRoom() throws Exception {
        setAccess(EXAM_1, "gate-student-1", "RETEST_PENDING");
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentToken = login("gate-student@example.com", "student123");
        RoomTestSupport.createJoinAndStartRoom(mockMvc, objectMapper, teacherToken, EXAM_1, studentToken);

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isConflict());

        assertThat(attemptCount(EXAM_1, "gate-student-1")).isZero();
    }

    @Test
    void retestRejectedIsRejectedEvenWithActiveRoom() throws Exception {
        setAccess(EXAM_1, "gate-student-1", "RETEST_REJECTED");
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentToken = login("gate-student@example.com", "student123");
        RoomTestSupport.createJoinAndStartRoom(mockMvc, objectMapper, teacherToken, EXAM_1, studentToken);

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isConflict());

        assertThat(attemptCount(EXAM_1, "gate-student-1")).isZero();
    }

    @Test
    void retestApprovalStartsWithActiveRoom() throws Exception {
        setAccess(EXAM_1, "gate-student-1", "SUSPENDED");
        String studentToken = login("gate-student@example.com", "student123");
        String adminToken = login("gate-admin@example.com", "admin123");

        String requestResponse = mockMvc.perform(post("/api/retest-requests/" + EXAM_1)
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String requestId = objectMapper.readTree(requestResponse).path("data").path("id").asText();
        assertThat(requestId).isNotBlank();

        mockMvc.perform(post("/api/retest-requests/admin/" + requestId + "/review")
                        .header("Authorization", RoomTestSupport.bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        String teacherToken = login("gate-teacher@example.com", "teacher123");
        RoomTestSupport.createJoinAndStartRoom(mockMvc, objectMapper, teacherToken, EXAM_1, studentToken);

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptId").isNotEmpty());

        assertThat(accessStatus(EXAM_1, "gate-student-1")).isEqualTo("ELIGIBLE");
        assertThat(attemptCount(EXAM_1, "gate-student-1")).isEqualTo(1);
    }

    @Test
    void retestApprovalWithoutRoomIsStillRejected() throws Exception {
        setAccess(EXAM_1, "gate-student-1", "RETEST_APPROVED");
        String studentToken = login("gate-student@example.com", "student123");

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isForbidden());

        assertThat(attemptCount(EXAM_1, "gate-student-1")).isZero();
    }

    @Test
    void leftMembershipDoesNotPermitStart() throws Exception {
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentToken = login("gate-student@example.com", "student123");
        RoomTestSupport.Room room = RoomTestSupport.createRoom(mockMvc, objectMapper, teacherToken, EXAM_1);
        RoomTestSupport.joinRoom(mockMvc, studentToken, room.code());
        RoomTestSupport.startRoom(mockMvc, teacherToken, room.id());
        jdbcTemplate.update(
                "update exam_room_members set status = 'LEFT' where room_id = ? and student_id = ?",
                room.id(), "gate-student-1");

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isForbidden());

        assertThat(attemptCount(EXAM_1, "gate-student-1")).isZero();
    }

    @Test
    void twoStudentsShareActiveRoomButStartOwnAttempts() throws Exception {
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentOneToken = login("gate-student@example.com", "student123");
        String studentTwoToken = login("gate-student2@example.com", "student234");
        RoomTestSupport.Room room = RoomTestSupport.createRoom(mockMvc, objectMapper, teacherToken, EXAM_1);
        RoomTestSupport.joinRoom(mockMvc, studentOneToken, room.code());
        RoomTestSupport.joinRoom(mockMvc, studentTwoToken, room.code());
        RoomTestSupport.startRoom(mockMvc, teacherToken, room.id());

        String first = mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentOneToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(studentTwoToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String firstAttempt = objectMapper.readTree(first).path("data").path("attemptId").asText();
        String secondAttempt = objectMapper.readTree(second).path("data").path("attemptId").asText();
        assertThat(firstAttempt).isNotBlank();
        assertThat(secondAttempt).isNotBlank();
        assertThat(firstAttempt).isNotEqualTo(secondAttempt);
    }

    @Test
    void teacherAndAnonymousStartRemainRejectedBeforeRoomCheck() throws Exception {
        String teacherToken = login("gate-teacher@example.com", "teacher123");

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start")
                        .header("Authorization", RoomTestSupport.bearer(teacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/exams/" + EXAM_1 + "/start"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roomEndingDoesNotCreateAttempts() throws Exception {
        String teacherToken = login("gate-teacher@example.com", "teacher123");
        String studentToken = login("gate-student@example.com", "student123");
        RoomTestSupport.Room room = RoomTestSupport.createRoom(mockMvc, objectMapper, teacherToken, EXAM_1);
        RoomTestSupport.joinRoom(mockMvc, studentToken, room.code());
        RoomTestSupport.startRoom(mockMvc, teacherToken, room.id());
        RoomTestSupport.endRoom(mockMvc, teacherToken, room.id());

        mockMvc.perform(get("/api/exams/attempts/active")
                        .header("Authorization", RoomTestSupport.bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(attemptCount(EXAM_1, "gate-student-1")).isZero();
    }

    private void setAccess(String examId, String studentId, String status) {
        jdbcTemplate.update("insert into exam_access_state (student_id, exam_id, status) values (?, ?, ?)",
                studentId, examId, status);
    }

    private String accessStatus(String examId, String studentId) {
        return jdbcTemplate.queryForObject(
                "select status from exam_access_state where student_id = ? and exam_id = ?",
                String.class, studentId, examId);
    }

    private int attemptCount(String examId, String studentId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from exam_attempts where exam_id = ? and student_id = ?",
                Integer.class, examId, studentId);
        return count == null ? 0 : count;
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(response).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private void insertUser(String id, String name, String email, String password, Role role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role.name());
    }

    private void insertExam(String id, String title, String createdBy) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, 'Math', '2026-09-15', 60, 'PUBLISHED', 0, null, ?)",
                id, title, createdBy);
    }
}