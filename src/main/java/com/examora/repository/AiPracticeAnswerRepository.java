package com.examora.repository;

import com.examora.model.AiPracticeAnswer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiPracticeAnswerRepository {
    private final JdbcTemplate jdbc;

    public AiPracticeAnswerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AiPracticeAnswer> findBySessionAndQuestion(String sessionId, String questionId) {
        return jdbc.query(
                "select * from ai_practice_answers where session_id = ? and question_id = ?",
                this::map, sessionId, questionId)
                .stream().findFirst();
    }

    public List<AiPracticeAnswer> findBySessionId(String sessionId) {
        return jdbc.query(
                "select * from ai_practice_answers where session_id = ? order by answered_at",
                this::map, sessionId);
    }

    public AiPracticeAnswer create(AiPracticeAnswer answer) {
        jdbc.update(
                "insert into ai_practice_answers (id, session_id, question_id, option_id, selected_option, correct, answered_at) values (?, ?, ?, ?, ?, ?, ?)",
                answer.id(), answer.sessionId(), answer.questionId(), answer.optionId(),
                answer.selectedOption(), answer.correct(), Timestamp.from(answer.answeredAt()));
        return answer;
    }

    public int upsert(AiPracticeAnswer answer) {
        return jdbc.update(
                "update ai_practice_answers set option_id = ?, selected_option = ?, correct = ?, answered_at = ? where session_id = ? and question_id = ?",
                answer.optionId(), answer.selectedOption(), answer.correct(), Timestamp.from(answer.answeredAt()),
                answer.sessionId(), answer.questionId());
    }

    public AiPracticeAnswer save(AiPracticeAnswer answer) {
        int updated = upsert(answer);
        if (updated == 0) {
            create(answer);
        }
        return answer;
    }

    private AiPracticeAnswer map(ResultSet rs, int rowNum) throws SQLException {
        Boolean correct = rs.getObject("correct", Boolean.class);
        return new AiPracticeAnswer(
                rs.getString("id"), rs.getString("session_id"), rs.getString("question_id"),
                rs.getString("option_id"), rs.getString("selected_option"), correct,
                rs.getTimestamp("answered_at").toInstant());
    }
}
