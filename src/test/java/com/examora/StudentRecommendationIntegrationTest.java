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
        "spring.datasource.url=jdbc:h2:mem:examora-recommendations;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-recommendations-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class StudentRecommendationIntegrationTest {
    private static final String ENDPOINT = "/api/student/recommendations";

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

    @Test
    void newStudentGetsSafeBaselineRecommendation() throws Exception {
        JsonNode data = recommendations("student@example.com", "student123");
        JsonNode recs = data.path("recommendations");
        assertThat(recs.size()).isEqualTo(1);
        JsonNode rec = recs.get(0);
        assertThat(rec.path("code").asText()).isEqualTo("BUILD_BASELINE");
        assertThat(rec.path("priority").asText()).isEqualTo("LOW");
        assertThat(rec.path("action").asText()).isEqualTo("BASELINE");
        assertThat(rec.path("reasonCode").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(rec.path("confidence").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(rec.path("dimension").isNull()).isTrue();
        assertThat(rec.path("difficulty").isNull()).isTrue();
        assertThat(rec.path("targetQuestionCount").asInt()).isEqualTo(5);
        assertThat(rec.path("supportingMetrics").path("observations").asInt()).isZero();
        assertThat(rec.path("supportingMetrics").path("accuracy").isNull()).isTrue();
        assertThat(data.path("summary").path("primaryRecommendation").asText()).isEqualTo("BUILD_BASELINE");
        assertThat(data.path("summary").path("recommendationCount").asInt()).isEqualTo(1);
        assertThat(data.path("summary").path("dataSufficient").asBoolean()).isFalse();
        assertThat(data.path("generatedAt").asText()).isNotBlank();
    }

    @Test
    void strongEasyAndWeakHardPrioritizesHard() throws Exception {
        seedAnswerBlock("mix1", "Mix 1", 40, "2026-01-01",
                new int[]{1, 1, 1, 1, 1, 1, 5, 5, 5, 5, 5, 5},
                new boolean[]{true, true, true, true, true, true, true, false, false, false, false, false});

        JsonNode data = recommendations("student@example.com", "student123");
        JsonNode recs = data.path("recommendations");
        JsonNode first = recs.get(0);
        assertThat(first.path("code").asText()).isEqualTo("PRACTICE_HARD_QUESTIONS");
        assertThat(first.path("priority").asText()).isEqualTo("HIGH");
        assertThat(first.path("action").asText()).isEqualTo("PRACTICE");
        assertThat(first.path("dimension").asText()).isEqualTo("HARD_DIFFICULTY");
        assertThat(first.path("difficulty").asText()).isEqualTo("HARD");
        assertThat(first.path("reasonCode").asText()).isEqualTo("LOW_HARD_ACCURACY");
        assertThat(first.path("targetQuestionCount").asInt()).isEqualTo(10);
        assertThat(first.path("supportingMetrics").path("accuracy").asDouble()).isEqualTo(16.67);
        assertThat(first.path("supportingMetrics").path("observations").asInt()).isEqualTo(6);
        assertThat(first.path("supportingMetrics").path("recentlyPracticed").asBoolean()).isFalse();

        JsonNode easyMaintain = find(recs, "dimension", "EASY_DIFFICULTY");
        assertThat(easyMaintain.path("code").asText()).isEqualTo("MAINTAIN_STRENGTH");
        assertThat(easyMaintain.path("targetQuestionCount").asInt()).isEqualTo(5);
        assertThat(recs.path("1").path("code").asText()).isNotEqualTo("PRACTICE_HARD_QUESTIONS");
    }

    @Test
    void mediumWeaknessYieldsMediumRecommendation() throws Exception {
        seedAnswerBlock("mix2", "Mix 2", 50, "2026-01-01",
                new int[]{3, 3, 3, 3, 3},
                new boolean[]{true, true, false, false, false});

        JsonNode recs = recommendations("student@example.com", "student123").path("recommendations");
        JsonNode medium = find(recs, "dimension", "MEDIUM_DIFFICULTY");
        assertThat(medium.path("code").asText()).isEqualTo("PRACTICE_MEDIUM_QUESTIONS");
        assertThat(medium.path("difficulty").asText()).isEqualTo("MEDIUM");
        assertThat(medium.path("priority").asText()).isEqualTo("HIGH");
        assertThat(medium.path("supportingMetrics").path("accuracy").asDouble()).isEqualTo(40.0);
    }

    @Test
    void severeWeaknessIsHighPriority() throws Exception {
        seedAnswerBlock("mix3", "Mix 3", 30, "2026-01-01",
                new int[]{5, 5, 5, 5, 5, 5},
                new boolean[]{true, false, false, false, false, false});

        JsonNode recs = recommendations("student@example.com", "student123").path("recommendations");
        JsonNode hard = find(recs, "code", "PRACTICE_HARD_QUESTIONS");
        assertThat(hard.path("priority").asText()).isEqualTo("HIGH");
        assertThat(hard.path("reasonCode").asText()).isEqualTo("LOW_HARD_ACCURACY");
        assertThat(hard.path("confidence").asText()).isEqualTo("LOW_CONFIDENCE");
        assertThat(hard.path("targetQuestionCount").asInt()).isEqualTo(10);
        assertThat(hard.path("supportingMetrics").path("accuracy").asDouble()).isEqualTo(16.67);
        assertThat(hard.path("supportingMetrics").path("observations").asInt()).isEqualTo(6);
    }

    @Test
    void recentDeclineIncreasesPriorityOfReview() throws Exception {
        seedResultsOnly(new int[]{90, 85, 60, 40}, "d");

        JsonNode data = recommendations("student@example.com", "student123");
        assertThat(data.path("summary").path("recommendationCount").asInt()).isEqualTo(1);
        JsonNode rec = data.path("recommendations").get(0);
        assertThat(rec.path("code").asText()).isEqualTo("REVIEW_RECENT_EXAMS");
        assertThat(rec.path("priority").asText()).isEqualTo("HIGH");
        assertThat(rec.path("action").asText()).isEqualTo("REVIEW");
        assertThat(rec.path("dimension").asText()).isEqualTo("RECENT_PERFORMANCE");
        assertThat(rec.path("reasonCode").asText()).isEqualTo("RECENT_DECLINE");
        assertThat(rec.path("targetQuestionCount").asInt()).isEqualTo(10);
        assertThat(rec.path("supportingMetrics").path("recentAccuracy").asDouble()).isEqualTo(50.0);
        assertThat(rec.path("supportingMetrics").path("previousAccuracy").asDouble()).isEqualTo(87.5);
    }

    @Test
    void recentImprovementDoesNotCreateFalseWeakness() throws Exception {
        seedAnswerBlock("imp1", "Imp 1", 40, "2026-01-01",
                new int[]{1, 1, 1, 5, 5, 5}, new boolean[]{true, true, true, true, true, true});
        seedAnswerBlock("imp2", "Imp 2", 60, "2026-02-01",
                new int[]{1, 1, 1, 5, 5, 5}, new boolean[]{true, true, true, true, true, true});
        seedAnswerBlock("imp3", "Imp 3", 70, "2026-03-01",
                new int[]{1, 1, 1, 5, 5, 5}, new boolean[]{true, true, true, true, true, true});
        seedAnswerBlock("imp4", "Imp 4", 90, "2026-04-01",
                new int[]{1, 1, 1, 5, 5, 5}, new boolean[]{true, true, true, true, true, true});

        JsonNode data = recommendations("student@example.com", "student123");
        JsonNode recs = data.path("recommendations");
        assertThat(data.path("summary").path("dataSufficient").asBoolean()).isTrue();
        for (JsonNode rec : recs) {
            assertThat(rec.path("code").asText()).isEqualTo("MAINTAIN_STRENGTH");
        }
        assertThat(recs.size()).isGreaterThanOrEqualTo(3);
        assertThat(find(recs, "code", "REVIEW_RECENT_EXAMS").path("code").asText("")).isEmpty();
    }

    @Test
    void insufficientDataDoesNotFabricateWeakness() throws Exception {
        seedAnswerBlock("insuf", "Insuf", 30, "2026-01-01",
                new int[]{5, 5, 5}, new boolean[]{true, false, false});

        JsonNode data = recommendations("student@example.com", "student123");
        JsonNode recs = data.path("recommendations");
        assertThat(data.path("summary").path("dataSufficient").asBoolean()).isFalse();
        assertThat(find(recs, "code", "PRACTICE_HARD_QUESTIONS").path("code").asText("")).isEmpty();
        assertThat(recs.size()).isEqualTo(1);
        assertThat(recs.get(0).path("code").asText()).isEqualTo("BUILD_BASELINE");
        assertThat(recs.get(0).path("supportingMetrics").path("observations").asInt()).isEqualTo(3);
        assertThat(recs.get(0).path("supportingMetrics").path("accuracy").isNull()).isTrue();
    }

    @Test
    void persistentWeaknessRemainsActionableAfterRecentPractice() throws Exception {
        for (int i = 1; i <= 4; i++) {
            seedAnswerBlock("pe" + i, "Persist " + i, 50, "2026-0" + i + "-01",
                    new int[]{5, 5, 5, 5}, new boolean[]{true, true, false, false});
        }
        seedRecentPracticeSession("ps-persist", "student-1", "pe1",
                new String[]{"pe1-q0", "pe1-q1", "pe1-q2", "pe1-q3"},
                new int[]{5, 5, 5, 5}, new boolean[]{true, true, false, false});

        JsonNode recs = recommendations("student@example.com", "student123").path("recommendations");
        JsonNode hard = find(recs, "code", "PRACTICE_HARD_QUESTIONS");
        assertThat(hard.path("priority").asText()).isEqualTo("HIGH");
        assertThat(hard.path("targetQuestionCount").asInt()).isEqualTo(10);
        assertThat(hard.path("supportingMetrics").path("recentlyPracticed").asBoolean()).isTrue();
        assertThat(hard.path("supportingMetrics").path("accuracy").asDouble()).isEqualTo(50.0);
    }

    @Test
    void recommendationOrderingIsDeterministic() throws Exception {
        seedAnswerBlock("ord1", "Ord 1", 60, "2026-01-01",
                new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 5, 5, 5, 5, 5, 5},
                new boolean[]{true, true, true, true, true, true, true, true, true, true, true, true,
                        true, true, false, false, false, false, true, false, true, false, false, false, false, false});

        JsonNode recs = recommendations("student@example.com", "student123").path("recommendations");
        String[] expected = {"PRACTICE_HARD_QUESTIONS", "PRACTICE_MEDIUM_QUESTIONS", "MAINTAIN_STRENGTH"};
        assertThat(recs.size()).isGreaterThanOrEqualTo(expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(recs.get(i).path("code").asText()).isEqualTo(expected[i]);
        }
    }

    @Test
    void targetQuestionCountIsDeterministic() throws Exception {
        seedAnswerBlock("tgt", "Tgt", 60, "2026-01-01",
                new int[]{1, 1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 5, 5, 5, 5, 5, 5},
                new boolean[]{true, true, true, true, true, true, true, true, true, false, false, true, false, false, false, false, false});

        JsonNode recs = recommendations("student@example.com", "student123").path("recommendations");
        assertThat(find(recs, "code", "PRACTICE_HARD_QUESTIONS").path("targetQuestionCount").asInt()).isEqualTo(10);
        assertThat(find(recs, "code", "PRACTICE_MEDIUM_QUESTIONS").path("targetQuestionCount").asInt()).isEqualTo(8);
        assertThat(find(recs, "code", "MAINTAIN_STRENGTH").path("targetQuestionCount").asInt()).isEqualTo(5);
    }

    @Test
    void recentEquivalentPracticeAffectsRecommendationAppropriately() throws Exception {
        seedAnswerBlock("mpx", "Mix", 70, "2026-01-01",
                new int[]{1, 1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 5, 5, 5, 5, 5, 5},
                new boolean[]{true, true, true, true, true, true,
                        true, true, false, false, true, true, false, false,
                        true, true, true, true, false, false});
        seedRecentPracticeSession("ps-near", "student-1", "mpx",
                new String[]{"mpx-q6", "mpx-q7", "mpx-q8", "mpx-q9", "mpx-q10", "mpx-q14", "mpx-q15", "mpx-q16", "mpx-q17", "mpx-q18"},
                new int[]{3, 3, 3, 3, 3, 5, 5, 5, 5, 5},
                new boolean[]{true, true, true, true, true, true, true, true, true, true});

        JsonNode recs = recommendations("student@example.com", "student123").path("recommendations");
        assertThat(find(recs, "code", "PRACTICE_MEDIUM_QUESTIONS").path("supportingMetrics")
                .path("recentlyPracticed").asBoolean()).isTrue();
        assertThat(find(recs, "code", "PRACTICE_MEDIUM_QUESTIONS").path("priority").asText()).isEqualTo("MEDIUM");
        assertThat(find(recs, "code", "PRACTICE_MEDIUM_QUESTIONS").path("targetQuestionCount").asInt()).isEqualTo(8);
        assertThat(find(recs, "code", "PRACTICE_HARD_QUESTIONS").path("code").asText("")).isEmpty();
        JsonNode easyMaintain = find(recs, "dimension", "EASY_DIFFICULTY");
        assertThat(easyMaintain.path("code").asText()).isEqualTo("MAINTAIN_STRENGTH");
        assertThat(easyMaintain.path("supportingMetrics").path("recentlyPracticed").asBoolean()).isFalse();
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
    }

    @Test
    void roleIsolationBlocksTeachersAndAdmins() throws Exception {
        insertAdmin();
        mockMvc.perform(get(ENDPOINT)
                        .header("Authorization", bearer(login("teacher@example.com", "teacher123"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(ENDPOINT)
                        .header("Authorization", bearer(login("admin@example.com", "admin123"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void noCrossStudentLeakage() throws Exception {
        seedAnswerBlock("leak", "Leak", 30, "2026-01-01",
                new int[]{5, 5, 5, 5, 5, 5}, new boolean[]{true, false, false, false, false, false});

        String s1 = login("student@example.com", "student123");
        String s2 = login("student2@example.com", "student234");
        JsonNode d1 = data(call(s1, null));
        JsonNode d2 = data(call(s2, null));
        JsonNode d2WithParam = data(call(s2, "studentId=student-1"));
        assertThat(find(d1.path("recommendations"), "code", "PRACTICE_HARD_QUESTIONS")
                .path("code").asText("")).isEqualTo("PRACTICE_HARD_QUESTIONS");
        assertThat(d2.path("summary").path("primaryRecommendation").asText()).isEqualTo("BUILD_BASELINE");
        assertThat(d2WithParam.path("recommendations")).isEqualTo(d2.path("recommendations"));
        assertThat(d2WithParam.path("summary")).isEqualTo(d2.path("summary"));
    }

    @Test
    void repeatedRequestReturnsDeterministicResult() throws Exception {
        seedAnswerBlock("det", "Det", 60, "2026-01-01",
                new int[]{1, 1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 5, 5, 5, 5, 5, 5},
                new boolean[]{true, true, true, true, true, true,
                        true, false, false, false, false, false, false, false,
                        true, false, false, false, false, false});

        String token = login("student@example.com", "student123");
        JsonNode first = data(call(token, null));
        JsonNode second = data(call(token, null));
        assertThat(first.path("recommendations")).isEqualTo(second.path("recommendations"));
        assertThat(first.path("summary")).isEqualTo(second.path("summary"));
    }

    // ---------- helpers ----------

    private JsonNode recommendations(String email, String password) throws Exception {
        JsonNode root = objectMapper.readTree(call(login(email, password), null));
        return root.path("data");
    }

    private JsonNode data(String body) throws Exception {
        return objectMapper.readTree(body).path("data");
    }

    private String call(String token, String query) throws Exception {
        var request = get(ENDPOINT).header("Authorization", bearer(token));
        if (query != null) {
            request = get(ENDPOINT + "?" + query).header("Authorization", bearer(token));
        }
        var result = mockMvc.perform(request).andReturn();
        if (result.getResponse().getStatus() != 200) {
            System.out.println("=== STATUS " + result.getResponse().getStatus() + " BODY: " + result.getResponse().getContentAsString());
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private JsonNode find(JsonNode array, String key, String value) {
        if (array == null || !array.isArray()) {
            return array == null ? objectMapper.createObjectNode() : array;
        }
        for (JsonNode node : array) {
            if (node.path(key).asText().equals(value)) {
                return node;
            }
        }
        return objectMapper.createObjectNode();
    }

    private void seedResultsOnly(int[] scores, String prefix) {
        String[] months = {"01", "02", "03", "04", "05", "06"};
        for (int i = 0; i < scores.length; i++) {
            String examId = prefix + (i + 1);
            seedExam(examId, "Series " + (i + 1), "Math");
            jdbcTemplate.update(
                    "insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    "r-" + examId, "student-1", examId, "Series " + (i + 1), "Math",
                    scores[i], "2026-" + months[i] + "-01", 100);
        }
    }

    /** Seeds one exam + attempt + questions/answers with per-question difficulties + one result. */
    private void seedAnswerBlock(String examId, String title, int score, String date,
                                 int[] difficulties, boolean[] correct) {
        seedExam(examId, title, "Math");
        String attemptId = "attempt-" + examId;
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, submitted_at, version) "
                        + "values (?, ?, ?, 1, 'SUBMITTED', current_timestamp, current_timestamp, current_timestamp, 0)",
                attemptId, examId, "student-1");
        StringBuilder questions = new StringBuilder("insert into questions (id, exam_id, text, answer, difficulty) values ");
        for (int i = 0; i < difficulties.length; i++) {
            if (i > 0) {
                questions.append(",");
            }
            questions.append("('").append(examId).append("-q").append(i).append("','").append(examId)
                    .append("','Q?','x',").append(difficulties[i]).append(")");
        }
        jdbcTemplate.update(questions.toString());
        StringBuilder answers = new StringBuilder(
                "insert into answers (id, user_id, exam_id, question_id, answer_value, attempt_id, correct) values ");
        for (int i = 0; i < correct.length; i++) {
            if (i > 0) {
                answers.append(",");
            }
            answers.append("('").append(examId).append("-a").append(i).append("','student-1','").append(examId)
                    .append("','").append(examId).append("-q").append(i).append("','x','").append(attemptId)
                    .append("',").append(correct[i]).append(")");
        }
        jdbcTemplate.update(answers.toString());
        jdbcTemplate.update(
                "insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                "result-" + examId, "student-1", examId, title, "Math", score, date, 100);
    }

    private void seedExam(String id, String title, String subject) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, ?, '2026-09-01', 60, 'COMPLETED', 0, null, ?)",
                id, title, subject, "teacher-1");
    }

    /** Seeds a COMPLETED practice session at the current timestamp with the given answers. */
    private void seedRecentPracticeSession(String sessionId, String studentId, String examId,
                                           String[] questionIds, int[] difficulties, boolean[] correct) {
        jdbcTemplate.update(
                "insert into practice_sessions (id, student_id, exam_id, status, target_question_count, "
                        + "working_difficulty, answered_count, correct_count, started_at, last_activity_at, completed_at) "
                        + "values (?, ?, ?, 'COMPLETED', 10, 3.0, ?, ?, current_timestamp, current_timestamp, current_timestamp)",
                sessionId, studentId, examId, questionIds.length, countTrue(correct));
        StringBuilder values = new StringBuilder(
                "insert into practice_answers (id, session_id, question_id, answer_value, correct, difficulty, sequence_index, answered_at) values ");
        for (int i = 0; i < questionIds.length; i++) {
            if (i > 0) {
                values.append(",");
            }
            values.append("('pa-").append(sessionId).append("-").append(i).append("','").append(sessionId)
                    .append("','").append(questionIds[i]).append("','x',").append(correct[i]).append(",")
                    .append(difficulties[i]).append(",").append(i)
                    .append(",current_timestamp)");
        }
        jdbcTemplate.update(values.toString());
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

    private void insertAdmin() {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                "admin-1", "Admin", "admin@example.com", passwordEncoder.encode("admin123"), "ADMIN");
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