package com.examora.service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        Integer result = jdbcTemplate.queryForObject("select 1", Integer.class);
        String database = jdbcTemplate.queryForObject("select current_database()", String.class);
        return Map.of("connected", result != null && result == 1, "database", database == null ? "" : database);
    }
}
