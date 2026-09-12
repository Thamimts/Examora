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
                "select id, user_id, exam_id, question_id, option_id, answer_value, attempt_id from answers order by updated_at desc",
                this::mapAnswer);
    }

    public Optional<Answer> findById(String id) {
        return jdbcTemplate.query(
                        "select id, user_id, exam_id, question_id, option_id, answer_value, attempt_id from answers where id = ?",
                        this::mapAnswer,
                        id)
                .stream()
                .findFirst();
    }

    public List<Answer> findByExamId(String examId) {
        return jdbcTemplate.query(
                "select id, user_id, exam_id, question_id, option_id, answer_value, attempt_id from answers where exam_id = ? order by updated_at desc",
                this::mapAnswer,
                examId);
    }

    public List<Answer> findByUserId(String userId) {
        return jdbcTemplate.query(
                "select id, user_id, exam_id, question_id, option_id, answer_value, attempt_id from answers where user_id = ? order by updated_at desc",
                this::mapAnswer,
                userId);
    }

    public Answer create(Answer answer) {
        jdbcTemplate.update(
                "insert into answers (id, user_id, exam_id, question_id, option_id, answer_value, attempt_id) values (?, ?, ?, ?, ?, ?, ?)",
                answer.id(),
                answer.userId(),
                answer.examId(),
                answer.questionId(),
                answer.optionId(),
                answer.value(),
                answer.attemptId());
        return answer;
    }

    public int update(String id, Answer answer) {
        return jdbcTemplate.update(
                "update answers set user_id = ?, exam_id = ?, question_id = ?, option_id = ?, answer_value = ?, attempt_id = ? where id = ?",
                answer.userId(),
                answer.examId(),
                answer.questionId(),
                answer.optionId(),
                answer.value(),
                answer.attemptId(),
                id);
    }

    public int delete(String id) {
        return jdbcTemplate.update("delete from answers where id = ?", id);
    }

    public Answer createForAttempt(String attemptId, Answer answer, boolean correct) {
        jdbcTemplate.update(
                "insert into answers (id, user_id, exam_id, question_id, option_id, answer_value, attempt_id, correct) values (?, ?, ?, ?, ?, ?, ?, ?)",
                answer.id(), answer.userId(), answer.examId(), answer.questionId(), answer.optionId(), answer.value(), attemptId, correct);
        return answer;
    }

    public List<ReviewRow> findReviewRows(String attemptId, String examId) {
        return jdbcTemplate.query(
                "select q.id as question_id, q.text as question_text, q.answer as answer_text, "
                        + "a.answer_value as selected_value, a.correct as correct "
                        + "from questions q "
                        + "left join answers a on a.question_id = q.id and a.attempt_id = ? "
                        + "where q.exam_id = ? "
                        + "order by q.id",
                this::mapReviewRow,
                attemptId, examId);
    }

    private Answer mapAnswer(ResultSet rs, int rowNum) throws SQLException {
        return new Answer(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("exam_id"),
                rs.getString("question_id"),
                rs.getString("option_id"),
                rs.getString("answer_value"),
                rs.getString("attempt_id"));
    }

    private ReviewRow mapReviewRow(ResultSet rs, int rowNum) throws SQLException {
        Boolean correct = rs.getObject("correct", Boolean.class);
        return new ReviewRow(
                rs.getString("question_id"),
                rs.getString("question_text"),
                rs.getString("answer_text"),
                rs.getString("selected_value"),
                correct);
    }

    public record ReviewRow(String questionId, String questionText, String answerText,
                            String selectedValue, Boolean correct) {
    }
}
