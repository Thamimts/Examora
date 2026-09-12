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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-aiprog;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-ai-progression-verification-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class AiPracticeProgressionIntegrationTest {
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
    }

    // --- Initial state ---

    @Test
    void newTopicStartsAtEasyWithMediumAndHardLocked() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Arrays", "EASY", 5, 5, 80.0);
        answerIncorrect(token, sessionId, 0);
        answerIncorrect(token, sessionId, 1);
        answerIncorrect(token, sessionId, 2);
        submit(token, sessionId);

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(progress.path("currentDifficulty").asText()).isEqualTo("EASY");
        assertThat(progress.path("unlockedDifficulty").asText()).isEqualTo("EASY");
        assertThat(progress.path("easy").path("attempted").asInt()).isEqualTo(1);
        assertThat(progress.path("easy").path("completed").asInt()).isEqualTo(0);
        assertThat(progress.path("medium").path("attempted").asInt()).isEqualTo(0);
        assertThat(progress.path("hard").path("attempted").asInt()).isEqualTo(0);
    }

    @Test
    void unpracticedTopicIsAbsentFromProgress() throws Exception {
        String token = login("student@example.com", "student123");
        createSession(token, "Arrays", "EASY", 5, 5, 80.0);
        String otherToken = login("student2@example.com", "student234");
        JsonNode progress = progressList(otherToken);
        assertThat(progress.size()).isEqualTo(0);
    }

    // --- EASY level progression ---

    @Test
    void easyQualifyingAttemptUnlocksMedium() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Arrays", "EASY", 5, 5, 80.0);
        answerCorrect(token, sessionId, 0);
        answerCorrect(token, sessionId, 1);
        answerCorrect(token, sessionId, 2);
        answerCorrect(token, sessionId, 3);

        String submitBody = submit(token, sessionId);
        assertThat(objectMapper.readTree(submitBody).path("data").path("completionMet").asBoolean()).isTrue();

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("status").asText()).isEqualTo("READY_FOR_NEXT");
        assertThat(progress.path("currentDifficulty").asText()).isEqualTo("MEDIUM");
        assertThat(progress.path("unlockedDifficulty").asText()).isEqualTo("MEDIUM");
        assertThat(progress.path("easy").path("completed").asInt()).isEqualTo(1);
        assertThat(progress.path("easy").path("bestAccuracy").asDouble()).isEqualTo(80.0);
    }

    @Test
    void easyFailedAttemptDoesNotUnlockMedium() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Arrays", "EASY", 5, 5, 80.0);
        answerCorrect(token, sessionId, 0);
        answerCorrect(token, sessionId, 1);
        answerCorrect(token, sessionId, 2);
        submit(token, sessionId);

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("currentDifficulty").asText()).isEqualTo("EASY");
        assertThat(progress.path("unlockedDifficulty").asText()).isEqualTo("EASY");
        assertThat(progress.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(progress.path("easy").path("completed").asInt()).isEqualTo(0);
    }

    @Test
    void multipleAttemptsEventuallyQualify() throws Exception {
        String token = login("student@example.com", "student123");

        runAttempt(token, "Arrays", "EASY", 2);
        runAttempt(token, "Arrays", "EASY", 3);
        runAttempt(token, "Arrays", "EASY", 4);

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("currentDifficulty").asText()).isEqualTo("MEDIUM");
        assertThat(progress.path("unlockedDifficulty").asText()).isEqualTo("MEDIUM");
        assertThat(progress.path("easy").path("attempted").asInt()).isEqualTo(3);
        assertThat(progress.path("easy").path("completed").asInt()).isEqualTo(1);
        assertThat(progress.path("easy").path("bestAccuracy").asDouble()).isEqualTo(80.0);
    }

    @Test
    void previousCompletionIsNotDowngradedByLaterFailure() throws Exception {
        String token = login("student@example.com", "student123");

        runAttempt(token, "Arrays", "EASY", 4);
        runAttempt(token, "Arrays", "EASY", 3);

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("currentDifficulty").asText()).isEqualTo("MEDIUM");
        assertThat(progress.path("easy").path("completed").asInt()).isEqualTo(1);
        assertThat(progress.path("easy").path("bestAccuracy").asDouble()).isEqualTo(80.0);
    }

    @Test
    void regenerationStyleSessionsDoNotResetProgress() throws Exception {
        String token = login("student@example.com", "student123");

        runAttempt(token, "Arrays", "EASY", 4);
        runAttempt(token, "Arrays", "EASY", 3);
        runAttempt(token, "Arrays", "MEDIUM", 4);

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("easy").path("completed").asInt()).isEqualTo(1);
        assertThat(progress.path("easy").path("bestAccuracy").asDouble()).isEqualTo(80.0);
        assertThat(progress.path("medium").path("completed").asInt()).isEqualTo(1);
        assertThat(progress.path("currentDifficulty").asText()).isEqualTo("HARD");
    }

    // --- MEDIUM level progression ---

    @Test
    void mediumQualifyingAttemptUnlocksHard() throws Exception {
        String token = login("student@example.com", "student123");
        runAttempt(token, "Arrays", "EASY", 4);
        runAttempt(token, "Arrays", "MEDIUM", 4);

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("status").asText()).isEqualTo("READY_FOR_NEXT");
        assertThat(progress.path("currentDifficulty").asText()).isEqualTo("HARD");
        assertThat(progress.path("unlockedDifficulty").asText()).isEqualTo("HARD");
        assertThat(progress.path("medium").path("completed").asInt()).isEqualTo(1);
    }

    @Test
    void mediumFailedAttemptDoesNotUnlockHard() throws Exception {
        String token = login("student@example.com", "student123");
        runAttempt(token, "Arrays", "EASY", 4);
        runAttempt(token, "Arrays", "MEDIUM", 3);

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("currentDifficulty").asText()).isEqualTo("MEDIUM");
        assertThat(progress.path("unlockedDifficulty").asText()).isEqualTo("MEDIUM");
        assertThat(progress.path("medium").path("completed").asInt()).isEqualTo(0);
    }

    // --- HARD level / mastery ---

    @Test
    void hardQualifyingAttemptMarksTopicMastered() throws Exception {
        String token = login("student@example.com", "student123");
        runAttempt(token, "Arrays", "EASY", 4);
        runAttempt(token, "Arrays", "MEDIUM", 4);
        runAttempt(token, "Arrays", "HARD", 4);

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("status").asText()).isEqualTo("MASTERED");
        assertThat(progress.path("currentDifficulty").asText()).isEqualTo("MASTERED");
        assertThat(progress.path("unlockedDifficulty").asText()).isEqualTo("MASTERED");
        assertThat(progress.path("hard").path("completed").asInt()).isEqualTo(1);
    }

    @Test
    void masteredTopicCannotUnlockAnotherLevel() throws Exception {
        String token = login("student@example.com", "student123");
        runAttempt(token, "Arrays", "EASY", 4);
        runAttempt(token, "Arrays", "MEDIUM", 4);
        runAttempt(token, "Arrays", "HARD", 4);
        runAttempt(token, "Arrays", "HARD", 4);

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("status").asText()).isEqualTo("MASTERED");
        assertThat(progress.path("unlockedDifficulty").asText()).isEqualTo("MASTERED");
        assertThat(progress.path("hard").path("attempted").asInt()).isEqualTo(2);
    }

    // --- Grading / completion criteria edge cases ---

    @Test
    void exactAccuracyThresholdPasses() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Arrays", "EASY", 5, 5, 80.0);
        answerCorrect(token, sessionId, 0);
        answerCorrect(token, sessionId, 1);
        answerCorrect(token, sessionId, 2);
        answerCorrect(token, sessionId, 3);
        String submitBody = submit(token, sessionId);
        assertThat(objectMapper.readTree(submitBody).path("data").path("completionMet").asBoolean()).isTrue();
    }

    @Test
    void justBelowAccuracyThresholdFails() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Arrays", "EASY", 5, 5, 80.0);
        answerCorrect(token, sessionId, 0);
        answerCorrect(token, sessionId, 1);
        answerCorrect(token, sessionId, 2);
        String submitBody = submit(token, sessionId);
        JsonNode submitData = objectMapper.readTree(submitBody).path("data");
        assertThat(submitData.path("percentage").asDouble()).isEqualTo(60.0);
        assertThat(submitData.path("completionMet").asBoolean()).isFalse();

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("unlockedDifficulty").asText()).isEqualTo("EASY");
    }

    @Test
    void insufficientQuestionCountFailsEvenAtFullAccuracy() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Arrays", "EASY", 4, 5, 80.0);
        answerCorrect(token, sessionId, 0);
        answerCorrect(token, sessionId, 1);
        answerCorrect(token, sessionId, 2);
        answerCorrect(token, sessionId, 3);
        String submitBody = submit(token, sessionId);
        JsonNode submitData = objectMapper.readTree(submitBody).path("data");
        assertThat(submitData.path("percentage").asDouble()).isEqualTo(100.0);
        assertThat(submitData.path("completionMet").asBoolean()).isFalse();
    }

    @Test
    void moreQuestionsThanRequiredCanPass() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Arrays", "EASY", 8, 5, 80.0);
        for (int i = 0; i < 7; i++) {
            answerCorrect(token, sessionId, i);
        }
        String submitBody = submit(token, sessionId);
        JsonNode submitData = objectMapper.readTree(submitBody).path("data");
        assertThat(submitData.path("percentage").asDouble()).isEqualTo(87.5);
        assertThat(submitData.path("completionMet").asBoolean()).isTrue();
    }

    @Test
    void unansweredQuestionsAreCountedInAccuracyDenominator() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Arrays", "EASY", 5, 5, 80.0);
        answerCorrect(token, sessionId, 0);
        answerIncorrect(token, sessionId, 1);
        answerCorrect(token, sessionId, 2);
        answerCorrect(token, sessionId, 3);

        String submitBody = submit(token, sessionId);
        JsonNode submitData = objectMapper.readTree(submitBody).path("data");
        assertThat(submitData.path("correctCount").asInt()).isEqualTo(3);
        assertThat(submitData.path("incorrectCount").asInt()).isEqualTo(1);
        assertThat(submitData.path("unansweredCount").asInt()).isEqualTo(1);
        assertThat(submitData.path("percentage").asDouble()).isEqualTo(60.0);
        assertThat(submitData.path("completionMet").asBoolean()).isFalse();
    }

    @Test
    void completionCriteriaBoundsAreValidated() throws Exception {
        String token = login("student@example.com", "student123");
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Arrays\",\"difficulty\":\"EASY\",\"minimumQuestions\":0,\"questions\":" + block(5) + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Arrays\",\"difficulty\":\"EASY\",\"minimumQuestions\":21,\"questions\":" + block(5) + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Arrays\",\"difficulty\":\"EASY\",\"minimumAccuracy\":101,\"questions\":" + block(5) + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Arrays\",\"difficulty\":\"EASY\",\"minimumAccuracy\":0,\"questions\":" + block(5) + "}"))
                .andExpect(status().isBadRequest());
    }

    // --- Review enrichment ---

    @Test
    void completedReviewReflectsProgressionState() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Arrays", "EASY", 5, 5, 80.0);
        answerCorrect(token, sessionId, 0);
        answerCorrect(token, sessionId, 1);
        answerCorrect(token, sessionId, 2);
        answerCorrect(token, sessionId, 3);
        submit(token, sessionId);

        String body = mockMvc.perform(get("/api/student/ai-practice/sessions/" + sessionId + "/review")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).path("data");
        assertThat(data.path("completionMet").asBoolean()).isTrue();
        assertThat(data.path("topicStatus").asText()).isEqualTo("READY_FOR_NEXT");
        assertThat(data.path("currentDifficulty").asText()).isEqualTo("MEDIUM");
        assertThat(data.path("unlockedDifficulty").asText()).isEqualTo("MEDIUM");
        assertThat(data.path("minimumQuestions").asInt()).isEqualTo(5);
        assertThat(data.path("minimumAccuracy").asDouble()).isEqualTo(80.0);
    }

    // --- Security / isolation ---

    @Test
    void progressIsScopedPerStudent() throws Exception {
        String token = login("student@example.com", "student123");
        String otherToken = login("student2@example.com", "student234");
        runAttempt(token, "Arrays", "EASY", 4);

        JsonNode otherProgress = progressList(otherToken);
        assertThat(otherProgress.size()).isEqualTo(0);
    }

    @Test
    void teacherCannotReadStudentProgress() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        runAttempt(studentToken, "Arrays", "EASY", 4);
        mockMvc.perform(get("/api/student/ai-practice/progress")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void clientSentStudentIdIsIgnored() throws Exception {
        String token = login("student@example.com", "student123");
        String otherToken = login("student2@example.com", "student234");

        String sessionId = mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Arrays\",\"difficulty\":\"EASY\",\"minimumQuestions\":5,\"minimumAccuracy\":80,\"studentId\":\"student-2\",\"questions\":" + block(5) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(sessionId).path("data").path("sessionId").asText();
        answerCorrect(token, id, 0);
        answerCorrect(token, id, 1);
        answerCorrect(token, id, 2);
        answerCorrect(token, id, 3);
        submit(token, id);

        assertThat(topicProgress(token, "Arrays").path("status").asText()).isEqualTo("READY_FOR_NEXT");
        assertThat(progressList(otherToken).size()).isEqualTo(0);
    }

    @Test
    void clientCannotForceALockedDifficultyOnAFreshTopic() throws Exception {
        String token = login("student@example.com", "student123");

        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Arrays\",\"difficulty\":\"MEDIUM\",\"minimumQuestions\":5,\"minimumAccuracy\":80,\"questions\":" + block(5) + "}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Arrays\",\"difficulty\":\"HARD\",\"minimumQuestions\":5,\"minimumAccuracy\":80,\"questions\":" + block(5) + "}"))
                .andExpect(status().isForbidden());

        createSession(token, "Arrays", "EASY", 5, 5, 80.0);
    }

    @Test
    void hardStaysLockedUntilMediumIsCompleted() throws Exception {
        String token = login("student@example.com", "student123");

        runAttempt(token, "Arrays", "EASY", 4);

        mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Arrays\",\"difficulty\":\"HARD\",\"minimumQuestions\":5,\"minimumAccuracy\":80,\"questions\":" + block(5) + "}"))
                .andExpect(status().isForbidden());

        String mediumId = createSession(token, "Arrays", "MEDIUM", 5, 5, 80.0);
        for (int i = 0; i < 4; i++) {
            answerCorrect(token, mediumId, i);
        }
        submit(token, mediumId);
    }

    @Test
    void completedDifficultiesRemainPracticableAfterMastery() throws Exception {
        String token = login("student@example.com", "student123");

        runAttempt(token, "Arrays", "EASY", 4);
        runAttempt(token, "Arrays", "MEDIUM", 4);
        runAttempt(token, "Arrays", "HARD", 4);

        createSession(token, "Arrays", "EASY", 5, 5, 80.0);
        createSession(token, "Arrays", "MEDIUM", 5, 5, 80.0);
        createSession(token, "Arrays", "HARD", 5, 5, 80.0);

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("status").asText()).isEqualTo("MASTERED");
    }

    @Test
    void duplicateSubmitLeavesProgressUnchanged() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token, "Arrays", "EASY", 5, 5, 80.0);
        answerCorrect(token, sessionId, 0);
        answerCorrect(token, sessionId, 1);
        answerCorrect(token, sessionId, 2);
        answerCorrect(token, sessionId, 3);
        submit(token, sessionId);
        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/submit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());

        JsonNode progress = topicProgress(token, "Arrays");
        assertThat(progress.path("easy").path("completed").asInt()).isEqualTo(1);
        assertThat(progress.path("easy").path("attempted").asInt()).isEqualTo(1);
    }

    @Test
    void progressResponseDoesNotLeakAnswerKeys() throws Exception {
        String token = login("student@example.com", "student123");
        runAttempt(token, "Arrays", "EASY", 4);

        String body = mockMvc.perform(get("/api/student/ai-practice/progress")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("correctOption", "correctAnswer", "\"correct\"", "explanation");
    }

    // --- helpers ---

    private void runAttempt(String token, String topic, String difficulty, int correctAnswers) throws Exception {
        int count = 5;
        double minAccuracy = 80.0;
        String sessionId = createSession(token, topic, difficulty, count, count, minAccuracy);
        for (int i = 0; i < count; i++) {
            if (i < correctAnswers) {
                answerCorrect(token, sessionId, i);
            } else {
                answerIncorrect(token, sessionId, i);
            }
        }
        submit(token, sessionId);
    }

    private String createSession(String token, String topic, String difficulty, int count,
                                 Integer minimumQuestions, Double minimumAccuracy) throws Exception {
        StringBuilder body = new StringBuilder("{\"topic\":\"").append(topic)
                .append("\",\"difficulty\":\"").append(difficulty).append("\"");
        if (minimumQuestions != null) {
            body.append(",\"minimumQuestions\":").append(minimumQuestions);
        }
        if (minimumAccuracy != null) {
            body.append(",\"minimumAccuracy\":").append(minimumAccuracy);
        }
        body.append(",\"questions\":").append(block(count)).append("}");
        String response = mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("sessionId").asText();
    }

    private String block(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"question\":\"Q").append(i).append("?\",\"options\":[\"4\",\"5\",\"3\",\"6\"],\"correctOption\":\"4\",\"explanation\":\"E").append(i).append(".\"}");
        }
        return sb.append("]").toString();
    }

    private String submit(String token, String sessionId) throws Exception {
        return mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/submit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void answerCorrect(String token, String sessionId, int index) throws Exception {
        answer(token, sessionId, index, "4");
    }

    private void answerIncorrect(String token, String sessionId, int index) throws Exception {
        answer(token, sessionId, index, "5");
    }

    private void answer(String token, String sessionId, int index, String text) throws Exception {
        String questionId = questionIdByIndex(token, sessionId, index);
        String optionId = optionIdForText(token, sessionId, index, text);
        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"" + questionId + "\",\"optionId\":\"" + optionId + "\"}"))
                .andExpect(status().isOk());
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

    private JsonNode progressList(String token) throws Exception {
        String response = mockMvc.perform(get("/api/student/ai-practice/progress")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode topicProgress(String token, String topic) throws Exception {
        JsonNode list = progressList(token);
        for (JsonNode node : list) {
            if (topic.equals(node.path("topic").asText())) return node;
        }
        throw new AssertionError("Topic not found in progress: " + topic);
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