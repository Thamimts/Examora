package com.examora;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-autosave;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-autosave-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class ExamAutosaveIntegrationTest {
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
    void studentCanSaveOwnAnswerDuringActiveAttempt() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"A\"}"))
                .andExpect(status().isOk());

        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                "select user_id, exam_id, question_id, answer_value, correct, attempt_id from answers "
                        + "where attempt_id = ? and question_id = ?",
                attemptId, questionId);

        assertThat(row.get("user_id")).isEqualTo("student-1");
        assertThat(row.get("exam_id")).isEqualTo(examId);
        assertThat(row.get("question_id")).isEqualTo(questionId);
        assertThat(row.get("answer_value")).isEqualTo("A");
        assertThat(row.get("attempt_id")).isEqualTo(attemptId);
        assertThat(row.get("correct")).isNull();
    }

    @Test
    void changingAnswerUpdatesTheExistingRow() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"A\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"B\"}"))
                .andExpect(status().isOk());

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from answers where attempt_id = ? and question_id = ?", Integer.class,
                attemptId, questionId);
        assertThat(count).isEqualTo(1);

        String value = jdbcTemplate.queryForObject(
                "select answer_value from answers where attempt_id = ? and question_id = ?", String.class,
                attemptId, questionId);
        assertThat(value).isEqualTo("B");
    }

    @Test
    void clearingAnswerRemovesTheRow() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"A\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isOk());

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from answers where attempt_id = ? and question_id = ?", Integer.class,
                attemptId, questionId);
        assertThat(count).isZero();
    }

    @Test
    void duplicateAutosaveNeverCreatesDuplicateRows() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"A\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"A\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"B\"}"))
                .andExpect(status().isOk());

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from answers where attempt_id = ?", Integer.class, attemptId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void studentAutosaveOnlyTouchesTheirOwnAttempt() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentOneToken = login("student@example.com", "student123");
        String studentTwoToken = login("student2@example.com", "student234");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String victimAttemptId = startExam(studentOneToken, examId);
        String otherAttemptId = startExam(studentTwoToken, examId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentTwoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"B\"}"))
                .andExpect(status().isOk());

        Integer victimCount = jdbcTemplate.queryForObject(
                "select count(*) from answers where attempt_id = ?", Integer.class, victimAttemptId);
        assertThat(victimCount).isZero();

        String owner = jdbcTemplate.queryForObject(
                "select user_id from answers where attempt_id = ?", String.class, otherAttemptId);
        assertThat(owner).isEqualTo("student-2");
    }

    @Test
    void cannotSaveAQuestionFromAnotherExam() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String firstExamId = createPublishedExam(teacherToken);
        String secondExamId = createPublishedExam(teacherToken);
        String foreignQuestionId = createQuestion(teacherToken, firstExamId, "Foreign", "A", "B", "A");
        startExam(studentToken, secondExamId);

        mockMvc.perform(put("/api/exams/" + secondExamId + "/attempt/answers/" + foreignQuestionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"A\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotSaveValueThatDoesNotBelongToTheQuestion() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"C\"}"))
                .andExpect(status().isBadRequest());

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from answers where attempt_id = ?", Integer.class, attemptId);
        assertThat(count).isZero();
    }

    @Test
    void cannotSaveAfterAttemptExpiration() throws Exception {
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
                        .content("{\"value\":\"A\"}"))
                .andExpect(status().isConflict());

        String status = jdbcTemplate.queryForObject(
                "select status from exam_attempts where id = ?", String.class, attemptId);
        assertThat(status).isEqualTo("EXPIRED");

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from answers where attempt_id = ?", Integer.class, attemptId);
        assertThat(count).isZero();
    }

    @Test
    void cannotSaveAfterSubmission() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        jdbcTemplate.update("update exam_attempts set status = 'SUBMITTED', submitted_at = ? where id = ?",
                Timestamp.from(Instant.now()), attemptId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"A\"}"))
                .andExpect(status().isConflict());

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from answers where attempt_id = ?", Integer.class, attemptId);
        assertThat(count).isZero();
    }

    @Test
    void clientCannotBypassOwnershipOrCorrectnessViaBodyFields() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        String attemptId = startExam(studentToken, examId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"B\",\"studentId\":\"student-2\",\"correct\":true,\"score\":99}"))
                .andExpect(status().isOk());

        String owner = jdbcTemplate.queryForObject(
                "select user_id from answers where attempt_id = ?", String.class, attemptId);
        assertThat(owner).isEqualTo("student-1");

        Boolean correct = jdbcTemplate.queryForObject(
                "select correct from answers where attempt_id = ?", Boolean.class, attemptId);
        assertThat(correct).isNull();
    }

    @Test
    void teacherCannotUseTheStudentAutosaveEndpoint() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        startExam(studentToken, examId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"A\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"A\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void progressReturnsSavedAnswersWithoutAnyAnswerKey() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String q1 = createQuestion(teacherToken, examId, "Q1", "A", "B", "A");
        String q2 = createQuestion(teacherToken, examId, "Q2", "C", "D", "C");
        startExam(studentToken, examId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + q1)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"A\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + q2)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"D\"}"))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/exams/" + examId + "/attempt/progress")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptId").isNotEmpty())
                .andExpect(jsonPath("$.data.examId").value(examId))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.remainingSeconds").isNumber())
                .andExpect(jsonPath("$.data.answers." + q1).value("A"))
                .andExpect(jsonPath("$.data.answers." + q2).value("D"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"correct\"");
        assertThat(body).doesNotContain("correctOptionText");
        assertThat(body).doesNotContain("\"answer\"");
    }

    @Test
    void resumeAfterRefreshRestoresSavedAnswers() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId, "Q", "A", "B", "A");
        startExam(studentToken, examId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + questionId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"B\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/exams/" + examId + "/attempt/progress")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answers." + questionId).value("B"));
    }

    @Test
    void finalGradingAndResultReviewRemainCorrectAfterAutosave() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String q1 = createQuestion(teacherToken, examId, "2+2?", "3", "4", "4");
        String q2 = createQuestion(teacherToken, examId, "Capital?", "Paris", "Rome", "Paris");
        String attemptId = startExam(studentToken, examId);

        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + q1)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"4\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/exams/" + examId + "/attempt/answers/" + q2)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Rome\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + q1 + "\",\"value\":\"4\"},"
                                + "{\"questionId\":\"" + q2 + "\",\"value\":\"Paris\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(2))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.percentage").value(100.0));

        String submittedStatus = jdbcTemplate.queryForObject(
                "select status from exam_attempts where id = ?", String.class, attemptId);
        assertThat(submittedStatus).isEqualTo("SUBMITTED");

        mockMvc.perform(get("/api/exams/" + examId + "/result")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correctCount").value(2))
                .andExpect(jsonPath("$.data.incorrectCount").value(0))
                .andExpect(jsonPath("$.data.unansweredCount").value(0))
                .andExpect(jsonPath("$.data.questions.length()").value(2))
                .andExpect(jsonPath("$.data.questions[0].correct").value(true));
    }

    private String createPublishedExam(String token) throws Exception {
        String created = mockMvc.perform(post("/api/exams")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Autosave Exam\",\"subject\":\"Math\",\"date\":\"2026-09-01\",\"duration\":60}"))
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
}