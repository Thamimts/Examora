package com.examora.model;

import java.util.List;

public record Question(String id, String examId, String text, List<String> options, String answer) {

    public Question(String id, String text, List<String> options, String answer) {
        this(id, null, text, options, answer);
    }
}
