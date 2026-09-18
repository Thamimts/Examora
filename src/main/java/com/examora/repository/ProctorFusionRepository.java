package com.examora.repository;

import com.examora.service.ProctorFusionService.Contribution;
import com.examora.service.ProctorFusionService.FusionResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Shadow-mode storage for the experimental fusion results. Writes are idempotent
 * (upsert keyed on attempt + algorithm version) and never affect enforcement state.
 */
@Repository
public class ProctorFusionRepository {
    private final JdbcTemplate jdbcTemplate;

    public ProctorFusionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(String attemptId, FusionResult result) {
        jdbcTemplate.update(
                "insert into proctor_fusion_results "
                        + "(id, attempt_id, calculated_at, window_start, window_end, baseline_score, "
                        + "fused_score, fused_confidence, evidence_count, algorithm_version) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "on duplicate key update "
                        + "calculated_at = values(calculated_at), window_start = values(window_start), "
                        + "window_end = values(window_end), baseline_score = values(baseline_score), "
                        + "fused_score = values(fused_score), fused_confidence = values(fused_confidence), "
                        + "evidence_count = values(evidence_count)",
                UUID.randomUUID().toString(),
                attemptId,
                Timestamp.from(Instant.now()),
                result.windowStart(),
                result.windowEnd(),
                result.baselineScore(),
                result.fusedScore(),
                result.fusedConfidence(),
                result.evidenceCount(),
                result.algorithmVersion());
    }

    /** Latest persisted shadow result for an attempt, or null when absent. */
    public StoredFusion findLatest(String attemptId) {
        List<StoredFusion> rows = jdbcTemplate.query(
                "select attempt_id, window_start, window_end, baseline_score, fused_score, "
                        + "fused_confidence, evidence_count, algorithm_version from proctor_fusion_results "
                        + "where attempt_id = ? order by calculated_at desc limit 1",
                (rs, rowNum) -> new StoredFusion(
                        rs.getString("attempt_id"),
                        rs.getString("window_start"),
                        rs.getString("window_end"),
                        rs.getDouble("baseline_score"),
                        rs.getDouble("fused_score"),
                        rs.getObject("fused_confidence", Double.class),
                        rs.getInt("evidence_count"),
                        rs.getString("algorithm_version")),
                attemptId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public record StoredFusion(String attemptId, String windowStart, String windowEnd,
                               double baselineScore, double fusedScore, Double fusedConfidence,
                               int evidenceCount, String algorithmVersion) {
    }
}