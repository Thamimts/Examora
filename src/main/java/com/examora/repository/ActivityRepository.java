package com.examora.repository;

import com.examora.model.ActivityEvent;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ActivityRepository {
    private final JdbcTemplate jdbc;
    public ActivityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public ActivityEvent create(String id, String actorId, String audience, String type, String message) {
        jdbc.update("insert into activity_events (id, actor_id, audience, type, message) values (?, ?, ?, ?, ?)", id, actorId, audience, type, message);
        return new ActivityEvent(id, type, message, java.time.Instant.now());
    }
    public List<ActivityEvent> findForStudent(String studentId) { return find("where audience = 'STUDENT' and actor_id = ?", studentId); }
    public List<ActivityEvent> findForAdmin() { return find("where audience = 'ADMIN'", new Object[0]); }
    private List<ActivityEvent> find(String where, Object... args) {
        return jdbc.query("select id, type, message, created_at from activity_events " + where + " order by created_at desc limit 20",
                (rs, row) -> new ActivityEvent(rs.getString("id"), rs.getString("type"), rs.getString("message"), rs.getTimestamp("created_at").toInstant()), args);
    }
}
