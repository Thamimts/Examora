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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-aitutor;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-ai-tutor-verification-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class AiTutorIntegrationTest {
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
        jdbcTemplate.update("delete from ai_tutor_questions");
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

    // --- Authentication / authorization ---

    @Test
    void unauthenticatedTutorRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/student/ai-tutor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody("some-session", "some-question", "EXPLAIN_CONCEPT")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedTutorQuestionCreationIsRejected() throws Exception {
        mockMvc.perform(post("/api/student/ai-tutor/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorQuestionBody("some-session", "some-question")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedTutorQuestionAnswerIsRejected() throws Exception {
        mockMvc.perform(post("/api/student/ai-tutor/questions/some-id/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"opt-1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void teacherCannotUseAiTutor() throws Exception {
        String token = login("teacher@example.com", "teacher123");
        mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody("some-session", "some-question", "EXPLAIN_CONCEPT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotCreateTutorQuestions() throws Exception {
        String token = login("admin@example.com", "admin123");
        mockMvc.perform(post("/api/student/ai-tutor/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorQuestionBody("some-session", "some-question")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotGradeTutorQuestions() throws Exception {
        String token = login("admin@example.com", "admin123");
        mockMvc.perform(post("/api/student/ai-tutor/questions/some-id/answer")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"opt-1\"}"))
                .andExpect(status().isForbidden());
    }

    // --- Ownership / isolation ---

    @Test
    void otherStudentCannotGetTutorContextForAnotherStudentsSession() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String questionId = firstQuestionId(token, sessionId);

        String otherToken = login("student2@example.com", "student234");
        mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody(sessionId, questionId, "EXPLAIN_WRONG")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tutorContextQuestionMustBelongToSession() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String otherSessionId = completedWrongSession(token);

        mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody(sessionId, firstQuestionId(token, otherSessionId), "EXPLAIN_CONCEPT")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherStudentCannotAnswerAnotherStudentsTutorQuestion() throws Exception {
        String token = login("student@example.com", "student123");
        AiTutorTestData data = readyTutorQuestion(token, "SIMILAR_QUESTION");

        String otherToken = login("student2@example.com", "student234");
        mockMvc.perform(post("/api/student/ai-tutor/questions/" + data.questionId + "/answer")
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + data.optionsCorrectId + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tutorIsUnavailableBeforeThePracticeIsSubmitted() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = createSession(token);
        String questionId = firstQuestionId(token, sessionId);

        mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody(sessionId, questionId, "EXPLAIN_CONCEPT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tutorIsUnavailableForMissingSession() throws Exception {
        String token = login("student@example.com", "student123");
        mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody("missing", "question", "EXPLAIN_CONCEPT")))
                .andExpect(status().isNotFound());
    }

    // --- Server-authoritative context (forging attempt) ---

    @Test
    void clientSuppliedCorrectnessAndAnswersAreIgnored() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String questionId = firstQuestionId(token, sessionId);

        String forgedBody = "{\"sessionId\":\"" + sessionId + "\",\"questionId\":\"" + questionId
                + "\",\"action\":\"EXPLAIN_WRONG\",\"yourAnswer\":\"4\",\"correctAnswer\":\"4\",\"correct\":true}";
        String response = mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(forgedBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("correct").asBoolean()).isFalse();
        assertThat(data.path("answered").asBoolean()).isTrue();
        assertThat(data.path("yourAnswer").asText()).isEqualTo("5");
        assertThat(data.path("correctAnswer").asText()).isEqualTo("4");
    }

    // --- Context actions ---

    @Test
    void explainWrongWorksForIncorrectAnswer() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String questionId = firstQuestionId(token, sessionId);

        String response = mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody(sessionId, questionId, "EXPLAIN_WRONG")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(true))
                .andExpect(jsonPath("$.data.correct").value(false))
                .andExpect(jsonPath("$.data.yourAnswer").value("5"))
                .andExpect(jsonPath("$.data.correctAnswer").value("4"))
                .andExpect(jsonPath("$.data.options.length()").value(4))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(response).path("data").path("topic").asText()).isEqualTo("Algebra");
    }

    @Test
    void explainWrongIsRejectedWhenTheQuestionWasAnsweredCorrectly() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedCorrectSession(token);
        String questionId = firstQuestionId(token, sessionId);

        mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody(sessionId, questionId, "EXPLAIN_WRONG")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void explainWrongIsRejectedWhenTheQuestionWasLeftUnanswered() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWithUnansweredQuestion(token);
        String questionId = firstQuestionId(token, sessionId);

        mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody(sessionId, questionId, "EXPLAIN_WRONG")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void learnActionsWorkOnAnyQuestionState() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String questionId = firstQuestionId(token, sessionId);

        for (String action : List.of("EXPLAIN_SIMPLE", "GIVE_EXAMPLE", "EXPLAIN_CONCEPT")) {
            mockMvc.perform(post("/api/student/ai-tutor")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tutorBody(sessionId, questionId, action)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.answered").value(true))
                    .andExpect(jsonPath("$.data.questionId").value(questionId));
        }
    }

    @Test
    void learnActionsWorkOnCorrectlyAnsweredQuestion() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedCorrectSession(token);
        String questionId = firstQuestionId(token, sessionId);

        for (String action : List.of("EXPLAIN_SIMPLE", "GIVE_EXAMPLE", "EXPLAIN_CONCEPT", "HARDER_QUESTION")) {
            mockMvc.perform(post("/api/student/ai-tutor")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tutorBody(sessionId, questionId, action)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.correct").value(true))
                    .andExpect(jsonPath("$.data.answered").value(true));
        }
    }

    @Test
    void contextIncludesSessionTopicAndDifficulty() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String questionId = firstQuestionId(token, sessionId);

        String response = mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody(sessionId, questionId, "EXPLAIN_CONCEPT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topic").value("Algebra"))
                .andExpect(jsonPath("$.data.difficulty").value("EASY"))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(response).path("data").path("questionId").asText()).isEqualTo(questionId);
    }

    @Test
    void similarQuestionIsAllowedEvenWhenUnanswered() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWithUnansweredQuestion(token);
        String questionId = firstQuestionId(token, sessionId);

        mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody(sessionId, questionId, "SIMILAR_QUESTION")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(false));
    }

    @Test
    void harderQuestionRequiresAnAnswerFirst() throws Exception {
        String token = login("student@example.com", "student123");
        String unansweredSession = completedWithUnansweredQuestion(token);
        String unansweredQuestion = firstQuestionId(token, unansweredSession);
        mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody(unansweredSession, unansweredQuestion, "HARDER_QUESTION")))
                .andExpect(status().isBadRequest());

        String answeredSession = completedWrongSession(token);
        String answeredQuestion = firstQuestionId(token, answeredSession);
        mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody(answeredSession, answeredQuestion, "HARDER_QUESTION")))
                .andExpect(status().isOk());
    }

    @Test
    void invalidActionIsRejected() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        mockMvc.perform(post("/api/student/ai-tutor")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorBody(sessionId, firstQuestionId(token, sessionId), "CHEAT")))
                .andExpect(status().isBadRequest());
    }

    // --- Tutor question creation is sanitized ---

    @Test
    void createdTutorQuestionNeverLeaksTheAnswerKey() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String sourceQuestionId = firstQuestionId(token, sessionId);

        String response = mockMvc.perform(post("/api/student/ai-tutor/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorQuestionBody(sessionId, sourceQuestionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.questionId").isNotEmpty())
                .andExpect(jsonPath("$.data.question").isNotEmpty())
                .andExpect(jsonPath("$.data.options.length()").value(4))
                .andExpect(jsonPath("$.data.difficulty").isNumber())
                .andExpect(jsonPath("$.data.hint").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("correctAnswer", "correct", "answered");
    }

    @Test
    void tutorQuestionCreationRequiresSourceFromTheSameSession() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        mockMvc.perform(post("/api/student/ai-tutor/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorQuestionBody(sessionId, "foreign-question")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tutorQuestionCreationValidatesOptionShapeAndAnswerMembership() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String sourceQuestionId = firstQuestionId(token, sessionId);

        String badOptions = "{\"sessionId\":\"" + sessionId + "\",\"sourceQuestionId\":\"" + sourceQuestionId
                + "\",\"kind\":\"SIMILAR_QUESTION\",\"question\":\"Which one is a prime?\",\"options\":[\"A\",\"B\"],"
                + "\"difficulty\":2,\"hint\":\"Think.\",\"correctAnswer\":\"A\"}";
        mockMvc.perform(post("/api/student/ai-tutor/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(badOptions))
                .andExpect(status().isBadRequest());

        String badAnswer = "{\"sessionId\":\"" + sessionId + "\",\"sourceQuestionId\":\"" + sourceQuestionId
                + "\",\"kind\":\"SIMILAR_QUESTION\",\"question\":\"Which one is a prime?\","
                + "\"options\":[\"A\",\"B\",\"C\",\"D\"],\"difficulty\":2,\"hint\":\"Think.\",\"correctAnswer\":\"Z\"}";
        mockMvc.perform(post("/api/student/ai-tutor/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(badAnswer))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tutorQuestionCreationRejectsDuplicateOptions() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String sourceQuestionId = firstQuestionId(token, sessionId);

        String duplicated = "{\"sessionId\":\"" + sessionId + "\",\"sourceQuestionId\":\"" + sourceQuestionId
                + "\",\"kind\":\"SIMILAR_QUESTION\",\"question\":\"Which one is a prime?\","
                + "\"options\":[\"2\",\"2\",\"6\",\"8\"],\"difficulty\":2,\"hint\":\"Think.\",\"correctAnswer\":\"2\"}";
        mockMvc.perform(post("/api/student/ai-tutor/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(duplicated))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tutorQuestionCreationRejectsOutOfRangeDifficulty() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String sourceQuestionId = firstQuestionId(token, sessionId);

        for (int difficulty : List.of(0, 6)) {
            String outOfRange = "{\"sessionId\":\"" + sessionId + "\",\"sourceQuestionId\":\"" + sourceQuestionId
                    + "\",\"kind\":\"HARDER_QUESTION\",\"question\":\"Which one is a prime?\","
                    + "\"options\":[\"2\",\"4\",\"6\",\"8\"],\"difficulty\":" + difficulty
                    + ",\"hint\":\"Think.\",\"correctAnswer\":\"2\"}";
            mockMvc.perform(post("/api/student/ai-tutor/questions")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(outOfRange))
                    .andExpect(status().isBadRequest());
        }
    }

    // --- Grading is done server-side ---

    @Test
    void tutorQuestionIsGradedCorrectly() throws Exception {
        String token = login("student@example.com", "student123");
        AiTutorTestData data = readyTutorQuestion(token, "SIMILAR_QUESTION");

        mockMvc.perform(post("/api/student/ai-tutor/questions/" + data.questionId + "/answer")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + data.optionsCorrectId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correct").value(true))
                .andExpect(jsonPath("$.data.correctAnswer").isNotEmpty());
    }

    @Test
    void tutorQuestionIsGradedIncorrectlyWhenWrongOptionIsChosen() throws Exception {
        String token = login("student@example.com", "student123");
        AiTutorTestData data = readyTutorQuestion(token, "SIMILAR_QUESTION");

        mockMvc.perform(post("/api/student/ai-tutor/questions/" + data.questionId + "/answer")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + data.optionsWrongId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correct").value(false))
                .andExpect(jsonPath("$.data.correctAnswer").isNotEmpty());
    }

    @Test
    void aTutorQuestionCanOnlyBeAnsweredOnce() throws Exception {
        String token = login("student@example.com", "student123");
        AiTutorTestData data = readyTutorQuestion(token, "HARDER_QUESTION");

        mockMvc.perform(post("/api/student/ai-tutor/questions/" + data.questionId + "/answer")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + data.optionsCorrectId + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/student/ai-tutor/questions/" + data.questionId + "/answer")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + data.optionsWrongId + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void gradingRequiresAnOptionOfTheTutorQuestion() throws Exception {
        String token = login("student@example.com", "student123");
        AiTutorTestData data = readyTutorQuestion(token, "SIMILAR_QUESTION");

        mockMvc.perform(post("/api/student/ai-tutor/questions/" + data.questionId + "/answer")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"not-an-option\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void gradingRequiresAnOptionId() throws Exception {
        String token = login("student@example.com", "student123");
        AiTutorTestData data = readyTutorQuestion(token, "SIMILAR_QUESTION");

        mockMvc.perform(post("/api/student/ai-tutor/questions/" + data.questionId + "/answer")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void gradingForUnknownTutorQuestionReturnsNotFound() throws Exception {
        String token = login("student@example.com", "student123");
        mockMvc.perform(post("/api/student/ai-tutor/questions/does-not-exist/answer")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"whatever\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void forgedCorrectnessAndSelectedAnswerFieldsAreIgnoredWhenGrading() throws Exception {
        String token = login("student@example.com", "student123");
        AiTutorTestData data = readyTutorQuestion(token, "SIMILAR_QUESTION");

        mockMvc.perform(post("/api/student/ai-tutor/questions/" + data.questionId + "/answer")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + data.optionsWrongId + "\",\"correct\":true,"
                                + "\"correctAnswer\":\"8\",\"answered\":false,\"score\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correct").value(false))
                .andExpect(jsonPath("$.data.correctAnswer").value("2"));
    }

    // --- Isolation: tutor activity never touches official exams or progression ---

    @Test
    void tutorQuestionsAreIsolatedFromOfficialExamsAndAnswers() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String sourceQuestionId = firstQuestionId(token, sessionId);

        long examsBefore = count("exams");
        long questionsBefore = count("questions");
        long optionsBefore = count("question_options");
        long answersBefore = count("answers");
        long attemptsBefore = count("exam_attempts");
        long practiceAnswersBefore = count("ai_practice_answers");
        long tutorQuestionsBefore = count("ai_tutor_questions");

        AiTutorTestData data = readyTutorQuestionFrom(token, sessionId, sourceQuestionId, "HARDER_QUESTION");
        mockMvc.perform(post("/api/student/ai-tutor/questions/" + data.questionId + "/answer")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + data.optionsWrongId + "\"}"))
                .andExpect(status().isOk());

        assertThat(count("exams")).isEqualTo(examsBefore);
        assertThat(count("questions")).isEqualTo(questionsBefore);
        assertThat(count("question_options")).isEqualTo(optionsBefore);
        assertThat(count("answers")).isEqualTo(answersBefore);
        assertThat(count("exam_attempts")).isEqualTo(attemptsBefore);
        assertThat(count("ai_practice_answers")).isEqualTo(practiceAnswersBefore);
        assertThat(count("ai_tutor_questions")).isEqualTo(tutorQuestionsBefore + 1);
    }

    @Test
    void tutorQuestionsDoNotMutateProgression() throws Exception {
        String token = login("student@example.com", "student123");
        String sessionId = completedWrongSession(token);
        String sourceQuestionId = firstQuestionId(token, sessionId);

        JsonNode before = progress(token);
        AiTutorTestData data = readyTutorQuestionFrom(token, sessionId, sourceQuestionId, "HARDER_QUESTION");
        mockMvc.perform(post("/api/student/ai-tutor/questions/" + data.questionId + "/answer")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + data.optionsWrongId + "\"}"))
                .andExpect(status().isOk());

        JsonNode after = progress(token);
        assertThat(after).isEqualTo(before);
    }

    // --- helpers ---

    private JsonNode progress(String token) throws Exception {
        String response = mockMvc.perform(get("/api/student/ai-practice/progress")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private long count(String table) {
        Integer value = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private AiTutorTestData readyTutorQuestion(String token, String kind) throws Exception {
        String sessionId = completedWrongSession(token);
        String sourceQuestionId = firstQuestionId(token, sessionId);
        return readyTutorQuestionFrom(token, sessionId, sourceQuestionId, kind);
    }

    private AiTutorTestData readyTutorQuestionFrom(String token, String sessionId, String sourceQuestionId, String kind)
            throws Exception {
        String response = mockMvc.perform(post("/api/student/ai-tutor/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tutorQuestionBody(sessionId, sourceQuestionId, kind)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        String questionId = data.path("questionId").asText();
        JsonNode options = data.path("options");
        return new AiTutorTestData(questionId,
                options.get(0).path("id").asText(),
                options.get(1).path("id").asText());
    }

    private JsonNode questionOptions(String token, String sessionId, String questionId) throws Exception {
        String response = mockMvc.perform(get("/api/student/ai-practice/sessions/" + sessionId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode questions = objectMapper.readTree(response).path("data").path("questions");
        for (JsonNode node : questions) {
            if (questionId.equals(node.path("id").asText())) return node.path("options");
        }
        throw new AssertionError("Question not found: " + questionId);
    }

    private String completedWrongSession(String token) throws Exception {
        String sessionId = createSession(token);
        answerW(token, sessionId, 0);
        submit(token, sessionId);
        return sessionId;
    }

    private String completedCorrectSession(String token) throws Exception {
        String sessionId = createSession(token);
        answerC(token, sessionId, 0);
        submit(token, sessionId);
        return sessionId;
    }

    private String completedWithUnansweredQuestion(String token) throws Exception {
        String sessionId = createSession(token);
        submit(token, sessionId);
        return sessionId;
    }

    private String createSession(String token) throws Exception {
        return createSession(token, oneQuestionArray());
    }

    private String createSession(String token, String questionsJson) throws Exception {
        String response = mockMvc.perform(post("/api/student/ai-practice/sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"Algebra\",\"difficulty\":\"EASY\",\"questions\":" + questionsJson + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("sessionId").asText();
    }

    private String oneQuestionArray() {
        return "[{\"question\":\"What is 2+2?\",\"options\":[\"4\",\"5\",\"3\",\"6\"],\"correctOption\":\"4\",\"explanation\":\"2+2 equals 4.\"}]";
    }

    private void submit(String token, String sessionId) throws Exception {
        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/submit")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private void answerC(String token, String sessionId, int index) throws Exception {
        answerWith(token, sessionId, index, "4");
    }

    private void answerW(String token, String sessionId, int index) throws Exception {
        answerWith(token, sessionId, index, "5");
    }

    private void answerWith(String token, String sessionId, int index, String text) throws Exception {
        String questionId = questionIdByIndex(token, sessionId, index);
        String optionId = optionIdForText(token, sessionId, index, text);
        mockMvc.perform(post("/api/student/ai-practice/sessions/" + sessionId + "/answer")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"" + questionId + "\",\"optionId\":\"" + optionId + "\"}"))
                .andExpect(status().isOk());
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
        JsonNode options = questionOptions(token, sessionId, questionIdByIndex(token, sessionId, index));
        for (JsonNode option : options) {
            if (text.equals(option.path("text").asText())) return option.path("id").asText();
        }
        throw new AssertionError("Option not found: " + text);
    }

    private String tutorBody(String sessionId, String questionId, String action) {
        return "{\"sessionId\":\"" + sessionId + "\",\"questionId\":\"" + questionId + "\",\"action\":\"" + action + "\"}";
    }

    private String tutorQuestionBody(String sessionId, String sourceQuestionId) {
        return tutorQuestionBody(sessionId, sourceQuestionId, "SIMILAR_QUESTION");
    }

    private String tutorQuestionBody(String sessionId, String sourceQuestionId, String kind) {
        return "{\"sessionId\":\"" + sessionId + "\",\"sourceQuestionId\":\"" + sourceQuestionId
                + "\",\"kind\":\"" + kind
                + "\",\"question\":\"Which number is prime?\",\"options\":[\"2\",\"4\",\"6\",\"8\"],"
                + "\"difficulty\":3,\"hint\":\"Think about divisible.\",\"correctAnswer\":\"2\"}";
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

    private record AiTutorTestData(String questionId, String optionsCorrectId, String optionsWrongId) {
    }
}