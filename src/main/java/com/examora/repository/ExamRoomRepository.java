package com.examora.repository;

import com.examora.model.ExamRoom;
import com.examora.model.ExamRoomStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExamRoomRepository {
    private final JdbcTemplate jdbc;

    public ExamRoomRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_COLUMNS =
            "select id, exam_id, room_code, status, created_by, started_at, ended_at, created_at, updated_at from exam_rooms";

    /** Earliest ACTIVE room for the exam that the student has JOINED. */
    public Optional<String> findActiveRoomForStudent(String examId, String studentId) {
        return jdbc.query(
                        "select r.id from exam_rooms r join exam_room_members m on m.room_id = r.id "
                                + "where r.exam_id = ? and m.student_id = ? and r.status = 'ACTIVE' and m.status = 'JOINED' "
                                + "order by r.started_at asc, r.created_at asc, r.id asc limit 1",
                        (rs, row) -> rs.getString(1), examId, studentId)
                .stream().findFirst();
    }

    public ExamRoom create(ExamRoom room) {
        jdbc.update(
                "insert into exam_rooms (id, exam_id, room_code, status, created_by, started_at, ended_at) values (?, ?, ?, ?, ?, ?, ?)",
                room.id(),
                room.examId(),
                room.roomCode(),
                room.status().name(),
                room.createdBy(),
                room.startedAt() == null ? null : Timestamp.from(room.startedAt()),
                room.endedAt() == null ? null : Timestamp.from(room.endedAt()));
        return room;
    }

    public Optional<ExamRoom> findById(String id) {
        return jdbc.query(SELECT_COLUMNS + " where id = ?", this::map, id).stream().findFirst();
    }

    public List<ExamRoom> findByExamId(String examId) {
        return jdbc.query(SELECT_COLUMNS + " where exam_id = ? order by created_at asc, id asc", this::map, examId);
    }

    public Optional<ExamRoom> findByCode(String roomCode) {
        return jdbc.query(SELECT_COLUMNS + " where room_code = ?", this::map, roomCode).stream().findFirst();
    }

    public List<ExamRoom> findAll() {
        return jdbc.query(SELECT_COLUMNS + " order by created_at desc", this::map);
    }

    public List<ExamRoom> findByCreator(String createdBy) {
        return jdbc.query(SELECT_COLUMNS + " where created_by = ? order by created_at desc", this::map, createdBy);
    }

    public List<ExamRoom> findRoomsJoinedByStudent(String studentId) {
        return jdbc.query(
                "select r.id, r.exam_id, r.room_code, r.status, r.created_by, r.started_at, r.ended_at, "
                        + "r.created_at, r.updated_at "
                        + "from exam_rooms r join exam_room_members m on m.room_id = r.id "
                        + "where m.student_id = ? and m.status = 'JOINED' order by r.created_at desc",
                this::map, studentId);
    }

    public Optional<String> findExamId(String roomId) {
        return jdbc.query("select exam_id from exam_rooms where id = ?", (rs, row) -> rs.getString(1), roomId)
                .stream().findFirst();
    }

    public Optional<String> findCreatorId(String roomId) {
        return jdbc.query("select created_by from exam_rooms where id = ?", (rs, row) -> rs.getString(1), roomId)
                .stream().findFirst();
    }

    /** Guarded transition: only a WAITING room can become ACTIVE. Returns affected row count. */
    public int markActive(String roomId, Instant now) {
        return jdbc.update(
                "update exam_rooms set status = 'ACTIVE', started_at = ?, updated_at = ? where id = ? and status = 'WAITING'",
                Timestamp.from(now), Timestamp.from(now), roomId);
    }

    /** Guarded transition: only an ACTIVE room can become ENDED. Returns affected row count. */
    public int markEnded(String roomId, Instant now) {
        return jdbc.update(
                "update exam_rooms set status = 'ENDED', ended_at = ?, updated_at = ? where id = ? and status = 'ACTIVE'",
                Timestamp.from(now), Timestamp.from(now), roomId);
    }

    private ExamRoom map(ResultSet rs, int rowNum) throws SQLException {
        return new ExamRoom(
                rs.getString("id"),
                rs.getString("exam_id"),
                rs.getString("room_code"),
                ExamRoomStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant(),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());
    }
}