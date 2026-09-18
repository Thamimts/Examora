package com.examora.repository;

import com.examora.exception.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ResearchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ResearchExperiment insertExperiment(String name, String description, String algorithmVersion,
                                               String baselineVersion, String status, String createdBy) {
        String id = UUID.randomUUID().toString();
        Timestamp createdAt = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "insert into research_experiments "
                        + "(id, name, description, algorithm_version, baseline_version, status, created_by, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, description, algorithmVersion, baselineVersion, status, createdBy, createdAt);
        return new ResearchExperiment(id, name, description, algorithmVersion, baselineVersion, status,
                createdBy, createdAt.toInstant());
    }

    public Optional<ResearchExperiment> findExperimentById(String id) {
        List<ResearchExperiment> rows = jdbcTemplate.query(
                "select id, name, description, algorithm_version, baseline_version, status, created_by, created_at "
                        + "from research_experiments where id = ?",
                (rs, rowNum) -> new ResearchExperiment(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("algorithm_version"),
                        rs.getString("baseline_version"),
                        rs.getString("status"),
                        rs.getString("created_by"),
                        toInstant(rs.getTimestamp("created_at"))),
                id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<ResearchExperiment> listExperiments() {
        return jdbcTemplate.query(
                "select id, name, description, algorithm_version, baseline_version, status, created_by, created_at "
                        + "from research_experiments order by created_at desc",
                (rs, rowNum) -> new ResearchExperiment(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("algorithm_version"),
                        rs.getString("baseline_version"),
                        rs.getString("status"),
                        rs.getString("created_by"),
                        toInstant(rs.getTimestamp("created_at"))));
    }

    public void updateExperimentStatus(String id, String status) {
        jdbcTemplate.update("update research_experiments set status = ? where id = ?", status, id);
    }

    public ResearchSample insertSample(String experimentId, String attemptId, String windowStart,
                                       String windowEnd, String label, Map<String, String> metadata,
                                       Long rawMediaBytes, Long signalBytes) {
        String id = UUID.randomUUID().toString();
        Timestamp createdAt = Timestamp.from(Instant.now());
        try {
            jdbcTemplate.update(
                    "insert into research_samples "
                            + "(id, experiment_id, attempt_id, window_start, window_end, label, metadata, "
                            + "raw_media_bytes, signal_bytes, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, experimentId, attemptId, windowStart, windowEnd, label, writeMetadata(metadata),
                    rawMediaBytes, signalBytes, createdAt);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "a sample already exists for this experiment and window");
        }
        return new ResearchSample(id, experimentId, attemptId, windowStart, windowEnd, label, metadata,
                rawMediaBytes, signalBytes, createdAt.toInstant());
    }

    public Optional<ResearchSample> findSampleById(String id) {
        List<ResearchSample> rows = jdbcTemplate.query(
                "select id, experiment_id, attempt_id, window_start, window_end, label, metadata, "
                        + "raw_media_bytes, signal_bytes, created_at from research_samples where id = ?",
                (rs, rowNum) -> mapSample(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<ResearchSample> findSamplesByExperiment(String experimentId, int limit) {
        return jdbcTemplate.query(
                "select id, experiment_id, attempt_id, window_start, window_end, label, metadata, "
                        + "raw_media_bytes, signal_bytes, created_at from research_samples "
                        + "where experiment_id = ? order by created_at desc limit ?",
                (rs, rowNum) -> mapSample(rs), experimentId, limit);
    }

    public Map<String, Long> countReviewsByExperiment(String experimentId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "select s.id as sample_id, count(r.id) as review_count from research_samples s "
                        + "left join research_reviews r on r.sample_id = s.id "
                        + "where s.experiment_id = ? group by s.id",
                rs -> {
                    counts.put(rs.getString("sample_id"), rs.getLong("review_count"));
                }, experimentId);
        return counts;
    }

    public List<ResearchReview> findReviewsBySampleId(String sampleId) {
        return jdbcTemplate.query(
                "select id, sample_id, reviewer_id, label, confidence, notes, reviewed_at "
                        + "from research_reviews where sample_id = ? order by reviewed_at asc",
                (rs, rowNum) -> new ResearchReview(
                        rs.getString("id"),
                        rs.getString("sample_id"),
                        rs.getString("reviewer_id"),
                        rs.getString("label"),
                        rs.getObject("confidence", Double.class),
                        rs.getString("notes"),
                        toInstant(rs.getTimestamp("reviewed_at"))),
                sampleId);
    }

    public void upsertReview(String sampleId, String reviewerId, String label, Double confidence, String notes) {
        jdbcTemplate.update(
                "insert into research_reviews "
                        + "(id, sample_id, reviewer_id, label, confidence, notes, reviewed_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?) "
                        + "on duplicate key update label = values(label), confidence = values(confidence), "
                        + "notes = values(notes), reviewed_at = values(reviewed_at)",
                UUID.randomUUID().toString(), sampleId, reviewerId, label, confidence, notes,
                Timestamp.from(Instant.now()));
    }

    public void updateSampleLabel(String sampleId, String label) {
        jdbcTemplate.update("update research_samples set label = ? where id = ?", label, sampleId);
    }

    private ResearchSample mapSample(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResearchSample(
                rs.getString("id"),
                rs.getString("experiment_id"),
                rs.getString("attempt_id"),
                rs.getString("window_start"),
                rs.getString("window_end"),
                rs.getString("label"),
                readMetadata(rs.getString("metadata")),
                rs.getObject("raw_media_bytes", Long.class),
                rs.getObject("signal_bytes", Long.class),
                toInstant(rs.getTimestamp("created_at")));
    }

    private String writeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "conditions could not be serialized");
        }
    }

    private Map<String, String> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record ResearchExperiment(String id, String name, String description, String algorithmVersion,
                                     String baselineVersion, String status, String createdBy, Instant createdAt) {
    }

    public record ResearchSample(String id, String experimentId, String attemptId, String windowStart,
                                 String windowEnd, String label, Map<String, String> metadata,
                                 Long rawMediaBytes, Long signalBytes, Instant createdAt) {
    }

    public record ResearchReview(String id, String sampleId, String reviewerId, String label,
                                 Double confidence, String notes, Instant reviewedAt) {
    }
}