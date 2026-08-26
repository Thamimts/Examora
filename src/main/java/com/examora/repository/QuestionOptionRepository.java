package com.examora.repository;

import com.examora.model.QuestionOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QuestionOptionRepository {
    private final JdbcTemplate jdbcTemplate;

    public QuestionOptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<QuestionOption> findAll() {
        return jdbcTemplate.query(
                "select id, question_id, text, display_order, correct_answer from question_options order by question_id, display_order, id",
                this::mapOption);
    }

    public List<QuestionOption> findByQuestionId(String questionId) {
        return jdbcTemplate.query(
                "select id, question_id, text, display_order, correct_answer from question_options where question_id = ? order by display_order, id",
                this::mapOption,
                questionId);
    }

    public Optional<QuestionOption> findById(String id) {
        return jdbcTemplate.query(
                        "select id, question_id, text, display_order, correct_answer from question_options where id = ?",
                        this::mapOption,
                        id)
                .stream()
                .findFirst();
    }

    public QuestionOption create(QuestionOption option) {
        jdbcTemplate.update(
                "insert into question_options (id, question_id, text, display_order, correct_answer) values (?, ?, ?, ?, ?)",
                option.id(),
                option.questionId(),
                option.text(),
                option.displayOrder(),
                option.correctAnswer());
        return option;
    }

    public int update(String id, QuestionOption option) {
        return jdbcTemplate.update(
                "update question_options set question_id = ?, text = ?, display_order = ?, correct_answer = ? where id = ?",
                option.questionId(),
                option.text(),
                option.displayOrder(),
                option.correctAnswer(),
                id);
    }

    public int delete(String id) {
        return jdbcTemplate.update("delete from question_options where id = ?", id);
    }

    public void deleteByQuestionId(String questionId) {
        jdbcTemplate.update("delete from question_options where question_id = ?", questionId);
    }

    private QuestionOption mapOption(ResultSet rs, int rowNum) throws SQLException {
        return new QuestionOption(
                rs.getString("id"),
                rs.getString("question_id"),
                rs.getString("text"),
                rs.getInt("display_order"),
                rs.getBoolean("correct_answer"));
    }
}
