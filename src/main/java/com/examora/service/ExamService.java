package com.examora.service;

import com.examora.exception.ApiException;
import com.examora.model.Exam;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ExamRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExamService {
    private final ExamRepository examRepository;

    public ExamService(ExamRepository examRepository) {
        this.examRepository = examRepository;
    }

    public List<Exam> findAll() {
        return examRepository.findAll();
    }

    public List<Exam> findForUser(User user) {
        if (user.role() == Role.STUDENT) {
            return examRepository.findAvailable();
        }
        return findAll();
    }

    public Exam findById(String id) {
        return examRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found."));
    }

    public Exam findByIdForUser(String id, User user) {
        Exam exam = findById(id);
        if (user.role() == Role.STUDENT && isDraft(exam)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This exam is not available to students.");
        }
        return exam;
    }

    public Exam create(Exam exam) {
        Exam normalized = normalize(exam.id(), exam);
        return examRepository.create(normalized);
    }

    public Exam update(String id, Exam exam) {
        findById(id);
        examRepository.update(id, normalize(id, exam));
        return findById(id);
    }

    public void delete(String id) {
        if (examRepository.delete(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Exam not found.");
        }
    }

    public Exam publish(String id) {
        if (examRepository.publish(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Exam not found.");
        }
        return findById(id);
    }

    public boolean isDraft(Exam exam) {
        return "DRAFT".equalsIgnoreCase(exam.status());
    }

    private Exam normalize(String id, Exam exam) {
        return new Exam(
                id == null || id.isBlank() ? UUID.randomUUID().toString() : id,
                required(exam.title(), "Title"),
                required(exam.subject(), "Subject"),
                exam.date() == null || exam.date().isBlank() ? LocalDate.now().toString() : exam.date().trim(),
                exam.duration() <= 0 ? 60 : exam.duration(),
                exam.status() == null || exam.status().isBlank() ? "DRAFT" : exam.status().trim().toUpperCase(),
                Math.max(exam.participants(), 0),
                exam.averageScore());
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required.");
        }
        return value.trim();
    }
}
