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
                                               String baselineVersion, String datasetVersion, String status,
                                               String examId, String createdBy) {
        String id = UUID.randomUUID().toString();
        Timestamp createdAt = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "insert into research_experiments "
                        + "(id, name, description, algorithm_version, baseline_version, dataset_version, status, exam_id, created_by, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, description, algorithmVersion, baselineVersion, datasetVersion, status, examId, createdBy, createdAt);
        return new ResearchExperiment(id, name, description, algorithmVersion, baselineVersion, datasetVersion,
                status, examId, createdBy, createdAt.toInstant());
    }

    public Optional<ResearchExperiment> findExperimentById(String id) {
        List<ResearchExperiment> rows = jdbcTemplate.query(
                "select id, name, description, algorithm_version, baseline_version, dataset_version, status, "
                        + "exam_id, created_by, created_at "
                        + "from research_experiments where id = ?",
                (rs, rowNum) -> new ResearchExperiment(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("algorithm_version"),
                        rs.getString("baseline_version"),
                        rs.getString("dataset_version"),
                        rs.getString("status"),
                        rs.getString("exam_id"),
                        rs.getString("created_by"),
                        toInstant(rs.getTimestamp("created_at"))),
                id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<ResearchExperiment> listExperiments() {
        return jdbcTemplate.query(
                "select id, name, description, algorithm_version, baseline_version, dataset_version, status, "
                        + "exam_id, created_by, created_at "
                        + "from research_experiments order by created_at desc",
                (rs, rowNum) -> new ResearchExperiment(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("algorithm_version"),
                        rs.getString("baseline_version"),
                        rs.getString("dataset_version"),
                        rs.getString("status"),
                        rs.getString("exam_id"),
                        rs.getString("created_by"),
                        toInstant(rs.getTimestamp("created_at"))));
    }

    public void updateExperimentStatus(String id, String status) {
        jdbcTemplate.update("update research_experiments set status = ? where id = ?", status, id);
    }

    public ResearchSample insertSample(String experimentId, String attemptId, String windowStart,
                                       String windowEnd, String scenario, String label,
                                       Map<String, String> metadata, Long rawMediaBytes, Long signalBytes,
                                       Long measuredLatencyMs) {
        String id = UUID.randomUUID().toString();
        Timestamp createdAt = Timestamp.from(Instant.now());
        try {
            jdbcTemplate.update(
                    "insert into research_samples "
                            + "(id, experiment_id, attempt_id, window_start, window_end, scenario, label, metadata, "
                            + "raw_media_bytes, signal_bytes, measured_latency_ms, created_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, experimentId, attemptId, windowStart, windowEnd, scenario, label, writeMetadata(metadata),
                    rawMediaBytes, signalBytes, measuredLatencyMs, createdAt);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "a sample already exists for this experiment and window");
        }
        return new ResearchSample(id, experimentId, attemptId, windowStart, windowEnd, scenario, label, metadata,
                rawMediaBytes, signalBytes, measuredLatencyMs, createdAt.toInstant());
    }

    public Optional<ResearchSample> findSampleById(String id) {
        List<ResearchSample> rows = jdbcTemplate.query(
                "select id, experiment_id, attempt_id, window_start, window_end, scenario, label, metadata, "
                        + "raw_media_bytes, signal_bytes, measured_latency_ms, created_at from research_samples where id = ?",
                (rs, rowNum) -> mapSample(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<ResearchSample> findSamplesByExperiment(String experimentId, int limit) {
        return jdbcTemplate.query(
                "select id, experiment_id, attempt_id, window_start, window_end, scenario, label, metadata, "
                        + "raw_media_bytes, signal_bytes, measured_latency_ms, created_at from research_samples "
                        + "where experiment_id = ? order by created_at desc limit ?",
                (rs, rowNum) -> mapSample(rs), experimentId, limit);
    }

    /**
     * Detects a sample whose window overlaps an existing sample of the same experiment and
     * attempt context (attemptId equal, including both null). Deterministic and bounded.
     */
    public boolean hasOverlappingSample(String experimentId, String attemptId, String windowStart, String windowEnd,
                                        String excludeSampleId) {
        Integer count;
        if (attemptId == null) {
            count = jdbcTemplate.queryForObject(
                    "select count(*) from research_samples "
                            + "where experiment_id = ? and attempt_id is null and id <> ? "
                            + "and window_start < ? and window_end > ?",
                    Integer.class, experimentId, excludeSampleId == null ? "" : excludeSampleId,
                    windowEnd, windowStart);
        } else {
            count = jdbcTemplate.queryForObject(
                    "select count(*) from research_samples "
                            + "where experiment_id = ? and attempt_id = ? and id <> ? "
                            + "and window_start < ? and window_end > ?",
                    Integer.class, experimentId, attemptId, excludeSampleId == null ? "" : excludeSampleId,
                    windowEnd, windowStart);
        }
        return count != null && count > 0;
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
                rs.getString("scenario"),
                rs.getString("label"),
                readMetadata(rs.getString("metadata")),
                rs.getObject("raw_media_bytes", Long.class),
                rs.getObject("signal_bytes", Long.class),
                rs.getObject("measured_latency_ms", Long.class),
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
                                     String baselineVersion, String datasetVersion, String status,
                                     String examId, String createdBy, Instant createdAt) {
    }

    public record ResearchSample(String id, String experimentId, String attemptId, String windowStart,
                                 String windowEnd, String scenario, String label,
                                 Map<String, String> metadata, Long rawMediaBytes, Long signalBytes,
                                 Long measuredLatencyMs, Instant createdAt) {
    }

    public record ResearchReview(String id, String sampleId, String reviewerId, String label,
                                 Double confidence, String notes, Instant reviewedAt) {
    }

    public record ResearchRun(String id, String experimentId, String runCode, Instant startedAt,
                              Instant endedAt, String operatorId, String operatorName, String status,
                              String datasetVersion, String notes, Instant createdAt) {
    }

    public record ResearchRunSample(String id, String runId, String attemptId, String scenario,
                                    String conditionsKey, Map<String, String> conditions, String status,
                                    String startedAt, String endedAt, Long measuredLatencyMs,
                                    Long rawMediaBytes, Long signalBytes, String researchSampleId,
                                    Instant createdAt) {
    }

    public ResearchRun insertRun(String experimentId, String runCode, String datasetVersion, String status,
                                 String notes, String operatorId) {
        String id = UUID.randomUUID().toString();
        Timestamp createdAt = Timestamp.from(Instant.now());
        try {
            jdbcTemplate.update(
                    "insert into research_runs "
                            + "(id, experiment_id, run_code, dataset_version, status, notes, operator_id, created_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    id, experimentId, runCode, datasetVersion, status, notes, operatorId, createdAt);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "a run with this run code already exists for the experiment");
        }
        return new ResearchRun(id, experimentId, runCode, null, null, operatorId, null,
                status, datasetVersion, notes, createdAt.toInstant());
    }

    public Optional<ResearchRun> findRunById(String id) {
        List<ResearchRun> rows = jdbcTemplate.query(
                "select r.id, r.experiment_id, r.run_code, r.started_at, r.ended_at, r.operator_id, "
                        + "u.name as operator_name, r.status, r.dataset_version, r.notes, r.created_at "
                        + "from research_runs r left join users u on u.id = r.operator_id "
                        + "where r.id = ?",
                (rs, rowNum) -> mapRun(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<ResearchRun> listRunsByExperiment(String experimentId) {
        return jdbcTemplate.query(
                "select r.id, r.experiment_id, r.run_code, r.started_at, r.ended_at, r.operator_id, "
                        + "u.name as operator_name, r.status, r.dataset_version, r.notes, r.created_at "
                        + "from research_runs r left join users u on u.id = r.operator_id "
                        + "where r.experiment_id = ? order by r.created_at desc",
                (rs, rowNum) -> mapRun(rs), experimentId);
    }

    /** Conditional, compare-and-set transition. Returns the number of rows actually updated. */
    public int transitionRun(String runId, String fromStatusesSql, String toStatus, String statusColumnValue,
                             java.sql.Timestamp transitionedAt) {
        String sql = "update research_runs set status = ?"
                + (transitionedAt == null ? "" : ", " + statusColumnValue + " = ?")
                + " where id = ? and status in (" + fromStatusesSql + ")";
        if (transitionedAt == null) {
            return jdbcTemplate.update(sql, toStatus, runId);
        }
        return jdbcTemplate.update(sql, toStatus, transitionedAt, runId);
    }

    public ResearchRunSample insertRunSample(String runId, String attemptId, String scenario,
                                             String conditionsKey, Map<String, String> conditions) {
        String id = UUID.randomUUID().toString();
        Timestamp createdAt = Timestamp.from(Instant.now());
        String json = writeMetadata(conditions);
        try {
            jdbcTemplate.update(
                    "insert into research_run_samples "
                            + "(id, run_id, attempt_id, scenario, conditions_key, conditions_json, status, created_at) "
                            + "values (?, ?, ?, ?, ?, ?, 'PLANNED', ?)",
                    id, runId, attemptId, scenario, conditionsKey, json, createdAt);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "a planned sample already exists for this run, attempt, scenario and conditions");
        }
        return new ResearchRunSample(id, runId, attemptId, scenario, conditionsKey, conditions, "PLANNED",
                null, null, null, null, null, null, createdAt.toInstant());
    }

    public Optional<ResearchRunSample> findRunSampleById(String id) {
        List<ResearchRunSample> rows = jdbcTemplate.query(
                "select id, run_id, attempt_id, scenario, conditions_key, conditions_json, status, "
                        + "started_at, ended_at, measured_latency_ms, raw_media_bytes, signal_bytes, "
                        + "research_sample_id, created_at from research_run_samples where id = ?",
                (rs, rowNum) -> mapRunSample(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<ResearchRunSample> listRunSamplesByRun(String runId, int limit) {
        return jdbcTemplate.query(
                "select id, run_id, attempt_id, scenario, conditions_key, conditions_json, status, "
                        + "started_at, ended_at, measured_latency_ms, raw_media_bytes, signal_bytes, "
                        + "research_sample_id, created_at from research_run_samples "
                        + "where run_id = ? order by created_at asc limit ?",
                (rs, rowNum) -> mapRunSample(rs), runId, limit);
    }

    /** Marks a run sample as capturing; records the server-side observation start time. */
    public int observeRunSample(String runSampleId, String startedAt) {
        return jdbcTemplate.update(
                "update research_run_samples set status = 'CAPTURING', started_at = ? "
                        + "where id = ? and status = 'PLANNED'",
                startedAt, runSampleId);
    }

    /** Marks a run sample as captured (started/ended window recorded). Requirement: was PLANNED or CAPTURING. */
    public int captureRunSample(String runSampleId, String startedAt, String endedAt, Long measuredLatencyMs,
                                Long rawMediaBytes, Long signalBytes) {
        return jdbcTemplate.update(
                "update research_run_samples set status = 'CAPTURED', started_at = ?, ended_at = ?, "
                        + "measured_latency_ms = ?, raw_media_bytes = ?, signal_bytes = ? "
                        + "where id = ? and status in ('PLANNED', 'CAPTURING')",
                startedAt, endedAt, measuredLatencyMs, rawMediaBytes, signalBytes, runSampleId);
    }

    public void linkRunSampleToResearchSample(String runSampleId, String researchSampleId) {
        jdbcTemplate.update("update research_run_samples set research_sample_id = ? where id = ?",
                researchSampleId, runSampleId);
    }

    public long countRunSamplesWithStatus(String runId, String status) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from research_run_samples where run_id = ? and status = ?",
                Long.class, runId, status);
        return count == null ? 0L : count;
    }

    public boolean hasOverlappingRunSample(String runId, String attemptId, String windowStart, String windowEnd,
                                           String excludeRunSampleId) {
        Integer count;
        if (attemptId == null) {
            count = jdbcTemplate.queryForObject(
                    "select count(*) from research_run_samples "
                            + "where run_id = ? and attempt_id is null and status = 'CAPTURED' and id <> ? "
                            + "and started_at < ? and ended_at > ?",
                    Integer.class, runId, excludeRunSampleId == null ? "" : excludeRunSampleId, windowEnd, windowStart);
        } else {
            count = jdbcTemplate.queryForObject(
                    "select count(*) from research_run_samples "
                            + "where run_id = ? and attempt_id = ? and status = 'CAPTURED' and id <> ? "
                            + "and started_at < ? and ended_at > ?",
                    Integer.class, runId, attemptId, excludeRunSampleId == null ? "" : excludeRunSampleId,
                    windowEnd, windowStart);
        }
        return count != null && count > 0;
    }

    /** Review labels grouped by research sample id for an experiment (single bounded query). */
    public Map<String, List<String>> reviewLabelsByExperiment(String experimentId) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                "select s.id as sample_id, r.label as label from research_samples s "
                        + "left join research_reviews r on r.sample_id = s.id "
                        + "where s.experiment_id = ? order by r.reviewed_at asc",
                rs -> {
                    String sampleId = rs.getString("sample_id");
                    String label = rs.getString("label");
                    result.computeIfAbsent(sampleId, key -> new java.util.ArrayList<>());
                    if (label != null) {
                        result.get(sampleId).add(label);
                    }
                }, experimentId);
        return result;
    }

    private ResearchRun mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResearchRun(
                rs.getString("id"),
                rs.getString("experiment_id"),
                rs.getString("run_code"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("ended_at")),
                rs.getString("operator_id"),
                rs.getString("operator_name"),
                rs.getString("status"),
                rs.getString("dataset_version"),
                rs.getString("notes"),
                toInstant(rs.getTimestamp("created_at")));
    }

    private ResearchRunSample mapRunSample(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResearchRunSample(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("attempt_id"),
                rs.getString("scenario"),
                rs.getString("conditions_key"),
                readMetadata(rs.getString("conditions_json")),
                rs.getString("status"),
                rs.getString("started_at"),
                rs.getString("ended_at"),
                rs.getObject("measured_latency_ms", Long.class),
                rs.getObject("raw_media_bytes", Long.class),
                rs.getObject("signal_bytes", Long.class),
                rs.getString("research_sample_id"),
                toInstant(rs.getTimestamp("created_at")));
    }
}