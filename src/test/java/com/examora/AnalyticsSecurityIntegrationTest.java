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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-analytics-security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-analytics-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class AnalyticsSecurityIntegrationTest {
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
        insertUser("admin-1", "Admin", "admin@example.com", "admin123", "ADMIN");

        insertFixtures();
    }

    @Test
    void teacherResultListIsScopedToOwnedExams() throws Exception {
        String teacherOneToken = login("teacher@example.com", "teacher123");
        String teacherTwoToken = login("teacher2@example.com", "teacher223");

        mockMvc.perform(get("/api/results").header("Authorization", bearer(teacherOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].examId").value("exam-1"));

        mockMvc.perform(get("/api/results").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].examId").value("exam-2"));
    }

    @Test
    void staffMeRequiresExplicitUserId() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/results/me").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/results/me?userId=student-1").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].userId").value("student-1"));
    }

    @Test
    void adminMeCanQueryAnyStudent() throws Exception {
        String adminToken = login("admin@example.com", "admin123");

        mockMvc.perform(get("/api/results/me?userId=student-1").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/results/me?userId=student-2").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void teacherCannotReadOthersResultById() throws Exception {
        String teacherTwoToken = login("teacher2@example.com", "teacher223");

        mockMvc.perform(get("/api/results/result-1").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/results/result-2").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isOk());
    }

    @Test
    void teacherCannotWriteResultsForOtherExams() throws Exception {
        String teacherTwoToken = login("teacher2@example.com", "teacher223");

        mockMvc.perform(post("/api/results")
                        .header("Authorization", bearer(teacherTwoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"student-1\",\"examId\":\"exam-1\",\"examTitle\":\"Week 1\","
                                + "\"subject\":\"Math\",\"score\":50,\"total\":100,\"date\":\"2026-09-03\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/results/result-1").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/results/result-2").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/results/result-2").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void teacherAnswersAreScopedToOwnedExams() throws Exception {
        String teacherOneToken = login("teacher@example.com", "teacher123");
        String teacherTwoToken = login("teacher2@example.com", "teacher223");

        mockMvc.perform(get("/api/answers?examId=exam-1").header("Authorization", bearer(teacherOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/answers?examId=exam-2").header("Authorization", bearer(teacherOneToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/answers").header("Authorization", bearer(teacherOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/answers/answer-1").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/answers/answer-1").header("Authorization", bearer(teacherOneToken)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/answers/answer-1").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/answers/answer-2").header("Authorization", bearer(teacherTwoToken)))
                .andExpect(status().isOk());
    }

    @Test
    void teacherCannotCreateAnswersInOtherExams() throws Exception {
        String teacherTwoToken = login("teacher2@example.com", "teacher223");

        mockMvc.perform(post("/api/answers")
                        .header("Authorization", bearer(teacherTwoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"student-1\",\"examId\":\"exam-1\",\"questionId\":\"question-1\",\"value\":\"4\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentCannotAccessStaffAnalyticEndpoints() throws Exception {
        String studentToken = login("student@example.com", "student123");

        mockMvc.perform(get("/api/results").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/answers").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/student/adaptive/sessions").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/analytics/summary").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk());
    }

    @Test
    void teacherCanNoLongerReachStudentOnlyAnalyticOrAdaptiveEndpoints() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/student/analytics/summary").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/student/analytics/exams").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/student/adaptive/sessions").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
    }

    private void insertFixtures() {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values ('exam-1', 'Week 1', 'Math', '2026-09-01', 60, 'UPCOMING', 0, null, 'teacher-1')");
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values ('exam-2', 'Week 2', 'Science', '2026-09-02', 60, 'UPCOMING', 0, null, 'teacher-2')");
        jdbcTemplate.update(
                "insert into questions (id, exam_id, text, answer, difficulty) values ('question-1', 'exam-1', 'Q', '4', 2)");
        jdbcTemplate.update(
                "insert into questions (id, exam_id, text, answer, difficulty) values ('question-3', 'exam-2', 'Q', '4', 2)");
        jdbcTemplate.update(
                "insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) values "
                        + "('result-1', 'student-1', 'exam-1', 'Week 1', 'Math', 80, '2026-09-02', 100)");
        jdbcTemplate.update(
                "insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) values "
                        + "('result-2', 'student-2', 'exam-2', 'Week 2', 'Science', 90, '2026-09-02', 100)");
        jdbcTemplate.update(
                "insert into answers (id, user_id, exam_id, question_id, option_id, answer_value, attempt_id, correct) values "
                        + "('answer-1', 'student-1', 'exam-1', 'question-1', null, '4', null, true)");
        jdbcTemplate.update(
                "insert into answers (id, user_id, exam_id, question_id, option_id, answer_value, attempt_id, correct) values "
                        + "('answer-2', 'student-2', 'exam-2', 'question-3', null, '4', null, true)");
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