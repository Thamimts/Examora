package com.examora.repository;

import com.examora.model.AiGeneratedQuestion;
import com.examora.model.AiQuestionOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiGeneratedQuestionRepository {
    private final JdbcTemplate jdbc;

    public AiGeneratedQuestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AiGeneratedQuestion> findBySessionId(String sessionId) {
        return jdbc.query(
                "select * from ai_generated_questions where session_id = ? order by order_index",
                this::mapQuestion, sessionId);
    }

    public AiGeneratedQuestion create(AiGeneratedQuestion question) {
        jdbc.update(
                "insert into ai_generated_questions (id, session_id, question_text, correct_option, explanation, topic, difficulty, order_index) values (?, ?, ?, ?, ?, ?, ?, ?)",
                question.id(), question.sessionId(), question.questionText(), question.correctOption(),
                question.explanation(), question.topic(), question.difficulty(), question.orderIndex());
        return question;
    }

    public List<AiQuestionOption> findOptionsByQuestionId(String questionId) {
        return jdbc.query(
                "select * from ai_question_options where question_id = ? order by display_order",
                this::mapOption, questionId);
    }

    public List<AiQuestionOption> findOptionsByQuestionIds(List<String> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(questionIds.size(), "?"));
        return jdbc.query(
                "select * from ai_question_options where question_id in (" + placeholders + ") order by question_id, display_order",
                this::mapOption, questionIds.toArray());
    }

    public void createOption(AiQuestionOption option) {
        jdbc.update(
                "insert into ai_question_options (id, question_id, text, display_order) values (?, ?, ?, ?)",
                option.id(), option.questionId(), option.text(), option.displayOrder());
    }

    public void createBatch(String sessionId, List<AiGeneratedQuestion> questions, java.util.Map<String, List<AiQuestionOption>> optionsByQuestion) {
        for (AiGeneratedQuestion q : questions) {
            create(q);
            List<AiQuestionOption> options = optionsByQuestion.getOrDefault(q.id(), List.of());
            for (AiQuestionOption opt : options) {
                createOption(opt);
            }
        }
    }

    private AiGeneratedQuestion mapQuestion(ResultSet rs, int rowNum) throws SQLException {
        return new AiGeneratedQuestion(
                rs.getString("id"), rs.getString("session_id"), rs.getString("question_text"),
                rs.getString("correct_option"), rs.getString("explanation"),
                rs.getString("topic"), rs.getString("difficulty"), rs.getInt("order_index"),
                rs.getTimestamp("created_at").toInstant());
    }

    private AiQuestionOption mapOption(ResultSet rs, int rowNum) throws SQLException {
        return new AiQuestionOption(
                rs.getString("id"), rs.getString("question_id"), rs.getString("text"),
                rs.getInt("display_order"));
    }
}
