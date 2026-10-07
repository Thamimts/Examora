package com.examora.model;

import java.time.Instant;

public record Exam(
        String id,
        String title,
        String subject,
        String date,
        int duration,
        String status,
        int participants,
        Double averageScore,
        Instant startAt,
        Instant endAt,
        String centreId
) {
    public Exam(String id, String title, String subject, String date, int duration, String status, int participants, Double averageScore, Instant startAt, Instant endAt) {
        this(id, title, subject, date, duration, status, participants, averageScore, startAt, endAt, null);
    }

    public Exam(String id, String title, String subject, String date, int duration, String status, int participants, Double averageScore) {
        this(id, title, subject, date, duration, status, participants, averageScore, null, null, null);
    }
}
