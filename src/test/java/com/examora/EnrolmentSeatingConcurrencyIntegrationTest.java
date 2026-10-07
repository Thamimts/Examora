package com.examora;

import com.examora.model.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency hardening for the deterministic seat allocator. Uses the real HTTP stack on a random
 * port so N requests genuinely race: the writer threads are released at the same latch, each posting
 * its own {@code POST /api/enrollments} for the same exam.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora_enrolment_concurrency_gate;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-enrolment-concurrency-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class EnrolmentSeatingConcurrencyIntegrationTest {
    private static final String EXAM = "concurrency-exam-1";
    private static final String CENTRE = "concurrency-centre-1";
    private static final String ROOM_1 = "concurrency-room-1";
    private static final String ROOM_2 = "concurrency-room-2";
    private static final String TEACHER = "concurrency-teacher-1";
    private static final String TEACHER_EMAIL = "concurrency-teacher@example.com";
    private static final String TEACHER_PASSWORD = "teacher123";
    private static final String ADMIN_EMAIL = "concurrency-admin@example.com";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String STUDENT_PASSWORD = "student123";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

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

        insertUser("concurrency-teacher-1", "Concurrency Teacher", TEACHER_EMAIL, TEACHER_PASSWORD, Role.TEACHER);
        insertUser("concurrency-admin-1", "Concurrency Admin", ADMIN_EMAIL, ADMIN_PASSWORD, Role.ADMIN);
        jdbcTemplate.update("insert into exam_centres (id, name, code, status) values (?, ?, ?, 'ACTIVE')",
                CENTRE, "Concurrency Centre", "CC-01");
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) "
                        + "values (?, 'Concurrency Math Exam', 'Math', '2026-09-15', 60, 'PUBLISHED', 0, null, ?)",
                EXAM, TEACHER);
    }

    @Test
    void concurrentEnrolmentIntoSingleCapacityRoomKeepsOneSeat() throws Exception {
        setupCentreWithRoom(ROOM_1, "Single Capacity Room", "CC-SC", 1);

        // 3 students race for 1 seat.
        List<String> studentIds = insertStudents(3, 1);
        List<Integer> statuses = fireConcurrentEnrollments(studentIds);

        assertThat(statuses).hasSize(3);
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
        assertThat(statuses.stream().filter(status -> status == 409)).hasSize(2);

        assertThat(enrollmentRows()).isEqualTo(1);
        assertThat(assignmentRows()).isEqualTo(1);
        assertThat(duplicateSeatRows()).isZero();
        assertThat(duplicateStudentAssignmentRows()).isZero();

        Integer seat = jdbcTemplate.queryForObject(
                "select seat_number from room_assignments where exam_id = ? and room_id = ?",
                Integer.class, EXAM, ROOM_1);
        assertThat(seat).isEqualTo(1);
        assertThat(roomOccupancy(ROOM_1)).isEqualTo(1);
    }

    @Test
    void concurrentEnrolmentFillsRoomsWithoutDuplicatingSeatsOrExceedingCapacity() throws Exception {
        setupCentreWithRoom(ROOM_1, "Room Gamma", "CC-GA", 2);
        setupCentreWithRoom(ROOM_2, "Room Delta", "CC-DE", 2);

        // 10 students race for 4 seats across 2 rooms.
        List<String> studentIds = insertStudents(10, 2);
        List<Integer> statuses = fireConcurrentEnrollments(studentIds);

        assertThat(statuses).hasSize(10);
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(4);
        assertThat(statuses.stream().filter(status -> status == 409)).hasSize(6);

        assertThat(enrollmentRows()).isEqualTo(4);
        assertThat(assignmentRows()).isEqualTo(4);
        assertThat(duplicateSeatRows()).isZero();
        assertThat(duplicateStudentAssignmentRows()).isZero();

        int roomOne = roomOccupancy(ROOM_1);
        int roomTwo = roomOccupancy(ROOM_2);
        assertThat(roomOne).isLessThanOrEqualTo(2);
        assertThat(roomTwo).isLessThanOrEqualTo(2);
        assertThat(roomOne + roomTwo).isEqualTo(4);

        // Deterministic fill: least-occupied room first, ties by room name.
        List<SeatRow> seats = jdbcTemplate.query(
                "select room_id, seat_number from room_assignments where exam_id = ? order by room_id, seat_number",
                (rs, row) -> new SeatRow(rs.getString("room_id"), rs.getInt("seat_number")), EXAM);
        assertThat(seats).extracting(SeatRow::seatNumber).containsExactlyInAnyOrder(1, 2, 1, 2);
        assertThat(seats).extracting(SeatRow::roomId).containsExactlyInAnyOrder(ROOM_1, ROOM_1, ROOM_2, ROOM_2);
    }

    @Test
    void concurrentDuplicateEnrolmentBySameStudentIsIdempotent() throws Exception {
        setupCentreWithRoom(ROOM_1, "Single Capacity Room", "CC-SC", 2);

        List<String> studentIds = insertStudents(1, 3);
        String student = studentIds.get(0);

        List<Integer> statuses = fireConcurrentEnrollments(List.of(student, student, student));

        assertThat(statuses).hasSize(3);
        assertThat(statuses).allMatch(status -> status == 200);

        assertThat(enrollmentRows()).isEqualTo(1);
        assertThat(assignmentRows()).isEqualTo(1);
        assertThat(duplicateStudentAssignmentRows()).isZero();

        Integer seat = jdbcTemplate.queryForObject(
                "select seat_number from room_assignments where exam_id = ? and student_id = ?",
                Integer.class, EXAM, student);
        assertThat(seat).isEqualTo(1);
    }

    @Test
    void duplicateSeatInsertCannotProduceTwoAssignmentsForSameRoomsSeat() throws Exception {
        setupCentreWithRoom(ROOM_1, "Twin Room", "CC-TW", 10);

        // Students fire in a burst large enough to collide on the same next-seat computations.
        List<String> studentIds = insertStudents(8, 4);
        List<Integer> statuses = fireConcurrentEnrollments(studentIds);

        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(8);
        assertThat(duplicateSeatRows()).isZero();
        assertThat(duplicateStudentAssignmentRows()).isZero();
        assertThat(assignmentRows()).isEqualTo(8);

        Integer seats = jdbcTemplate.queryForObject(
                "select count(distinct seat_number) from room_assignments where exam_id = ? and room_id = ?",
                Integer.class, EXAM, ROOM_1);
        assertThat(seats).isEqualTo(8);
    }

    private List<Integer> fireConcurrentEnrollments(List<String> studentIds) throws Exception {
        int count = studentIds.size();
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(count);
        ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        List<Thread> threads = new ArrayList<>();
        for (String studentId : studentIds) {
            Thread thread = new Thread(() -> {
                try {
                    String token = login(emailOf(studentId), STUDENT_PASSWORD);
                    ready.countDown();
                    start.await();
                    HttpResponse<String> response = postEnroll(token);
                    statuses.add(response.statusCode());
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            threads.add(thread);
        }

        threads.forEach(Thread::start);
        assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        return new ArrayList<>(statuses);
    }

    private HttpResponse<String> postEnroll(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/enrollments"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"examId\":\"" + EXAM + "\"}"))
                .build();
        return http.send(request, BodyHandlers.ofString());
    }

    private String login(String email, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .build();
        HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).as("login for %s", email).isEqualTo(HttpStatus.OK.value());
        JsonNode body = objectMapper.readTree(response.body());
        return body.path("data").path("token").asText();
    }

    private List<String> insertStudents(int count, int startingIndex) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "concurrency-student-" + (startingIndex + i);
            insertUser(id, "Conc Student " + i, emailOf(id), STUDENT_PASSWORD, Role.STUDENT);
            ids.add(id);
        }
        return ids;
    }

    private String emailOf(String userId) {
        return userId + "@example.com";
    }

    private void setupCentreWithRoom(String roomId, String roomName, String roomCode, int capacity) {
        jdbcTemplate.update("insert into exam_centre_rooms (id, centre_id, room_name, room_code, capacity, status, invigilator_id) "
                        + "values (?, ?, ?, ?, ?, 'ACTIVE', ?)",
                roomId, CENTRE, roomName, roomCode, capacity, TEACHER);
        jdbcTemplate.update("update exams set centre_id = ? where id = ?", CENTRE, EXAM);
    }

    private int enrollmentRows() {
        Integer value = jdbcTemplate.queryForObject(
                "select count(*) from exam_enrollments where exam_id = ?", Integer.class, EXAM);
        return value == null ? 0 : value;
    }

    private int assignmentRows() {
        Integer value = jdbcTemplate.queryForObject(
                "select count(*) from room_assignments where exam_id = ?", Integer.class, EXAM);
        return value == null ? 0 : value;
    }

    private int roomOccupancy(String roomId) {
        Integer value = jdbcTemplate.queryForObject(
                "select count(*) from room_assignments where exam_id = ? and room_id = ?", Integer.class, EXAM, roomId);
        return value == null ? 0 : value;
    }

    private long duplicateSeatRows() {
        Long value = jdbcTemplate.queryForObject(
                "select count(*) from (select exam_id, room_id, seat_number from room_assignments "
                        + "where exam_id = ? group by exam_id, room_id, seat_number having count(*) > 1)",
                Long.class, EXAM);
        return value == null ? 0 : value;
    }

    private long duplicateStudentAssignmentRows() {
        Long value = jdbcTemplate.queryForObject(
                "select count(*) from (select exam_id, student_id from room_assignments "
                        + "where exam_id = ? group by exam_id, student_id having count(*) > 1)",
                Long.class, EXAM);
        return value == null ? 0 : value;
    }

    private void insertUser(String id, String name, String email, String password, Role role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id, name, email, passwordEncoder.encode(password), role.name());
    }

    private record SeatRow(String roomId, int seatNumber) {
    }
}