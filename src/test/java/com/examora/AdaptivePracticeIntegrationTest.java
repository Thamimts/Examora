package com.examora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
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

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-adaptive;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-adaptive-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class AdaptivePracticeIntegrationTest {
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
        jdbcTemplate.update("delete from practice_answers");
        jdbcTemplate.update("delete from practice_sessions");
        jdbcTemplate.update("delete from proctor_events");
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from retest_requests");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from activity_events");
        jdbcTemplate.update("delete from users");

        insertUser("student-1", "Student One", "student@example.com", "student123", "STUDENT");
        insertUser("student-2", "Student Two", "student2@example.com", "student234", "STUDENT");
        insertUser("teacher-1", "Teacher One", "teacher@example.com", "teacher123", "TEACHER");
    }

    @Test
    void startCreatesSessionWithoutLeakingAnswers() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Practice Math", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "What is 2+2?", "3", "4", "4", 3);

        mockMvc.perform(get("/api/student/adaptive/exams/" + examId + "/session")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("STARTED"))
                .andExpect(jsonPath("$.data.level").value(3))
                .andExpect(jsonPath("$.data.currentQuestion.text").value("What is 2+2?"))
                .andExpect(jsonPath("$.data.currentQuestion.options.length()").value(2))
                .andExpect(jsonPath("$.data.currentQuestion.options[0].id").isNotEmpty())
                .andExpect(jsonPath("$.data.currentQuestion.answer").doesNotExist())
                .andExpect(jsonPath("$.data.currentQuestion.options[0].correctAnswer").doesNotExist());
    }

    @Test
    void resumeReturnsSameSession() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createExam(teacherToken, "Practice Math", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "Q1", "A", "B", "A", 2);

        String first = mockMvc.perform(get("/api/student/adaptive/exams/" + examId + "/session")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstId = objectMapper.readTree(first).path("data").path("id").asText();

        mockMvc.perform(get("/api/student/adaptive/exams/" + examId + "/session")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(firstId));
    }

    @Test
    void answeringCorrectlyAdvancesSession() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createExam(teacherToken, "Practice Math", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "Q1", "A", "B", "A", 2);
        createQuestion(teacherToken, examId, "Q2", "C", "D", "C", 4);

        String sessionId = startSession(studentToken, examId);
        JsonNode question = currentQuestion(studentToken, sessionId);
        String correctText = "Q2".equals(question.path("text").asText()) ? "C" : "A";
        JsonNode response = answer(studentToken, sessionId, question.path("id").asText(),
                optionIdFor(question, correctText));

        assertThat(response.path("data").path("correct").asBoolean()).isTrue();
        assertThat(response.path("data").path("correctOptionText").asText()).isEqualTo(correctText);
        assertThat(response.path("data").path("answeredCount").asInt()).isEqualTo(1);
        assertThat(response.path("data").path("correctCount").asInt()).isEqualTo(1);
        assertThat(response.path("data").path("completed").asBoolean()).isFalse();
        assertThat(response.path("data").path("next").path("id").asText()).isNotBlank();

        mockMvc.perform(post("/api/student/adaptive/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"" + question.path("id").asText() + "\",\"optionId\":\"x\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void wrongAnswerReportsCorrectOption() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createExam(teacherToken, "Practice Math", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "Q1", "A", "B", "A", 2);

        String sessionId = startSession(studentToken, examId);
        JsonNode question = currentQuestion(studentToken, sessionId);
        JsonNode response = answer(studentToken, sessionId, question.path("id").asText(),
                optionIdFor(question, "B"));

        assertThat(response.path("data").path("correct").asBoolean()).isFalse();
        assertThat(response.path("data").path("correctOptionText").asText()).isEqualTo("A");
        assertThat(response.path("data").path("correctCount").asInt()).isZero();
    }

    @Test
    void optionFromAnotherQuestionIsRejected() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createExam(teacherToken, "Practice Math", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        String q1 = createQuestion(teacherToken, examId, "Q1", "A", "B", "A", 2);
        String q2 = createQuestion(teacherToken, examId, "Q2", "C", "D", "C", 4);

        String sessionId = startSession(studentToken, examId);
        JsonNode question = currentQuestion(studentToken, sessionId);
        String foreignQuestionId = "Q2".equals(question.path("text").asText()) ? q1 : q2;
        String foreignOption = objectMapper.readTree(
                        mockMvc.perform(get("/api/questions/" + foreignQuestionId + "/options")
                                        .header("Authorization", bearer(teacherToken)))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString())
                .path("data").path(0).path("id").asText();

        mockMvc.perform(post("/api/student/adaptive/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"" + question.path("id").asText() + "\",\"optionId\":\"" + foreignOption + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentDuplicateAnswerIsRejected() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createExam(teacherToken, "Practice Math", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "Q1", "A", "B", "A", 2);

        String sessionId = startSession(studentToken, examId);
        JsonNode question = currentQuestion(studentToken, sessionId);
        String optionId = optionIdFor(question, "A");
        jdbcTemplate.update(
                "insert into practice_answers (id, session_id, question_id, option_id, answer_value, correct, difficulty, sequence_index, answered_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)",
                "stale-answer", sessionId, question.path("id").asText(), optionId, "A", true, 2, 0);

        mockMvc.perform(post("/api/student/adaptive/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"" + question.path("id").asText() + "\",\"optionId\":\"" + optionId + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void crossStudentAccessIsForbidden() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String otherStudentToken = login("student2@example.com", "student234");
        String examId = createExam(teacherToken, "Practice Math", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "Q1", "A", "B", "A", 2);

        String sessionId = startSession(studentToken, examId);
        JsonNode question = currentQuestion(studentToken, sessionId);

        mockMvc.perform(get("/api/student/adaptive/sessions/" + sessionId)
                        .header("Authorization", bearer(otherStudentToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/student/adaptive/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(otherStudentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"" + question.path("id").asText() + "\",\"optionId\":\"" + optionIdFor(question, "A") + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherCannotStartOrAnswer() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String examId = createExam(teacherToken, "Practice Math", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "Q1", "A", "B", "A", 2);

        mockMvc.perform(get("/api/student/adaptive/exams/" + examId + "/session")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/student/adaptive/exams/" + examId + "/sessions")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void draftExamIsForbidden() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createExam(teacherToken, "Draft Practice", "Math", "DRAFT");
        createQuestion(teacherToken, examId, "Q1", "A", "B", "A", 2);

        mockMvc.perform(get("/api/student/adaptive/exams/" + examId + "/session")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void examWithoutQuestionsIsUnprocessable() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createExam(teacherToken, "Empty Practice", "Math", "UPCOMING");
        publishExam(teacherToken, examId);

        mockMvc.perform(get("/api/student/adaptive/exams/" + examId + "/session")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void completingSessionReturnsSummaryAndWritesOnlyPracticeTables() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createExam(teacherToken, "Practice Math", "Math", "UPCOMING");
        publishExam(teacherToken, examId);

        Map<String, String> answersByText = new LinkedHashMap<>();
        for (int index = 1; index <= 5; index++) {
            String text = "pq-" + index;
            String answer = index % 2 == 0 ? "B" : "A";
            answersByText.put(text, answer);
            createQuestion(teacherToken, examId, text, "A", "B", answer, index);
        }

        String sessionId = startSession(studentToken, examId);
        JsonNode last = null;
        boolean completed = false;
        for (int round = 0; round < 5 && !completed; round++) {
            JsonNode question = currentQuestion(studentToken, sessionId);
            JsonNode response = answer(studentToken, sessionId, question.path("id").asText(),
                    optionIdFor(question, answersByText.get(question.path("text").asText())));
            last = response;
            completed = response.path("data").path("completed").asBoolean();
        }

        assertThat(completed).isTrue();
        JsonNode data = last.path("data");
        assertThat(data.path("answeredCount").asInt()).isEqualTo(5);
        assertThat(data.path("correctCount").asInt()).isEqualTo(5);
        assertThat(data.path("completed").asBoolean()).isTrue();
        assertThat(data.path("summary").path("accuracy").asDouble()).isEqualTo(100.0);
        assertThat(data.path("summary").path("totalAnswered").asInt()).isEqualTo(5);
        assertThat(data.path("summary").path("correctCount").asInt()).isEqualTo(5);
        assertThat(data.path("summary").path("perDifficulty").size()).isEqualTo(5);

        mockMvc.perform(get("/api/student/adaptive/sessions/" + sessionId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.currentQuestion").doesNotExist())
                .andExpect(jsonPath("$.data.summary.correctCount").value(5));

        assertThat(jdbcTemplate.queryForObject("select count(*) from results", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from exam_attempts", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from answers", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from practice_answers", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("select count(*) from practice_sessions", Integer.class)).isEqualTo(1);

        mockMvc.perform(post("/api/student/adaptive/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"x\",\"optionId\":\"y\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void newSessionSupersedesOld() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createExam(teacherToken, "Practice Math", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "Q1", "A", "B", "A", 2);

        String firstId = startSession(studentToken, examId);
        String secondResponse = mockMvc.perform(post("/api/student/adaptive/exams/" + examId + "/sessions")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondId = objectMapper.readTree(secondResponse).path("data").path("id").asText();
        assertThat(secondId).isNotEqualTo(firstId);

        mockMvc.perform(get("/api/student/adaptive/sessions/" + firstId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.currentQuestion").doesNotExist());

        mockMvc.perform(get("/api/student/adaptive/sessions/" + secondId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STARTED"));
    }

    @Test
    void targetQuestionCountIsClampedServerSide() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createExam(teacherToken, "Practice Math", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "Q1", "A", "B", "A", 2);

        String below = mockMvc.perform(post("/api/student/adaptive/exams/" + examId + "/sessions")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetQuestionCount\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(below).path("data").path("targetQuestionCount").asInt()).isEqualTo(5);

        String above = mockMvc.perform(post("/api/student/adaptive/exams/" + examId + "/sessions")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetQuestionCount\":99}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(above).path("data").path("targetQuestionCount").asInt()).isEqualTo(20);

        String defaultTarget = mockMvc.perform(post("/api/student/adaptive/exams/" + examId + "/sessions")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(defaultTarget).path("data").path("targetQuestionCount").asInt()).isEqualTo(10);
    }

    private void publishExam(String token, String examId) throws Exception {
        mockMvc.perform(post("/api/exams/" + examId + "/publish").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private String startSession(String studentToken, String examId) throws Exception {
        String response = mockMvc.perform(get("/api/student/adaptive/exams/" + examId + "/session")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asText();
    }

    private JsonNode currentQuestion(String studentToken, String sessionId) throws Exception {
        String response = mockMvc.perform(get("/api/student/adaptive/sessions/" + sessionId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("currentQuestion");
    }

    private JsonNode answer(String studentToken, String sessionId, String questionId, String optionId) throws Exception {
        String response = mockMvc.perform(post("/api/student/adaptive/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"" + questionId + "\",\"optionId\":\"" + optionId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String optionIdFor(JsonNode question, String text) {
        for (JsonNode option : question.path("options")) {
            if (text.equals(option.path("text").asText())) {
                return option.path("id").asText();
            }
        }
        throw new IllegalArgumentException("No option with text: " + text);
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