package com.examora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-lifecycle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-lifecycle-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class ExamAttemptLifecycleIntegrationTest {
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

        insertUser("student-1", "Student One", "student@example.com", "student123", "STUDENT");
        insertUser("student-2", "Student Two", "student2@example.com", "student234", "STUDENT");
        insertUser("teacher-1", "Teacher One", "teacher@example.com", "teacher123", "TEACHER");
        insertUser("admin-1", "Admin One", "admin@example.com", "admin123", "ADMIN");
    }

    @Test
    void firstStartCreatesAttemptNumberOne() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");

        String attemptId = startExam(studentToken, examId);

        assertAttempt(examId, "student-1", attemptId, 1, "STARTED");
        assertThat(attemptRows(examId, "student-1")).isEqualTo(1);
        assertThat(answersFor(attemptId)).isEqualTo(0);
    }

    @Test
    void repeatedStartReturnsTheSameActiveAttempt() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");

        String firstAttemptId = startExam(studentToken, examId);
        String secondAttemptId = startExam(studentToken, examId);

        assertThat(secondAttemptId).isEqualTo(firstAttemptId);
        assertThat(attemptRows(examId, "student-1")).isEqualTo(1);
        assertAttempt(examId, "student-1", firstAttemptId, 1, "STARTED");
    }

    @Test
    void concurrentStartResolvesToSingleSharedAttempt() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        RoomTestSupport.createJoinAndStartRoom(mockMvc, objectMapper, teacherToken, examId, studentToken);

        List<MvcResult> results = runConcurrently(2, index -> mockMvc.perform(post("/api/exams/" + examId + "/start")
                        .header("Authorization", bearer(studentToken)))
                .andReturn());

        Set<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).collect(Collectors.toSet());
        assertThat(statuses).contains(200).doesNotContain(500);

        Set<String> attemptIds = new HashSet<>();
        for (MvcResult result : results) {
            attemptIds.add(objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("attemptId").asText());
        }
        assertThat(attemptIds).hasSize(1).doesNotContain("");
        assertThat(attemptRows(examId, "student-1")).isEqualTo(1);
        assertThat(activeCount(examId, "student-1")).isEqualTo(1);
    }

    @Test
    void concurrentStartForTwoStudentsCreatesIndependentAttempts() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentOneToken = login("student@example.com", "student123");
        String studentTwoToken = login("student2@example.com", "student234");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        RoomTestSupport.Room room = RoomTestSupport.createRoom(mockMvc, objectMapper, teacherToken, examId);
        RoomTestSupport.joinRoom(mockMvc, studentOneToken, room.code());
        RoomTestSupport.joinRoom(mockMvc, studentTwoToken, room.code());
        RoomTestSupport.startRoom(mockMvc, teacherToken, room.id());

        List<MvcResult> results = runConcurrently(2, index -> mockMvc.perform(post("/api/exams/" + examId + "/start")
                        .header("Authorization", bearer(index % 2 == 0 ? studentOneToken : studentTwoToken)))
                .andReturn());

        Set<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).collect(Collectors.toSet());
        assertThat(statuses).contains(200).doesNotContain(500);
        assertThat(attemptRows(examId, "student-1")).isEqualTo(1);
        assertThat(attemptRows(examId, "student-2")).isEqualTo(1);
        Integer numbers = jdbcTemplate.queryForObject(
                "select count(*) from exam_attempts where exam_id = ? and attempt_number = 1", Integer.class, examId);
        assertThat(numbers).isEqualTo(2);
    }

    @Test
    void submitProducesSingleResultAndSubmittedAttempt() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(1))
                .andExpect(jsonPath("$.data.total").value(1));

        assertAttempt(examId, "student-1", attemptId, 1, "SUBMITTED");
        assertThat(resultCount(examId, "student-1")).isEqualTo(1);
        assertThat(answersFor(attemptId)).isEqualTo(1);
    }

    @Test
    void resubmitReturnsExistingResultIdempotently() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        startExam(studentToken, examId);

        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(1));

        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"B\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(1));

        assertThat(resultCount(examId, "student-1")).isEqualTo(1);
        assertThat(attemptRows(examId, "student-1")).isEqualTo(1);
    }

    @Test
    void concurrentSubmitYieldsExactlyOneResultAndOneSubmittedAttempt() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        startExam(studentToken, examId);

        String payload = "{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"A\"}]}";
        List<MvcResult> results = runConcurrently(2, index -> mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn());

        Set<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).collect(Collectors.toSet());
        assertThat(statuses).contains(200).doesNotContain(500);

        assertThat(attemptRows(examId, "student-1")).isEqualTo(1);
        assertThat(activeCount(examId, "student-1")).isZero();
        assertThat(submittedCount(examId, "student-1")).isEqualTo(1);
        assertThat(resultCount(examId, "student-1")).isEqualTo(1);
        assertThat(answersFor(anyAttemptId(examId, "student-1"))).isEqualTo(1);
    }

    @Test
    void submitAfterExpiryIsRejectedAndAttemptExpires() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        jdbcTemplate.update("update exam_attempts set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), attemptId);

        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"A\"}]}"))
                .andExpect(status().isConflict());

        assertThat(submittedCount(examId, "student-1")).isZero();
        assertThat(resultCount(examId, "student-1")).isZero();
    }

    @Test
    void startAfterExpiryIsRejectedAndAttemptStaysExpired() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        jdbcTemplate.update("update exam_attempts set expires_at = ?, status = 'EXPIRED' where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), attemptId);

        mockMvc.perform(post("/api/exams/" + examId + "/start")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isConflict());

        assertThat(attemptRows(examId, "student-1")).isEqualTo(1);
        assertThat(expiredCount(examId, "student-1")).isEqualTo(1);
    }

    @Test
    void autosaveAndProgressAreRejectedAfterSubmission() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        submitExam(studentToken, examId, questionId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"B\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/exams/" + examId + "/attempt/progress")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isConflict());

        assertThat(answersFor(attemptId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select answer_value from answers where attempt_id = ?", String.class, attemptId))
                .isEqualTo("A");
    }

    @Test
    void autosaveAndProgressAreRejectedAfterExpiry() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        jdbcTemplate.update("update exam_attempts set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), attemptId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"B\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/exams/" + examId + "/attempt/progress")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject("select status from exam_attempts where id = ?", String.class, attemptId))
                .isEqualTo("EXPIRED");
        assertThat(answersFor(attemptId)).isZero();
    }

    @Test
    void submittedAttemptWithoutResultIsNotResubmittable() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        jdbcTemplate.update("update exam_attempts set status = 'SUBMITTED', submitted_at = ? where id = ?",
                Timestamp.from(Instant.now()), attemptId);

        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"A\"}]}"))
                .andExpect(status().isConflict());

        assertThat(resultCount(examId, "student-1")).isZero();
        assertThat(submittedCount(examId, "student-1")).isEqualTo(1);
        assertThat(activeCount(examId, "student-1")).isZero();
    }

    @Test
    void retestProducesNextAttemptNumber() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String adminToken = login("admin@example.com", "admin123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");

        String firstAttemptId = startExam(studentToken, examId);
        submitExam(studentToken, examId, questionId);
        assertAttempt(examId, "student-1", firstAttemptId, 1, "SUBMITTED");

        String requestId = requestRetest(studentToken, examId);
        approveRetest(adminToken, requestId);

        String secondAttemptId = startExam(studentToken, examId);
        assertAttempt(examId, "student-1", secondAttemptId, 2, "STARTED");
        assertThat(attemptRows(examId, "student-1")).isEqualTo(2);
    }

    @Test
    void thirdAttemptNumberedThreeAfterTwoCompletions() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String adminToken = login("admin@example.com", "admin123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");

        String firstAttemptId = startExam(studentToken, examId);
        submitExam(studentToken, examId, questionId);
        String requestId = requestRetest(studentToken, examId);
        approveRetest(adminToken, requestId);

        String secondAttemptId = startExam(studentToken, examId);
        assertAttempt(examId, "student-1", secondAttemptId, 2, "STARTED");
        jdbcTemplate.update("update exam_attempts set status = 'SUBMITTED', submitted_at = ? where id = ?",
                Timestamp.from(Instant.now()), secondAttemptId);

        String thirdAttemptId = startExam(studentToken, examId);
        assertAttempt(examId, "student-1", thirdAttemptId, 3, "STARTED");
        assertThat(attemptRows(examId, "student-1")).isEqualTo(3);
    }

    @Test
    void submitWithoutStartCreatesAttemptAutomatically() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");

        submitExam(studentToken, examId, questionId);

        assertThat(attemptRows(examId, "student-1")).isEqualTo(1);
        assertThat(submittedCount(examId, "student-1")).isEqualTo(1);
        assertThat(resultCount(examId, "student-1")).isEqualTo(1);
        Integer number = jdbcTemplate.queryForObject(
                "select attempt_number from exam_attempts where exam_id = ? and student_id = ?", Integer.class,
                examId, "student-1");
        assertThat(number).isEqualTo(1);
    }

    @Test
    void teacherAndAnonymousCannotStartOrSubmit() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");

        mockMvc.perform(post("/api/exams/" + examId + "/start")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/exams/" + examId + "/start"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"A\"}]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"A\"}]}"))
                .andExpect(status().isUnauthorized());

        assertThat(attemptRows(examId, "student-1")).isZero();
        assertThat(attemptRows(examId, "student-2")).isZero();
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
                return call.perform(counter.incrementAndGet());
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

    private void assertAttempt(String examId, String studentId, String attemptId, Integer number, String status) {
        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                "select attempt_number, status from exam_attempts where id = ?", attemptId);
        assertThat(row.get("attempt_number")).isEqualTo(number);
        assertThat(row.get("status")).isEqualTo(status);
    }

    private int attemptRows(String examId, String studentId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from exam_attempts where exam_id = ? and student_id = ?", Integer.class,
                examId, studentId);
    }

    private int activeCount(String examId, String studentId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from exam_attempts where exam_id = ? and student_id = ? and status = 'STARTED'",
                Integer.class, examId, studentId);
    }

    private int submittedCount(String examId, String studentId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from exam_attempts where exam_id = ? and student_id = ? and status = 'SUBMITTED'",
                Integer.class, examId, studentId);
    }

    private int expiredCount(String examId, String studentId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from exam_attempts where exam_id = ? and student_id = ? and status = 'EXPIRED'",
                Integer.class, examId, studentId);
    }

    private int resultCount(String examId, String studentId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from results where exam_id = ? and user_id = ?", Integer.class, examId, studentId);
    }

    private int answersFor(String attemptId) {
        return jdbcTemplate.queryForObject("select count(*) from answers where attempt_id = ?", Integer.class, attemptId);
    }

    private String anyAttemptId(String examId, String studentId) {
        return jdbcTemplate.queryForObject(
                "select id from exam_attempts where exam_id = ? and student_id = ?", String.class, examId, studentId);
    }

    private void submitExam(String token, String examId, String questionId) throws Exception {
        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(1));
    }

    private String requestRetest(String studentToken, String examId) throws Exception {
        String created = mockMvc.perform(post("/api/retest-requests/" + examId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String requestId = objectMapper.readTree(created).path("data").path("id").asText();
        assertThat(requestId).isNotBlank();
        return requestId;
    }

    private void approveRetest(String adminToken, String requestId) throws Exception {
        mockMvc.perform(post("/api/retest-requests/admin/" + requestId + "/review")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    private String createPublishedExam(String token) throws Exception {
        String created = mockMvc.perform(post("/api/exams")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Lifecycle Exam\",\"subject\":\"Math\",\"date\":\"2026-09-01\",\"duration\":60}"))
                .andReturn().getResponse().getContentAsString();
        String examId = objectMapper.readTree(created).path("data").path("id").asText();
        mockMvc.perform(post("/api/exams/" + examId + "/publish").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        return examId;
    }

    private String createQuestion(String token, String examId, String text, String firstOption,
                                  String secondOption, String answer) throws Exception {
        String response = mockMvc.perform(post("/api/exams/" + examId + "/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\",\"options\":[\"" + firstOption + "\",\"" + secondOption
                                + "\"],\"answer\":\"" + answer + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asText();
    }

    private String startExam(String token, String examId) throws Exception {
        RoomTestSupport.createJoinAndStartRoom(mockMvc, objectMapper, login("teacher@example.com", "teacher123"),
                examId, token);
        String response = mockMvc.perform(post("/api/exams/" + examId + "/start")
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();
        String attemptId = objectMapper.readTree(response).path("data").path("attemptId").asText();
        assertThat(attemptId).isNotBlank();
        return attemptId;
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

    private void insertUser(String id, String name, String email, String password, String role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private interface ConcurrentCall {
        MvcResult perform(int index) throws Exception;
    }
}