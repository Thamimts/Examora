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
        "spring.datasource.url=jdbc:h2:mem:examora-dashboard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-dashboard-verification-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class StudentDashboardIntegrationTest {
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
        jdbcTemplate.update("delete from practice_answers");
        jdbcTemplate.update("delete from practice_sessions");
        jdbcTemplate.update("delete from proctor_events");
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from retest_requests");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from activity_events");
        jdbcTemplate.update("delete from users");

        insertUser("student-1", "Student One", "student@example.com", "student123", "STUDENT");
        insertUser("student-2", "Student Two", "student2@example.com", "student234", "STUDENT");
        insertUser("teacher-1", "Teacher One", "teacher@example.com", "teacher123", "TEACHER");
    }

    // --- Active attempts: auth + isolation ---

    @Test
    void unauthenticatedAttemptsRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/exams/attempts/active"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void teacherCannotAccessStudentAttemptsEndpoint() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/exams/attempts/active")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentWithNoAttemptsGetsEmptyActiveList() throws Exception {
        String token = login("student@example.com", "student123");

        mockMvc.perform(get("/api/exams/attempts/active")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void activeAttemptsAreScopedToAuthenticatedStudent() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentOneToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Scoped Attempt Exam", "Math", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "What is 2+2?", "3", "4", "4", 3);

        mockMvc.perform(post("/api/exams/" + examId + "/start")
                        .header("Authorization", bearer(studentOneToken)))
                .andExpect(status().isOk());

        // Student 1 sees the active attempt
        mockMvc.perform(get("/api/exams/attempts/active")
                        .header("Authorization", bearer(studentOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].examId").value(examId))
                .andExpect(jsonPath("$.data[0].status").value("STARTED"));

        // Student 2 sees nothing
        String studentTwoToken = login("student2@example.com", "student234");
        mockMvc.perform(get("/api/exams/attempts/active")
                        .header("Authorization", bearer(studentTwoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void submittedAttemptDisappearsFromActiveList() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Submit Exam", "Physics", "UPCOMING");
        publishExam(teacherToken, examId);
        String questionId = createQuestion(teacherToken, examId, "What is speed of light?", "A", "B", "A", 5);

        mockMvc.perform(post("/api/exams/" + examId + "/start")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptId").isNotEmpty());

        mockMvc.perform(get("/api/exams/attempts/active")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].examTitle").value("Submit Exam"))
                .andExpect(jsonPath("$.data[0].subject").value("Physics"))
                .andExpect(jsonPath("$.data[0].duration").value(60))
                .andExpect(jsonPath("$.data[0].remainingSeconds").isNumber());

        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + questionId + "\",\"value\":\"A\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/exams/attempts/active")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- Practice sessions: auth + isolation ---

    @Test
    void unauthenticatedSessionsRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/student/adaptive/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void teacherCannotAccessStudentSessionsEndpoint() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");

        mockMvc.perform(get("/api/student/adaptive/sessions")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentWithNoPracticeGetsEmptySessionList() throws Exception {
        String token = login("student@example.com", "student123");

        mockMvc.perform(get("/api/student/adaptive/sessions")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void createdPracticeSessionAppearsInSessionsEndpoint() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Practice Session Exam", "Chemistry", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "What is H2O?", "Water", "Oxygen", "Water", 2);

        // Create a practice session via the existing adaptive GET endpoint
        mockMvc.perform(get("/api/student/adaptive/exams/" + examId + "/session")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("STARTED"));

        mockMvc.perform(get("/api/student/adaptive/sessions")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].examId").value(examId))
                .andExpect(jsonPath("$.data[0].examTitle").value("Practice Session Exam"))
                .andExpect(jsonPath("$.data[0].subject").value("Chemistry"))
                .andExpect(jsonPath("$.data[0].status").value("STARTED"))
                .andExpect(jsonPath("$.data[0].targetQuestionCount").isNumber())
                .andExpect(jsonPath("$.data[0].answeredCount").value(0));
    }

    @Test
    void practiceSessionsAreScopedToAuthenticatedStudent() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentOneToken = login("student@example.com", "student123");
        String studentTwoToken = login("student2@example.com", "student234");

        String examId = createExam(teacherToken, "Scoped Practice Exam", "Biology", "UPCOMING");
        publishExam(teacherToken, examId);
        createQuestion(teacherToken, examId, "DNA basics", "A", "B", "A", 3);

        mockMvc.perform(get("/api/student/adaptive/exams/" + examId + "/session")
                        .header("Authorization", bearer(studentOneToken)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/student/adaptive/sessions")
                        .header("Authorization", bearer(studentOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/student/adaptive/sessions")
                        .header("Authorization", bearer(studentTwoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- helpers (mirrors existing test patterns) ---

    private String createExam(String token, String title, String subject, String status) throws Exception {
        String response = mockMvc.perform(post("/api/exams")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"subject\":\"" + subject + "\",\"date\":\"2026-09-01\",\"duration\":60,\"status\":\"" + status + "\",\"participants\":0}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asText();
    }

    private void publishExam(String token, String examId) throws Exception {
        mockMvc.perform(post("/api/exams/" + examId + "/publish")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private String createQuestion(String token, String examId, String text, String firstOption, String secondOption,
                                  String answer, int difficulty) throws Exception {
        String response = mockMvc.perform(post("/api/exams/" + examId + "/questions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\",\"options\":[\"" + firstOption + "\",\"" + secondOption + "\"],\"answer\":\"" + answer + "\",\"difficulty\":" + difficulty + "}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asText();
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
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
