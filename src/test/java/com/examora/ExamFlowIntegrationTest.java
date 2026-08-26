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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-flow-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class ExamFlowIntegrationTest {
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
        insertUser("teacher-1", "Teacher One", "teacher@example.com", "teacher123", "TEACHER");
        insertUser("admin-1", "Admin One", "admin@example.com", "admin123", "ADMIN");
    }

    @Test
    void teacherCanCrudExamsAndQuestions() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String examId = createExam(teacherToken, "Core Java", "Java", "DRAFT");

        mockMvc.perform(get("/api/exams").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/exams/" + examId).header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Core Java"));

        mockMvc.perform(put("/api/exams/" + examId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Advanced Java\",\"subject\":\"Java\",\"date\":\"2026-09-01\",\"duration\":90,\"status\":\"DRAFT\",\"participants\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Advanced Java"));

        String questionId = createQuestion(teacherToken, examId, "Which keyword creates inheritance?", "extends", "implements", "extends");

        mockMvc.perform(get("/api/exams/" + examId + "/questions").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].examId").value(examId))
                .andExpect(jsonPath("$.data[0].answer").value("extends"));

        mockMvc.perform(get("/api/questions/" + questionId + "/options").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].correctAnswer").value(true));

        mockMvc.perform(put("/api/questions/" + questionId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":\"" + examId + "\",\"text\":\"Which keyword implements an interface?\",\"options\":[\"extends\",\"implements\"],\"answer\":\"implements\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("implements"));

        mockMvc.perform(delete("/api/questions/" + questionId).header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/exams/" + examId).header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
    }

    @Test
    void studentCanRetrieveSubmitAndViewResult() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String examId = createExam(teacherToken, "Math Basics", "Math", "DRAFT");
        String hiddenQuestionId = createQuestion(teacherToken, examId, "What is 2 + 2?", "3", "4", "4");

        mockMvc.perform(get("/api/exams").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(post("/api/exams/" + examId + "/publish").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UPCOMING"));

        mockMvc.perform(get("/api/exams").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(examId));

        mockMvc.perform(post("/api/exams/" + examId + "/start").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STARTED"));

        mockMvc.perform(get("/api/exams/" + examId + "/questions").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(hiddenQuestionId))
                .andExpect(jsonPath("$.data[0].answer").doesNotExist())
                .andExpect(jsonPath("$.data[0].options.length()").value(2));

        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + hiddenQuestionId + "\",\"value\":\"4\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.percentage").value(100.0))
                .andExpect(jsonPath("$.data.result.userId").value("student-1"))
                .andExpect(jsonPath("$.data.result.examId").value(examId));

        mockMvc.perform(post("/api/exams/" + examId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + hiddenQuestionId + "\",\"value\":\"3\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(1))
                .andExpect(jsonPath("$.data.total").value(1));

        Integer savedAnswers = jdbcTemplate.queryForObject("select count(*) from answers where user_id = ? and exam_id = ?", Integer.class, "student-1", examId);
        Integer savedResults = jdbcTemplate.queryForObject("select count(*) from results where user_id = ? and exam_id = ? and score = 1 and total = 1", Integer.class, "student-1", examId);
        assertThat(savedAnswers).isEqualTo(1);
        assertThat(savedResults).isEqualTo(1);

        mockMvc.perform(get("/api/results/me").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].examId").value(examId))
                .andExpect(jsonPath("$.data[0].score").value(1));
    }

    @Test
    void questionRequiresOptionsAndAValidCorrectAnswer() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String examId = createExam(teacherToken, "Validation", "Math", "DRAFT");

        mockMvc.perform(post("/api/exams/" + examId + "/questions")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Incomplete MCQ\",\"options\":[\"Only one\"],\"answer\":\"Only one\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/exams/" + examId + "/questions")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Wrong answer\",\"options\":[\"A\",\"B\"],\"answer\":\"C\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submissionRejectsQuestionFromAnotherExam() throws Exception {
        String teacherToken = login("teacher@example.com", "teacher123");
        String studentToken = login("student@example.com", "student123");

        String firstExamId = createExam(teacherToken, "First", "Math", "UPCOMING");
        String secondExamId = createExam(teacherToken, "Second", "Science", "UPCOMING");
        String otherQuestionId = createQuestion(teacherToken, secondExamId, "Water formula?", "H2O", "CO2", "H2O");

        mockMvc.perform(post("/api/exams/" + firstExamId + "/submit")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"" + otherQuestionId + "\",\"value\":\"H2O\"}]}"))
                .andExpect(status().isBadRequest());
    }

    private String createExam(String token, String title, String subject, String status) throws Exception {
        String response = mockMvc.perform(post("/api/exams")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"subject\":\"" + subject + "\",\"date\":\"2026-09-01\",\"duration\":60,\"status\":\"" + status + "\",\"participants\":0}"))
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
