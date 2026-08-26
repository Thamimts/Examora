package com.examora.service;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseService {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> health() {
        Integer result = jdbcTemplate.queryForObject("select 1", Integer.class);
        String database = jdbcTemplate.queryForObject("select database()", String.class);
        return Map.of("connected", result != null && result == 1, "database", database == null ? "" : database);
    }
}
