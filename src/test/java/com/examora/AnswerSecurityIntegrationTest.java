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
        "spring.datasource.url=jdbc:h2:mem:examora-answers;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-answer-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class AnswerSecurityIntegrationTest {
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
    void studentCannotCreateAnswerWithoutAttemptId() throws Exception {
        String token = login("student@example.com", "student123");
        mockMvc.perform(post("/api/answers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"student-1\",\"examId\":\"exam-1\",\"questionId\":\"q-1\",\"value\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void studentCanCreateAnswerOnlyOnTheirOwnActiveAttempt() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String otherToken = login("student2@example.com", "student234");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId);
        String attemptId = startExam(studentToken, examId);
        String otherAttemptId = startExam(otherToken, examId);

        mockMvc.perform(post("/api/answers")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerJson("student-1", examId, questionId, attemptId, "value-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("student-1"))
                .andExpect(jsonPath("$.data.attemptId").value(attemptId));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from answers where user_id = 'student-1' and attempt_id = ?", Integer.class, attemptId);
        assertThat(count).isEqualTo(1);

        mockMvc.perform(post("/api/answers")
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerJson("student-2", examId, questionId, attemptId, "value-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentCannotCreateAnswerOnAnotherStudentsAttempt() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String otherToken = login("student2@example.com", "student234");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId);
        String victimAttemptId = startExam(login("student@example.com", "student123"), examId);

        mockMvc.perform(post("/api/answers")
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerJson("student-1", examId, questionId, victimAttemptId, "value-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentCannotCreateAnswerForExpiredAttempt() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId);
        String attemptId = startExam(studentToken, examId);

        jdbcTemplate.update("update exam_attempts set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), attemptId);

        mockMvc.perform(post("/api/answers")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerJson("student-1", examId, questionId, attemptId, "value-1")))
                .andExpect(status().isConflict());
    }

    @Test
    void studentCannotCreateAnswerForCompletedAttempt() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId);
        String attemptId = startExam(studentToken, examId);

        jdbcTemplate.update("update exam_attempts set status = 'SUBMITTED', submitted_at = ? where id = ?",
                Timestamp.from(Instant.now()), attemptId);

        mockMvc.perform(post("/api/answers")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerJson("student-1", examId, questionId, attemptId, "value-1")))
                .andExpect(status().isConflict());
    }

    @Test
    void studentCannotCreateAnswerForAttemptOfAnotherExam() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String firstExamId = createPublishedExam(teacherToken);
        String secondExamId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, firstExamId);
        String secondExamAttemptId = startExam(studentToken, secondExamId);

        mockMvc.perform(post("/api/answers")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerJson("student-1", firstExamId, questionId, secondExamAttemptId, "value-1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void studentCannotUpdateAnotherStudentsAnswer() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentOneToken = login("student@example.com", "student123");
        String studentTwoToken = login("student2@example.com", "student234");

        String examId = createPublishedExam(teacherToken);
        String questionId = createQuestion(teacherToken, examId);
        String attemptId = startExam(studentOneToken, examId);

        String created = mockMvc.perform(post("/api/answers")
                        .header("Authorization", bearer(studentOneToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerJson("student-1", examId, questionId, attemptId, "value-1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String answerId = objectMapper.readTree(created).path("data").path("id").asText();

        mockMvc.perform(put("/api/answers/" + answerId)
                        .header("Authorization", bearer(studentTwoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerJson("student-1", examId, questionId, attemptId, "value-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentCannotReadAnotherStudentsAnswers() throws Exception {
        String token = login("student2@example.com", "student234");
        mockMvc.perform(get("/api/answers?userId=student-1").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/answers").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    private String createPublishedExam(String token) throws Exception {
        String created = mockMvc.perform(post("/api/exams")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Answer Security\",\"subject\":\"Security\",\"date\":\"2026-09-01\",\"duration\":60}"))
                .andReturn().getResponse().getContentAsString();
        String examId = objectMapper.readTree(created).path("data").path("id").asText();
        mockMvc.perform(post("/api/exams/" + examId + "/publish").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        return examId;
    }

    private String createQuestion(String token, String examId) throws Exception {
        String response = mockMvc.perform(post("/api/exams/" + examId + "/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Q\",\"options\":[\"A\",\"B\"],\"answer\":\"A\"}"))
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

    private String answerJson(String userId, String examId, String questionId, String attemptId, String value) {
        return "{\"userId\":\"" + userId + "\",\"examId\":\"" + examId + "\",\"questionId\":\""
                + questionId + "\",\"attemptId\":\"" + attemptId + "\",\"value\":\"" + value + "\"}";
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