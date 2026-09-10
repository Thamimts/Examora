package com.examora.repository;

import com.examora.model.PracticeSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PracticeSessionRepository {
    private final JdbcTemplate jdbc;

    public PracticeSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT =
            "select id, student_id, exam_id, status, target_question_count, working_difficulty, "
            + "in_progress_question_id, answered_count, correct_count, started_at, last_activity_at, completed_at "
            + "from practice_sessions ";

    public Optional<PracticeSession> findById(String id) {
        return jdbc.query(SELECT + "where id = ?", this::map, id).stream().findFirst();
    }

    public Optional<PracticeSession> findActive(String studentId, String examId) {
        return jdbc.query(SELECT + "where student_id = ? and exam_id = ? and status = 'STARTED' order by started_at desc",
                this::map, studentId, examId).stream().findFirst();
    }

    public PracticeSession create(PracticeSession session) {
        jdbc.update(
                "insert into practice_sessions (id, student_id, exam_id, status, target_question_count, working_difficulty, "
                        + "in_progress_question_id, answered_count, correct_count, started_at, last_activity_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                session.id(),
                session.studentId(),
                session.examId(),
                session.status(),
                session.targetQuestionCount(),
                session.workingDifficulty(),
                session.inProgressQuestionId(),
                session.answeredCount(),
                session.correctCount(),
                Timestamp.from(session.startedAt()),
                Timestamp.from(session.lastActivityAt()));
        return session;
    }

    public int advanceProgress(String id, String inProgressQuestionId, int answeredCount, int correctCount,
                               double workingDifficulty, Instant lastActivityAt) {
        return jdbc.update(
                "update practice_sessions set in_progress_question_id = ?, answered_count = ?, correct_count = ?, "
                        + "working_difficulty = ?, last_activity_at = ? where id = ? and status = 'STARTED'",
                inProgressQuestionId,
                answeredCount,
                correctCount,
                workingDifficulty,
                Timestamp.from(lastActivityAt),
                id);
    }

    public int complete(String id, int answeredCount, int correctCount, Instant completedAt) {
        return jdbc.update(
                "update practice_sessions set status = 'COMPLETED', in_progress_question_id = null, "
                        + "answered_count = ?, correct_count = ?, last_activity_at = ?, completed_at = ? "
                        + "where id = ? and status = 'STARTED'",
                answeredCount,
                correctCount,
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                id);
    }

    public int expire(String id, Instant at) {
        return jdbc.update(
                "update practice_sessions set status = 'EXPIRED', in_progress_question_id = null, "
                        + "last_activity_at = ?, completed_at = ? where id = ? and status = 'STARTED'",
                Timestamp.from(at),
                Timestamp.from(at),
                id);
    }

    public int expireActiveForExam(String studentId, String examId, Instant at) {
        return jdbc.update(
                "update practice_sessions set status = 'EXPIRED', in_progress_question_id = null, "
                        + "last_activity_at = ?, completed_at = ? "
                        + "where student_id = ? and exam_id = ? and status = 'STARTED'",
                Timestamp.from(at),
                Timestamp.from(at),
                studentId,
                examId);
    }

    private PracticeSession map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new PracticeSession(
                rs.getString("id"),
                rs.getString("student_id"),
                rs.getString("exam_id"),
                rs.getString("status"),
                rs.getInt("target_question_count"),
                rs.getDouble("working_difficulty"),
                rs.getString("in_progress_question_id"),
                rs.getInt("answered_count"),
                rs.getInt("correct_count"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("last_activity_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }
}