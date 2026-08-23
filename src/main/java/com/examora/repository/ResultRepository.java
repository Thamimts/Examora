package com.examora.repository;

import com.examora.model.Result;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ResultRepository {
    private final JdbcTemplate jdbcTemplate;

    public ResultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Result> findAll() {
        return jdbcTemplate.query(
                "select id, user_id, exam_id, exam_title, subject, score, date, total from results order by date desc",
                this::mapResult);
    }

    public Optional<Result> findById(String id) {
        return jdbcTemplate.query(
                        "select id, user_id, exam_id, exam_title, subject, score, date, total from results where id = ?",
                        this::mapResult,
                        id)
                .stream()
                .findFirst();
    }

    public List<Result> findByUserId(String userId) {
        return jdbcTemplate.query(
                "select id, user_id, exam_id, exam_title, subject, score, date, total from results where user_id = ? order by date desc",
                this::mapResult,
                userId);
    }

    public Result create(Result result) {
        jdbcTemplate.update(
                "insert into results (id, user_id, exam_id, exam_title, subject, score, date, total) values (?, ?, ?, ?, ?, ?, ?, ?)",
                result.id(),
                result.userId(),
                result.examId(),
                result.examTitle(),
                result.subject(),
                result.score(),
                result.date(),
                result.total());
        return result;
    }

    public int update(String id, Result result) {
        return jdbcTemplate.update(
                "update results set user_id = ?, exam_id = ?, exam_title = ?, subject = ?, score = ?, date = ?, total = ? where id = ?",
                result.userId(),
                result.examId(),
                result.examTitle(),
                result.subject(),
                result.score(),
                result.date(),
                result.total(),
                id);
    }

    public int delete(String id) {
        return jdbcTemplate.update("delete from results where id = ?", id);
    }

    private Result mapResult(ResultSet rs, int rowNum) throws SQLException {
        return new Result(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("exam_id"),
                rs.getString("exam_title"),
                rs.getString("subject"),
                rs.getInt("score"),
                rs.getString("date"),
                rs.getInt("total"));
    }
}
