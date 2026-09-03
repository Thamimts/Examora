package com.examora.repository;

import com.examora.model.RetestRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RetestRequestRepository {
    private final JdbcTemplate jdbc;
    public RetestRequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<RetestRequest> findForStudent(String studentId) { return jdbc.query("select r.*, u.name student_name, e.title exam_title from retest_requests r join users u on u.id=r.student_id join exams e on e.id=r.exam_id where r.student_id=? order by r.requested_at desc", this::map, studentId); }
    public List<RetestRequest> findAll() { return jdbc.query("select r.*, u.name student_name, e.title exam_title from retest_requests r join users u on u.id=r.student_id join exams e on e.id=r.exam_id order by r.requested_at desc", this::map); }
    public Optional<RetestRequest> findPending(String studentId, String examId) { return jdbc.query("select r.*, u.name student_name, e.title exam_title from retest_requests r join users u on u.id=r.student_id join exams e on e.id=r.exam_id where r.student_id=? and r.exam_id=? and r.status='PENDING'", this::map, studentId, examId).stream().findFirst(); }
    public Optional<RetestRequest> findApproved(String studentId, String examId) { return jdbc.query("select r.*, u.name student_name, e.title exam_title from retest_requests r join users u on u.id=r.student_id join exams e on e.id=r.exam_id where r.student_id=? and r.exam_id=? and r.status='APPROVED'", this::map, studentId, examId).stream().findFirst(); }
    public RetestRequest create(RetestRequest r) { jdbc.update("insert into retest_requests (id,exam_id,student_id,status,requested_at) values (?,?,?,?,?)", r.id(),r.examId(),r.studentId(),r.status(),Timestamp.from(r.requestedAt())); return r; }
    public Optional<RetestRequest> findById(String id) { return jdbc.query("select r.*, u.name student_name, e.title exam_title from retest_requests r join users u on u.id=r.student_id join exams e on e.id=r.exam_id where r.id=?",this::map,id).stream().findFirst(); }
    public int review(String id, String status, String adminId, String reason, Instant at) { return jdbc.update("update retest_requests set status=?, reviewed_by=?, reason=?, reviewed_at=? where id=? and status='PENDING'",status,adminId,reason,Timestamp.from(at),id); }
    private RetestRequest map(ResultSet rs,int row) throws SQLException { Timestamp requested=rs.getTimestamp("requested_at"), reviewed=rs.getTimestamp("reviewed_at"); return new RetestRequest(rs.getString("id"),rs.getString("exam_id"),rs.getString("student_id"),rs.getString("student_name"),rs.getString("exam_title"),rs.getString("status"),requested.toInstant(),reviewed==null?null:reviewed.toInstant(),rs.getString("reviewed_by"),rs.getString("reason")); }
}
