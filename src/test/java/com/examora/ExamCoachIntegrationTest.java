package com.examora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-ai-coach;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-ai-coach-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class ExamCoachIntegrationTest {
    private static final String LIST_ENDPOINT = "/api/student/ai-coach/exams";
    private static final String CONTEXT_ENDPOINT = "/api/student/ai-coach/exams/";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from proctor_events");
        jdbcTemplate.update("delete from ai_practice_reviews");
        jdbcTemplate.update("delete from ai_practice_answers");
        jdbcTemplate.update("delete from ai_question_options");
        jdbcTemplate.update("delete from ai_generated_questions");
        jdbcTemplate.update("delete from ai_tutor_questions");
        jdbcTemplate.update("delete from practice_answers");
        jdbcTemplate.update("delete from practice_sessions");
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from ai_practice_sessions");
        jdbcTemplate.update("delete from users");

        insertUser("student-1", "Student One", "student@example.com", "student123", "STUDENT");
        insertUser("student-2", "Student Two", "student2@example.com", "student234", "STUDENT");
        insertUser("teacher-1", "Teacher One", "teacher@example.com", "teacher123", "TEACHER");
    }

    // ---------- completed exam list ----------

    @Test
    void anonymousRequestsAreRejectedForTheExamList() throws Exception {
        mockMvc.perform(get(LIST_ENDPOINT)).andExpect(status().isUnauthorized());
    }

    @Test
    void teachersCannotAccessTheExamList() throws Exception {
        mockMvc.perform(get(LIST_ENDPOINT)
                        .header("Authorization", bearer(login("teacher@example.com", "teacher123"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentSeesOnlyOwnCompletedExamsWithSafeFieldsNewestFirst() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 1, 3, 5}, new boolean[]{true, true, true, false});
        seedCompletedExam("student-1", "ex-b", "Beta", "Science", "2026-02-01",
                new int[]{3, 3, 5}, new boolean[]{true, false, false});
        seedCompletedExam("student-2", "ex-c", "Gamma", "History", "2026-03-01",
                new int[]{1, 2}, new boolean[]{true, true});

        JsonNode list = exams("student@example.com", "student123");
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).path("examId").asText()).isEqualTo("ex-b");
        assertThat(list.get(1).path("examId").asText()).isEqualTo("ex-a");

        JsonNode beta = list.get(0);
        assertThat(beta.path("title").asText()).isEqualTo("Beta");
        assertThat(beta.path("subject").asText()).isEqualTo("Science");
        assertThat(beta.path("score").asInt()).isEqualTo(1);
        assertThat(beta.path("total").isMissingNode()).isTrue();
        assertThat(beta.path("percentage").asDouble()).isEqualTo(33.33);
        assertThat(beta.path("completedAt").asText()).isNotBlank();
        assertThat(beta.path("attemptNumber").asInt()).isEqualTo(1);
        assertThat(beta.path("userId").isMissingNode()).isTrue();
        assertThat(beta.path("resultId").isMissingNode()).isTrue();
    }

    @Test
    void unfinishedExamWithoutResultIsExcludedFromTheList() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 1}, new boolean[]{true, true});
        seedStartedOnlyExam("ex-d", "Delta", "Physics");

        JsonNode list = exams("student@example.com", "student123");
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("examId").asText()).isEqualTo("ex-a");
    }

    @Test
    void retakeShowsLatestAttemptNumberAndCompletion() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 1, 3}, new boolean[]{true, true, true});
        String attempt2 = "attempt-ex-a-2";
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, submitted_at, version) "
                        + "values (?, ?, ?, 2, 'SUBMITTED', '2026-02-01 08:00:00', '2026-02-01 09:30:00', '2026-02-01 09:00:00', 0)",
                attempt2, "ex-a", "student-1");
        seedRetakeAnswers("ex-a", new boolean[]{true, true, false}, attempt2);

        JsonNode list = exams("student@example.com", "student123");
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("attemptNumber").asInt()).isEqualTo(2);
        assertThat(list.get(0).path("completedAt").asText()).isNotBlank();
        assertThat(list.get(0).path("score").asInt()).isEqualTo(3);
    }

    @Test
    void emptyCompletedExamListIsReturnedForNewStudent() throws Exception {
        JsonNode list = exams("student@example.com", "student123");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isZero();
    }

    // ---------- exam-scoped context ----------

    @Test
    void anonymousRequestsAreRejectedForTheContext() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 1}, new boolean[]{true, false});
        mockMvc.perform(post(CONTEXT_ENDPOINT + "ex-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EXPLAIN_PERFORMANCE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void teachersCannotRequestStudentCoachContext() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 1}, new boolean[]{true, false});
        mockMvc.perform(post(CONTEXT_ENDPOINT + "ex-a")
                        .header("Authorization", bearer(login("teacher@example.com", "teacher123")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EXPLAIN_PERFORMANCE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentCannotSelectAnotherStudentsCompletedExam() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 1}, new boolean[]{true, false});
        mockMvc.perform(post(CONTEXT_ENDPOINT + "ex-a")
                        .header("Authorization", bearer(login("student2@example.com", "student234")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EXPLAIN_PERFORMANCE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void selectingExamWithoutCompletedResultIsNotFound() throws Exception {
        seedStartedOnlyExam("ex-d", "Delta", "Physics");
        mockMvc.perform(post(CONTEXT_ENDPOINT + "ex-d")
                        .header("Authorization", bearer(login("student@example.com", "student123")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EXPLAIN_PERFORMANCE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidOrMissingActionIsRejected() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 1}, new boolean[]{true, false});
        String token = login("student@example.com", "student123");
        mockMvc.perform(post(CONTEXT_ENDPOINT + "ex-a")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(CONTEXT_ENDPOINT + "ex-a")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"FABRICATE_ANSWER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void questionActionsRequireAQuestionId() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 1}, new boolean[]{true, false});
        String token = login("student@example.com", "student123");
        mockMvc.perform(post(CONTEXT_ENDPOINT + "ex-a")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EXPLAIN_QUESTION\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(CONTEXT_ENDPOINT + "ex-a")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"GENERATE_SIMILAR_QUESTION\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void explainPerformanceReturnsExamSummaryAndStatsWithoutAnswerKeys() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 1, 3, 5}, new boolean[]{true, true, true, false});

        JsonNode data = context("student@example.com", "student123", "ex-a",
                "{\"action\":\"EXPLAIN_PERFORMANCE\"}");
        assertThat(data.path("examId").asText()).isEqualTo("ex-a");
        assertThat(data.path("examTitle").asText()).isEqualTo("Alpha");
        assertThat(data.path("subject").asText()).isEqualTo("Math");
        assertThat(data.path("score").asInt()).isEqualTo(3);
        assertThat(data.path("total").asInt()).isEqualTo(4);
        assertThat(data.path("percentage").asDouble()).isEqualTo(75.0);
        assertThat(data.path("attemptNumber").asInt()).isEqualTo(1);

        JsonNode stats = data.path("stats");
        assertThat(stats.path("correct").asInt()).isEqualTo(3);
        assertThat(stats.path("incorrect").asInt()).isEqualTo(1);
        assertThat(stats.path("unanswered").asInt()).isZero();
        assertThat(stats.path("accuracy").asDouble()).isEqualTo(75.0);
        assertThat(stats.path("easyCorrect").asInt()).isEqualTo(2);
        assertThat(stats.path("easyTotal").asInt()).isEqualTo(2);
        assertThat(stats.path("mediumCorrect").asInt()).isEqualTo(1);
        assertThat(stats.path("mediumTotal").asInt()).isEqualTo(1);
        assertThat(stats.path("hardCorrect").asInt()).isZero();
        assertThat(stats.path("hardTotal").asInt()).isEqualTo(1);

        assertThat(data.path("questions").isNull()).isTrue();
        assertThat(data.path("mistakes").isNull()).isTrue();
        assertThat(data.path("question").isNull()).isTrue();
        assertThat(data.path("correctAnswer").isNull()).isTrue();
    }

    @Test
    void explainMistakesReturnsOnlyIncorrectQuestionsWithoutAnswers() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 3, 5}, new boolean[]{true, false, false});

        JsonNode data = context("student@example.com", "student123", "ex-a",
                "{\"action\":\"EXPLAIN_MISTAKES\"}");
        JsonNode mistakes = data.path("mistakes");
        assertThat(mistakes.isArray()).isTrue();
        assertThat(mistakes.size()).isEqualTo(2);
        assertThat(mistakes.get(0).path("number").asInt()).isEqualTo(2);
        assertThat(mistakes.get(0).path("questionId").asText()).isEqualTo("ex-a-q1");
        assertThat(mistakes.get(0).path("text").asText()).contains("ex-a");
        assertThat(mistakes.get(0).path("selectedOptionText").asText()).isEqualTo("Opt B");
        assertThat(mistakes.get(0).path("difficultyName").asText()).isEqualTo("MEDIUM");
        assertThat(mistakes.get(0).path("options").isArray()).isTrue();
        assertThat(mistakes.get(0).path("correct").asBoolean()).isFalse();
        assertThat(mistakes.get(0).path("text").asText()).doesNotContainIgnoringCase("correct");

        assertThat(data.path("questions").isNull()).isTrue();
        assertThat(data.path("question").isNull()).isTrue();
        assertThat(data.path("correctAnswer").isNull()).isTrue();
        assertThat(data.path("stats").path("correct").asInt()).isEqualTo(1);
    }

    @Test
    void explainQuestionReturnsExactlyThatQuestionWithoutAnswerKey() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 3, 5}, new boolean[]{true, false, false});

        JsonNode data = context("student@example.com", "student123", "ex-a",
                "{\"action\":\"EXPLAIN_QUESTION\",\"questionId\":\"ex-a-q2\"}");
        JsonNode question = data.path("question");
        assertThat(question.isNull()).isFalse();
        assertThat(question.path("questionId").asText()).isEqualTo("ex-a-q2");
        assertThat(question.path("number").asInt()).isEqualTo(3);
        assertThat(question.path("difficultyName").asText()).isEqualTo("HARD");
        assertThat(question.path("selectedOptionText").asText()).isEqualTo("Opt B");
        assertThat(question.path("options").isArray()).isTrue();
        assertThat(question.path("correct").asBoolean()).isFalse();
        assertThat(question.path("text").asText()).doesNotContainIgnoringCase("correct");

        assertThat(data.path("correctAnswer").isNull()).isTrue();
        assertThat(data.path("questions").isNull()).isTrue();
        assertThat(data.path("mistakes").isNull()).isTrue();
        assertThat(data.path("stats").isNull()).isTrue();
    }

    @Test
    void explainQuestionRejectsQuestionFromAnotherExamOrUnknown() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 1}, new boolean[]{true, false});
        seedCompletedExam("student-1", "ex-b", "Beta", "Science", "2026-02-01",
                new int[]{3, 3}, new boolean[]{true, true});
        String token = login("student@example.com", "student123");

        mockMvc.perform(post(CONTEXT_ENDPOINT + "ex-a")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EXPLAIN_QUESTION\",\"questionId\":\"ex-b-q0\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(CONTEXT_ENDPOINT + "ex-a")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EXPLAIN_QUESTION\",\"questionId\":\"not-a-question\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reviewWeakAreasReturnsStatsAndMistakesOnly() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 3, 5}, new boolean[]{true, false, false});

        JsonNode data = context("student@example.com", "student123", "ex-a",
                "{\"action\":\"REVIEW_WEAK_AREAS\"}");
        assertThat(data.path("stats").isNull()).isFalse();
        assertThat(data.path("mistakes").size()).isEqualTo(2);
        assertThat(data.path("questions").isNull()).isTrue();
        assertThat(data.path("question").isNull()).isTrue();
        assertThat(data.path("correctAnswer").isNull()).isTrue();
    }

    @Test
    void suggestRevisionReturnsStatsAndAllQuestionsWithoutAnswerKeys() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 3, 5}, new boolean[]{true, false, false});

        JsonNode data = context("student@example.com", "student123", "ex-a",
                "{\"action\":\"SUGGEST_REVISION\"}");
        JsonNode questions = data.path("questions");
        assertThat(questions.isArray()).isTrue();
        assertThat(questions.size()).isEqualTo(3);
        for (JsonNode question : questions) {
            assertThat(question.path("correctAnswer").isMissingNode()).isTrue();
            assertThat(question.path("text").asText()).doesNotContainIgnoringCase("answer");
        }
        assertThat(data.path("stats").isNull()).isFalse();
        assertThat(data.path("mistakes").isNull()).isTrue();
        assertThat(data.path("question").isNull()).isTrue();
        assertThat(data.path("correctAnswer").isNull()).isTrue();
    }

    @Test
    void generateSimilarQuestionProvidesTheAnswerKeyPermittedForThatAction() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 3}, new boolean[]{true, false});

        JsonNode data = context("student@example.com", "student123", "ex-a",
                "{\"action\":\"GENERATE_SIMILAR_QUESTION\",\"questionId\":\"ex-a-q1\"}");
        assertThat(data.path("question").path("questionId").asText()).isEqualTo("ex-a-q1");
        assertThat(data.path("question").path("correct").asBoolean()).isFalse();
        assertThat(data.path("correctAnswer").asText()).isEqualTo("Opt A");
        assertThat(data.path("stats").isNull()).isTrue();
        assertThat(data.path("questions").isNull()).isTrue();
        assertThat(data.path("mistakes").isNull()).isTrue();
    }

    @Test
    void chatReturnsFullExamContextWithoutAnswerKeys() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 3}, new boolean[]{true, false});

        JsonNode data = context("student@example.com", "student123", "ex-a",
                "{\"action\":\"CHAT\"}");
        assertThat(data.path("stats").isNull()).isFalse();
        assertThat(data.path("questions").size()).isEqualTo(2);
        assertThat(data.path("mistakes").isNull()).isTrue();
        assertThat(data.path("question").isNull()).isTrue();
        assertThat(data.path("correctAnswer").isNull()).isTrue();
    }

    @Test
    void contextNeverLeaksQuestionsFromAnotherExam() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 3, 5}, new boolean[]{true, false, false});
        seedCompletedExam("student-1", "ex-b", "Beta", "Science", "2026-02-01",
                new int[]{3, 3, 3}, new boolean[]{true, true, true});

        JsonNode forA = context("student@example.com", "student123", "ex-a",
                "{\"action\":\"SUGGEST_REVISION\"}");
        for (JsonNode question : forA.path("questions")) {
            assertThat(question.path("questionId").asText()).startsWith("ex-a-");
            assertThat(question.path("text").asText()).contains("ex-a");
        }
        JsonNode forB = context("student@example.com", "student123", "ex-b",
                "{\"action\":\"SUGGEST_REVISION\"}");
        for (JsonNode question : forB.path("questions")) {
            assertThat(question.path("questionId").asText()).startsWith("ex-b-");
        }
        assertThat(forB.path("stats").path("score").isMissingNode()).isTrue();
        assertThat(forB.path("examId").asText()).isEqualTo("ex-b");
    }

    @Test
    void repeatRequestReturnsIdenticalDeterministicContext() throws Exception {
        seedCompletedExam("student-1", "ex-a", "Alpha", "Math", "2026-01-01",
                new int[]{1, 3, 5}, new boolean[]{true, false, false});

        JsonNode first = context("student@example.com", "student123", "ex-a",
                "{\"action\":\"REVIEW_WEAK_AREAS\"}");
        JsonNode second = context("student@example.com", "student123", "ex-a",
                "{\"action\":\"REVIEW_WEAK_AREAS\"}");
        assertThat(first).isEqualTo(second);
    }

    // ---------- helpers ----------

    private JsonNode exams(String email, String password) throws Exception {
        JsonNode root = objectMapper.readTree(mockMvc.perform(get(LIST_ENDPOINT)
                        .header("Authorization", bearer(login(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return root.path("data");
    }

    private JsonNode context(String email, String password, String examId, String body) throws Exception {
        JsonNode root = objectMapper.readTree(mockMvc.perform(post(CONTEXT_ENDPOINT + examId)
                        .header("Authorization", bearer(login(email, password)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return root.path("data");
    }

    /** Seeds exam + a single SUBMITTED attempt + questions/options/answers + one result row. */
    private void seedCompletedExam(String studentId, String examId, String title, String subject,
                                   String date, int[] difficulties, boolean[] correct) {
        seedExam(examId, title, subject);
        String attemptId = "attempt-" + examId;
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, submitted_at, version) "
                        + "values (?, ?, ?, 1, 'SUBMITTED', '2026-01-01 08:00:00', '2026-01-01 09:30:00', '2026-01-01 09:00:00', 0)",
                attemptId, examId, studentId);
        seedQuestionsAndAnswers(examId, difficulties, correct, attemptId);
        jdbcTemplate.update(
                "insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                "result-" + examId, studentId, examId, title, subject,
                countTrue(correct), date, correct.length);
    }

    private void seedStartedOnlyExam(String examId, String title, String subject) {
        seedExam(examId, title, subject);
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) "
                        + "values (?, ?, ?, 1, 'STARTED', current_timestamp, current_timestamp, 0)",
                "attempt-" + examId, examId, "student-1");
    }

    private void seedExam(String id, String title, String subject) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, ?, '2026-09-01', 90, 'COMPLETED', 0, null, ?)",
                id, title, subject, "teacher-1");
    }

    private void seedQuestionsAndAnswers(String examId, int[] difficulties, boolean[] correct, String attemptId) {
        List<String> questionIds = new ArrayList<>();
        StringBuilder questions = new StringBuilder("insert into questions (id, exam_id, text, answer, difficulty) values ");
        StringBuilder options = new StringBuilder("insert into question_options (id, question_id, text, display_order, correct_answer) values ");
        for (int i = 0; i < difficulties.length; i++) {
            String questionId = examId + "-q" + i;
            questionIds.add(questionId);
            if (i > 0) {
                questions.append(",");
            }
            questions.append("('").append(questionId).append("','").append(examId)
                    .append("','Exam ").append(examId).append(" Q").append(i + 1)
                    .append("','Opt A',").append(difficulties[i]).append(")");
            String[] texts = {"Opt A", "Opt B", "Opt C", "Opt D"};
            for (int o = 0; o < texts.length; o++) {
                if (!(i == 0 && o == 0)) {
                    options.append(",");
                }
                options.append("('opt-").append(questionId).append("-").append(o)
                        .append("','").append(questionId).append("','").append(texts[o])
                        .append("',").append(o).append(",").append(o == 0).append(")");
            }
        }
        jdbcTemplate.update(questions.toString());
        jdbcTemplate.update(options.toString());

        StringBuilder answers = new StringBuilder(
                "insert into answers (id, user_id, exam_id, question_id, option_id, answer_value, attempt_id, correct) values ");
        for (int i = 0; i < correct.length; i++) {
            if (i > 0) {
                answers.append(",");
            }
            String questionId = examId + "-q" + i;
            String selected = correct[i] ? "Opt A" : "Opt B";
            String optionId = correct[i] ? "opt-" + questionId + "-0" : "opt-" + questionId + "-1";
            answers.append("('").append(examId).append("-a").append(i).append("','student-1','").append(examId)
                    .append("','").append(questionId).append("','").append(optionId)
                    .append("','").append(selected).append("','").append(attemptId)
                    .append("',").append(correct[i]).append(")");
        }
        jdbcTemplate.update(answers.toString());
    }

    /** Inserts answers for a retake against questions that already exist in the database. */
    private void seedRetakeAnswers(String examId, boolean[] correct, String attemptId) {
        StringBuilder answers = new StringBuilder(
                "insert into answers (id, user_id, exam_id, question_id, option_id, answer_value, attempt_id, correct) values ");
        for (int i = 0; i < correct.length; i++) {
            if (i > 0) {
                answers.append(",");
            }
            String questionId = examId + "-q" + i;
            String selected = correct[i] ? "Opt A" : "Opt B";
            String optionId = correct[i] ? "opt-" + questionId + "-0" : "opt-" + questionId + "-1";
            answers.append("('").append(examId).append("-a-retake-").append(i).append("','student-1','").append(examId)
                    .append("','").append(questionId).append("','").append(optionId)
                    .append("','").append(selected).append("','").append(attemptId)
                    .append("',").append(correct[i]).append(")");
        }
        jdbcTemplate.update(answers.toString());
    }

    private int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private void insertUser(String id, String name, String email, String password, String role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role);
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}