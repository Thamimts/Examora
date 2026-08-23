package com.examora.backend.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExamRepository extends JpaRepository<Exam, UUID> {

    List<Exam> findByCreatedBy(UUID createdBy);

    List<Exam> findByStatus(ExamStatus status);
}
