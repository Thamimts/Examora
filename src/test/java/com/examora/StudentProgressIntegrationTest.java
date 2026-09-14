package com.examora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
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
        "spring.datasource.url=jdbc:h2:mem:examora-progress;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-progress-intelligence-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class StudentProgressIntegrationTest {
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
    void emptyStudentReturnsInsufficientEverything() throws Exception {
        JsonNode d = progress("student@example.com", "student123");
        assertThat(d.path("overall").path("direction").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(d.path("overall").path("recentAverage").isNull()).isTrue();
        assertThat(d.path("overall").path("previousAverage").isNull()).isTrue();
        assertThat(d.path("overall").path("delta").isNull()).isTrue();
        assertThat(d.path("examProgress").path("history").size()).isZero();
        assertThat(d.path("practiceProgress").path("direction").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(d.path("practiceProgress").path("recentQuestions").asInt()).isZero();
        assertThat(d.path("difficultyProgress").path("easy").path("direction").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(d.path("difficultyProgress").path("medium").path("direction").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(d.path("difficultyProgress").path("hard").path("direction").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(d.path("intelligenceChanges").size()).isZero();
        assertThat(d.path("milestones").size()).isZero();
        assertThat(d.path("dataQuality").path("sufficientForTrend").asBoolean()).isFalse();
        assertThat(d.path("dataQuality").path("completedExamCount").asInt()).isZero();
        assertThat(d.path("dataQuality").path("practiceObservationCount").asInt()).isZero();
    }

    @Test
    void singleCompletedExamIsInsufficientTrend() throws Exception {
        seedExam("e1", "Exam 1", "Math");
        seedResult("r1", "student-1", "e1", "Exam 1", 50, 100, "2026-01-01");

        JsonNode d = progress("student@example.com", "student123");
        assertThat(d.path("overall").path("direction").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(d.path("overall").path("recentAverage").isNull()).isTrue();
        assertThat(d.path("examProgress").path("history").size()).isEqualTo(1);
        JsonNode point = d.path("examProgress").path("history").get(0);
        assertThat(point.path("examId").asText()).isEqualTo("e1");
        assertThat(point.path("percentage").asDouble()).isEqualTo(50.0);
        assertThat(d.path("dataQuality").path("sufficientForTrend").asBoolean()).isFalse();
        assertThat(d.path("dataQuality").path("completedExamCount").asInt()).isEqualTo(1);
        assertThat(codes(d, "milestones")).containsExactly("FIRST_COMPLETED_EXAM");
    }

    @Test
    void fourExamsProduceCorrectRecentVsPrevious() throws Exception {
        seedResultSeries(new int[]{50, 60, 70, 80});
        JsonNode d = progress("student@example.com", "student123");
        assertThat(d.path("overall").path("direction").asText()).isEqualTo("IMPROVING");
        assertThat(d.path("overall").path("recentAverage").asDouble()).isEqualTo(75.0);
        assertThat(d.path("overall").path("previousAverage").asDouble()).isEqualTo(55.0);
        assertThat(d.path("overall").path("delta").asDouble()).isEqualTo(20.0);
        assertThat(d.path("examProgress").path("history").size()).isEqualTo(4);
        assertThat(d.path("dataQuality").path("sufficientForTrend").asBoolean()).isTrue();
        assertThat(d.path("dataQuality").path("completedExamCount").asInt()).isEqualTo(4);
    }

    @Test
    void improvingExamHistory() throws Exception {
        seedResultSeries(new int[]{40, 50, 60, 80});
        JsonNode d = progress("student@example.com", "student123");
        assertThat(d.path("overall").path("direction").asText()).isEqualTo("IMPROVING");
        assertThat(d.path("overall").path("recentAverage").asDouble()).isEqualTo(70.0);
        assertThat(d.path("overall").path("previousAverage").asDouble()).isEqualTo(45.0);
        assertThat(d.path("overall").path("delta").asDouble()).isEqualTo(25.0);
        assertThat(d.path("intelligenceChanges").size()).isZero();
    }

    @Test
    void decliningExamHistory() throws Exception {
        seedResultSeries(new int[]{80, 70, 60, 50});
        JsonNode d = progress("student@example.com", "student123");
        assertThat(d.path("overall").path("direction").asText()).isEqualTo("DECLINING");
        assertThat(d.path("overall").path("recentAverage").asDouble()).isEqualTo(55.0);
        assertThat(d.path("overall").path("previousAverage").asDouble()).isEqualTo(75.0);
        assertThat(d.path("overall").path("delta").asDouble()).isEqualTo(-20.0);
        JsonNode decline = find(d.path("intelligenceChanges"), "type", "RECENT_DECLINE");
        assertThat(decline.path("previousAccuracy").asDouble()).isEqualTo(75.0);
        assertThat(decline.path("currentAccuracy").asDouble()).isEqualTo(55.0);
    }

    @Test
    void stableExamHistory() throws Exception {
        seedResultSeries(new int[]{70, 70, 70, 70});
        JsonNode d = progress("student@example.com", "student123");
        assertThat(d.path("overall").path("direction").asText()).isEqualTo("STABLE");
        assertThat(d.path("overall").path("delta").asDouble()).isZero();
        assertThat(d.path("intelligenceChanges").size()).isZero();
    }

    @Test
    void practiceImprovement() throws Exception {
        seedExam("exam-p", "Practice Exam", "Math");
        seedExam("exam-q", "Question Exam", "Math");
        seedQuestions("exam-q", new String[]{"q0", "q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8", "q9"}, 3);
        boolean[] oldCorrect = {false, false, false, false, false};
        boolean[] newCorrect = {true, true, true, true, true};
        String[] oldIds = {"q0", "q1", "q2", "q3", "q4"};
        String[] newIds = {"q5", "q6", "q7", "q8", "q9"};
        seedPracticeSession("ps-old", "student-1", "exam-p", "2026-01-01 10:00:00", 5, 0);
        seedPracticeSession("ps-new", "student-1", "exam-p", "2026-02-01 10:00:00", 5, 5);
        seedPracticeAnswers("ps-old", oldIds, oldCorrect,
                new String[]{"2026-01-01 10:01:00", "2026-01-01 10:02:00", "2026-01-01 10:03:00", "2026-01-01 10:04:00", "2026-01-01 10:05:00"});
        seedPracticeAnswers("ps-new", newIds, newCorrect,
                new String[]{"2026-02-01 10:01:00", "2026-02-01 10:02:00", "2026-02-01 10:03:00", "2026-02-01 10:04:00", "2026-02-01 10:05:00"});

        JsonNode p = progress("student@example.com", "student123").path("practiceProgress");
        assertThat(p.path("direction").asText()).isEqualTo("IMPROVING");
        assertThat(p.path("recentAccuracy").asDouble()).isEqualTo(100.0);
        assertThat(p.path("previousAccuracy").asDouble()).isEqualTo(0.0);
        assertThat(p.path("delta").asDouble()).isEqualTo(100.0);
        assertThat(p.path("recentQuestions").asInt()).isEqualTo(5);
        assertThat(p.path("previousQuestions").asInt()).isEqualTo(5);
    }

    @Test
    void practiceDecline() throws Exception {
        seedExam("exam-p", "Practice Exam", "Math");
        seedExam("exam-q", "Question Exam", "Math");
        seedQuestions("exam-q", new String[]{"q0", "q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8", "q9"}, 3);
        String[] oldIds = {"q0", "q1", "q2", "q3", "q4"};
        String[] newIds = {"q5", "q6", "q7", "q8", "q9"};
        seedPracticeSession("ps-old", "student-1", "exam-p", "2026-01-01 10:00:00", 5, 5);
        seedPracticeSession("ps-new", "student-1", "exam-p", "2026-02-01 10:00:00", 5, 0);
        seedPracticeAnswers("ps-old", oldIds, new boolean[]{true, true, true, true, true},
                new String[]{"2026-01-01 10:01:00", "2026-01-01 10:02:00", "2026-01-01 10:03:00", "2026-01-01 10:04:00", "2026-01-01 10:05:00"});
        seedPracticeAnswers("ps-new", newIds, new boolean[]{false, false, false, false, false},
                new String[]{"2026-02-01 10:01:00", "2026-02-01 10:02:00", "2026-02-01 10:03:00", "2026-02-01 10:04:00", "2026-02-01 10:05:00"});

        JsonNode p = progress("student@example.com", "student123").path("practiceProgress");
        assertThat(p.path("direction").asText()).isEqualTo("DECLINING");
        assertThat(p.path("recentAccuracy").asDouble()).isEqualTo(0.0);
        assertThat(p.path("previousAccuracy").asDouble()).isEqualTo(100.0);
        assertThat(p.path("delta").asDouble()).isEqualTo(-100.0);
    }

    @Test
    void difficultyImprovement() throws Exception {
        seedExam("de1", "Hard 1", "Math");
        seedExam("de2", "Hard 2", "Math");
        seedExam("de3", "Hard 3", "Math");
        seedExam("de4", "Hard 4", "Math");
        seedQuestions("de1", new String[]{"de1a", "de1b", "de1c", "de1d"}, 5);
        seedQuestions("de2", new String[]{"de2a", "de2b", "de2c", "de2d"}, 5);
        seedQuestions("de3", new String[]{"de3a", "de3b", "de3c", "de3d"}, 5);
        seedQuestions("de4", new String[]{"de4a", "de4b", "de4c", "de4d"}, 5);
        seedExamWithAnswers("de1", "de1", new boolean[]{true, false, false, false}, new String[]{"de1a", "de1b", "de1c", "de1d"}, 30, "2026-01-01");
        seedExamWithAnswers("de2", "de2", new boolean[]{true, false, false, false}, new String[]{"de2a", "de2b", "de2c", "de2d"}, 30, "2026-02-01");
        seedExamWithAnswers("de3", "de3", new boolean[]{true, true, true, false}, new String[]{"de3a", "de3b", "de3c", "de3d"}, 60, "2026-03-01");
        seedExamWithAnswers("de4", "de4", new boolean[]{true, true, true, false}, new String[]{"de4a", "de4b", "de4c", "de4d"}, 60, "2026-04-01");

        JsonNode d = progress("student@example.com", "student123");
        JsonNode hard = d.path("difficultyProgress").path("hard");
        assertThat(hard.path("direction").asText()).isEqualTo("IMPROVING");
        assertThat(hard.path("recentAccuracy").asDouble()).isEqualTo(75.0);
        assertThat(hard.path("previousAccuracy").asDouble()).isEqualTo(25.0);
        assertThat(hard.path("delta").asDouble()).isEqualTo(50.0);
        assertThat(hard.path("recentObservations").asInt()).isEqualTo(8);
        assertThat(hard.path("previousObservations").asInt()).isEqualTo(8);
        assertThat(d.path("difficultyProgress").path("easy").path("direction").asText()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void insufficientDifficultyData() throws Exception {
        seedExam("d1", "D1", "Math");
        seedExam("d2", "D2", "Math");
        seedExam("d3", "D3", "Math");
        seedExam("d4", "D4", "Math");
        seedQuestions("d1", new String[]{"d1a"}, 5);
        seedQuestions("d2", new String[]{"d2a"}, 5);
        seedQuestions("d3", new String[]{"d3a"}, 5);
        seedQuestions("d4", new String[]{"d4a"}, 5);
        seedExamWithAnswers("d1", "d1", new boolean[]{true}, new String[]{"d1a"}, 30, "2026-01-01");
        seedExamWithAnswers("d2", "d2", new boolean[]{false}, new String[]{"d2a"}, 30, "2026-02-01");
        seedExamWithAnswers("d3", "d3", new boolean[]{true}, new String[]{"d3a"}, 60, "2026-03-01");
        seedExamWithAnswers("d4", "d4", new boolean[]{false}, new String[]{"d4a"}, 60, "2026-04-01");

        JsonNode hard = progress("student@example.com", "student123")
                .path("difficultyProgress").path("hard");
        assertThat(hard.path("direction").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(hard.path("recentObservations").asInt()).isEqualTo(2);
        assertThat(hard.path("previousObservations").asInt()).isEqualTo(2);
        assertThat(hard.path("recentAccuracy").isNull()).isTrue();
    }

    @Test
    void chronologicalExamHistoryIsBoundedAndOrdered() throws Exception {
        int[] scores = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 60, 70};
        for (int i = 0; i < scores.length; i++) {
            String examId = "hist" + (i + 1);
            String title = "Hist " + (i + 1);
            String date = String.format("2026-%02d-01", i + 1);
            seedExam(examId, title, "Math");
            seedResult("rh" + (i + 1), "student-1", examId, title, scores[i], 100, date);
        }
        seedAttemptN("attempt-last", "hist12", "student-1", 2);

        JsonNode history = progress("student@example.com", "student123").path("examProgress").path("history");
        assertThat(history.size()).isEqualTo(10);
        assertThat(history.get(0).path("date").asText()).isEqualTo("2026-03-01");
        assertThat(history.get(9).path("date").asText()).isEqualTo("2026-12-01");
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).path("date").asText().compareTo(history.get(i - 1).path("date").asText()))
                    .isPositive();
        }
        assertThat(history.get(9).path("examId").asText()).isEqualTo("hist12");
        assertThat(history.get(9).path("percentage").asDouble()).isEqualTo(70.0);
        assertThat(history.get(9).path("attemptNumber").asInt()).isEqualTo(2);
    }

    @Test
    void milestoneCalculation() throws Exception {
        seedExam("me1", "M1", "Math");
        seedExam("me2", "M2", "Math");
        seedExam("me3", "M3", "Math");
        seedQuestionsForMilestone("me1", new String[]{"me1a", "me1b"}, new int[]{1, 5});
        seedQuestionsForMilestone("me2", new String[]{"me2a"}, new int[]{5});
        seedAttempt("attempt-me1", "me1", "student-1");
        seedAttempt("attempt-me2", "me2", "student-1");
        seedAnswers("student-1", "me1", "attempt-me1", new String[]{"me1a", "me1b"}, new boolean[]{true, false});
        seedAnswers("student-1", "me2", "attempt-me2", new String[]{"me2a"}, new boolean[]{true});
        seedResult("mr1", "student-1", "me1", "M1", 40, 100, "2026-01-01");
        seedResult("mr2", "student-1", "me2", "M2", 70, 100, "2026-02-01");
        seedResult("mr3", "student-1", "me3", "M3", 50, 100, "2026-03-01");
        seedPracticeSession("ps-milestone", "student-1", "me2", "2026-02-01 09:00:00", 4, 4);

        JsonNode milestones = progress("student@example.com", "student123").path("milestones");
        Set<String> codes = new HashSet<>();
        for (JsonNode m : milestones) {
            codes.add(m.path("code").asText());
        }
        assertThat(codes).containsExactlyInAnyOrder(
                "FIRST_COMPLETED_EXAM", "IMPROVED_EXAM_PERFORMANCE",
                "FIRST_HARD_QUESTION_SUCCESS", "IMPROVED_HARD_DIFFICULTY",
                "FIRST_PRACTICE_SESSION");
        JsonNode first = findIn(milestones, "code", "FIRST_COMPLETED_EXAM");
        assertThat(first.path("occurredAt").asText()).isEqualTo("2026-01-01");
        assertThat(first.path("metric").asDouble()).isEqualTo(40.0);
        JsonNode improved = findIn(milestones, "code", "IMPROVED_EXAM_PERFORMANCE");
        assertThat(improved.path("occurredAt").asText()).isEqualTo("2026-02-01");
        assertThat(improved.path("metric").asDouble()).isEqualTo(70.0);
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/student/progress")).andExpect(status().isUnauthorized());
    }

    @Test
    void roleIsolationBlocksTeachersAndAdmins() throws Exception {
        insertAdmin();
        mockMvc.perform(get("/api/student/progress")
                        .header("Authorization", bearer(login("teacher@example.com", "teacher123"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/student/progress")
                        .header("Authorization", bearer(login("admin@example.com", "admin123"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void noCrossStudentLeakage() throws Exception {
        String s1 = login("student@example.com", "student123");
        String s2 = login("student2@example.com", "student234");
        seedExam("leak1", "Leak 1", "Math");
        seedResult("lr1", "student-1", "leak1", "Leak 1", 40, 100, "2026-01-01");
        seedResult("lr2", "student-2", "leak1", "Leak 1", 90, 100, "2026-01-01");

        String own = call(s1, null);
        String other = call(s2, null);
        String otherWithParam = call(s2, "studentId=student-1");
        assertThat(objectMapper.readTree(own).path("data").path("dataQuality")
                .path("completedExamCount").asInt()).isEqualTo(1);
        assertThat(objectMapper.readTree(other).path("data").path("dataQuality")
                .path("completedExamCount").asInt()).isEqualTo(1);
        assertThat(other).isEqualTo(otherWithParam);
    }

    @Test
    void repeatedCallsReturnDeterministicResults() throws Exception {
        String token = login("student@example.com", "student123");
        seedResultSeries(new int[]{45, 55, 60, 65});
        seedExam("exam-q", "Question Exam", "Math");
        seedQuestions("exam-q", new String[]{"q0", "q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8", "q9"}, 3);
        seedPracticeSession("ps-det-old", "student-1", "exam-q", "2026-01-01 10:00:00", 5, 1);
        seedPracticeSession("ps-det-new", "student-1", "exam-q", "2026-02-01 10:00:00", 5, 4);
        seedPracticeAnswers("ps-det-old", new String[]{"q0", "q1", "q2", "q3", "q4"},
                new boolean[]{true, false, false, false, false},
                new String[]{"2026-01-01 10:01:00", "2026-01-01 10:02:00", "2026-01-01 10:03:00", "2026-01-01 10:04:00", "2026-01-01 10:05:00"});
        seedPracticeAnswers("ps-det-new", new String[]{"q5", "q6", "q7", "q8", "q9"},
                new boolean[]{true, true, true, true, false},
                new String[]{"2026-02-01 10:01:00", "2026-02-01 10:02:00", "2026-02-01 10:03:00", "2026-02-01 10:04:00", "2026-02-01 10:05:00"});

        String first = call(token, null);
        String second = call(token, null);
        assertThat(first).isEqualTo(second);
    }

    // ---------- helpers ----------

    private void seedResultSeries(int[] scores) {
        String[] months = {"01", "02", "03", "04", "05", "06"};
        for (int i = 0; i < scores.length; i++) {
            String examId = "se" + (i + 1);
            String title = "Series " + (i + 1);
            seedExam(examId, title, "Math");
            seedResult("rs" + (i + 1), "student-1", examId, title,
                    scores[i], 100, "2026-" + months[i] + "-01");
        }
    }

    private void seedExamWithAnswers(String examId, String qPrefix, boolean[] correct,
                                     String[] questionIds, int score, String date) {
        seedAttempt("attempt-" + examId, examId, "student-1");
        seedAnswers("student-1", examId, "attempt-" + examId, questionIds, correct);
        seedResult("result-" + examId, "student-1", examId, examId, score, 100, date);
    }

    private JsonNode progress(String email, String password) throws Exception {
        String body = call(login(email, password), null);
        return objectMapper.readTree(body).path("data");
    }

    private JsonNode find(JsonNode array, String key, String value) {
        if (array.isArray()) {
            for (JsonNode node : array) {
                if (value.equals(node.path(key).asText())) {
                    return node;
                }
            }
        }
        return objectMapper.createObjectNode();
    }

    private JsonNode findIn(JsonNode array, String key, String value) {
        if (array.isArray()) {
            for (JsonNode node : array) {
                if (value.equals(node.path(key).asText())) {
                    return node;
                }
            }
        }
        return objectMapper.createObjectNode();
    }

    private Set<String> codes(JsonNode data, String field) {
        Set<String> codes = new HashSet<>();
        if (data.path(field).isArray()) {
            for (JsonNode node : data.path(field)) {
                codes.add(node.path("code").asText());
            }
        }
        return codes;
    }

    private String call(String token, String query) throws Exception {
        var request = get("/api/student/progress").header("Authorization", bearer(token));
        if (query != null) {
            request = get("/api/student/progress?" + query).header("Authorization", bearer(token));
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void seedExam(String id, String title, String subject) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, ?, '2026-09-01', 60, 'UPCOMING', 0, null, ?)",
                id, title, subject, "teacher-1");
    }

    private void seedQuestions(String examId, String[] questions, int difficulty) {
        int[] difficulties = new int[questions.length];
        java.util.Arrays.fill(difficulties, difficulty);
        seedQuestionsForMilestone(examId, questions, difficulties);
    }

    private void seedQuestionsForMilestone(String examId, String[] questions, int[] difficulties) {
        StringBuilder values = new StringBuilder("insert into questions (id, exam_id, text, answer, difficulty) values ");
        for (int i = 0; i < questions.length; i++) {
            if (i > 0) {
                values.append(",");
            }
            values.append("('").append(questions[i]).append("','").append(examId).append("','Q?','x',")
                    .append(difficulties[i]).append(")");
        }
        jdbcTemplate.update(values.toString());
    }

    private void seedAttempt(String id, String examId, String studentId) {
        seedAttemptN(id, examId, studentId, 1);
    }

    private void seedAttemptN(String id, String examId, String studentId, int attemptNumber) {
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, submitted_at, version) "
                        + "values (?, ?, ?, ?, 'SUBMITTED', current_timestamp, current_timestamp, current_timestamp, 0)",
                id, examId, studentId, attemptNumber);
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

    private void seedResult(String id, String userId, String examId, String examTitle,
                            String subject, int score, int total, String date) {
        jdbcTemplate.update(
                "insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, userId, examId, examTitle, subject, score, date, total);
    }

    private void seedResult(String id, String userId, String examId, String title, int score, int total, String date) {
        seedResult(id, userId, examId, title, "Math", score, total, date);
    }

    private void seedPracticeSession(String id, String studentId, String examId, String completedAt,
                                     int answeredCount, int correctCount) {
        jdbcTemplate.update(
                "insert into practice_sessions (id, student_id, exam_id, status, target_question_count, "
                        + "working_difficulty, answered_count, correct_count, started_at, last_activity_at, completed_at) "
                        + "values (?, ?, ?, 'COMPLETED', 10, 3.0, ?, ?, ?, ?, ?)",
                id, studentId, examId, answeredCount, correctCount, completedAt, completedAt, completedAt);
    }

    private void seedPracticeAnswers(String sessionId, String[] questionIds, boolean[] correct, String[] answeredAt) {
        StringBuilder values = new StringBuilder("insert into practice_answers (id, session_id, question_id, answer_value, correct, difficulty, sequence_index, answered_at) values ");
        for (int i = 0; i < questionIds.length; i++) {
            if (i > 0) {
                values.append(",");
            }
            values.append("('pa-").append(sessionId).append("-").append(i).append("','").append(sessionId)
                    .append("','").append(questionIds[i]).append("','x',").append(correct[i]).append(",3,")
                    .append(i).append(",'").append(answeredAt[i]).append("')");
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