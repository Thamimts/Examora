package com.examora.repository;

import com.examora.model.Result;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
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

    public List<Result> findAllByExamIds(List<String> examIds) {
        if (examIds == null || examIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(examIds.size(), "?"));
        return jdbcTemplate.query(
                "select id, user_id, exam_id, exam_title, subject, score, date, total from results "
                        + "where exam_id in (" + placeholders + ") order by date desc",
                this::mapResult,
                examIds.toArray());
    }

    public List<Result> findByUserIdInExamIds(String userId, List<String> examIds) {
        if (userId == null || userId.isBlank() || examIds == null || examIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(examIds.size(), "?"));
        Object[] params = new Object[examIds.size() + 1];
        params[0] = userId;
        System.arraycopy(examIds.toArray(), 0, params, 1, examIds.size());
        return jdbcTemplate.query(
                "select id, user_id, exam_id, exam_title, subject, score, date, total from results "
                        + "where user_id = ? and exam_id in (" + placeholders + ") order by date desc",
                this::mapResult,
                params);
    }

public Optional<Result> findByUserIdAndExamId(String userId, String examId) {
        return jdbcTemplate.query(
                        "select id, user_id, exam_id, exam_title, subject, score, date, total from results where user_id = ? and exam_id = ?",
                        this::mapResult,
                        userId, examId)
                .stream()
                .findFirst();
    }

    /** The most recent result row for a student + exam (retakes produce several rows on re-test). */
    public Optional<Result> findLatestByUserIdAndExamId(String userId, String examId) {
        return jdbcTemplate.query(
                        "select id, user_id, exam_id, exam_title, subject, score, date, total "
                                + "from results where user_id = ? and exam_id = ? "
                                + "order by date desc, id desc limit 1",
                        this::mapResult,
                        userId, examId)
                .stream()
                .findFirst();
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
