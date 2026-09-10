package com.examora.repository;

import com.examora.model.PracticeAnswer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PracticeAnswerRepository {
    private final JdbcTemplate jdbc;

    public PracticeAnswerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PracticeAnswer create(PracticeAnswer answer) {
        jdbc.update(
                "insert into practice_answers (id, session_id, question_id, option_id, answer_value, correct, "
                        + "difficulty, sequence_index, answered_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                answer.id(),
                answer.sessionId(),
                answer.questionId(),
                answer.optionId(),
                answer.answerValue(),
                answer.correct(),
                answer.difficulty(),
                answer.sequenceIndex(),
                Timestamp.from(answer.answeredAt()));
        return answer;
    }

    public List<PracticeAnswer> findBySessionId(String sessionId) {
        return jdbc.query(
                "select id, session_id, question_id, option_id, answer_value, correct, difficulty, "
                        + "sequence_index, answered_at from practice_answers where session_id = ? order by sequence_index",
                this::map,
                sessionId);
    }

    private PracticeAnswer map(ResultSet rs, int rowNum) throws SQLException {
        return new PracticeAnswer(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("question_id"),
                rs.getString("option_id"),
                rs.getString("answer_value"),
                rs.getBoolean("correct"),
                rs.getInt("difficulty"),
                rs.getInt("sequence_index"),
                rs.getTimestamp("answered_at").toInstant());
    }
}