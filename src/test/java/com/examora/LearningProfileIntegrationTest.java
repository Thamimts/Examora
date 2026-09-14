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
        "spring.datasource.url=jdbc:h2:mem:examora-learning-profile;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-learning-profile-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class LearningProfileIntegrationTest {
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
        insertUser("teacher-2", "Teacher Two", "teacher2@example.com", "teacher223", "TEACHER");
    }

    @Test
    void authenticatedStudentReceivesOwnProfile() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");
        String examId = createExam(teacherToken, "Exam A", "Math", "2026-01-01");
        String q = createQuestion(teacherToken, examId, "Q1?", "a", "b", "b");
        mockMvc.perform(post("/api/exams/" + examId + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + q + "\",\"value\":\"b\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.overall.completedExams").value(1))
                .andExpect(jsonPath("$.data.overall.averageScore").value(100.0))
                .andExpect(jsonPath("$.data.overall.highestScore").value(100))
                .andExpect(jsonPath("$.data.overall.lowestScore").value(100));
    }

    @Test
    void anonymousRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/student/learning-profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void twoStudentsGetDifferentProfiles() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String s1Token = login("student@example.com", "student123");
        String s2Token = login("student2@example.com", "student234");
        String examId = createExam(teacherToken, "Exam A", "Math", "2026-01-01");
        String q = createQuestion(teacherToken, examId, "Q1?", "a", "b", "b");
        mockMvc.perform(post("/api/exams/" + examId + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(s1Token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + q + "\",\"value\":\"b\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(s1Token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall.completedExams").value(1));

        mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(s2Token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall.completedExams").value(0));
    }

    @Test
    void emptyStudentGetsDeterministicEmptyState() throws Exception {
        String studentToken = login("student@example.com", "student123");
        mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall.completedExams").value(0))
                .andExpect(jsonPath("$.data.overall.averageScore").isEmpty())
                .andExpect(jsonPath("$.data.overall.highestScore").isEmpty())
                .andExpect(jsonPath("$.data.overall.lowestScore").isEmpty())
                .andExpect(jsonPath("$.data.overall.submittedAttempts").value(0))
                .andExpect(jsonPath("$.data.examPerformance.averageAccuracy").isEmpty())
                .andExpect(jsonPath("$.data.examPerformance.recentAccuracy").isEmpty())
                .andExpect(jsonPath("$.data.examPerformance.improvementDelta").isEmpty())
                .andExpect(jsonPath("$.data.practice.sessionsCompleted").value(0))
                .andExpect(jsonPath("$.data.practice.questionsAnswered").value(0))
                .andExpect(jsonPath("$.data.practice.accuracy").isEmpty())
                .andExpect(jsonPath("$.data.practice.recentAccuracy").isEmpty())
                .andExpect(jsonPath("$.data.aiPractice.sessionsCompleted").value(0))
                .andExpect(jsonPath("$.data.aiPractice.questionsAnswered").value(0))
                .andExpect(jsonPath("$.data.aiPractice.accuracy").isEmpty())
                .andExpect(jsonPath("$.data.difficulty.exam.easy.attempted").value(0))
                .andExpect(jsonPath("$.data.difficulty.exam.easy.accuracy").isEmpty())
                .andExpect(jsonPath("$.data.difficulty.practice.easy.attempted").value(0))
                .andExpect(jsonPath("$.data.trend.direction").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.data.trend.delta").isEmpty())
                .andExpect(jsonPath("$.data.learningSignals.consistency").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.data.learningSignals.weaknesses[0]").value("INSUFFICIENT_DATA"));
    }

    @Test
    void examMetricsMatchRealDatabaseData() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String s1 = login("student@example.com", "student123");
        String s2 = login("student2@example.com", "student234");

        String e1 = createExam(teacherToken, "Math", "Math", "2026-01-15");
        String e2 = createExam(teacherToken, "Science", "Science", "2026-01-20");
        String q1 = createQuestion(teacherToken, e1, "Q1", "a", "b", "b");
        String q2 = createQuestion(teacherToken, e2, "Q2", "a", "b", "a");
        mockMvc.perform(post("/api/exams/" + e1 + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + e2 + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());

        // s1 gets both exams (80% + 50% = 65 avg)
        mockMvc.perform(post("/api/exams/" + e1 + "/submit").header("Authorization", bearer(s1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + q1 + "\",\"value\":\"b\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + e2 + "/submit").header("Authorization", bearer(s1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + q2 + "\",\"value\":\"b\"}]}"))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(s1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        assertThat(data.path("overall").path("completedExams").asInt()).isEqualTo(2);
        assertThat(data.path("overall").path("submittedAttempts").asInt()).isEqualTo(2);
        double avg = data.path("overall").path("averageScore").asDouble();
        assertThat(avg).isEqualTo(50.0);
    }

    @Test
    void practiceMetricsMatchRealDatabaseData() throws Exception {
        String studentToken = login("student@example.com", "student123");
        insertExamForPractice("practice-exam", "Practice Exam", "Math", "UPCOMING");
        insertQuestions("practice-exam", "q1", "q2", "q3", "q4");
        jdbcTemplate.update(
                "insert into practice_sessions (id, student_id, exam_id, status, target_question_count, "
                        + "working_difficulty, answered_count, correct_count, started_at, last_activity_at, completed_at) "
                        + "values ('ps1', ?, 'practice-exam', 'COMPLETED', 10, 2.0, 4, 3, current_timestamp, current_timestamp, current_timestamp)",
                "student-1");
        jdbcTemplate.update(
                "insert into practice_sessions (id, student_id, exam_id, status, target_question_count, "
                        + "working_difficulty, answered_count, correct_count, started_at, last_activity_at) "
                        + "values ('ps2', ?, 'practice-exam', 'STARTED', 10, 2.0, 1, 1, current_timestamp, current_timestamp)",
                "student-1");
        jdbcTemplate.update(
                "insert into practice_answers (id, session_id, question_id, option_id, answer_value, correct, difficulty, sequence_index, answered_at) values "
                        + "('pa1','ps1','q1',null,'a',true,2,0,current_timestamp), "
                        + "('pa2','ps1','q2',null,'b',true,3,1,current_timestamp), "
                        + "('pa3','ps1','q3',null,'x',false,3,2,current_timestamp), "
                        + "('pa4','ps2','q4',null,'y',true,1,0,current_timestamp)");

        mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.practice.sessionsCompleted").value(1))
                .andExpect(jsonPath("$.data.practice.questionsAnswered").value(4))
                .andExpect(jsonPath("$.data.practice.correctAnswers").value(3))
                .andExpect(jsonPath("$.data.practice.accuracy").value(75.0));
    }

    @Test
    void difficultyMetricsMatchRealDatabaseData() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String s1 = login("student@example.com", "student123");
        String e1 = createExam(teacherToken, "Diff Exam", "Math", "2026-01-01");
        jdbcTemplate.update("insert into questions (id, exam_id, text, answer, difficulty) values "
                        + "('dq1', ?, 'Q1?', 'b', 1), ('dq2', ?, 'Q2?', 'a', 2), ('dq3', ?, 'Q3?', 'c', 3), "
                        + "('dq4', ?, 'Q4?', 'd', 4), ('dq5', ?, 'Q5?', 'e', 5)",
                e1, e1, e1, e1, e1);
        mockMvc.perform(post("/api/exams/" + e1 + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + e1 + "/submit").header("Authorization", bearer(s1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":["
                                + "{\"questionId\":\"dq1\",\"value\":\"b\"},"
                                + "{\"questionId\":\"dq2\",\"value\":\"b\"},"
                                + "{\"questionId\":\"dq4\",\"value\":\"d\"},"
                                + "{\"questionId\":\"dq5\",\"value\":\"w\"}]}"))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(s1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode diff = objectMapper.readTree(body).path("data").path("difficulty").path("exam");
        assertThat(diff.path("easy").path("attempted").asInt()).isEqualTo(2);   // diff 1+2
        assertThat(diff.path("easy").path("correct").asInt()).isEqualTo(1);     // diff 2 wrong
        assertThat(diff.path("medium").path("attempted").asInt()).isEqualTo(0); // diff 3 unanswered
        assertThat(diff.path("hard").path("attempted").asInt()).isEqualTo(2);   // diff 4+5
        assertThat(diff.path("hard").path("correct").asInt()).isEqualTo(1);     // diff 4 correct, diff5 wrong
    }

    @Test
    void trendCalculationMatchesSeededHistoricalData() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String s1 = login("student@example.com", "student123");

        // 4 exams → 2 recent (60%,70% avg=65) vs 2 previous (40%,50% avg=45) → delta=20 IMPROVING
        String e1 = createExam(teacherToken, "Exam1", "Math", "2026-01-01");
        String e2 = createExam(teacherToken, "Exam2", "Math", "2026-02-01");
        String e3 = createExam(teacherToken, "Exam3", "Math", "2026-03-01");
        String e4 = createExam(teacherToken, "Exam4", "Math", "2026-04-01");
        // Each exam: 1 question, 1 answer. score=right? → 1 correct = 100%, 0 correct = 0%.
        // Seed 5-q exams to use score: result score directly.
        jdbcTemplate.update("insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) values "
                        + "('r1','student-1',?,'Exam1','Math',40,'2026-01-01',100),"
                        + "('r2','student-1',?,'Exam2','Math',50,'2026-02-01',100),"
                        + "('r3','student-1',?,'Exam3','Math',60,'2026-03-01',100),"
                        + "('r4','student-1',?,'Exam4','Math',70,'2026-04-01',100)",
                e1, e2, e3, e4);

        String body = mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(s1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode trend = objectMapper.readTree(body).path("data").path("trend");
        assertThat(trend.path("direction").asText()).isEqualTo("IMPROVING");
        assertThat(trend.path("delta").asDouble()).isEqualTo(20.0);
    }

    @Test
    void insufficientHistoricalDataReturnsInsufficientData() throws Exception {
        String s1 = login("student@example.com", "student123");
        String teacherToken = login("teacher@example.com", "teacher123");
        String e1 = createExam(teacherToken, "Exam1", "Math", "2026-01-01");
        jdbcTemplate.update("insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) "
                        + "values ('r1','student-1',?,'Exam1','Math',80,'2026-01-01',100)", e1);

        mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(s1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trend.direction").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.data.trend.delta").isEmpty());
    }

    @Test
    void roleIsolationBlocksTeachersAndAdmins() throws Exception {
        insertAdmin();
        String teacherToken = login("teacher@example.com", "teacher123");
        String adminToken = login("admin@example.com", "admin123");

        mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void noCrossStudentDataLeakage() throws Exception {
        String s1Token = login("student@example.com", "student123");
        String s2Token = login("student2@example.com", "student234");
        String teacherToken = login("teacher@example.com", "teacher123");
        String e1 = createExam(teacherToken, "Scope", "Math", "2026-01-01");
        String q1 = createQuestion(teacherToken, e1, "Q1", "a", "b", "b");
        mockMvc.perform(post("/api/exams/" + e1 + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + e1 + "/submit").header("Authorization", bearer(s1Token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + q1 + "\",\"value\":\"b\"}]}"))
                .andExpect(status().isOk());

        String body1 = mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(s1Token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String body2 = mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(s2Token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body1).path("data").path("overall").path("completedExams").asInt()).isEqualTo(1);
        assertThat(objectMapper.readTree(body2).path("data").path("overall").path("completedExams").asInt()).isEqualTo(0);
    }

    @Test
    void learningSignalsReflectRealData() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String s1 = login("student@example.com", "student123");

        // Seed practice with enough data for STRONG_PRACTICE_PERFORMANCE signal (>=5 answers, >=80%)
        String e1 = createExam(teacherToken, "Exam1", "Math", "2026-01-01");
        insertQuestions(e1, "q1", "q2", "q3", "q4", "q5", "q6");
        jdbcTemplate.update("insert into practice_sessions (id, student_id, exam_id, status, target_question_count, "
                        + "working_difficulty, answered_count, correct_count, started_at, last_activity_at, completed_at) "
                        + "values ('ps1','student-1',?,'COMPLETED',10,2.0,6,6,current_timestamp,current_timestamp,current_timestamp)",
                e1);
        jdbcTemplate.update("insert into practice_answers (id, session_id, question_id, option_id, answer_value, correct, difficulty, sequence_index, answered_at) values "
                        + "('pa1','ps1','q1',null,'a',true,1,0,current_timestamp),"
                        + "('pa2','ps1','q2',null,'b',true,1,1,current_timestamp),"
                        + "('pa3','ps1','q3',null,'c',true,2,2,current_timestamp),"
                        + "('pa4','ps1','q4',null,'d',true,2,3,current_timestamp),"
                        + "('pa5','ps1','q5',null,'e',true,3,4,current_timestamp),"
                        + "('pa6','ps1','q6',null,'f',true,3,5,current_timestamp)");

        String body = mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(s1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode signals = objectMapper.readTree(body).path("data").path("learningSignals");
        assertThat(signals.path("strengths").toString()).contains("STRONG_PRACTICE_PERFORMANCE");
    }

    @Test
    void submittedAttemptsCountIncludesRealAttempts() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String s1 = login("student@example.com", "student123");
        String e1 = createExam(teacherToken, "Exam1", "Math", "2026-01-01");
        String e2 = createExam(teacherToken, "Exam2", "Math", "2026-02-01");
        String q1 = createQuestion(teacherToken, e1, "Q1", "a", "b", "b");
        String q2 = createQuestion(teacherToken, e2, "Q2", "a", "b", "b");
        mockMvc.perform(post("/api/exams/" + e1 + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + e2 + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + e1 + "/submit").header("Authorization", bearer(s1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + q1 + "\",\"value\":\"b\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + e2 + "/submit").header("Authorization", bearer(s1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + q2 + "\",\"value\":\"b\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(s1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall.submittedAttempts").value(2));
    }

    @Test
    void consistencyIsVolatileWhenScoresVary() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String s1 = login("student@example.com", "student123");
        String e1 = createExam(teacherToken, "E1", "Math", "2026-01-01");
        String e2 = createExam(teacherToken, "E2", "Math", "2026-02-01");
        String e3 = createExam(teacherToken, "E3", "Math", "2026-03-01");
        // Scores: 10, 50, 90 → stddev large → VARIABLE
        jdbcTemplate.update("insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) values "
                        + "('c1','student-1',?,'E1','Math',10,'2026-01-01',100),"
                        + "('c2','student-1',?,'E2','Math',50,'2026-02-01',100),"
                        + "('c3','student-1',?,'E3','Math',90,'2026-03-01',100)",
                e1, e2, e3);

        mockMvc.perform(get("/api/student/learning-profile").header("Authorization", bearer(s1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.learningSignals.consistency").value("VARIABLE"));
    }

    // ---------- helpers ----------

    private void insertAdmin() {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                "admin-1", "Admin", "admin@example.com", passwordEncoder.encode("admin123"), "ADMIN");
    }

    private void insertExamForPractice(String id, String title, String subject, String status) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, ?, '2026-09-01', 60, ?, 0, null, ?)",
                id, title, subject, status, "teacher-1");
    }

    private void insertQuestions(String examId, String... ids) {
        StringBuilder values = new StringBuilder("insert into questions (id, exam_id, text, answer, difficulty) values ");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                values.append(",");
            }
            values.append("('").append(ids[i]).append("', '").append(examId).append("', 'Q?', 'x', ").append(2).append(")");
        }
        jdbcTemplate.update(values.toString());
    }

    private String createExam(String token, String title, String subject, String date) throws Exception {
        String response = mockMvc.perform(post("/api/exams")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"subject\":\"" + subject + "\",\"date\":\"" + date + "\",\"duration\":60}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).path("data").path("id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    private String createQuestion(String token, String examId, String text, String a, String b, String answer) throws Exception {
        String response = mockMvc.perform(post("/api/exams/" + examId + "/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\",\"options\":[\"" + a + "\",\"" + b + "\"],\"answer\":\"" + answer + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).path("data").path("id").asText();
        assertThat(id).isNotBlank();
        return id;
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

    private void insertUser(String id, String name, String email, String password, String role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}