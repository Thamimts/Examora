package com.examora.service;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void upgradeExamLifecycleColumns() {
        try {
            jdbcTemplate.execute("ALTER TABLE exams ADD COLUMN IF NOT EXISTS start_at TIMESTAMPTZ");
        } catch (Exception ex) {
            log.debug("start_at column migration skipped: {}", ex.getMessage());
        }
        try {
            jdbcTemplate.execute("ALTER TABLE exams ADD COLUMN IF NOT EXISTS end_at TIMESTAMPTZ");
        } catch (Exception ex) {
            log.debug("end_at column migration skipped: {}", ex.getMessage());
        }
    }

    public Map<String, Object> health() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("connected", false);
        report.put("database", "");
        try {
            Integer result = jdbcTemplate.queryForObject("select 1", Integer.class);
            report.put("connected", result != null && result == 1);
        } catch (DataAccessException ex) {
            log.warn("Database health check failed: {}", ex.getMessage());
            return report;
        }
        if (Boolean.TRUE.equals(report.get("connected"))) {
            try {
                String database = jdbcTemplate.queryForObject("select current_database()", String.class);
                report.put("database", database == null ? "" : database);
            } catch (DataAccessException ex) {
                log.debug("current_database() is not available on this database: {}", ex.getMessage());
            }
        }
        return report;
    }
}
