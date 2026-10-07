package com.examora;

import com.examora.model.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_enrolment_gate;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-enrolment-seating-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class EnrolmentSeatingIntegrationTest {
    private static final String EXAM_1 = "enrolment-exam-1";
    private static final String EXAM_2 = "enrolment-exam-2";
    private static final String CENTRE_1 = "enrolment-centre-1";
    private static final String CENTRE_2 = "enrolment-centre-2";
    private static final String ROOM_1 = "enrolment-room-1";
    private static final String ROOM_2 = "enrolment-room-2";

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
        jdbcTemplate.update("delete from room_assignments");
        jdbcTemplate.update("delete from exam_enrollments");
        jdbcTemplate.update("delete from exam_centre_rooms");
        jdbcTemplate.update("delete from exam_centres");
        jdbcTemplate.update("delete from activity_events");
        jdbcTemplate.update("delete from proctor_events");
        jdbcTemplate.update("delete from answers");
        jdbcTemplate.update("delete from results");
        jdbcTemplate.update("delete from question_options");
        jdbcTemplate.update("delete from questions");
        jdbcTemplate.update("delete from exam_attempts");
        jdbcTemplate.update("delete from exam_access_state");
        jdbcTemplate.update("delete from retest_requests");
        jdbcTemplate.update("delete from exams");
        jdbcTemplate.update("delete from users");

        insertUser("enrol-teacher-1", "Enrol Teacher One", "enrol-teacher@example.com", "teacher123", Role.TEACHER);
        insertUser("enrol-teacher-2", "Enrol Teacher Two", "enrol-teacher2@example.com", "teacher234", Role.TEACHER);
        insertUser("enrol-admin-1", "Enrol Admin One", "enrol-admin@example.com", "admin123", Role.ADMIN);
        insertUser("enrol-student-1", "Enrol Student One", "enrol-student@example.com", "student123", Role.STUDENT);
        insertUser("enrol-student-2", "Enrol Student Two", "enrol-student2@example.com", "student234", Role.STUDENT);
        insertExam(EXAM_1, "Enrolment Math Exam", "enrol-teacher-1");
        insertExam(EXAM_2, "Enrolment Physics Exam", "enrol-teacher-1");
    }

    @Test
    void academicFirstLoginThenPasswordChange() throws Exception {
        String adminToken = login("enrol-admin@example.com", "admin123");

        String createResponse = mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Academic Student\","
                                + "\"email\":\"academic@example.com\","
                                + "\"role\":\"STUDENT\","
                                + "\"rollNumber\":\"AC-2024-001\","
                                + "\"dateOfBirth\":\"2004-05-12\","
                                + "\"department\":\"Computer Science\","
                                + "\"batch\":\"2024\""
                                + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String studentId = objectMapper.readTree(createResponse).path("data").path("id").asText();
        assertThat(studentId).isNotBlank();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"academic@example.com\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized());

        String firstLoginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"AC-2024-001\",\"dateOfBirth\":\"2004-05-12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresPasswordChange").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String firstLoginToken = objectMapper.readTree(firstLoginResponse).path("data").path("token").asText();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"AC-2024-002\",\"dateOfBirth\":\"2004-05-12\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"AC-2024-001\",\"dateOfBirth\":\"1990-01-01\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", bearer(firstLoginToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"temporary\",\"newPassword\":\"brand-new-pass-1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"AC-2024-001\",\"dateOfBirth\":\"2004-05-12\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"academic@example.com\",\"password\":\"brand-new-pass-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresPasswordChange").value(false));

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", bearer(login("academic@example.com", "brand-new-pass-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rollNumber").value("AC-2024-001"))
                .andExpect(jsonPath("$.data.department").value("Computer Science"));
    }

    @Test
    void duplicateRollNumberIsRejected() throws Exception {
        String adminToken = login("enrol-admin@example.com", "admin123");
        String body = "{"
                + "\"name\":\"Academic Two\","
                + "\"email\":\"academic-two@example.com\","
                + "\"role\":\"STUDENT\","
                + "\"rollNumber\":\"DUPLICATE-ROLL\","
                + "\"dateOfBirth\":\"2004-05-12\""
                + "}";
        mockMvc.perform(post("/api/users").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/users").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("academic-two@example.com", "academic-three@example.com")))
                .andExpect(status().isConflict());
    }

    @Test
    void teacherCanResetStudentCredentials() throws Exception {
        String adminToken = login("enrol-admin@example.com", "admin123");
        String response = mockMvc.perform(post("/api/users").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Reset Candidate\","
                                + "\"email\":\"reset-candidate@example.com\","
                                + "\"role\":\"STUDENT\","
                                + "\"password\":\"passwordhash-1\""
                                + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String studentId = objectMapper.readTree(response).path("data").path("id").asText();

        mockMvc.perform(post("/api/users/" + studentId + "/reset-credentials")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"RESET-2024-09\",\"dateOfBirth\":\"2003-07-21\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rollNumber").value("RESET-2024-09"))
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(true));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNumber\":\"RESET-2024-09\",\"dateOfBirth\":\"2003-07-21\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresPasswordChange").value(true));
    }

    @Test
    void enrolmentAssignsDeterministicPersistentSeats() throws Exception {
        String adminToken = login("enrol-admin@example.com", "admin123");
        String teacherToken = login("enrol-teacher@example.com", "teacher123");
        setupCentreWithRooms();

        mockMvc.perform(put("/api/exams/" + EXAM_1 + "/centre")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"centreId\":\"" + CENTRE_1 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.centreId").value(CENTRE_1));

        String studentOneToken = login("enrol-student@example.com", "student123");
        String studentTwoToken = login("enrol-student2@example.com", "student234");

        String first = mockMvc.perform(post("/api/enrollments")
                        .header("Authorization", bearer(studentOneToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":\"" + EXAM_1 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(ROOM_1))
                .andExpect(jsonPath("$.data.seatNumber").value(1))
                .andExpect(jsonPath("$.data.centreName").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String firstAssignment = objectMapper.readTree(first).path("data").path("assignmentId").asText();
        assertThat(firstAssignment).isNotBlank();

        String duplicate = mockMvc.perform(post("/api/enrollments")
                        .header("Authorization", bearer(studentOneToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":\"" + EXAM_1 + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String duplicateAssignment = objectMapper.readTree(duplicate).path("data").path("assignmentId").asText();
        assertThat(duplicateAssignment).isEqualTo(firstAssignment);

        String second = mockMvc.perform(post("/api/enrollments")
                        .header("Authorization", bearer(studentTwoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":\"" + EXAM_1 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(ROOM_2))
                .andExpect(jsonPath("$.data.seatNumber").value(1))
                .andReturn().getResponse().getContentAsString();
        String secondRoomId = objectMapper.readTree(second).path("data").path("roomId").asText();
        assertThat(secondRoomId).isEqualTo(ROOM_2);

        assertThat(assignmentCount(EXAM_1)).isEqualTo(2);
        assertThat(rows("select count(*) from room_assignments where exam_id = ? and room_id = ?", EXAM_1, ROOM_1)).isEqualTo(1);
        assertThat(rows("select count(*) from room_assignments where exam_id = ? and room_id = ?", EXAM_1, ROOM_2)).isEqualTo(1);
    }

    @Test
    void enrolmentWithoutCentreIsRejected() throws Exception {
        String studentToken = login("enrol-student@example.com", "student123");

        mockMvc.perform(post("/api/enrollments")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":\"" + EXAM_2 + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This exam has no exam centre configured, so no seat can be assigned."));
    }

    @Test
    void capacityExhaustionIsRejected() throws Exception {
        String adminToken = login("enrol-admin@example.com", "admin123");
        String teacherToken = login("enrol-teacher@example.com", "teacher123");

        jdbcTemplate.update("insert into exam_centres (id, name, code, status) values (?, ?, ?, 'ACTIVE')", CENTRE_2, "Capacity Centre", "CAP-01");
        jdbcTemplate.update("insert into exam_centre_rooms (id, centre_id, room_name, room_code, capacity, status, invigilator_id) "
                        + "values (?, ?, ?, ?, 1, 'ACTIVE', 'enrol-teacher-1')",
                ROOM_2, CENTRE_2, "Single Room", "SR-01");

        mockMvc.perform(put("/api/exams/" + EXAM_2 + "/centre")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"centreId\":\"" + CENTRE_2 + "\"}"))
                .andExpect(status().isOk());

        String studentOneToken = login("enrol-student@example.com", "student123");
        String studentTwoToken = login("enrol-student2@example.com", "student234");

        mockMvc.perform(post("/api/enrollments").header("Authorization", bearer(studentOneToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"examId\":\"" + EXAM_2 + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/enrollments").header("Authorization", bearer(studentTwoToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"examId\":\"" + EXAM_2 + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No seats are available for this exam."));
    }

    @Test
    void roleDenialsAroundCentresRoomsAndRosters() throws Exception {
        String adminToken = login("enrol-admin@example.com", "admin123");
        String teacherToken = login("enrol-teacher@example.com", "teacher123");
        String otherTeacherToken = login("enrol-teacher2@example.com", "teacher234");
        String studentOneToken = login("enrol-student@example.com", "student123");
        setupCentreWithRooms();
        mockMvc.perform(put("/api/exams/" + EXAM_1 + "/centre")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"centreId\":\"" + CENTRE_1 + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/enrollments").header("Authorization", bearer(studentOneToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"examId\":\"" + EXAM_1 + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/centres").header("Authorization", bearer(studentOneToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\",\"code\":\"NOPE\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/enrollments/exam/" + EXAM_1)
                        .header("Authorization", bearer(studentOneToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/enrollments/exam/" + EXAM_1)
                        .header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/enrollments/exam/" + EXAM_1)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].studentId").value("enrol-student-1"))
                .andExpect(jsonPath("$.data[0].roomId").value(ROOM_1))
                .andExpect(jsonPath("$.data[0].seatNumber").value(1));

        mockMvc.perform(get("/api/centre-rooms/" + ROOM_1 + "/seating")
                        .header("Authorization", bearer(studentOneToken))
                        .param("examId", EXAM_1))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/centre-rooms/" + ROOM_1 + "/seating")
                        .header("Authorization", bearer(otherTeacherToken))
                        .param("examId", EXAM_1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You are not the invigilator of this room."));

        mockMvc.perform(put("/api/enrollments/exam/" + EXAM_1 + "/students/enrol-student-1")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":\"" + ROOM_1 + "\",\"seatNumber\":1}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/centre-rooms")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"centreId\":\"" + CENTRE_1 + "\",\"roomName\":\"Sneaky\",\"roomCode\":\"SNEAKY\",\"capacity\":10}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/centres")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/centres/" + CENTRE_1)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/centres/" + CENTRE_1)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
    }

    @Test
    void centreStatsAppearInCentreListings() throws Exception {
        String adminToken = login("enrol-admin@example.com", "admin123");
        String teacherToken = login("enrol-teacher@example.com", "teacher123");
        String studentToken = login("enrol-student@example.com", "student123");
        setupCentreWithRooms();
        mockMvc.perform(put("/api/exams/" + EXAM_1 + "/centre")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"centreId\":\"" + CENTRE_1 + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/enrollments").header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"examId\":\"" + EXAM_1 + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/centres").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + CENTRE_1 + "')].totalCapacity", hasItem(4)))
                .andExpect(jsonPath("$.data[?(@.id=='" + CENTRE_1 + "')].assignedSeats", hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.id=='" + CENTRE_1 + "')].availableSeats", hasItem(3)));

        mockMvc.perform(get("/api/centres/" + CENTRE_1).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCapacity").value(4))
                .andExpect(jsonPath("$.data.assignedSeats").value(1))
                .andExpect(jsonPath("$.data.availableSeats").value(3))
                .andExpect(jsonPath("$.data.roomCount").value(2));

        mockMvc.perform(get("/api/centres/" + CENTRE_1 + "/rooms").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + ROOM_1 + "')].occupied", hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.id=='" + ROOM_1 + "')].availableSeats", hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.id=='" + ROOM_2 + "')].occupied", hasItem(0)))
                .andExpect(jsonPath("$.data[?(@.id=='" + ROOM_2 + "')].availableSeats", hasItem(2)));
    }

    @Test
    void enrollmentDetailCarriesAttemptStatusAndEnrollmentId() throws Exception {
        String teacherToken = login("enrol-teacher@example.com", "teacher123");
        String studentToken = login("enrol-student@example.com", "student123");
        setupCentreWithRooms();
        mockMvc.perform(put("/api/exams/" + EXAM_1 + "/centre")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"centreId\":\"" + CENTRE_1 + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/enrollments").header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"examId\":\"" + EXAM_1 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrollmentId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("ENROLLED"))
                .andExpect(jsonPath("$.data.attemptStatus").value(nullValue()))
                .andExpect(jsonPath("$.data.roomId").value(ROOM_1))
                .andExpect(jsonPath("$.data.seatNumber").value(1));

        mockMvc.perform(get("/api/enrollments/my").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].enrollmentId").isNotEmpty())
                .andExpect(jsonPath("$.data[0].attemptStatus").value(nullValue()))
                .andExpect(jsonPath("$.data[0].centreName").value("Enrolment Centre"))
                .andExpect(jsonPath("$.data[0].roomCode").value("RA-01"));
    }

    @Test
    void invigilatorRoomSummaryRespectsRolesAndNumbers() throws Exception {
        String adminToken = login("enrol-admin@example.com", "admin123");
        String teacherToken = login("enrol-teacher@example.com", "teacher123");
        String otherTeacherToken = login("enrol-teacher2@example.com", "teacher234");
        String studentToken = login("enrol-student@example.com", "student123");
        setupCentreWithRooms();
        mockMvc.perform(put("/api/exams/" + EXAM_1 + "/centre")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"centreId\":\"" + CENTRE_1 + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/enrollments").header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"examId\":\"" + EXAM_1 + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/centre-rooms/mine/summary").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/centre-rooms/mine/summary").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].examId", hasItem(EXAM_1)))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].examTitle", hasItem("Enrolment Math Exam")))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].occupied", hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].activeCount", hasItem(0)))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].submittedCount", hasItem(0)))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].live", hasItem(false)))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].riskLevel", hasItem(nullValue())))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_2 + "')].examId", hasItem(nullValue())))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_2 + "')].occupied", hasItem(0)));

        mockMvc.perform(get("/api/centre-rooms/mine/summary").header("Authorization", bearer(otherTeacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/centre-rooms/mine/summary").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].examId", hasItem(EXAM_1)));

        mockMvc.perform(get("/api/enrollments/exam/" + EXAM_1).header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attemptStatus").value(nullValue()))
                .andExpect(jsonPath("$.data[0].riskLevel").value(nullValue()));

        mockMvc.perform(get("/api/centre-rooms/" + ROOM_1 + "/seating")
                        .header("Authorization", bearer(teacherToken))
                        .param("examId", EXAM_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.occupied").value(1))
                .andExpect(jsonPath("$.data.students[0].seatNumber").value(1))
                .andExpect(jsonPath("$.data.students[0].status").value(nullValue()))
                .andExpect(jsonPath("$.data.students[0].riskLevel").value(nullValue()));
    }

    @Test
    void adminManualAssignmentEdgeCases() throws Exception {
        String adminToken = login("enrol-admin@example.com", "admin123");
        String teacherToken = login("enrol-teacher@example.com", "teacher123");
        String studentToken = login("enrol-student@example.com", "student123");
        insertUser("enrol-student-3", "Enrol Student Three", "enrol-student3@example.com", "student345", Role.STUDENT);
        setupCentreWithRooms();
        mockMvc.perform(put("/api/exams/" + EXAM_1 + "/centre")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"centreId\":\"" + CENTRE_1 + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/enrollments").header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"examId\":\"" + EXAM_1 + "\"}"))
                .andExpect(status().isOk());

        jdbcTemplate.update("insert into exam_enrollments (id, exam_id, student_id, status) values (?, ?, ?, 'ENROLLED')",
                "enrolment-x-2", EXAM_1, "enrol-student-2");
        jdbcTemplate.update("insert into exam_enrollments (id, exam_id, student_id, status) values (?, ?, ?, 'ENROLLED')",
                "enrolment-x-3", EXAM_1, "enrol-student-3");

        String assign = "{\"roomId\":\"%s\",\"seatNumber\":%d}";
        mockMvc.perform(put("/api/enrollments/exam/" + EXAM_1 + "/students/enrol-student-2")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(assign, "missing-room", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Exam room not found."));

        mockMvc.perform(put("/api/enrollments/exam/" + EXAM_1 + "/students/enrol-student-2")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(assign, ROOM_2, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Seat number must be between 1 and 2."));

        mockMvc.perform(put("/api/enrollments/exam/" + EXAM_1 + "/students/enrol-student-2")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(assign, ROOM_2, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(ROOM_2))
                .andExpect(jsonPath("$.data.seatNumber").value(1))
                .andExpect(jsonPath("$.data.studentId").value("enrol-student-2"));

        mockMvc.perform(put("/api/enrollments/exam/" + EXAM_1 + "/students/enrol-student-3")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(assign, ROOM_2, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("That seat is already taken for this exam."));

        mockMvc.perform(put("/api/enrollments/exam/" + EXAM_1 + "/students/enrol-student-3")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(assign, ROOM_1, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("That seat is already taken for this exam."));

        mockMvc.perform(put("/api/enrollments/exam/" + EXAM_1 + "/students/enrol-student-1")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(assign, ROOM_1, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This student already has a room assignment for the exam."));

        mockMvc.perform(put("/api/enrollments/exam/" + EXAM_1 + "/students/enrol-admin-1")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(assign, ROOM_1, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This student is not enrolled in the exam."));

        mockMvc.perform(put("/api/enrollments/exam/" + EXAM_1 + "/students/enrol-student-2")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(assign, ROOM_1, 2)))
                .andExpect(status().isForbidden());

        assertThat(assignmentCount(EXAM_1)).isEqualTo(2);
    }

    @Test
    void riskAndAttemptStatusFlowIntoInvigilatorViews() throws Exception {
        String teacherToken = login("enrol-teacher@example.com", "teacher123");
        String studentToken = login("enrol-student@example.com", "student123");
        setupCentreWithRooms();
        mockMvc.perform(put("/api/exams/" + EXAM_1 + "/centre")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"centreId\":\"" + CENTRE_1 + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/enrollments").header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"examId\":\"" + EXAM_1 + "\"}"))
                .andExpect(status().isOk());

        jdbcTemplate.update("insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at) "
                        + "values ('attempt-risk-1', ?, 'enrol-student-1', 1, 'STARTED', current_timestamp, DATEADD('HOUR', 1, current_timestamp))",
                EXAM_1);
        jdbcTemplate.update("insert into proctor_events (id, attempt_id, event_id, type, occurred_at, source) "
                + "values ('pe-risk-1', 'attempt-risk-1', 'ev-risk-1', 'MULTIPLE_FACES', '2026-09-20T12:00:00Z', 'BROWSER')");
        jdbcTemplate.update("insert into proctor_events (id, attempt_id, event_id, type, occurred_at, source) "
                + "values ('pe-risk-2', 'attempt-risk-1', 'ev-risk-2', 'MULTIPLE_FACES', '2026-09-20T12:00:01Z', 'BROWSER')");

        mockMvc.perform(get("/api/centre-rooms/mine/summary").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].activeCount", hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].live", hasItem(true)))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].riskLevel", hasItem("HIGH")))
                .andExpect(jsonPath("$.data[?(@.roomId=='" + ROOM_1 + "')].riskScore", hasItem(60.0)));

        mockMvc.perform(get("/api/enrollments/exam/" + EXAM_1).header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attemptStatus").value("STARTED"))
                .andExpect(jsonPath("$.data[0].riskLevel").value("HIGH"));

        mockMvc.perform(get("/api/centre-rooms/" + ROOM_1 + "/seating")
                        .header("Authorization", bearer(teacherToken))
                        .param("examId", EXAM_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.students[0].status").value("STARTED"))
                .andExpect(jsonPath("$.data.students[0].riskLevel").value("HIGH"));
    }

    private void setupCentreWithRooms() {
        jdbcTemplate.update("insert into exam_centres (id, name, code, status) values (?, ?, ?, 'ACTIVE')",
                CENTRE_1, "Enrolment Centre", "ENR-01");
        jdbcTemplate.update("insert into exam_centre_rooms (id, centre_id, room_name, room_code, capacity, status, invigilator_id) "
                        + "values (?, ?, ?, ?, 2, 'ACTIVE', 'enrol-teacher-1')",
                ROOM_1, CENTRE_1, "Room Alpha", "RA-01");
        jdbcTemplate.update("insert into exam_centre_rooms (id, centre_id, room_name, room_code, capacity, status, invigilator_id) "
                        + "values (?, ?, ?, ?, 2, 'ACTIVE', 'enrol-teacher-1')",
                ROOM_2, CENTRE_1, "Room Beta", "RB-01");
    }

    private long assignmentCount(String examId) {
        return rows("select count(*) from room_assignments where exam_id = ?", examId);
    }

    private long rows(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(response).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private void insertUser(String id, String name, String email, String password, Role role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role.name());
    }

    private void insertExam(String id, String title, String createdBy) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, ?, 'Math', '2026-09-15', 60, 'PUBLISHED', 0, null, ?)",
                id, title, createdBy);
    }
}