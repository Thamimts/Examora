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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-learning-intelligence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-intelligence-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class LearningIntelligenceIntegrationTest {
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
    void strongDifficultyPerformanceProducedStrength() throws Exception {
        seedExam("exam-easy", "Easy Exam", "Math");
        seedQuestions("exam-easy", new String[]{"eq0", "eq1", "eq2", "eq3", "eq4", "eq5"}, 1);
        seedAttempt("attempt-easy", "exam-easy", "student-1");
        seedAnswers("student-1", "exam-easy", "attempt-easy", new String[]{"eq0", "eq1", "eq2", "eq3", "eq4", "eq5"},
                new boolean[]{true, true, true, true, true, true});

        JsonNode data = intelligence("student@example.com", "student123");
        assertThat(list(data, "strengths")).contains("EASY_DIFFICULTY");
        JsonNode easy = find(data, "strengths", "dimension", "EASY_DIFFICULTY");
        assertThat(easy.path("accuracy").asDouble()).isEqualTo(100.0);
        assertThat(easy.path("observations").asInt()).isEqualTo(6);
        assertThat(easy.path("signal").asText()).isEqualTo("HIGH_EASY_ACCURACY");
        assertThat(data.path("dataQuality").path("sufficientData").asBoolean()).isTrue();
    }

    @Test
    void weakDifficultyPerformanceProducesWeakness() throws Exception {
        seedExam("exam-hard", "Hard Exam", "Math");
        seedQuestions("exam-hard", new String[]{"hq0", "hq1", "hq2", "hq3", "hq4", "hq5"}, 5);
        seedAttempt("attempt-hard", "exam-hard", "student-1");
        seedAnswers("student-1", "exam-hard", "attempt-hard",
                new String[]{"hq0", "hq1", "hq2", "hq3", "hq4", "hq5"},
                new boolean[]{true, true, false, false, false, false});

        JsonNode data = intelligence("student@example.com", "student123");
        JsonNode hard = find(data, "weaknesses", "dimension", "HARD_DIFFICULTY");
        assertThat(hard).isNotNull();
        assertThat(hard.path("accuracy").asDouble()).isEqualTo(33.33);
        assertThat(hard.path("signal").asText()).isEqualTo("LOW_HARD_ACCURACY");
        assertThat(hard.path("severity").asText()).isEqualTo("HIGH");
    }

    @Test
    void severeWeaknessIsClassifiedHighSeverity() throws Exception {
        seedExam("exam-med", "Medium Exam", "Math");
        seedQuestions("exam-med", new String[]{"mq0", "mq1", "mq2", "mq3", "mq4"}, 3);
        seedAttempt("attempt-med", "exam-med", "student-1");
        seedAnswers("student-1", "exam-med", "attempt-med",
                new String[]{"mq0", "mq1", "mq2", "mq3", "mq4"},
                new boolean[]{true, false, false, false, false});

        JsonNode data = intelligence("student@example.com", "student123");
        JsonNode med = find(data, "weaknesses", "dimension", "MEDIUM_DIFFICULTY");
        assertThat(med.path("accuracy").asDouble()).isEqualTo(20.0);
        assertThat(med.path("severity").asText()).isEqualTo("HIGH");
        assertThat(med.path("confidence").asText()).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    void insufficientSampleSizeProducesNoSignal() throws Exception {
        seedExam("exam-tiny", "Tiny Exam", "Math");
        seedQuestions("exam-tiny", new String[]{"tq0", "tq1", "tq2"}, 1);
        seedAttempt("attempt-tiny", "exam-tiny", "student-1");
        seedAnswers("student-1", "exam-tiny", "attempt-tiny",
                new String[]{"tq0", "tq1", "tq2"}, new boolean[]{true, true, true});

        JsonNode data = intelligence("student@example.com", "student123");
        assertThat(list(data, "strengths")).isEmpty();
        assertThat(list(data, "weaknesses")).isEmpty();
        assertThat(data.path("dataQuality").path("observationCount").asInt()).isEqualTo(3);
        assertThat(data.path("dataQuality").path("sufficientData").asBoolean()).isFalse();
    }

    @Test
    void highPriorityWeaknessHasHighPriority() throws Exception {
        String[] q = new String[25];
        boolean[] correct = new boolean[25];
        for (int i = 0; i < 25; i++) {
            q[i] = "xq" + i;
            correct[i] = i < 7;
        }
        seedExam("exam-big", "Big Exam", "Math");
        seedQuestions("exam-big", q, 5);
        seedAttempt("attempt-big", "exam-big", "student-1");
        seedAnswers("student-1", "exam-big", "attempt-big", q, correct);

        JsonNode data = intelligence("student@example.com", "student123");
        JsonNode hard = find(data, "weaknesses", "dimension", "HARD_DIFFICULTY");
        assertThat(hard.path("accuracy").asDouble()).isEqualTo(28.0);
        assertThat(hard.path("confidence").asText()).isEqualTo("HIGH_CONFIDENCE");
        JsonNode priority = find(data, "priorities", "dimension", "HARD_DIFFICULTY");
        assertThat(priority.path("priorityLevel").asText()).isEqualTo("HIGH");
    }

    @Test
    void mediumPriorityWeaknessHasMediumPriority() throws Exception {
        seedExam("exam-medp", "Med Priority Exam", "Math");
        seedQuestions("exam-medp", new String[]{"p0", "p1", "p2", "p3", "p4", "p5", "p6"}, 5);
        seedAttempt("attempt-medp", "exam-medp", "student-1");
        seedAnswers("student-1", "exam-medp", "attempt-medp",
                new String[]{"p0", "p1", "p2", "p3", "p4", "p5", "p6"},
                new boolean[]{true, true, true, true, false, false, false});

        JsonNode data = intelligence("student@example.com", "student123");
        JsonNode hard = find(data, "weaknesses", "dimension", "HARD_DIFFICULTY");
        assertThat(hard.path("accuracy").asDouble()).isEqualTo(57.14);
        assertThat(hard.path("severity").asText()).isEqualTo("LOW");
        JsonNode priority = find(data, "priorities", "dimension", "HARD_DIFFICULTY");
        assertThat(priority.path("priorityLevel").asText()).isEqualTo("MEDIUM");
    }

    @Test
    void recommendationsAreDeterministicCodes() throws Exception {
        seedExam("exam-recs", "Recs Exam", "Math");
        seedQuestions("exam-recs", new String[]{"r0", "r1", "r2", "r3", "r4", "r5"}, 5);
        seedAttempt("attempt-recs", "exam-recs", "student-1");
        seedAnswers("student-1", "exam-recs", "attempt-recs",
                new String[]{"r0", "r1", "r2", "r3", "r4", "r5"},
                new boolean[]{true, true, false, false, false, false});
        seedQuestionsForAnswers("exam-recs", new String[]{"pr0", "pr1", "pr2", "pr3", "pr4", "pr5"}, 5);
        seedPractice("student-1", "exam-recs",
                new String[]{"pr0", "pr1", "pr2", "pr3", "pr4", "pr5"},
                new boolean[]{true, true, false, false, false, false});

        JsonNode data = intelligence("student@example.com", "student123");
        JsonNode recs = data.path("recommendations");
        assertThat(recs.size()).isEqualTo(3);
        assertThat(recs.get(0).path("code").asText()).isEqualTo("REVIEW_RECENT_EXAMS");
        assertThat(recs.get(1).path("code").asText()).isEqualTo("PRACTICE_HARD_QUESTIONS");
        assertThat(recs.get(2).path("code").asText()).isEqualTo("INCREASE_PRACTICE");
    }

    @Test
    void decliningRecentPerformanceProducesWeakness() throws Exception {
        String[] exams = {"rpt1", "rpt2", "rpt3", "rpt4"};
        String[] dates = {"2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01"};
        int[] scores = {90, 85, 60, 40};
        for (int i = 0; i < 4; i++) {
            seedExam(exams[i], "Recent " + i, "Math");
            insertResult("res-" + i, "student-1", exams[i], "Recent " + i, "Math", scores[i], 100, dates[i]);
        }
        seedQuestionsForAnswers("rpt3", new String[]{"qrpt3a", "qrpt3b"}, 2);
        seedQuestionsForAnswers("rpt4", new String[]{"qrpt4a", "qrpt4b"}, 2);
        seedAttempt("attempt-rpt3", "rpt3", "student-1");
        seedAttempt("attempt-rpt4", "rpt4", "student-1");
        seedAnswers("student-1", "rpt3", "attempt-rpt3",
                new String[]{"qrpt3a", "qrpt3b"}, new boolean[]{true, false});
        seedAnswers("student-1", "rpt4", "attempt-rpt4",
                new String[]{"qrpt4a", "qrpt4b"}, new boolean[]{true, false});

        JsonNode data = intelligence("student@example.com", "student123");
        JsonNode recent = find(data, "weaknesses", "dimension", "RECENT_PERFORMANCE");
        assertThat(recent).isNotNull();
        assertThat(recent.path("signal").asText()).isEqualTo("RECENT_PERFORMANCE_DECLINING");
        assertThat(recent.path("accuracy").asDouble()).isEqualTo(50.0);
    }

    @Test
    void variableConsistencyProducesWeakness() throws Exception {
        seedExam("con1", "Con 1", "Math");
        seedExam("con2", "Con 2", "Math");
        seedExam("con3", "Con 3", "Math");
        insertResult("c1", "student-1", "con1", "Con 1", "Math", 10, 100, "2026-01-01");
        insertResult("c2", "student-1", "con2", "Con 2", "Math", 50, 100, "2026-02-01");
        insertResult("c3", "student-1", "con3", "Con 3", "Math", 90, 100, "2026-03-01");

        JsonNode data = intelligence("student@example.com", "student123");
        JsonNode consistency = find(data, "weaknesses", "dimension", "CONSISTENCY");
        assertThat(consistency).isNotNull();
        assertThat(consistency.path("signal").asText()).isEqualTo("CONSISTENCY_VARIABLE");
        assertThat(consistency.path("severity").asText()).isEqualTo("MEDIUM");
    }

    @Test
    void emptyStudentProducesNoStrengthsOrWeaknesses() throws Exception {
        JsonNode data = intelligence("student@example.com", "student123");
        assertThat(list(data, "strengths")).isEmpty();
        assertThat(list(data, "weaknesses")).isEmpty();
        assertThat(data.path("priorities").size()).isZero();
        assertThat(data.path("recommendations").size()).isZero();
        assertThat(data.path("dataQuality").path("observationCount").asInt()).isZero();
        assertThat(data.path("dataQuality").path("sufficientData").asBoolean()).isFalse();
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/student/learning-intelligence")).andExpect(status().isUnauthorized());
    }

    @Test
    void roleIsolationBlocksTeachersAndAdmins() throws Exception {
        insertAdmin();
        mockMvc.perform(get("/api/student/learning-intelligence")
                        .header("Authorization", bearer(login("teacher@example.com", "teacher123"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/student/learning-intelligence")
                        .header("Authorization", bearer(login("admin@example.com", "admin123"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void noCrossStudentLeakage() throws Exception {
        String s1 = login("student@example.com", "student123");
        String s2 = login("student2@example.com", "student234");
        seedExam("exam-leak", "Leak Exam", "Math");
        seedQuestions("exam-leak", new String[]{"lq0", "lq1", "lq2", "lq3", "lq4", "lq5"}, 5);
        seedAttempt("attempt-leak", "exam-leak", "student-1");
        seedAnswers("student-1", "exam-leak", "attempt-leak",
                new String[]{"lq0", "lq1", "lq2", "lq3", "lq4", "lq5"},
                new boolean[]{true, true, false, false, false, false});

        String own = call(s1, null);
        String other = call(s2, null);
        String otherWithParam = call(s2, "studentId=student-1");
        assertThat(list(objectMapper.readTree(own).path("data"), "weaknesses").size()).isGreaterThan(0);
        assertThat(list(objectMapper.readTree(other).path("data"), "weaknesses")).isEmpty();
        assertThat(other).isEqualTo(otherWithParam);
    }

    @Test
    void repeatedCallsReturnDeterministicResults() throws Exception {
        String token = login("student@example.com", "student123");
        seedExam("exam-det", "Det Exam", "Math");
        seedQuestions("exam-det", new String[]{"d0", "d1", "d2", "d3", "d4", "d5"}, 5);
        seedAttempt("attempt-det", "exam-det", "student-1");
        seedAnswers("student-1", "exam-det", "attempt-det",
                new String[]{"d0", "d1", "d2", "d3", "d4", "d5"},
                new boolean[]{true, true, false, false, false, false});

        String first = call(token, null);
        String second = call(token, null);
        assertThat(first).isEqualTo(second);
    }

    // ---------- helpers ----------

    private JsonNode intelligence(String email, String password) throws Exception {
        String body = call(login(email, password), null);
        return objectMapper.readTree(body).path("data");
    }

    private String call(String token, String query) throws Exception {
        var request = get("/api/student/learning-intelligence").header("Authorization", bearer(token));
        if (query != null) {
            request = get("/api/student/learning-intelligence?" + query).header("Authorization", bearer(token));
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private java.util.List<String> list(JsonNode data, String field) {
        java.util.List<String> values = new java.util.ArrayList<>();
        if (data.path(field).isArray()) {
            data.path(field).forEach(node -> values.add(node.path("dimension").asText()));
        }
        return values;
    }

    private JsonNode find(JsonNode data, String field, String key, String value) {
        if (data.path(field).isArray()) {
            for (JsonNode node : data.path(field)) {
                if (value.equals(node.path(key).asText())) {
                    return node;
                }
            }
        }
        return objectMapper.createObjectNode();
    }

    private void seedExam(String id, String title, String subject) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, ?, '2026-09-01', 60, 'UPCOMING', 0, null, ?)",
                id, title, subject, "teacher-1");
    }

    private void seedQuestions(String examId, String[] questions, int difficulty) {
        seedQuestionsForAnswers(examId, questions, difficulty);
    }

    private void seedQuestionsForAnswers(String examId, String[] questions, int difficulty) {
        StringBuilder values = new StringBuilder("insert into questions (id, exam_id, text, answer, difficulty) values ");
        for (int i = 0; i < questions.length; i++) {
            if (i > 0) {
                values.append(",");
            }
            values.append("('").append(questions[i]).append("','").append(examId).append("','Q?','x',").append(difficulty).append(")");
        }
        jdbcTemplate.update(values.toString());
    }

    private void seedAttempt(String id, String examId, String studentId) {
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, submitted_at, version) "
                        + "values (?, ?, ?, 1, 'SUBMITTED', current_timestamp, current_timestamp, current_timestamp, 0)",
                id, examId, studentId);
    }

    private void seedAnswers(String studentId, String examId, String attemptId, String[] questions, boolean[] correct) {
        StringBuilder values = new StringBuilder("insert into answers (id, user_id, exam_id, question_id, answer_value, attempt_id, correct) values ");
        for (int i = 0; i < questions.length; i++) {
            if (i > 0) {
                values.append(",");
            }
            values.append("('ans-").append(attemptId).append("-").append(i).append("','").append(studentId)
                    .append("','").append(examId).append("','").append(questions[i]).append("','x','")
                    .append(attemptId).append("',").append(correct[i]).append(")");
        }
        jdbcTemplate.update(values.toString());
    }

    private void insertResult(String id, String userId, String examId, String examTitle,
                              String subject, int score, int total, String date) {
        jdbcTemplate.update(
                "insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, userId, examId, examTitle, subject, score, date, total);
    }

    private void seedPractice(String studentId, String examId, String[] questions, boolean[] correct) {
        jdbcTemplate.update(
                "insert into practice_sessions (id, student_id, exam_id, status, target_question_count, "
                        + "working_difficulty, answered_count, correct_count, started_at, last_activity_at, completed_at) "
                        + "values ('ps-recs', ?, ?, 'COMPLETED', 10, 3.0, 6, 2, current_timestamp, current_timestamp, current_timestamp)",
                studentId, examId);
        StringBuilder values = new StringBuilder("insert into practice_answers (id, session_id, question_id, answer_value, correct, difficulty, sequence_index, answered_at) values ");
        for (int i = 0; i < questions.length; i++) {
            if (i > 0) {
                values.append(",");
            }
            values.append("('pa-recs-").append(i).append("','ps-recs','").append(questions[i])
                    .append("','x',").append(correct[i]).append(",5,").append(i).append(",current_timestamp)");
        }
        jdbcTemplate.update(values.toString());
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
        JsonNode token = objectMapper.readTree(response).path("data").path("token");
        assertThat(token.asText()).isNotBlank();
        return token.asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}