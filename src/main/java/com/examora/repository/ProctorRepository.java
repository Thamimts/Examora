package com.examora.repository;

import com.examora.exception.ApiException;
import com.examora.model.ProctorEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProctorRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProctorRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ProctorEvent> saveBatch(List<ProctorEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<ProctorEvent> saved = new ArrayList<>();
        for (ProctorEvent event : events) {
            if (insert(event)) {
                saved.add(event);
            }
        }
        return saved;
    }

    private boolean insert(ProctorEvent event) {
        try {
            jdbcTemplate.update(
                    "insert into proctor_events (id, attempt_id, event_id, type, occurred_at, metadata, source, confidence, duration_ms) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(),
                    event.attemptId(),
                    blankToNull(event.eventId()),
                    event.type(),
                    event.occurredAt() == null ? Instant.now().toString() : event.occurredAt(),
                    writeMetadata(event.metadata()),
                    event.source() == null ? "BROWSER" : event.source(),
                    event.confidence(),
                    event.durationMs());
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public List<ProctorEventRow> findByAttemptId(String attemptId, int limit) {
        return jdbcTemplate.query(
                "select id, attempt_id, event_id, type, occurred_at, metadata, server_received_at, source, confidence, duration_ms "
                        + "from proctor_events where attempt_id = ? "
                        + "order by occurred_at desc, server_received_at desc, id desc limit ?",
                this::mapRow,
                attemptId,
                limit);
    }

    public List<ProctorEventRow> findByAttemptIdWithin(String attemptId, String fromOccurredAt, String toOccurredAt, int limit) {
        return jdbcTemplate.query(
                "select id, attempt_id, event_id, type, occurred_at, metadata, server_received_at, source, confidence, duration_ms "
                        + "from proctor_events "
                        + "where attempt_id = ? and occurred_at >= ? and occurred_at <= ? "
                        + "order by occurred_at desc, server_received_at desc, id desc limit ?",
                this::mapRow,
                attemptId,
                fromOccurredAt,
                toOccurredAt,
                limit);
    }

    public List<ProctorEventRow> findByExamId(String examId) {
        return jdbcTemplate.query(
                "select pe.id, pe.attempt_id, pe.event_id, pe.type, pe.occurred_at, pe.metadata, pe.server_received_at, "
                        + "pe.source, pe.confidence, pe.duration_ms "
                        + "from proctor_events pe join exam_attempts ea on ea.id = pe.attempt_id "
                        + "where ea.exam_id = ? "
                        + "order by pe.occurred_at desc, pe.server_received_at desc, pe.id desc",
                this::mapRow,
                examId);
    }

    /**
     * Exact number of proctor events within the given inclusive bound for an attempt.
     * Used by run data-quality and matrix signal counts; precise, not capped.
     */
    public long countByAttemptIdWithin(String attemptId, String fromOccurredAt, String toOccurredAt) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from proctor_events "
                        + "where attempt_id = ? and occurred_at >= ? and occurred_at <= ?",
                Long.class, attemptId, fromOccurredAt, toOccurredAt);
        return count == null ? 0L : count;
    }

    /**
     * Aggregated signal types within the inclusive bound for an attempt, ordered by
     * descending count. Bounded by the number of distinct signal types.
     */
    public java.util.LinkedHashMap<String, Integer> signalTypeCountsByAttemptIdWithin(
            String attemptId, String fromOccurredAt, String toOccurredAt) {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        jdbcTemplate.query(
                "select type, count(*) as type_count from proctor_events "
                        + "where attempt_id = ? and occurred_at >= ? and occurred_at <= ? "
                        + "group by type order by type_count desc, type asc",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        counts.put(rs.getString("type"), rs.getInt("type_count")),
                attemptId, fromOccurredAt, toOccurredAt);
        return counts;
    }

    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor event metadata must be valid JSON.");
        }
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ProctorEventRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Timestamp received = rs.getTimestamp("server_received_at");
        return new ProctorEventRow(
                rs.getString("id"),
                rs.getString("event_id"),
                rs.getString("attempt_id"),
                rs.getString("type"),
                rs.getString("occurred_at"),
                received == null ? null : received.toInstant().toString(),
                readMetadata(rs.getString("metadata")),
                rs.getString("source"),
                rs.getObject("confidence", Double.class),
                rs.getObject("duration_ms", Long.class));
    }

    public record ProctorEventRow(String id, String eventId, String attemptId, String type, String occurredAt,
                                  String serverReceivedAt, Map<String, Object> metadata, String source,
                                  Double confidence, Long durationMs) {
    }
}