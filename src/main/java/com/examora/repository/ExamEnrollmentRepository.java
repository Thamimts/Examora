package com.examora.repository;

import com.examora.model.ExamEnrollment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExamEnrollmentRepository {
    private final JdbcTemplate jdbcTemplate;

    public ExamEnrollmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(String id, String examId, String studentId) {
        return jdbcTemplate.update(
                "insert into exam_enrollments (id, exam_id, student_id) values (?, ?, ?)",
                id, examId, studentId);
    }

    public Optional<ExamEnrollment> findByStudentAndExam(String studentId, String examId) {
        return jdbcTemplate.query(
                        "select id, exam_id, student_id, status, enrolled_at from exam_enrollments "
                                + "where student_id = ? and exam_id = ?",
                        this::mapEnrollment,
                        studentId, examId)
                .stream()
                .findFirst();
    }

    public List<ExamEnrollment> findByExamId(String examId) {
        return jdbcTemplate.query(
                "select id, exam_id, student_id, status, enrolled_at from exam_enrollments "
                        + "where exam_id = ? order by enrolled_at",
                this::mapEnrollment,
                examId);
    }

    public List<ExamEnrollment> findByStudentId(String studentId) {
        return jdbcTemplate.query(
                "select id, exam_id, student_id, status, enrolled_at from exam_enrollments "
                        + "where student_id = ? order by enrolled_at desc",
                this::mapEnrollment,
                studentId);
    }

    public int delete(String id) {
        return jdbcTemplate.update("delete from exam_enrollments where id = ?", id);
    }

    private ExamEnrollment mapEnrollment(ResultSet rs, int rowNum) throws SQLException {
        return new ExamEnrollment(
                rs.getString("id"),
                rs.getString("exam_id"),
                rs.getString("student_id"),
                rs.getString("status"),
                rs.getTimestamp("enrolled_at") == null ? null : rs.getTimestamp("enrolled_at").toInstant());
    }
}