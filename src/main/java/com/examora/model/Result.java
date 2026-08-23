package com.examora.model;

public record Result(String id, String userId, String examId, String examTitle, String subject, int score, String date, int total) {

    public Result(String id, String examTitle, String subject, int score, String date, int total) {
        this(id, null, null, examTitle, subject, score, date, total);
    }
}
