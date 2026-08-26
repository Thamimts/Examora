package com.examora.model;

public record Exam(
        String id,
        String title,
        String subject,
        String date,
        int duration,
        String status,
        int participants,
        Double averageScore
) {
}
