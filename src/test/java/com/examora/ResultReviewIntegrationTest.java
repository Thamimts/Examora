package com.examora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-review;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-result-review-verification-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class ResultReviewIntegrationTest {
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
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from retest_requests");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("student-1", "Student One", "student@example.com", "student123", "STUDENT");
        insertUser("student-2", "Student Two", "student2@example.com", "student234", "STUDENT");
        insertUser("teacher-1", "Teacher One", "teacher@example.com", "teacher123", "TEACHER");
        insertUser("admin-1", "Admin One", "admin@example.com", "admin123", "ADMIN");
    }

    // --- Auth / authorization ---

    @Test
    void unauthenticatedResultReviewIsRejected() throws Exception {
        mockMvc.perform(get("/api/exams/some-id/result"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void teacherCannotAccessStudentResultReview() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/exams/some-id/result")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotAccessStudentResultReview() throws Exception {
        String adminToken = login("admin@example.com", "admin123");

        mockMvc.perform(get("/api/exams/some-id/result")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
    }

    // --- Review lifecycle ---

    @Test
    void reviewBeforeSubmissionGetsNoReviewData() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "No Submit Exam", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "1+1?", "2", "3", "2", 2);

        mockMvc.perform(post("/api/exams/" + examId + "/start")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/exams/" + examId + "/result")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("correctOptionText", "\"correct\"");
    }

    @Test
    void reviewBeforeAnyAttemptIsRejected() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "No Attempt Exam", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "2+2?", "4", "5", "4", 2);

        mockMvc.perform(get("/api/exams/" + examId + "/result")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isNotFound());
    }

    // --- Happy path: full breakdown ---

    @Test
    void reviewAfterSubmitShowsFullBreakdown() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Breakdown Exam", "Science", "UPCOMING");
        publishExam(teacherToken, examId);
        String qCorrect = createQuestion(teacherToken, examId, "Capital of France?", "Paris", "Rome", "Paris", 2);
        String qWrong = createQuestion(teacherToken, examId, "Largest planet?", "Mars", "Jupiter", "Jupiter", 3);
        String qUnanswered = createQuestion(teacherToken, examId, "H2O is?", "Water", "Salt", "Water", 1);

        start(studentToken, examId);
        submit(studentToken, examId,
                "{\"answers\":["
                        + "{\"questionId\":\"" + qCorrect + "\",\"value\":\"Paris\"},"
                        + "{\"questionId\":\"" + qWrong + "\",\"value\":\"Mars\"}"
                        + "]}");

        ResultActions review = mockMvc.perform(get("/api/exams/" + examId + "/result")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resultId").isNotEmpty())
                .andExpect(jsonPath("$.data.examId").value(examId))
                .andExpect(jsonPath("$.data.examTitle").value("Breakdown Exam"))
                .andExpect(jsonPath("$.data.subject").value("Science"))
                .andExpect(jsonPath("$.data.score").value(1))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.percentage").value(33.33))
                .andExpect(jsonPath("$.data.correctCount").value(1))
                .andExpect(jsonPath("$.data.incorrectCount").value(1))
                .andExpect(jsonPath("$.data.unansweredCount").value(1))
                .andExpect(jsonPath("$.data.questions.length()").value(3));

        JsonNode body = objectMapper.readTree(review.andReturn().getResponse().getContentAsString());
        JsonNode questions = body.path("data").path("questions");
        assertThat(questions.isArray()).isTrue();
        assertThat(questions).hasSize(3);

        JsonNode correctQuestion = findQuestion(questions, qCorrect);
        assertThat(correctQuestion.path("answered").asBoolean()).isTrue();
        assertThat(correctQuestion.path("correct").asBoolean()).isTrue();
        assertThat(correctQuestion.path("correctOptionText").asText()).isEqualTo("Paris");
        assertThat(correctQuestion.path("selectedOptionText").asText()).isEqualTo("Paris");
        assertThat(correctQuestion.path("options").size()).isEqualTo(2);

        JsonNode wrongQuestion = findQuestion(questions, qWrong);
        assertThat(wrongQuestion.path("answered").asBoolean()).isTrue();
        assertThat(wrongQuestion.path("correct").asBoolean()).isFalse();
        assertThat(wrongQuestion.path("correctOptionText").asText()).isEqualTo("Jupiter");
        assertThat(wrongQuestion.path("selectedOptionText").asText()).isEqualTo("Mars");

        JsonNode unansweredQuestion = findQuestion(questions, qUnanswered);
        assertThat(unansweredQuestion.path("answered").asBoolean()).isFalse();
        assertThat(unansweredQuestion.path("correct").asBoolean()).isFalse();
        assertThat(unansweredQuestion.path("correctOptionText").asText()).isEqualTo("Water");
        assertThat(unansweredQuestion.path("selectedOptionText").isNull()).isTrue();
    }

    @Test
    void reviewNumbersAreSequentialFromOne() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Numbered Exam", "Biology", "UPCOMING");
        publishExam(teacherToken, examId);
        String qOne = createQuestion(teacherToken, examId, "Q one?", "A", "B", "A", 2);
        String qTwo = createQuestion(teacherToken, examId, "Q two?", "C", "D", "C", 2);

        start(studentToken, examId);
        submit(studentToken, examId,
                "{\"answers\":["
                        + "{\"questionId\":\"" + qOne + "\",\"value\":\"A\"},"
                        + "{\"questionId\":\"" + qTwo + "\",\"value\":\"D\"}"
                        + "]}");

        mockMvc.perform(get("/api/exams/" + examId + "/result")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].number").value(1))
                .andExpect(jsonPath("$.data.questions[1].number").value(2));
    }

    // --- Isolation ---

    @Test
    void resultReviewIsScopedToAuthenticatedStudent() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentOneToken = login("student@example.com", "student123");
        String studentTwoToken = login("student2@example.com", "student234");

        String examId = createExam(teacherToken, "Isolated Exam", "Physics", "UPCOMING");
        publishExam(teacherToken, examId);
        String questionId = createQuestion(teacherToken, examId, "Speed of light?", "300k", "150k", "300k", 5);

        start(studentOneToken, examId);
        submit(studentOneToken, examId,
                "{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"300k\"}]}");

        mockMvc.perform(get("/api/exams/" + examId + "/result")
                        .header("Authorization", bearer(studentOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correctCount").value(1));

        mockMvc.perform(get("/api/exams/" + examId + "/result")
                        .header("Authorization", bearer(studentTwoToken)))
                .andExpect(status().isNotFound());
    }

    // --- Persistence ---

    @Test
    void submittedCorrectnessIsPersistedInAnswers() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Persist Exam", "Chemistry", "UPCOMING");
        publishExam(teacherToken, examId);
        String qCorrect = createQuestion(teacherToken, examId, "Au is?", "Gold", "Silver", "Gold", 2);
        String qWrong = createQuestion(teacherToken, examId, "Fe is?", "Iron", "Copper", "Iron", 2);

        start(studentToken, examId);
        submit(studentToken, examId,
                "{\"answers\":["
                        + "{\"questionId\":\"" + qCorrect + "\",\"value\":\"Gold\"},"
                        + "{\"questionId\":\"" + qWrong + "\",\"value\":\"Copper\"}"
                        + "]}");

        List<Boolean> persisted = jdbcTemplate.queryForList(
                "select correct from answers where exam_id = ? order by question_id", Boolean.class, examId);
        assertThat(persisted).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void legacyNullCorrectnessIsDerivedOnReview() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Legacy Exam", "History", "UPCOMING");
        publishExam(teacherToken, examId);
        String qCorrect = createQuestion(teacherToken, examId, "Who won 2020?", "Bi", "Li", "Bi", 2);
        String qWrong = createQuestion(teacherToken, examId, "Oldest language?", "Latin", "Sanskrit", "Sanskrit", 2);

        start(studentToken, examId);
        submit(studentToken, examId,
                "{\"answers\":["
                        + "{\"questionId\":\"" + qCorrect + "\",\"value\":\"Bi\"},"
                        + "{\"questionId\":\"" + qWrong + "\",\"value\":\"Latin\"}"
                        + "]}");

        jdbcTemplate.update("update answers set correct = null where exam_id = ?", examId);

        mockMvc.perform(get("/api/exams/" + examId + "/result")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(1))
                .andExpect(jsonPath("$.data.correctCount").value(1))
                .andExpect(jsonPath("$.data.incorrectCount").value(1))
                .andExpect(jsonPath("$.data.unansweredCount").value(0));

        JsonNode body = objectMapper.readTree(mockMvc.perform(get("/api/exams/" + examId + "/result")
                        .header("Authorization", bearer(studentToken)))
                .andReturn().getResponse().getContentAsString());
        JsonNode questions = body.path("data").path("questions");
        JsonNode corrected = findQuestion(questions, qCorrect);
        assertThat(corrected.path("correct").asBoolean()).isTrue();
        assertThat(corrected.path("correctOptionText").asText()).isEqualTo("Bi");
        JsonNode missed = findQuestion(questions, qWrong);
        assertThat(missed.path("correct").asBoolean()).isFalse();
        assertThat(missed.path("correctOptionText").asText()).isEqualTo("Sanskrit");
    }

    // --- Active exam must not leak answer keys ---

    @Test
    void activeExamQuestionsDoNotExposeAnswerKeys() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Active Leak Exam", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "3*3?", "9", "8", "9", 2);

        String response = mockMvc.perform(get("/api/exams/" + examId + "/questions")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("\"correct\"", "\"correctAnswer\"", "\"answer\":\"9\"");
    }

    @Test
    void activeExamAnswerCreationDoesNotExposeCorrectness() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Save Answer Exam", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        String questionId = createQuestion(teacherToken, examId, "8/2?", "4", "2", "4", 2);

        String startResponse = mockMvc.perform(post("/api/exams/" + examId + "/start")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String attemptId = objectMapper.readTree(startResponse).path("data").path("attemptId").asText();

        String answerResponse = mockMvc.perform(post("/api/answers")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":\"" + examId + "\",\"questionId\":\"" + questionId
                                + "\",\"attemptId\":\"" + attemptId + "\",\"value\":\"4\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(answerResponse).doesNotContain("\"correct\"", "\"correctAnswer\"");
    }

    // --- helpers (mirrors existing test patterns) ---

    private JsonNode findQuestion(JsonNode questions, String questionId) {
        for (JsonNode node : questions) {
            if (questionId.equals(node.path("questionId").asText())) {
                return node;
            }
        }
        throw new AssertionError("Question not found in review: " + questionId);
    }

    private void start(String token, String examId) throws Exception {
        mockMvc.perform(post("/api/exams/" + examId + "/start")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private void submit(String token, String examId, String body) throws Exception {
        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String createExam(String token, String title, String subject, String status) throws Exception {
        String response = mockMvc.perform(post("/api/exams")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"subject\":\"" + subject + "\",\"date\":\"2026-09-01\",\"duration\":60,\"status\":\"" + status + "\",\"participants\":0}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asText();
    }

    private void publishExam(String token, String examId) throws Exception {
        mockMvc.perform(post("/api/exams/" + examId + "/publish")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private String createQuestion(String token, String examId, String text, String firstOption, String secondOption,
                                  String answer, int difficulty) throws Exception {
        String response = mockMvc.perform(post("/api/exams/" + examId + "/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\",\"options\":[\"" + firstOption + "\",\"" + secondOption + "\"],\"answer\":\"" + answer + "\",\"difficulty\":" + difficulty + "}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asText();
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
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