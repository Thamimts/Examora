package com.examora.repository;

import com.examora.model.ProctorEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProctorRepository {
    private final JdbcTemplate jdbcTemplate;

    public ProctorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int saveBatch(List<ProctorEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (ProctorEvent event : events) {
            jdbcTemplate.update(
                    "insert into proctor_events (id, attempt_id, type, occurred_at) values (?, ?, ?, ?)",
                    UUID.randomUUID().toString(),
                    event.attemptId(),
                    event.type(),
                    event.occurredAt() == null ? Instant.now().toString() : event.occurredAt());
            saved++;
        }
        return saved;
    }
}
