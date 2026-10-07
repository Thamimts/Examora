package com.examora.model;

import java.time.Instant;

/**
 * A student's enrolment in an exam. Enrolment is the trigger for automatic room/seat
 * assignment and is unique per (exam, student).
 */
public record ExamEnrollment(
        String id,
        String examId,
        String studentId,
        String status,
        Instant enrolledAt
) {
}