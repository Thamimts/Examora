package com.examora.model;

import java.util.List;

public record Question(String id, String examId, String text, List<String> options, String answer, int difficulty) {

    public Question(String id, String text, List<String> options, String answer) {
        this(id, null, text, options, answer, 2);
    }

    public Question(String id, String examId, String text, List<String> options, String answer) {
        this(id, examId, text, options, answer, 2);
    }
}
