package com.examora.service;

import com.examora.dto.CreateExamRequest;
import com.examora.exception.ApiException;
import com.examora.model.Exam;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ExamRepository;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExamService {
    private final ExamRepository examRepository;
    private final ActivityService activityService;

    public ExamService(ExamRepository examRepository, ActivityService activityService) {
        this.examRepository = examRepository;
        this.activityService = activityService;
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

    public Exam create(CreateExamRequest request, User actor) {
        requireTeacher(actor);
        Exam exam = new Exam(null, request.title(), request.subject(), request.date(), request.duration(), "DRAFT", 0, null, null, null);
        Exam normalized = normalize(null, exam);
        Exam created = examRepository.create(normalized, actor.id());
        activityService.admin(actor, "EXAM_CREATED", actor.name() + " created exam “" + created.title() + "”.");
        return created;
    }

    public Exam update(String id, Exam exam, User actor) {
        findById(id);
        requireOwner(id, actor);
        examRepository.update(id, normalize(id, exam));
        return findById(id);
    }

    public void delete(String id, User actor) {
        requireOwner(id, actor);
        if (examRepository.delete(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Exam not found.");
        }
    }

    public Exam publish(String id, User actor) {
        requireOwner(id, actor);
        if (examRepository.publish(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Exam not found.");
        }
        Exam published = findById(id);
        activityService.admin(actor, "EXAM_PUBLISHED", actor.name() + " published exam “" + published.title() + "”.");
        return published;
    }

    public void requireOwner(String examId, User actor) {
        if (actor.role() == Role.ADMIN) return;
        requireTeacher(actor);
        String ownerId = examRepository.findOwnerId(examId).orElse(null);
        // Legacy rows without an owner remain manageable by teachers during migration.
        if (ownerId != null && !ownerId.equals(actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You do not own this exam.");
        }
    }

    private void requireTeacher(User actor) {
        if (actor == null || (actor.role() != Role.TEACHER && actor.role() != Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Teacher or administrator access is required.");
        }
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
                exam.averageScore(),
                exam.startAt(),
                exam.endAt());
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required.");
        }
        return value.trim();
    }
}
