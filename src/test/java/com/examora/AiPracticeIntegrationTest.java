package com.examora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        "spring.datasource.url=jdbc:h2:mem:examora-aipractice;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-ai-practice-verification-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class AiPracticeIntegrationTest {
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
        jdbcTemplate.update("delete from ai_practice_reviews");
        jdbcTemplate.update("delete from ai_practice_answers");
        jdbcTemplate.update("delete from ai_question_options");
        jdbcTemplate.update("delete from ai_generated_questions");
        jdbcTemplate.update("delete from ai_practice_sessions");
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
    void unauthenticatedSessionCreationIsRejected() throws Exception {
        mockMvc.perform(post("/api/student/ai-practice/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(oneQuestionBody("Algebra", "EASY")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedSessionFetchIsRejected() throws Exception {
        mockMvc.perform(get("/api/student/ai-practice/sessions/some-id"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void teacherCannotCreateAiPracticeSessions() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON).content(oneQuestionBody("Algebra", "EASY")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotListAiPracticeSessions() throws Exception {
        String adminToken = login("admin@example.com", "admin123");
        mockMvc.perform(get("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherCannotReviewAiPractice() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        mockMvc.perform(get("/api/student/ai-practice/sessions/some-id/review")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
    }

    // --- Active session must never leak answer keys ---

    @Test
    void createResponseDoesNotLeakAnswers() throws Exception {
        String token = login("student@example.com", "student123");
        String body = mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(oneQuestionBody("Algebra", "EASY")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("correctOption", "correctAnswer", "\"correct\"", "explanation");
    }

    @Test
    void activeSessionFetchDoesNotLeakAnswers() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", threeQuestionBody());

        String body = mockMvc.perform(get("/api/student/ai-practice/sessions/" + sessionId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.questionCount").value(3))
                .andExpect(jsonPath("$.data.questions.length()").value(3))
                .andExpect(jsonPath("$.data.questions[0].options.length()").value(4))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("correctOption", "correctAnswer", "\"correct\"", "explanation");
    }

    @Test
    void answerDoesNotLeakCorrectness() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", oneQuestionArray());
        String optionId = correctOptionId(token, sessionId);

        String body = mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"" + questionId(token, sessionId) + "\",\"optionId\":\"" + optionId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("correctOption", "correctAnswer", "\"correct\"", "explanation");
    }

    @Test
    void activeSessionResumeShowsProgressWithoutAnswers() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", threeQuestionBody());
        answer(token, sessionId, 1);

        String body = mockMvc.perform(get("/api/student/ai-practice/sessions/" + sessionId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answeredCount").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("correctOption", "correctAnswer", "\"correct\"", "explanation");
    }

    // --- Answer handling ---

    @Test
    void answeringProgressesAndDuplicateIsRejected() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", threeQuestionBody());

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(answerBody(token, sessionId, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answeredCount").value(1))
                .andExpect(jsonPath("$.data.questionCount").value(3));

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(answerBody(token, sessionId, 0)))
                .andExpect(status().isConflict());
    }

    @Test
    void answerValidationRejectsForeignQuestionAndOption() throws Exception {
        String token = login("student@example.com", "student123");
        String otherToken = login("student2@example.com", "student234");

        String sessionOne = createSession(token, "Algebra", "EASY", oneQuestionArray());
        String sessionTwo = createSession(otherToken, "Biology", "EASY", oneQuestionArray());
        String otherQuestionId = firstQuestionId(otherToken, sessionTwo);
        String ownOptionId = correctOptionId(token, sessionOne);

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionOne + "/answer")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"" + otherQuestionId + "\",\"optionId\":\"" + ownOptionId + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void studentCannotAnswerAnotherStudentsSession() throws Exception {
        String token = login("student@example.com", "student123");
        String otherToken = login("student2@example.com", "student234");

        String sessionId = createSession(token, "Algebra", "EASY", oneQuestionArray());

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(otherToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"any\",\"optionId\":\"any\"}"))
                .andExpect(status().isForbidden());
    }

    // --- Submit / grading ---

    @Test
    void submitGradesCorrectlyWithUnanswered() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", threeQuestionBody());

        answerC(token, sessionId, 0);
        answerW(token, sessionId, 1);

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/submit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.correctCount").value(1))
                .andExpect(jsonPath("$.data.incorrectCount").value(1))
                .andExpect(jsonPath("$.data.unansweredCount").value(1))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.percentage").value(33.33));
    }

    @Test
    void gradingMathKeepsCorrectPlusIncorrectPlusUnansweredEqual() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", threeQuestionBody());

        answerC(token, sessionId, 0);
        answerC(token, sessionId, 1);

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/submit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correctCount").value(2))
                .andExpect(jsonPath("$.data.incorrectCount").value(0))
                .andExpect(jsonPath("$.data.unansweredCount").value(1));
    }

    @Test
    void duplicateSubmitIsRejected() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", oneQuestionArray());
        answerC(token, sessionId, 0);

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/submit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/submit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
    }

    @Test
    void answeringAfterSubmitIsRejected() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", threeQuestionBody());

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/submit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(answerBody(token, sessionId, 0)))
                .andExpect(status().isConflict());
    }

    // --- Review lifecycle ---

    @Test
    void reviewBeforeSubmissionIsRejected() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", oneQuestionArray());

        mockMvc.perform(get("/api/student/ai-practice/sessions/" + sessionId + "/review")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewAfterSubmitShowsFullBreakdown() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", threeQuestionBody());
        answerC(token, sessionId, 0);
        answerW(token, sessionId, 1);

        submit(token, sessionId);

        ResultActions review = mockMvc.perform(get("/api/student/ai-practice/sessions/" + sessionId + "/review")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correctCount").value(1))
                .andExpect(jsonPath("$.data.incorrectCount").value(1))
                .andExpect(jsonPath("$.data.unansweredCount").value(1))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.reviewCompleted").value(false))
                .andExpect(jsonPath("$.data.questions.length()").value(3));

        JsonNode body = objectMapper.readTree(review.andReturn().getResponse().getContentAsString());
        JsonNode questions = body.path("data").path("questions");

        JsonNode correctQuestion = findQuestion(questions, firstQuestionId(token, sessionId));
        assertThat(correctQuestion.path("answered").asBoolean()).isTrue();
        assertThat(correctQuestion.path("correct").asBoolean()).isTrue();
        assertThat(correctQuestion.path("yourAnswer").asText()).isEqualTo("4");
        assertThat(correctQuestion.path("correctAnswer").asText()).isEqualTo("4");

        JsonNode wrongQuestion = findQuestion(questions, questionIdByIndex(token, sessionId, 1));
        assertThat(wrongQuestion.path("answered").asBoolean()).isTrue();
        assertThat(wrongQuestion.path("correct").asBoolean()).isFalse();
        assertThat(wrongQuestion.path("correctAnswer").asText()).isEqualTo("4");

        JsonNode unansweredQuestion = findQuestion(questions, questionIdByIndex(token, sessionId, 2));
        assertThat(unansweredQuestion.path("answered").asBoolean()).isFalse();
        assertThat(unansweredQuestion.path("correct").asBoolean()).isFalse();
        assertThat(unansweredQuestion.path("yourAnswer").isNull()).isTrue();
    }

    @Test
    void reviewIsolationBlocksOtherStudents() throws Exception {
        String token = login("student@example.com", "student123");
        String otherToken = login("student2@example.com", "student234");
        String sessionId = createSession(token, "Algebra", "EASY", oneQuestionArray());
        submit(token, sessionId);

        mockMvc.perform(get("/api/student/ai-practice/sessions/" + sessionId + "/review")
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void savedExplanationsAppearInReview() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", oneQuestionArray());
        String qid = firstQuestionId(token, sessionId);
        submit(token, sessionId);

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/explanations")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"explanations\":[{\"questionId\":\"" + qid + "\",\"explanation\":\"2+2 equals 4 because of counting.\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/ai-practice/sessions/" + sessionId + "/review")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCompleted").value(true))
                .andExpect(jsonPath("$.data.questions[0].explanation").value("2+2 equals 4 because of counting."));
    }

    @Test
    void explanationsCannotBeSavedWhileActive() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", oneQuestionArray());

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/explanations")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"explanations\":[{\"questionId\":\"x\",\"explanation\":\"y\"}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void explanationsRejectForeignQuestions() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Algebra", "EASY", oneQuestionArray());
        submit(token, sessionId);

        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/explanations")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"explanations\":[{\"questionId\":\"foreign\",\"explanation\":\"y\"}]}"))
                .andExpect(status().isBadRequest());
    }

    // --- Creation validation ---

    @Test
    void invalidDifficultyIsRejected() throws Exception {
        String token = login("student@example.com", "student123");
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(oneQuestionBody("Algebra", "EXTREME")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooManyQuestionsAreRejected() throws Exception {
        String token = login("student@example.com", "student123");
        String body = questionBlock(21, "Z");
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Algebra\",\"difficulty\":\"MEDIUM\",\"questions\":" + body + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void questionWithWrongOptionCountIsRejected() throws Exception {
        String token = login("student@example.com", "student123");
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Algebra\",\"difficulty\":\"MEDIUM\",\"questions\":[{\"question\":\"1+1?\",\"options\":[\"2\",\"3\",\"4\"],\"correctOption\":\"2\",\"explanation\":\"x\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateOptionsAreRejected() throws Exception {
        String token = login("student@example.com", "student123");
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Algebra\",\"difficulty\":\"MEDIUM\",\"questions\":[{\"question\":\"1+1?\",\"options\":[\"2\",\"2\",\"3\",\"4\"],\"correctOption\":\"2\",\"explanation\":\"x\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void correctOptionNotAmongOptionsIsRejected() throws Exception {
        String token = login("student@example.com", "student123");
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Algebra\",\"difficulty\":\"MEDIUM\",\"questions\":[{\"question\":\"1+1?\",\"options\":[\"2\",\"3\",\"4\",\"5\"],\"correctOption\":\"99\",\"explanation\":\"x\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingTopicAndQuestionsAreRejected() throws Exception {
        String token = login("student@example.com", "student123");
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"\",\"difficulty\":\"MEDIUM\",\"questions\":[]}"))
                .andExpect(status().isBadRequest());
    }

    // --- Isolation of session list & history ---

    @Test
    void sessionListIsScopedToStudent() throws Exception {
        String token = login("student@example.com", "student123");
        String otherToken = login("student2@example.com", "student234");
        createSession(token, "Algebra", "EASY", oneQuestionArray());

        mockMvc.perform(get("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].topic").value("Algebra"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
    }

    // --- helpers ---

    private String createSession(String token, String topic, String difficulty, String questionsJson) throws Exception {
        String response = mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"" + topic + "\",\"difficulty\":\"" + difficulty + "\",\"questions\":" + questionsJson + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("sessionId").asText();
    }

    private String oneQuestionBody(String topic, String difficulty) {
        return "{\"topic\":\"" + topic + "\",\"difficulty\":\"" + difficulty + "\",\"questions\":" + oneQuestionArray() + "}";
    }

    private String oneQuestionArray() {
        return "[{\"question\":\"What is 2+2?\",\"options\":[\"4\",\"5\",\"3\",\"6\"],\"correctOption\":\"4\",\"explanation\":\"2+2 equals 4.\"}]";
    }

    private String threeQuestionBody() {
        return questionBlock(3, "");
    }

    private String questionBlock(int count, String suffix) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"question\":\"Q").append(i).append(suffix).append("?\",\"options\":[\"4\",\"5\",\"3\",\"6\"],\"correctOption\":\"4\",\"explanation\":\"E").append(i).append(".\"}");
        }
        return sb.append("]").toString();
    }

    private void submit(String token, String sessionId) throws Exception {
        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/submit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private void answerC(String token, String sessionId, int index) throws Exception {
        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(answerBody(token, sessionId, index)))
                .andExpect(status().isOk());
    }

    private void answerW(String token, String sessionId, int index) throws Exception {
        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(wrongAnswerBody(token, sessionId, index)))
                .andExpect(status().isOk());
    }

    private void answer(String token, String sessionId, int index) throws Exception {
        String body = answerBody(token, sessionId, index);
        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private String answerBody(String token, String sessionId, int index) throws Exception {
        return "{\"questionId\":\"" + questionIdByIndex(token, sessionId, index)
                + "\",\"optionId\":\"" + optionIdForText(token, sessionId, index, "4") + "\"}";
    }

    private String wrongAnswerBody(String token, String sessionId, int index) throws Exception {
        return "{\"questionId\":\"" + questionIdByIndex(token, sessionId, index)
                + "\",\"optionId\":\"" + optionIdForText(token, sessionId, index, "5") + "\"}";
    }

    private String correctOptionId(String token, String sessionId) throws Exception {
        return optionIdForText(token, sessionId, 0, "4");
    }

    private String questionId(String token, String sessionId) throws Exception {
        return firstQuestionId(token, sessionId);
    }

    private String firstQuestionId(String token, String sessionId) throws Exception {
        return questionIdByIndex(token, sessionId, 0);
    }

    private String questionIdByIndex(String token, String sessionId, int index) throws Exception {
        String response = mockMvc.perform(get("/api/student/ai-practice/sessions/" + sessionId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("questions").path(index).path("id").asText();
    }

    private String optionIdForText(String token, String sessionId, int index, String text) throws Exception {
        String response = mockMvc.perform(get("/api/student/ai-practice/sessions/" + sessionId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode options = objectMapper.readTree(response).path("data").path("questions").path(index).path("options");
        for (JsonNode option : options) {
            if (text.equals(option.path("text").asText())) return option.path("id").asText();
        }
        throw new AssertionError("Option not found: " + text);
    }

    private JsonNode findQuestion(JsonNode questions, String questionId) {
        for (JsonNode node : questions) {
            if (questionId.equals(node.path("id").asText())) return node;
        }
        throw new AssertionError("Question not found in review: " + questionId);
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
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