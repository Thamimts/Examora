package com.examora.repository;

import com.examora.model.AiPracticeSession;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiPracticeSessionRepository {
    private final JdbcTemplate jdbc;

    public AiPracticeSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ProgressRow(String topic, String difficulty, int attempted, int completed, BigDecimal bestAccuracy) {
    }

    public Optional<AiPracticeSession> findById(String id) {
        return jdbc.query(
                "select * from ai_practice_sessions where id = ?",
                this::map, id)
                .stream().findFirst();
    }

    public List<AiPracticeSession> findActiveForStudent(String studentId) {
        return jdbc.query(
                "select * from ai_practice_sessions where student_id = ? and status = 'ACTIVE' order by started_at desc",
                this::map, studentId);
    }

    public List<AiPracticeSession> findRecentForStudent(String studentId, int limit) {
        return jdbc.query(
                "select * from ai_practice_sessions where student_id = ? order by started_at desc limit ?",
                this::map, studentId, limit);
    }

    public List<ProgressRow> findProgressForStudent(String studentId) {
        return jdbc.query(
                "select topic, difficulty, count(*) as attempted, "
                        + "sum(case when completion_met then 1 else 0 end) as completed, "
                        + "max(percentage) as best_accuracy "
                        + "from ai_practice_sessions "
                        + "where student_id = ? and status = 'COMPLETED' "
                        + "group by topic, difficulty",
                (rs, rowNum) -> new ProgressRow(rs.getString("topic"), rs.getString("difficulty"),
                        rs.getInt("attempted"), rs.getInt("completed"), rs.getBigDecimal("best_accuracy")),
                studentId);
    }

    public List<ProgressRow> findProgressForStudentAndTopic(String studentId, String topic) {
        return jdbc.query(
                "select topic, difficulty, count(*) as attempted, "
                        + "sum(case when completion_met then 1 else 0 end) as completed, "
                        + "max(percentage) as best_accuracy "
                        + "from ai_practice_sessions "
                        + "where student_id = ? and status = 'COMPLETED' and topic = ? "
                        + "group by topic, difficulty",
                (rs, rowNum) -> new ProgressRow(rs.getString("topic"), rs.getString("difficulty"),
                        rs.getInt("attempted"), rs.getInt("completed"), rs.getBigDecimal("best_accuracy")),
                studentId, topic);
    }

    public AiPracticeSession create(AiPracticeSession session) {
        jdbc.update(
                "insert into ai_practice_sessions (id, student_id, topic, difficulty, status, question_count, "
                        + "min_question_count, min_accuracy, started_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                session.id(), session.studentId(), session.topic(), session.difficulty(), session.status(),
                session.questionCount(), session.minQuestionCount(), session.minAccuracy(),
                Timestamp.from(session.startedAt()));
        return session;
    }

    public int incrementAnswered(String id, boolean correct) {
        String sql = correct
                ? "update ai_practice_sessions set answered_count = answered_count + 1, correct_count = correct_count + 1 where id = ? and status = 'ACTIVE'"
                : "update ai_practice_sessions set answered_count = answered_count + 1 where id = ? and status = 'ACTIVE'";
        return jdbc.update(sql, id);
    }

    public int decrementAnswered(String id, boolean wasCorrect) {
        String sql = wasCorrect
                ? "update ai_practice_sessions set answered_count = answered_count - 1, correct_count = correct_count - 1 where id = ? and status = 'ACTIVE'"
                : "update ai_practice_sessions set answered_count = answered_count - 1 where id = ? and status = 'ACTIVE'";
        return jdbc.update(sql, id);
    }

    public int complete(String id, int correctCount, int total, java.math.BigDecimal score, java.math.BigDecimal percentage,
                        boolean completionMet, Instant at) {
        return jdbc.update(
                "update ai_practice_sessions set status = 'COMPLETED', correct_count = ?, answered_count = ?, score = ?, "
                        + "percentage = ?, completion_met = ?, completed_at = ? where id = ? and status = 'ACTIVE'",
                correctCount, total, score, percentage, completionMet, Timestamp.from(at), id);
    }

    private AiPracticeSession map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        BigDecimal minAccuracy = rs.getBigDecimal("min_accuracy");
        return new AiPracticeSession(
                rs.getString("id"), rs.getString("student_id"), rs.getString("topic"),
                rs.getString("difficulty"), rs.getString("status"), rs.getInt("question_count"),
                rs.getInt("answered_count"), rs.getInt("correct_count"),
                rs.getBigDecimal("score"), rs.getBigDecimal("percentage"),
                rs.getObject("min_question_count", Integer.class),
                minAccuracy == null ? null : minAccuracy.setScale(2, java.math.RoundingMode.HALF_UP),
                rs.getBoolean("completion_met"),
                rs.getTimestamp("started_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }
}
