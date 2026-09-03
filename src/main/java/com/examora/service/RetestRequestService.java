package com.examora.service;

import com.examora.exception.ApiException;
import com.examora.model.*;
import com.examora.repository.*;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetestRequestService {
 private final RetestRequestRepository requests; private final ResultRepository results; private final ExamRepository exams; private final ExamAttemptRepository attempts; private final ActivityService activity;
 public RetestRequestService(RetestRequestRepository requests, ResultRepository results, ExamRepository exams, ExamAttemptRepository attempts, ActivityService activity){this.requests=requests;this.results=results;this.exams=exams;this.attempts=attempts;this.activity=activity;}
 public List<RetestRequest> mine(User student){ requireStudent(student); return requests.findForStudent(student.id()); }
 @Transactional public RetestRequest create(String examId, User student){ requireStudent(student); if(!results.findByUserIdAndExamId(student.id(),examId).isPresent()) throw new ApiException(HttpStatus.CONFLICT,"A retest can only be requested for a completed exam."); if(requests.findPending(student.id(),examId).isPresent()) throw new ApiException(HttpStatus.CONFLICT,"A retest request is already pending."); Exam exam=exams.findById(examId).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Exam not found.")); try { RetestRequest r=new RetestRequest(UUID.randomUUID().toString(),examId,student.id(),student.name(),exam.title(),"PENDING",Instant.now(),null,null,null); RetestRequest saved=requests.create(r); activity.student(student,"RETEST_REQUESTED","Your retest request for “"+exam.title()+"” is pending review."); activity.admin("RETEST_REQUESTED",student.name()+" requested a retest for “"+exam.title()+"”."); return saved; } catch(DuplicateKeyException e){throw new ApiException(HttpStatus.CONFLICT,"A retest request already exists for this exam.");} }
 public List<RetestRequest> all(User admin){requireAdmin(admin);return requests.findAll();}
 @Transactional public RetestRequest review(String id, String status, String reason, User admin){requireAdmin(admin); if(!status.equals("APPROVED")&&!status.equals("REJECTED")) throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid review status."); RetestRequest current=requests.findById(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Retest request not found.")); if(!current.status().equals("PENDING")) throw new ApiException(HttpStatus.CONFLICT,"This request has already been reviewed."); if(requests.review(id,status,admin.id(),reason,Instant.now())!=1) throw new ApiException(HttpStatus.CONFLICT,"This request was reviewed concurrently."); return requests.findById(id).orElseThrow(); }
 public boolean approved(String studentId,String examId){return requests.findApproved(studentId,examId).isPresent();}
 private void requireStudent(User u){if(u==null||u.role()!=Role.STUDENT)throw new ApiException(HttpStatus.FORBIDDEN,"Student access is required.");} private void requireAdmin(User u){if(u==null||u.role()!=Role.ADMIN)throw new ApiException(HttpStatus.FORBIDDEN,"Administrator access is required.");}
}
