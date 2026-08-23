package com.examora.backend.exam;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ExamService {

    private final ExamRepository examRepository;

    public ExamService(ExamRepository examRepository) {
        this.examRepository = examRepository;
    }

    public Exam createExam(CreateExamRequest request, UUID createdBy) {
        Exam exam = new Exam(
                request.title(),
                request.subject(),
                request.durationMinutes(),
                createdBy
        );

        return examRepository.save(exam);
    }

    @Transactional(readOnly = true)
    public List<Exam> getAllExams() {
        return examRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Exam> getExamsByCreator(UUID createdBy) {
        return examRepository.findByCreatedBy(createdBy);
    }

    @Transactional(readOnly = true)
    public List<Exam> getPublishedExams() {
        return examRepository.findByStatus(ExamStatus.PUBLISHED);
    }

    public Exam updateStatus(UUID id, ExamStatus status) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found"));

        exam.setStatus(status);
        return examRepository.save(exam);
    }
}
