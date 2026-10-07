package com.examora.repository;

import com.examora.model.ExamAttempt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
    public Optional<ExamAttempt> findLatest(String examId, String studentId) {
        return jdbc.query("select * from exam_attempts where exam_id = ? and student_id = ? order by attempt_number desc, started_at desc", this::map, examId, studentId).stream().findFirst();
    }
    public Optional<ExamAttempt> findLatestSubmitted(String examId, String studentId) {
        return jdbc.query("select * from exam_attempts where exam_id = ? and student_id = ? and status = 'SUBMITTED' order by submitted_at desc, started_at desc", this::map, examId, studentId).stream().findFirst();
    }
    public List<ExamAttempt> findLatestForExam(String examId) {
        return jdbc.query(
                "select ea.id, ea.exam_id, ea.student_id, ea.attempt_number, ea.status, ea.started_at, "
                        + "ea.expires_at, ea.submitted_at, ea.version "
                        + "from exam_attempts ea "
                        + "join (select student_id, max(attempt_number) as attempt_number "
                        + "         from exam_attempts where exam_id = ? group by student_id) latest "
                        + "  on latest.student_id = ea.student_id and latest.attempt_number = ea.attempt_number "
                        + "where ea.exam_id = ?",
                this::map, examId, examId);
    }

    public ExamAttempt create(ExamAttempt a) {
        jdbc.update("insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) values (?, ?, ?, ?, ?, ?, ?, ?)", a.id(), a.examId(), a.studentId(), a.attemptNumber(), a.status(), Timestamp.from(a.startedAt()), Timestamp.from(a.expiresAt()), a.version());
        return a;
    }

    public int insertActiveIfAllowed(String id, String examId, String studentId, Instant startedAt, Instant expiresAt) {
        return jdbc.update(
                "insert into exam_attempts (id, exam_id, student_id, attempt_number, status, started_at, expires_at, version) "
                        + "select ?, ?, ?, coalesce(max(ea.attempt_number), 0) + 1, 'STARTED', ?, ?, 0 "
                        + "from exam_attempts ea "
                        + "where ea.exam_id = ? and ea.student_id = ? "
                        + "  and not exists (select 1 from exam_attempts x where x.exam_id = ? and x.student_id = ? and x.status = 'STARTED') "
                        + "  and not exists (select 1 from exam_attempts y where y.exam_id = ? and y.student_id = ? and y.status = 'EXPIRED')",
                id, examId, studentId, Timestamp.from(startedAt), Timestamp.from(expiresAt),
                examId, studentId, examId, studentId, examId, studentId);
    }
    public int markSubmitted(String id, Instant at) { return jdbc.update("update exam_attempts set status = 'SUBMITTED', submitted_at = ?, version = version + 1 where id = ? and status = 'STARTED'", Timestamp.from(at), id); }
    public int markExpired(String id) { return jdbc.update("update exam_attempts set status = 'EXPIRED', version = version + 1 where id = ? and status = 'STARTED'", id); }

    public int incrementWarningCount(String id, int maxWarnings) {
        return jdbc.update("update exam_attempts set warning_count = warning_count + 1, version = version + 1 "
                + "where id = ? and status = 'STARTED' and warning_count < ?", id, maxWarnings);
    }

    public Optional<Integer> warningCount(String id) {
        return jdbc.query("select warning_count from exam_attempts where id = ?",
                (rs, row) -> rs.getInt("warning_count"), id).stream().findFirst();
    }

    public int markProctorTerminated(String id, String reason, Instant at, int maxWarnings) {
        return jdbc.update("update exam_attempts set status = 'PROCTOR_TERMINATED', terminated_at = ?, "
                + "terminated_reason = ?, version = version + 1 "
                + "where id = ? and status = 'STARTED' and warning_count >= ?",
                Timestamp.from(at), reason, id, maxWarnings);
    }
    public int expireOverdue(Instant now) {
        return jdbc.update("update exam_attempts set status = 'EXPIRED', version = version + 1 where status = 'STARTED' and expires_at <= ?", Timestamp.from(now));
    }
    public List<ExamAttempt> findOverdue(Instant now) {
        return jdbc.query("select * from exam_attempts where status = 'STARTED' and expires_at <= ?", this::map, Timestamp.from(now));
    }
    public List<ActiveAttemptRow> findActiveForStudent(String studentId, Instant now) {
        return jdbc.query(
                "select ea.id as attempt_id, ea.exam_id, ea.status, ea.started_at, ea.expires_at, "
                        + "e.title as exam_title, e.subject, e.duration "
                        + "from exam_attempts ea join exams e on e.id = ea.exam_id "
                        + "where ea.student_id = ? and ea.status = 'STARTED' and ea.expires_at > ? "
                        + "order by ea.started_at desc",
                (rs, row) -> new ActiveAttemptRow(rs.getString("attempt_id"), rs.getString("exam_id"),
                        rs.getString("status"), rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(), rs.getString("exam_title"),
                        rs.getString("subject"), rs.getInt("duration")),
                studentId, Timestamp.from(now));
    }
    public List<AttemptWithStudent> findByExamWithStudent(String examId) {
        return jdbc.query(
                "select ea.*, u.name as student_name, u.email as student_email "
                        + "from exam_attempts ea join users u on u.id = ea.student_id "
                        + "where ea.exam_id = ? order by ea.started_at desc, ea.id desc",
                (rs, row) -> new AttemptWithStudent(mapAttempt(rs), rs.getString("student_name"), rs.getString("student_email")),
                examId);
    }

    public List<AttemptWithWarning> findByExamWithStudentAndWarnings(String examId) {
        return jdbc.query(
                "select ea.*, u.name as student_name, u.email as student_email, ea.warning_count "
                        + "from exam_attempts ea join users u on u.id = ea.student_id "
                        + "where ea.exam_id = ? order by ea.started_at desc, ea.id desc",
                (rs, row) -> new AttemptWithWarning(mapAttempt(rs), rs.getString("student_name"),
                        rs.getString("student_email"), rs.getInt("warning_count")),
                examId);
    }

    private ExamAttempt map(ResultSet rs, int ignored) throws SQLException {
        return mapAttempt(rs);
    }

    private ExamAttempt mapAttempt(ResultSet rs) throws SQLException {
        Timestamp submitted = rs.getTimestamp("submitted_at");
        return new ExamAttempt(rs.getString("id"), rs.getString("exam_id"), rs.getString("student_id"), rs.getInt("attempt_number"), rs.getString("status"), rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("expires_at").toInstant(), submitted == null ? null : submitted.toInstant(), rs.getInt("version"));
    }

    public record AttemptWithStudent(ExamAttempt attempt, String studentName, String studentEmail) {
    }

    public record AttemptWithWarning(ExamAttempt attempt, String studentName, String studentEmail, int warningCount) {
    }

    public record SubmittedAttemptRow(String examId, int attemptNumber, Instant submittedAt) {
    }

    /** All SUBMITTED attempts of a student, newest first — used to attach attempt metadata to results. */
    public List<SubmittedAttemptRow> findSubmittedByStudent(String studentId) {
        return jdbc.query(
                "select exam_id, attempt_number, submitted_at from exam_attempts "
                        + "where student_id = ? and status = 'SUBMITTED' "
                        + "order by submitted_at desc, started_at desc",
                (rs, row) -> new SubmittedAttemptRow(rs.getString("exam_id"),
                        rs.getInt("attempt_number"), rs.getTimestamp("submitted_at").toInstant()),
                studentId);
    }

    public record ActiveAttemptRow(String attemptId, String examId, String status,
                                   Instant startedAt, Instant expiresAt,
                                   String examTitle, String subject, int duration) {
    }
}