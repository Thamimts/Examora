package com.examora.repository;

import com.examora.model.ExamCentre;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExamCentreRepository {
    private final JdbcTemplate jdbcTemplate;

    public ExamCentreRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ExamCentre> findAll() {
        return jdbcTemplate.query(
                "select id, name, code, address, contact_phone, contact_email, status, created_by, created_at, updated_at "
                        + "from exam_centres order by name",
                this::mapCentre);
    }

    public Optional<ExamCentre> findById(String id) {
        return jdbcTemplate.query(
                        "select id, name, code, address, contact_phone, contact_email, status, created_by, created_at, updated_at "
                                + "from exam_centres where id = ?",
                        this::mapCentre,
                        id)
                .stream()
                .findFirst();
    }

    public ExamCentre create(ExamCentre centre) {
        jdbcTemplate.update(
                "insert into exam_centres (id, name, code, address, contact_phone, contact_email, status, created_by) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                centre.id(), centre.name(), centre.code(), centre.address(), centre.contactPhone(),
                centre.contactEmail(), centre.status(), centre.createdBy());
        return centre;
    }

    public int update(String id, ExamCentre centre) {
        return jdbcTemplate.update(
                "update exam_centres set name = ?, code = ?, address = ?, contact_phone = ?, contact_email = ?, "
                        + "status = ?, updated_at = current_timestamp where id = ?",
                centre.name(), centre.code(), centre.address(), centre.contactPhone(), centre.contactEmail(),
                centre.status(), id);
    }

    public int delete(String id) {
        return jdbcTemplate.update("delete from exam_centres where id = ?", id);
    }

    private ExamCentre mapCentre(ResultSet rs, int rowNum) throws SQLException {
        return new ExamCentre(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getString("address"),
                rs.getString("contact_phone"),
                rs.getString("contact_email"),
                rs.getString("status"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());
    }
}