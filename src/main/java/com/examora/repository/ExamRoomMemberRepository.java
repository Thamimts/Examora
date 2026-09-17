package com.examora.repository;

import com.examora.model.ExamRoomMember;
import com.examora.model.ExamRoomMemberStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExamRoomMemberRepository {
    private final JdbcTemplate jdbc;

    public ExamRoomMemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_COLUMNS =
            "select id, room_id, student_id, status, joined_at, left_at from exam_room_members";

    /**
     * Guarded join: only succeeds while the room exists, is not ENDED, and the
     * student does not already hold a JOINED membership. Returns affected row count.
     */
    public int joinIfAllowed(String memberId, String roomId, String studentId) {
        return jdbc.update(
                "insert into exam_room_members (id, room_id, student_id, joined_at, status) "
                        + "select ?, ?, ?, current_timestamp, 'JOINED' "
                        + "from exam_rooms r "
                        + "where r.id = ? and r.status <> 'ENDED' "
                        + "  and not exists (select 1 from exam_room_members m "
                        + "                   where m.room_id = ? and m.student_id = ? and m.status = 'JOINED')",
                memberId, roomId, studentId,
                roomId, roomId, studentId);
    }

    public Optional<ExamRoomMember> findForStudentByRoom(String roomId, String studentId, ExamRoomMemberStatus status) {
        return jdbc.query(SELECT_COLUMNS + " where room_id = ? and student_id = ? and status = ?",
                        this::map, roomId, studentId, status.name())
                .stream().findFirst();
    }

    public List<ExamRoomMember> findByRoomId(String roomId, ExamRoomMemberStatus status) {
        return jdbc.query(SELECT_COLUMNS + " where room_id = ? and status = ? order by joined_at asc",
                this::map, roomId, status.name());
    }

    public int countJoined(String roomId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from exam_room_members where room_id = ? and status = 'JOINED'",
                Integer.class, roomId);
        return count == null ? 0 : count;
    }

    public List<String> findJoinedStudentIds(String roomId) {
        return jdbc.query("select student_id from exam_room_members where room_id = ? and status = 'JOINED'",
                (rs, row) -> rs.getString(1), roomId);
    }

    /** Guarded leave: flips an existing JOINED membership to LEFT. Returns affected row count. */
    public int markLeft(String roomId, String studentId, Instant now) {
        return jdbc.update(
                "update exam_room_members set status = 'LEFT', left_at = ? "
                        + "where room_id = ? and student_id = ? and status = 'JOINED'",
                Timestamp.from(now), roomId, studentId);
    }

    private ExamRoomMember map(ResultSet rs, int rowNum) throws SQLException {
        return new ExamRoomMember(
                rs.getString("id"),
                rs.getString("room_id"),
                rs.getString("student_id"),
                ExamRoomMemberStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("joined_at") == null ? null : rs.getTimestamp("joined_at").toInstant(),
                rs.getTimestamp("left_at") == null ? null : rs.getTimestamp("left_at").toInstant());
    }
}