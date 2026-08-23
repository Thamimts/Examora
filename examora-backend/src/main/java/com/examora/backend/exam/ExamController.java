package com.examora.backend.exam;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/exams")
public class ExamController {

    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }

    @PostMapping
    public ResponseEntity<Exam> createExam(
            @Valid @RequestBody CreateExamRequest request
    ) {
        UUID userId = getAuthenticatedUserId();

        Exam exam = examService.createExam(request, userId);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(exam);
    }

    @GetMapping
    public ResponseEntity<List<Exam>> getExams() {
        return ResponseEntity.ok(
                examService.getAllExams()
        );
    }

    @GetMapping("/mine")
    public ResponseEntity<List<Exam>> getMyExams() {
        UUID userId = getAuthenticatedUserId();

        return ResponseEntity.ok(
                examService.getExamsByCreator(userId)
        );
    }

    @GetMapping("/published")
    public ResponseEntity<List<Exam>> getPublishedExams() {
        return ResponseEntity.ok(
                examService.getPublishedExams()
        );
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Exam> updateStatus(
            @PathVariable UUID id,
            @RequestParam ExamStatus status
    ) {
        return ResponseEntity.ok(
                examService.updateStatus(id, status)
        );
    }

    private UUID getAuthenticatedUserId() {
        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null ||
                authentication.getDetails() == null) {
            throw new IllegalStateException(
                    "Authenticated user ID is missing"
            );
        }

        return UUID.fromString(
                authentication.getDetails().toString()
        );
    }
}
