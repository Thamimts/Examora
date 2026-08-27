package com.examora.repository;

import com.examora.model.ExamAttempt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExamAttemptRepository {
    private final JdbcTemplate jdbc;
    public ExamAttemptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<ExamAttempt> findActive(String examId, String studentId) {
        return jdbc.query("select * from exam_attempts where exam_id = ? and student_id = ? and status = 'STARTED' order by started_at desc", this::map, examId, studentId).stream().findFirst();
    }
    public Optional<ExamAttempt> findById(String id) { return jdbc.query("select * from exam_attempts where id = ?", this::map, id).stream().findFirst(); }
    public ExamAttempt create(ExamAttempt a) {
        jdbc.update("insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) values (?, ?, ?, ?, ?, ?, ?, ?)", a.id(), a.examId(), a.studentId(), a.attemptNumber(), a.status(), Timestamp.from(a.startedAt()), Timestamp.from(a.expiresAt()), a.version());
        return a;
    }
    public int markSubmitted(String id, Instant at) { return jdbc.update("update exam_attempts set status = 'SUBMITTED', submitted_at = ?, version = version + 1 where id = ? and status = 'STARTED'", Timestamp.from(at), id); }
    public int markExpired(String id) { return jdbc.update("update exam_attempts set status = 'EXPIRED', version = version + 1 where id = ? and status = 'STARTED'", id); }
    private ExamAttempt map(ResultSet rs, int ignored) throws SQLException {
        Timestamp submitted = rs.getTimestamp("submitted_at");
        return new ExamAttempt(rs.getString("id"), rs.getString("exam_id"), rs.getString("student_id"), rs.getInt("attempt_number"), rs.getString("status"), rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("expires_at").toInstant(), submitted == null ? null : submitted.toInstant(), rs.getInt("version"));
    }
}
