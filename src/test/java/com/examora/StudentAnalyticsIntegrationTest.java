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
        "spring.datasource.url=jdbc:h2:mem:examora-analytics;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-analytics-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class StudentAnalyticsIntegrationTest {
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
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("student-1", "Student One", "student@example.com", "student123", "STUDENT");
        insertUser("student-2", "Student Two", "student2@example.com", "student234", "STUDENT");
        insertUser("teacher-1", "Teacher One", "teacher@example.com", "teacher123", "TEACHER");
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/student/performance"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void studentWithNoResultsGetsEmptyAnalytics() throws Exception {
        String studentToken = login("student@example.com", "student123");

        mockMvc.perform(get("/api/student/performance").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.completedExamCount").value(0))
                .andExpect(jsonPath("$.data.averageScore").value(0.0))
                .andExpect(jsonPath("$.data.highestScore").doesNotExist())
                .andExpect(jsonPath("$.data.lowestScore").doesNotExist())
                .andExpect(jsonPath("$.data.accuracy").value(0.0))
                .andExpect(jsonPath("$.data.recentScoreTrend.length()").value(0))
                .andExpect(jsonPath("$.data.subjectPerformance.length()").value(0))
                .andExpect(jsonPath("$.data.topicPerformance.length()").value(0));
    }

    @Test
    void studentWithSubmittedExamGetsComputedAnalytics() throws Exception {
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

        mockMvc.perform(get("/api/student/performance").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedExamCount").value(1))
                .andExpect(jsonPath("$.data.averageScore").value(100.0))
                .andExpect(jsonPath("$.data.highestScore").value(100))
                .andExpect(jsonPath("$.data.lowestScore").value(100))
                .andExpect(jsonPath("$.data.accuracy").value(100.0))
                .andExpect(jsonPath("$.data.recentScoreTrend.length()").value(1))
                .andExpect(jsonPath("$.data.recentScoreTrend[0].examTitle").value("Math Basics"))
                .andExpect(jsonPath("$.data.recentScoreTrend[0].score").value(100.0))
                .andExpect(jsonPath("$.data.subjectPerformance.length()").value(1))
                .andExpect(jsonPath("$.data.subjectPerformance[0].subject").value("Math"))
                .andExpect(jsonPath("$.data.subjectPerformance[0].completedExamCount").value(1));
    }

    @Test
    void analyticsAreScopedToTheAuthenticatedStudent() throws Exception {
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

        mockMvc.perform(get("/api/student/performance").header("Authorization", bearer(studentOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedExamCount").value(1));

        mockMvc.perform(get("/api/student/performance").header("Authorization", bearer(studentTwoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedExamCount").value(0));
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