package com.examora.repository;

import com.examora.model.RoomAssignment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoomAssignmentRepository {
    private final JdbcTemplate jdbcTemplate;

    public RoomAssignmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(String id, String examId, String studentId, String roomId, int seatNumber) {
        return jdbcTemplate.update(
                "insert into room_assignments (id, exam_id, student_id, room_id, seat_number) "
                        + "values (?, ?, ?, ?, ?)",
                id, examId, studentId, roomId, seatNumber);
    }

    public Optional<RoomAssignment> findByStudentAndExam(String studentId, String examId) {
        return jdbcTemplate.query(
                        "select id, exam_id, student_id, room_id, seat_number, assigned_at from room_assignments "
                                + "where student_id = ? and exam_id = ?",
                        this::mapAssignment,
                        studentId, examId)
                .stream()
                .findFirst();
    }

    public List<RoomAssignment> findByExamId(String examId) {
        return jdbcTemplate.query(
                "select id, exam_id, student_id, room_id, seat_number, assigned_at from room_assignments "
                        + "where exam_id = ? order by seat_number",
                this::mapAssignment,
                examId);
    }

    public List<RoomAssignment> findByRoomIdAndExam(String examId, String roomId) {
        return jdbcTemplate.query(
                "select id, exam_id, student_id, room_id, seat_number, assigned_at from room_assignments "
                        + "where exam_id = ? and room_id = ? order by seat_number",
                this::mapAssignment,
                examId, roomId);
    }

    public int countForRoom(String examId, String roomId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from room_assignments where exam_id = ? and room_id = ?",
                Integer.class, examId, roomId);
        return count == null ? 0 : count;
    }

    public List<RoomExamCount> countsByRoom() {
        return jdbcTemplate.query(
                "select room_id, exam_id, count(*) as cnt from room_assignments group by room_id, exam_id",
                (rs, rowNum) -> new RoomExamCount(rs.getString("room_id"), rs.getString("exam_id"), rs.getInt("cnt")));
    }

    public int delete(String id) {
        return jdbcTemplate.update("delete from room_assignments where id = ?", id);
    }

    private RoomAssignment mapAssignment(ResultSet rs, int rowNum) throws SQLException {
        return new RoomAssignment(
                rs.getString("id"),
                rs.getString("exam_id"),
                rs.getString("student_id"),
                rs.getString("room_id"),
                rs.getInt("seat_number"),
                rs.getTimestamp("assigned_at") == null ? null : rs.getTimestamp("assigned_at").toInstant());
    }

    public record RoomExamCount(String roomId, String examId, int count) {
    }
}