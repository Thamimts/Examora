package com.examora.repository;

import com.examora.model.Exam;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExamRepository {
    private final JdbcTemplate jdbcTemplate;

    public ExamRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Exam> findAll() {
        return jdbcTemplate.query(
                "select id, title, subject, date, duration, status, participants, average_score from exams order by date",
                this::mapExam);
    }

    public Optional<Exam> findById(String id) {
        return jdbcTemplate.query(
                        "select id, title, subject, date, duration, status, participants, average_score from exams where id = ?",
                        this::mapExam,
                        id)
                .stream()
                .findFirst();
    }

    public List<Exam> findAvailable() {
        return jdbcTemplate.query(
                "select id, title, subject, date, duration, status, participants, average_score from exams where status <> 'DRAFT' order by date",
                this::mapExam);
    }

    public Exam create(Exam exam) {
        return create(exam, null);
    }

    public Exam create(Exam exam, String createdBy) {
        jdbcTemplate.update(
                "insert into exams (id, title, subject, date, duration, status, participants, average_score, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                exam.id(),
                exam.title(),
                exam.subject(),
                exam.date(),
                exam.duration(),
                exam.status(),
                exam.participants(),
                exam.averageScore(),
                createdBy);
        return exam;
    }

    public Optional<String> findOwnerId(String id) {
        return jdbcTemplate.query("select created_by from exams where id = ?", (rs, row) -> rs.getString(1), id).stream().findFirst();
    }

    public int update(String id, Exam exam) {
        return jdbcTemplate.update(
                "update exams set title = ?, subject = ?, date = ?, duration = ?, status = ?, participants = ?, average_score = ? where id = ?",
                exam.title(),
                exam.subject(),
                exam.date(),
                exam.duration(),
                exam.status(),
                exam.participants(),
                exam.averageScore(),
                id);
    }

    public int delete(String id) {
        return jdbcTemplate.update("delete from exams where id = ?", id);
    }

    public int publish(String id) {
        return jdbcTemplate.update("update exams set status = 'UPCOMING' where id = ?", id);
    }

    public int updateStats(String id) {
        return jdbcTemplate.update(
                "update exams set participants = (select count(distinct user_id) from results where exam_id = ?), "
                        + "average_score = (select avg(score * 100.0 / total) from results where exam_id = ? and total > 0) where id = ?",
                id,
                id,
                id);
    }

    private Exam mapExam(ResultSet rs, int rowNum) throws SQLException {
        Double averageScore = rs.getObject("average_score") == null ? null : rs.getDouble("average_score");
        return new Exam(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("subject"),
                rs.getString("date"),
                rs.getInt("duration"),
                rs.getString("status"),
                rs.getInt("participants"),
                averageScore);
    }
}
