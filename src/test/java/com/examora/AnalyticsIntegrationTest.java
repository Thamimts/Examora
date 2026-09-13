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
        "spring.datasource.url=jdbc:h2:mem:examora-analytics-core;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-analytics-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class AnalyticsIntegrationTest {
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
        jdbcTemplate.update("delete from proctor_events");
        jdbcTemplate.update("delete from practice_answers");
        jdbcTemplate.update("delete from practice_sessions");
        jdbcTemplate.update("delete from ai_practice_reviews");
        jdbcTemplate.update("delete from ai_practice_answers");
        jdbcTemplate.update("delete from ai_question_options");
        jdbcTemplate.update("delete from ai_generated_questions");
        jdbcTemplate.update("delete from ai_tutor_questions");
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("student-1", "Student One", "student@example.com", "student123", "STUDENT");
        insertUser("student-2", "Student Two", "student2@example.com", "student234", "STUDENT");
        insertUser("teacher-1", "Teacher One", "teacher@example.com", "teacher123", "TEACHER");
        insertUser("teacher-2", "Teacher Two", "teacher2@example.com", "teacher223", "TEACHER");
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/analytics/exams")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/analytics/exams/exam-1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/analytics/students/student-1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/student/analytics/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/student/analytics/exams")).andExpect(status().isUnauthorized());
    }

    @Test
    void aggregatesAreComputedFromStoredRowsForTeacher() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertResultsAndAttempts();

        mockMvc.perform(get("/api/analytics/exams").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].examId").value("exam-1"))
                .andExpect(jsonPath("$.data[0].title").value("Week 1"))
                .andExpect(jsonPath("$.data[0].participants").value(2))
                .andExpect(jsonPath("$.data[0].submissions").value(2))
                .andExpect(jsonPath("$.data[0].averageScore").value(60.0))
                .andExpect(jsonPath("$.data[0].medianScore").value(60.0))
                .andExpect(jsonPath("$.data[0].highestScore").value(80))
                .andExpect(jsonPath("$.data[0].lowestScore").value(40))
                .andExpect(jsonPath("$.data[0].completionRate").value(66.67))
                .andExpect(jsonPath("$.data[0].passRate").isEmpty());

        mockMvc.perform(get("/api/analytics/exams/exam-1").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptsStarted").value(3))
                .andExpect(jsonPath("$.data.attemptsSubmitted").value(2))
                .andExpect(jsonPath("$.data.completionRate").value(66.67))
                .andExpect(jsonPath("$.data.scoreDistribution.length()").value(5))
                .andExpect(jsonPath("$.data.scoreDistribution[0].range").value("0-19"))
                .andExpect(jsonPath("$.data.scoreDistribution[0].count").value(0))
                .andExpect(jsonPath("$.data.scoreDistribution[2].range").value("40-59"))
                .andExpect(jsonPath("$.data.scoreDistribution[2].count").value(1))
                .andExpect(jsonPath("$.data.scoreDistribution[4].range").value("80-100"))
                .andExpect(jsonPath("$.data.scoreDistribution[4].count").value(1));
    }

    @Test
    void questionAnalyticsIncludeGradingUnansweredAndOptionDistribution() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertResultsAndAttempts();
        insertQuestionOptionAnswerRows();

        mockMvc.perform(get("/api/analytics/exams/exam-1").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questionAnalytics.length()").value(3))
                .andExpect(jsonPath("$.data.questionAnalytics[0].number").value(1))
                .andExpect(jsonPath("$.data.questionAnalytics[0].questionId").value("question-1"))
                .andExpect(jsonPath("$.data.questionAnalytics[0].difficulty").value(2))
                .andExpect(jsonPath("$.data.questionAnalytics[0].eligibleAttempts").value(2))
                .andExpect(jsonPath("$.data.questionAnalytics[0].gradedAttempts").value(2))
                .andExpect(jsonPath("$.data.questionAnalytics[0].correct").value(1))
                .andExpect(jsonPath("$.data.questionAnalytics[0].incorrect").value(1))
                .andExpect(jsonPath("$.data.questionAnalytics[0].ungradedAnswers").value(0))
                .andExpect(jsonPath("$.data.questionAnalytics[0].unanswered").value(0))
                .andExpect(jsonPath("$.data.questionAnalytics[0].accuracy").value(50.0))
                .andExpect(jsonPath("$.data.questionAnalytics[0].attemptRate").value(100.0))
                .andExpect(jsonPath("$.data.questionAnalytics[0].optionDistribution.length()").value(2))
                .andExpect(jsonPath("$.data.questionAnalytics[0].optionDistribution[0].optionId").value("option-1"))
                .andExpect(jsonPath("$.data.questionAnalytics[0].optionDistribution[0].count").value(1))
                .andExpect(jsonPath("$.data.questionAnalytics[0].optionDistribution[1].optionId").value("option-2"))
                .andExpect(jsonPath("$.data.questionAnalytics[0].optionDistribution[1].count").value(1));
    }

    @Test
    void ungradedAndUnansweredQuestionsAreReportedSeparately() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertResultsAndAttempts();
        insertQuestionOptionAnswerRows();

        mockMvc.perform(get("/api/analytics/exams/exam-1").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questionAnalytics[1].questionId").value("question-2"))
                .andExpect(jsonPath("$.data.questionAnalytics[1].gradedAttempts").value(0))
                .andExpect(jsonPath("$.data.questionAnalytics[1].correct").value(0))
                .andExpect(jsonPath("$.data.questionAnalytics[1].unanswered").value(2))
                .andExpect(jsonPath("$.data.questionAnalytics[1].accuracy").isEmpty())
                .andExpect(jsonPath("$.data.questionAnalytics[1].attemptRate").value(0.0))
                .andExpect(jsonPath("$.data.questionAnalytics[2].questionId").value("question-3"))
                .andExpect(jsonPath("$.data.questionAnalytics[2].gradedAttempts").value(0))
                .andExpect(jsonPath("$.data.questionAnalytics[2].ungradedAnswers").value(1))
                .andExpect(jsonPath("$.data.questionAnalytics[2].unanswered").value(2));
    }

    @Test
    void optionDistributionNeverExposesOptionTextOrCorrectness() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertResultsAndAttempts();
        insertQuestionOptionAnswerRows();

        String body = mockMvc.perform(get("/api/analytics/exams/exam-1").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode option = objectMapper.readTree(body).path("data").path("questionAnalytics")
                .path(0).path("optionDistribution").path(0);
        assertThat(option.has("text")).isFalse();
        assertThat(option.has("correctAnswer")).isFalse();
        assertThat(option.has("correct")).isFalse();
        assertThat(option.has("optionId")).isTrue();
        assertThat(option.has("count")).isTrue();
    }

    @Test
    void teacherOnlySeesOwnedExamsAndCannotViewOthers() throws Exception {
        String teacherOneToken = login("teacher@example.com", "teacher123");
        String teacherTwoToken = login("teacher2@example.com", "teacher223");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertExam("exam-2", "Week 2", "Science", "UPCOMING", "teacher-2");

        mockMvc.perform(get("/api/analytics/exams").header("Authorization", bearer(teacherOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].examId").value("exam-1"));

        mockMvc.perform(get("/api/analytics/exams/exam-2").header("Authorization", bearer(teacherOneToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/analytics/exams").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].examId").value("exam-2"));
    }

    @Test
    void adminSeesAllExams() throws Exception {
        insertAdmin();
        String adminToken = login("admin@example.com", "admin123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertExam("exam-2", "Week 2", "Science", "UPCOMING", "teacher-2");

        mockMvc.perform(get("/api/analytics/exams").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void studentCannotReadStaffAnalytics() throws Exception {
        String studentToken = login("student@example.com", "student123");
        mockMvc.perform(get("/api/analytics/exams").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/students/student-1").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void draftExamWithNoActivityReturnsZeroedMetrics() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Draft", "Math", "DRAFT", "teacher-1");

        mockMvc.perform(get("/api/analytics/exams").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].participants").value(0))
                .andExpect(jsonPath("$.data[0].submissions").value(0))
                .andExpect(jsonPath("$.data[0].averageScore").isEmpty())
                .andExpect(jsonPath("$.data[0].medianScore").isEmpty())
                .andExpect(jsonPath("$.data[0].highestScore").isEmpty())
                .andExpect(jsonPath("$.data[0].lowestScore").isEmpty())
                .andExpect(jsonPath("$.data[0].completionRate").isEmpty());

        mockMvc.perform(get("/api/analytics/exams/exam-1").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptsStarted").value(0))
                .andExpect(jsonPath("$.data.attemptsSubmitted").value(0))
                .andExpect(jsonPath("$.data.scoreDistribution[0].count").value(0))
                .andExpect(jsonPath("$.data.questionAnalytics.length()").value(0));
    }

    @Test
    void legacyResultsWithoutExamIdAreExcludedFromExamMetrics() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertResult("legacy-1", "student-1", null, "Week 1", "Math", 90, 100, "2026-08-01");

        mockMvc.perform(get("/api/analytics/exams").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].participants").value(0))
                .andExpect(jsonPath("$.data[0].submissions").value(0))
                .andExpect(jsonPath("$.data[0].averageScore").isEmpty());
    }

    @Test
    void teacherDrilldownPerStudentIncludesHistoryAndAccuracy() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertResultsAndAttempts();
        insertQuestionOptionAnswerRows();

        mockMvc.perform(get("/api/analytics/students/student-1").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentId").value("student-1"))
                .andExpect(jsonPath("$.data.studentName").value("Student One"))
                .andExpect(jsonPath("$.data.completedExams").value(1))
                .andExpect(jsonPath("$.data.averagePercentage").value(80.0))
                .andExpect(jsonPath("$.data.highestScore").value(80))
                .andExpect(jsonPath("$.data.lowestScore").value(80))
                .andExpect(jsonPath("$.data.trueAccuracy").value(100.0))
                .andExpect(jsonPath("$.data.recentTrend.length()").value(1))
                .andExpect(jsonPath("$.data.recentTrend[0].examTitle").value("Week 1"))
                .andExpect(jsonPath("$.data.examHistory.length()").value(1))
                .andExpect(jsonPath("$.data.examHistory[0].percentage").value(80.0));
    }

    @Test
    void teacherDrilldownRejectsUnrelatedStudent() throws Exception {
        String teacherTwoToken = login("teacher2@example.com", "teacher223");
        String teacherOneToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertResult("result-1", "student-1", "exam-1", "Week 1", "Math", 80, 100, "2026-09-02");

        mockMvc.perform(get("/api/analytics/students/student-1").header("Authorization", bearer(teacherOneToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/analytics/students/student-1").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentSummaryIsEmptyForNewStudent() throws Exception {
        String studentToken = login("student@example.com", "student123");
        mockMvc.perform(get("/api/student/analytics/summary").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.examCount").value(0))
                .andExpect(jsonPath("$.data.averagePercentage").isEmpty())
                .andExpect(jsonPath("$.data.gradedQuestions").value(0))
                .andExpect(jsonPath("$.data.correctGradedQuestions").value(0))
                .andExpect(jsonPath("$.data.trueAccuracy").isEmpty())
                .andExpect(jsonPath("$.data.examTrend.length()").value(0))
                .andExpect(jsonPath("$.data.practice.totalSessions").value(0))
                .andExpect(jsonPath("$.data.practice.accuracy").isEmpty());
    }

    @Test
    void studentSummaryComputesTrueAccuracyAndDifficultyPerformance() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Math Basics", "Math");
        String questionId = createQuestion(teacherToken, examId, "What is 2 + 2?", "3", "4", "4");
        mockMvc.perform(post("/api/exams/" + examId + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"4\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/analytics/summary").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.examCount").value(1))
                .andExpect(jsonPath("$.data.averagePercentage").value(100.0))
                .andExpect(jsonPath("$.data.highestScore").value(100))
                .andExpect(jsonPath("$.data.lowestScore").value(100))
                .andExpect(jsonPath("$.data.gradedQuestions").value(1))
                .andExpect(jsonPath("$.data.correctGradedQuestions").value(1))
                .andExpect(jsonPath("$.data.trueAccuracy").value(100.0))
                .andExpect(jsonPath("$.data.difficultyPerformance.length()").value(1))
                .andExpect(jsonPath("$.data.difficultyPerformance[0].difficulty").value(2))
                .andExpect(jsonPath("$.data.difficultyPerformance[0].accuracy").value(100.0))
                .andExpect(jsonPath("$.data.subjectPerformance.length()").value(1))
                .andExpect(jsonPath("$.data.subjectPerformance[0].subject").value("Math"))
                .andExpect(jsonPath("$.data.subjectPerformance[0].averagePercentage").value(100.0));
    }

    @Test
    void studentExamsIncludeAttemptInfoAndScore() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Math Basics", "Math");
        String questionId = createQuestion(teacherToken, examId, "What is 2 + 2?", "3", "4", "4");
        mockMvc.perform(post("/api/exams/" + examId + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"4\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/analytics/exams").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].examId").value(examId))
                .andExpect(jsonPath("$.data[0].score").value(1))
                .andExpect(jsonPath("$.data[0].total").value(1))
                .andExpect(jsonPath("$.data[0].percentage").value(100.0))
                .andExpect(jsonPath("$.data[0].attempt.attemptNumber").value(1))
                .andExpect(jsonPath("$.data[0].attempt.durationSeconds").isNumber())
                .andExpect(jsonPath("$.data[0].attempt.submittedAt").isNotEmpty());
    }

    @Test
    void practiceActivityIsSummarized() throws Exception {
        String studentToken = login("student@example.com", "student123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertAttempts();
        insertQuestionOptionAnswerRows();
        insertPracticeData();

        mockMvc.perform(get("/api/student/analytics/summary").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.practice.totalSessions").value(3))
                .andExpect(jsonPath("$.data.practice.sessionsCompleted").value(2))
                .andExpect(jsonPath("$.data.practice.totalQuestions").value(3))
                .andExpect(jsonPath("$.data.practice.correctAnswers").value(2))
                .andExpect(jsonPath("$.data.practice.accuracy").value(66.67))
                .andExpect(jsonPath("$.data.practice.accuracyByDifficulty.length()").value(2))
                .andExpect(jsonPath("$.data.practice.mostRecentActivity").isNotEmpty());
    }

    @Test
    void studentAnalyticsAreScopedByPrincipal() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentOneToken = login("student@example.com", "student123");
        String studentTwoToken = login("student2@example.com", "student234");

        String examId = createExam(teacherToken, "Scoped Exam", "Science");
        String questionId = createQuestion(teacherToken, examId, "Water formula?", "H2O", "CO2", "H2O");
        mockMvc.perform(post("/api/exams/" + examId + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentOneToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"H2O\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/analytics/summary").header("Authorization", bearer(studentOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.examCount").value(1));

        mockMvc.perform(get("/api/student/analytics/summary").header("Authorization", bearer(studentTwoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.examCount").value(0));
    }

    @Test
    void studentRosterExposesScoreAttemptAndPracticePerStudent() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertResultsAndAttempts();
        insertQuestionOptionAnswerRows();
        insertPracticeData();

        mockMvc.perform(get("/api/analytics/exams/exam-1/students").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].studentId").value("student-1"))
                .andExpect(jsonPath("$.data[0].studentName").value("Student One"))
                .andExpect(jsonPath("$.data[0].hasResult").value(true))
                .andExpect(jsonPath("$.data[0].score").value(80))
                .andExpect(jsonPath("$.data[0].total").value(100))
                .andExpect(jsonPath("$.data[0].percentage").value(80.0))
                .andExpect(jsonPath("$.data[0].submittedAt").isEmpty())
                .andExpect(jsonPath("$.data[0].durationSeconds").isEmpty())
                .andExpect(jsonPath("$.data[0].attemptNumber").value(2))
                .andExpect(jsonPath("$.data[0].attemptStatus").value("STARTED"))
                .andExpect(jsonPath("$.data[0].activeNow").value(false))
                .andExpect(jsonPath("$.data[0].practiceQuestions").value(3))
                .andExpect(jsonPath("$.data[0].practiceCorrect").value(2))
                .andExpect(jsonPath("$.data[0].practiceAccuracy").value(66.67))
                .andExpect(jsonPath("$.data[1].studentId").value("student-2"))
                .andExpect(jsonPath("$.data[1].hasResult").value(true))
                .andExpect(jsonPath("$.data[1].percentage").value(40.0))
                .andExpect(jsonPath("$.data[1].attemptNumber").value(1))
                .andExpect(jsonPath("$.data[1].attemptStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[1].practiceQuestions").value(0))
                .andExpect(jsonPath("$.data[1].practiceAccuracy").isEmpty());
    }

    @Test
    void submittedAttemptReportsDurationSecondsInRoster() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        java.time.Instant started = java.time.Instant.parse("2026-09-02T10:00:00Z");
        java.time.Instant submitted = java.time.Instant.parse("2026-09-02T10:25:00Z");
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, submitted_at, version) "
                        + "values (?, ?, ?, 1, 'SUBMITTED', ?, ?, ?, 0)",
                "attempt-submitted", "exam-1", "student-1",
                java.sql.Timestamp.from(started), java.sql.Timestamp.from(submitted),
                java.sql.Timestamp.from(submitted));

        mockMvc.perform(get("/api/analytics/exams/exam-1/students").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].studentId").value("student-1"))
                .andExpect(jsonPath("$.data[0].attemptStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[0].submittedAt").isNotEmpty())
                .andExpect(jsonPath("$.data[0].durationSeconds").value(1500));
    }

    @Test
    void activeAttemptIsMarkedActiveNowInRoster() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) "
                        + "values (?, ?, ?, 1, 'STARTED', ?, ?, 0)",
                "attempt-active", "exam-1", "student-1", java.sql.Timestamp.from(java.time.Instant.now()),
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)));

        mockMvc.perform(get("/api/analytics/exams/exam-1/students").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].studentId").value("student-1"))
                .andExpect(jsonPath("$.data[0].hasResult").value(false))
                .andExpect(jsonPath("$.data[0].score").isEmpty())
                .andExpect(jsonPath("$.data[0].attemptStatus").value("STARTED"))
                .andExpect(jsonPath("$.data[0].activeNow").value(true));
    }

    @Test
    void optionLabelsExposeTextWithCorrectnessHidden() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertAttempts();
        insertQuestionOptionAnswerRows();

        String body = mockMvc.perform(get("/api/analytics/exams/exam-1/options").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].questionId").value("question-1"))
                .andExpect(jsonPath("$.data[0].optionId").value("option-1"))
                .andExpect(jsonPath("$.data[0].text").value("3"))
                .andExpect(jsonPath("$.data[0].displayOrder").value(0))
                .andExpect(jsonPath("$.data[1].text").value("4"))
                .andExpect(jsonPath("$.data[1].displayOrder").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode firstOption = objectMapper.readTree(body).path("data").path(0);
        assertThat(firstOption.has("correct")).isFalse();
        assertThat(firstOption.has("correctAnswer")).isFalse();
        assertThat(firstOption.has("answer")).isFalse();
    }

    @Test
    void rosterAndOptionLabelsRespectAuthorization() throws Exception {
        String studentToken = login("student@example.com", "student123");
        String teacherTwoToken = login("teacher2@example.com", "teacher223");
        String teacherToken = login("teacher@example.com", "teacher123");
        insertExam("exam-1", "Week 1", "Math", "UPCOMING", "teacher-1");
        insertExam("exam-2", "Week 2", "Science", "UPCOMING", "teacher-2");
        insertResultsAndAttempts();

        mockMvc.perform(get("/api/analytics/exams/exam-1/students")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/analytics/exams/exam-1/options")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/analytics/exams/exam-2/students").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/exams/exam-2/options").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/exams/exam-1/students").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/exams/exam-1/options").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/exams/exam-1/students").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/exams/exam-1/options").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/exams/exam-1/students").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
    }

    private void insertResultsAndAttempts() {
        insertResult("result-1", "student-1", "exam-1", "Week 1", "Math", 80, 100, "2026-09-02");
        insertResult("result-2", "student-2", "exam-1", "Week 1", "Math", 40, 100, "2026-09-01");
        insertAttempts();
    }

    private void insertAttempts() {
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) "
                        + "values ('attempt-1', 'exam-1', 'student-1', 1, 'SUBMITTED', current_timestamp, current_timestamp, 0)");
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) "
                        + "values ('attempt-2', 'exam-1', 'student-2', 1, 'SUBMITTED', current_timestamp, current_timestamp, 0)");
        jdbcTemplate.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) "
                        + "values ('attempt-3', 'exam-1', 'student-1', 2, 'STARTED', current_timestamp, current_timestamp, 0)");
    }

    private void insertQuestionOptionAnswerRows() {
        jdbcTemplate.update(
                "insert into questions (id, exam_id, text, answer, difficulty) values "
                        + "('question-1', 'exam-1', 'What is 2+2?', '4', 2), "
                        + "('question-2', 'exam-1', 'What is water?', 'H2O', 3)");
        jdbcTemplate.update(
                "insert into questions (id, exam_id, text, answer, difficulty) values "
                        + "('question-3', 'exam-1', 'Unanswered?', 'B', 2)");
        jdbcTemplate.update(
                "insert into question_options (id, question_id, text, display_order, correct_answer) values "
                        + "('option-1', 'question-1', '3', 0, false), "
                        + "('option-2', 'question-1', '4', 1, true)");
        jdbcTemplate.update(
                "insert into answers (id, user_id, exam_id, question_id, option_id, answer_value, attempt_id, correct) values "
                        + "('answer-1', 'student-1', 'exam-1', 'question-1', 'option-1', '3', 'attempt-1', true)");
        jdbcTemplate.update(
                "insert into answers (id, user_id, exam_id, question_id, option_id, answer_value, attempt_id, correct) values "
                        + "('answer-2', 'student-2', 'exam-1', 'question-1', 'option-2', '4', 'attempt-2', false)");
        jdbcTemplate.update(
                "insert into answers (id, user_id, exam_id, question_id, option_id, answer_value, attempt_id, correct) values "
                        + "('answer-3', 'student-1', 'exam-1', 'question-3', null, 'B', 'attempt-1', null)");
    }

    private void insertPracticeData() {
        jdbcTemplate.update(
                "insert into practice_sessions (id, student_id, exam_id, status, target_question_count, working_difficulty, "
                        + "answered_count, correct_count, started_at, last_activity_at, completed_at) values "
                        + "('session-1', 'student-1', 'exam-1', 'COMPLETED', 10, 2.0, 2, 1, current_timestamp, current_timestamp, current_timestamp), "
                        + "('session-2', 'student-1', 'exam-1', 'COMPLETED', 10, 3.0, 1, 1, current_timestamp, current_timestamp, current_timestamp), "
                        + "('session-3', 'student-1', 'exam-1', 'STARTED', 10, 2.0, 0, 0, current_timestamp, current_timestamp, null)");
        jdbcTemplate.update(
                "insert into practice_answers (id, session_id, question_id, option_id, answer_value, correct, difficulty, sequence_index, answered_at) values "
                        + "('practice-answer-1', 'session-1', 'question-1', 'option-1', '3', true, 2, 0, current_timestamp), "
                        + "('practice-answer-2', 'session-1', 'question-2', null, 'wrong', false, 2, 1, current_timestamp), "
                        + "('practice-answer-3', 'session-2', 'question-2', null, 'H2O', true, 3, 0, current_timestamp)");
    }

    private void insertExam(String id, String title, String subject, String status, String ownerId) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, ?, '2026-09-01', 60, ?, 0, null, ?)",
                id, title, subject, status, ownerId);
    }

    private void insertResult(String id, String userId, String examId, String examTitle,
                              String subject, int score, int total, String date) {
        jdbcTemplate.update(
                "insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, userId, examId, examTitle, subject, score, date, total);
    }

    private void insertAdmin() {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                "admin-1", "Admin", "admin@example.com", passwordEncoder.encode("admin123"), "ADMIN");
    }

    private String createExam(String token, String title, String subject) throws Exception {
        String response = mockMvc.perform(post("/api/exams")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"subject\":\"" + subject + "\",\"date\":\"2026-09-01\",\"duration\":60}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(response).path("data").path("id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    private String createQuestion(String token, String examId, String text, String firstOption, String secondOption, String answer) throws Exception {
        String response = mockMvc.perform(post("/api/exams/" + examId + "/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\",\"options\":[\"" + firstOption + "\",\"" + secondOption + "\"],\"answer\":\"" + answer + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(response).path("data").path("id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
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