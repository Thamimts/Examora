package com.examora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.examora.model.ActivityEvent;
import com.examora.model.User;
import com.examora.security.JwtService;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-auth-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class AuthSecurityIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from activity_events");
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
    void loginReturnsTokenUserAndRoleForStudent() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@example.com\",\"password\":\"student123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.user.email").value("student@example.com"))
                .andExpect(jsonPath("$.data.user.role").value("STUDENT"));
    }

    @Test
    void incorrectCredentialsReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminLoginReturnsTheDatabaseAdminRole() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.user.role").value("ADMIN"));
    }

    @Test
    void registrationOnlyCreatesStudentAccounts() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New User\",\"email\":\"new.user@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("STUDENT"));
    }

    @Test
    void studentCannotUseTeacherOrAdminEndpoints() throws Exception {
        String token = login("student@example.com", "student123");

        mockMvc.perform(get("/api/admin/system").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        jdbcTemplate.update("insert into activity_events (id, audience, type, message) values ('admin-activity', 'ADMIN', 'EXAM_CREATED', 'Private administrator activity')");
        mockMvc.perform(get("/api/activity").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/exams")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Math Test\",\"subject\":\"Math\",\"duration\":60}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherCanManageExamsButCannotManageUsers() throws Exception {
        String token = login("teacher@example.com", "teacher123");

        mockMvc.perform(post("/api/exams")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Physics Test\",\"subject\":\"Physics\",\"duration\":45}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Physics Test"));

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanAccessUserManagement() throws Exception {
        String token = login("admin@example.com", "admin123");

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void authenticatedStudentConnectsToOwnActivityWebSocketDestination() throws Exception {
        User student = new User("student-1", "Student One", "student@example.com", com.examora.model.Role.STUDENT, null);
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws?token=" + jwtService.generateToken(student), new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
        LinkedBlockingQueue<ActivityEvent> events = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/activity", new StompFrameHandler() {
            @Override public Type getPayloadType(org.springframework.messaging.simp.stomp.StompHeaders headers) { return ActivityEvent.class; }
            @Override public void handleFrame(org.springframework.messaging.simp.stomp.StompHeaders headers, Object payload) { events.offer((ActivityEvent) payload); }
        });
        Thread.sleep(200);
        messagingTemplate.convertAndSendToUser("student-1", "/queue/activity", new ActivityEvent("live-1", "EXAM_STARTED", "Live activity", Instant.now()));
        assertThat(events.poll(5, TimeUnit.SECONDS)).isNotNull();
        session.disconnect();
        client.stop();
    }

    private String login(String email, String password) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
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
}
