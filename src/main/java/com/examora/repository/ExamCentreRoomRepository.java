package com.examora.repository;

import com.examora.model.ExamCentreRoom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExamCentreRoomRepository {
    private final JdbcTemplate jdbcTemplate;

    public ExamCentreRoomRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ExamCentreRoom> findByCentreId(String centreId) {
        return jdbcTemplate.query(
                "select id, centre_id, room_name, room_code, capacity, invigilator_id, status, created_at, updated_at "
                        + "from exam_centre_rooms where centre_id = ? order by room_name",
                this::mapRoom,
                centreId);
    }

    public List<ExamCentreRoom> findActiveByCentreId(String centreId) {
        return jdbcTemplate.query(
                "select id, centre_id, room_name, room_code, capacity, invigilator_id, status, created_at, updated_at "
                        + "from exam_centre_rooms where centre_id = ? and status = 'ACTIVE' order by room_name",
                this::mapRoom,
                centreId);
    }

    public List<ExamCentreRoom> findActive() {
        return jdbcTemplate.query(
                "select id, centre_id, room_name, room_code, capacity, invigilator_id, status, created_at, updated_at "
                        + "from exam_centre_rooms where status = 'ACTIVE' order by room_name",
                this::mapRoom);
    }

    public List<ExamCentreRoom> findActiveByCentreIds(List<String> centreIds) {
        if (centreIds == null || centreIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(centreIds.size(), "?"));
        return jdbcTemplate.query(
                "select id, centre_id, room_name, room_code, capacity, invigilator_id, status, created_at, updated_at "
                        + "from exam_centre_rooms where status = 'ACTIVE' and centre_id in (" + placeholders + ") "
                        + "order by room_name",
                this::mapRoom, centreIds.toArray());
    }

    public Optional<ExamCentreRoom> findById(String id) {
        return jdbcTemplate.query(
                        "select id, centre_id, room_name, room_code, capacity, invigilator_id, status, created_at, updated_at "
                                + "from exam_centre_rooms where id = ?",
                        this::mapRoom,
                        id)
                .stream()
                .findFirst();
    }

    public List<ExamCentreRoom> findByInvigilatorId(String invigilatorId) {
        return jdbcTemplate.query(
                "select id, centre_id, room_name, room_code, capacity, invigilator_id, status, created_at, updated_at "
                        + "from exam_centre_rooms where invigilator_id = ? order by room_name",
                this::mapRoom,
                invigilatorId);
    }

    public ExamCentreRoom create(ExamCentreRoom room) {
        jdbcTemplate.update(
                "insert into exam_centre_rooms (id, centre_id, room_name, room_code, capacity, invigilator_id, status) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                room.id(), room.centreId(), room.roomName(), room.roomCode(), room.capacity(),
                room.invigilatorId(), room.status());
        return room;
    }

    public int update(String id, ExamCentreRoom room) {
        return jdbcTemplate.update(
                "update exam_centre_rooms set room_name = ?, room_code = ?, capacity = ?, invigilator_id = ?, "
                        + "status = ?, updated_at = current_timestamp where id = ?",
                room.roomName(), room.roomCode(), room.capacity(), room.invigilatorId(), room.status(), id);
    }

    public int delete(String id) {
        return jdbcTemplate.update("delete from exam_centre_rooms where id = ?", id);
    }

    public boolean lockForUpdate(String id) {
        List<Integer> rows = jdbcTemplate.query(
                "select capacity from exam_centre_rooms where id = ? for update",
                (rs, rowNum) -> rs.getInt("capacity"), id);
        return !rows.isEmpty();
    }

    private ExamCentreRoom mapRoom(ResultSet rs, int rowNum) throws SQLException {
        return new ExamCentreRoom(
                rs.getString("id"),
                rs.getString("centre_id"),
                rs.getString("room_name"),
                rs.getString("room_code"),
                rs.getInt("capacity"),
                rs.getString("invigilator_id"),
                rs.getString("status"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());
    }
}