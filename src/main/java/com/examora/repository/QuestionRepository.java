package com.examora.repository;

import com.examora.model.Question;
import com.examora.model.QuestionOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QuestionRepository {
    private final JdbcTemplate jdbcTemplate;
    private final QuestionOptionRepository optionRepository;

    public QuestionRepository(JdbcTemplate jdbcTemplate, QuestionOptionRepository optionRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.optionRepository = optionRepository;
    }

    public List<Question> findAll() {
        return jdbcTemplate.query("select id, exam_id, text, answer from questions order by id", this::mapQuestion);
    }

    public List<Question> findByExamId(String examId) {
        return jdbcTemplate.query(
                "select id, exam_id, text, answer from questions where exam_id = ? order by id",
                this::mapQuestion,
                examId);
    }

    public Optional<Question> findById(String id) {
        return jdbcTemplate.query(
                        "select id, exam_id, text, answer from questions where id = ?",
                        this::mapQuestion,
                        id)
                .stream()
                .findFirst();
    }

    public Question create(Question question) {
        jdbcTemplate.update(
                "insert into questions (id, exam_id, text, answer) values (?, ?, ?, ?)",
                question.id(),
                question.examId(),
                question.text(),
                question.answer());
        return question;
    }

    public int update(String id, Question question) {
        return jdbcTemplate.update(
                "update questions set exam_id = ?, text = ?, answer = ? where id = ?",
                question.examId(),
                question.text(),
                question.answer(),
                id);
    }

    public int delete(String id) {
        return jdbcTemplate.update("delete from questions where id = ?", id);
    }

    private Question mapQuestion(ResultSet rs, int rowNum) throws SQLException {
        String questionId = rs.getString("id");
        List<String> options = optionRepository.findByQuestionId(questionId).stream()
                .map(QuestionOption::text)
                .toList();
        return new Question(
                questionId,
                rs.getString("exam_id"),
                rs.getString("text"),
                options,
                rs.getString("answer"));
    }
}
