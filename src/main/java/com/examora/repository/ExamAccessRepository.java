package com.examora.repository;

import com.examora.model.ExamAccessStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExamAccessRepository {

    private final JdbcTemplate jdbc;
    public ExamAccessRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<ExamAccessStatus> findStatus(String studentId, String examId) {
        return jdbc.query("select status from exam_access_state where student_id = ? and exam_id = ?",
                (rs, row) -> ExamAccessStatus.valueOf(rs.getString("status")), studentId, examId).stream().findFirst();
    }

    public void suspend(String studentId, String examId, String reason, Instant at) {
        String update = "update exam_access_state set status = 'SUSPENDED', suspended_at = ?, suspended_reason = ?, "
                + "updated_at = current_timestamp where student_id = ? and exam_id = ?";
        int updated = jdbc.update(update, Timestamp.from(at), reason, studentId, examId);
        if (updated == 0) {
            try {
                jdbc.update("insert into exam_access_state (student_id, exam_id, status, suspended_at, suspended_reason) "
                        + "values (?, ?, 'SUSPENDED', ?, ?)", studentId, examId, Timestamp.from(at), reason);
            } catch (DuplicateKeyException exception) {
                jdbc.update(update, Timestamp.from(at), reason, studentId, examId);
            }
        }
    }

    public int markRetestPending(String studentId, String examId) {
        return markRestricted(studentId, examId, ExamAccessStatus.RETEST_PENDING);
    }

    public int markRetestApproved(String studentId, String examId) {
        return markRestricted(studentId, examId, ExamAccessStatus.RETEST_APPROVED);
    }

    public int markRetestRejected(String studentId, String examId) {
        return markRestricted(studentId, examId, ExamAccessStatus.RETEST_REJECTED);
    }

    private int markRestricted(String studentId, String examId, ExamAccessStatus status) {
        return jdbc.update("update exam_access_state set status = ?, updated_at = current_timestamp "
                        + "where student_id = ? and exam_id = ? and status in (?, ?, ?, ?)",
                status.name(), studentId, examId, ExamAccessStatus.SUSPENDED.name(),
                ExamAccessStatus.RETEST_PENDING.name(), ExamAccessStatus.RETEST_APPROVED.name(),
                ExamAccessStatus.RETEST_REJECTED.name());
    }

    public int consumeRetestApproval(String studentId, String examId) {
        return jdbc.update("update exam_access_state set status = 'ELIGIBLE', suspended_at = null, "
                + "suspended_reason = null, updated_at = current_timestamp "
                + "where student_id = ? and exam_id = ? and status = 'RETEST_APPROVED'", studentId, examId);
    }
}