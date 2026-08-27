package com.examora.repository;

import com.examora.model.Answer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnswerRepository {
    private final JdbcTemplate jdbcTemplate;

    public AnswerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Answer> findAll() {
        return jdbcTemplate.query(
                "select id, user_id, exam_id, question_id, option_id, answer_value from answers order by updated_at desc",
                this::mapAnswer);
    }

    public Optional<Answer> findById(String id) {
        return jdbcTemplate.query(
                        "select id, user_id, exam_id, question_id, option_id, answer_value from answers where id = ?",
                        this::mapAnswer,
                        id)
                .stream()
                .findFirst();
    }

    public List<Answer> findByExamId(String examId) {
        return jdbcTemplate.query(
                "select id, user_id, exam_id, question_id, option_id, answer_value from answers where exam_id = ? order by updated_at desc",
                this::mapAnswer,
                examId);
    }

    public List<Answer> findByUserId(String userId) {
        return jdbcTemplate.query(
                "select id, user_id, exam_id, question_id, option_id, answer_value from answers where user_id = ? order by updated_at desc",
                this::mapAnswer,
                userId);
    }

    public Answer create(Answer answer) {
        jdbcTemplate.update(
                "insert into answers (id, user_id, exam_id, question_id, option_id, answer_value) values (?, ?, ?, ?, ?, ?)",
                answer.id(),
                answer.userId(),
                answer.examId(),
                answer.questionId(),
                answer.optionId(),
                answer.value());
        return answer;
    }

    public int update(String id, Answer answer) {
        return jdbcTemplate.update(
                "update answers set user_id = ?, exam_id = ?, question_id = ?, option_id = ?, answer_value = ? where id = ?",
                answer.userId(),
                answer.examId(),
                answer.questionId(),
                answer.optionId(),
                answer.value(),
                id);
    }

    public int delete(String id) {
        return jdbcTemplate.update("delete from answers where id = ?", id);
    }

    public Answer createForAttempt(String attemptId, Answer answer) {
        jdbcTemplate.update(
                "insert into answers (id, user_id, exam_id, question_id, option_id, answer_value, attempt_id) values (?, ?, ?, ?, ?, ?, ?)",
                answer.id(), answer.userId(), answer.examId(), answer.questionId(), answer.optionId(), answer.value(), attemptId);
        return answer;
    }

    private Answer mapAnswer(ResultSet rs, int rowNum) throws SQLException {
        return new Answer(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("exam_id"),
                rs.getString("question_id"),
                rs.getString("option_id"),
                rs.getString("answer_value"));
    }
}
