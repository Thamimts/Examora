package com.examora.repository;

import com.examora.model.AiTutorQuestion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiTutorQuestionRepository {
    private final JdbcTemplate jdbc;

    public AiTutorQuestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AiTutorQuestion> findById(String id) {
        return jdbc.query("select * from ai_tutor_questions where id = ?", this::map, id)
                .stream().findFirst();
    }

    public AiTutorQuestion create(AiTutorQuestion question) {
        jdbc.update(
                "insert into ai_tutor_questions (id, student_id, session_id, source_question_id, kind, question_text, options, difficulty, hint, correct_answer, answered, correct, answered_at, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                question.id(), question.studentId(), question.sessionId(), question.sourceQuestionId(),
                question.kind(), question.questionText(), question.options(), question.difficulty(),
                question.hint(), question.correctAnswer(), question.answered(), question.correct(),
                question.answeredAt() == null ? null : Timestamp.from(question.answeredAt()),
                Timestamp.from(question.createdAt()));
        return question;
    }

    public int markAnswered(String id, boolean correct, Instant answeredAt) {
        return jdbc.update(
                "update ai_tutor_questions set answered = true, correct = ?, answered_at = ? where id = ? and answered = false",
                correct, Timestamp.from(answeredAt), id);
    }

    private AiTutorQuestion map(ResultSet rs, int rowNum) throws SQLException {
        Boolean correct = rs.getObject("correct", Boolean.class);
        Timestamp answeredAt = rs.getTimestamp("answered_at");
        return new AiTutorQuestion(
                rs.getString("id"), rs.getString("student_id"), rs.getString("session_id"),
                rs.getString("source_question_id"), rs.getString("kind"), rs.getString("question_text"),
                rs.getString("options"), rs.getInt("difficulty"), rs.getString("hint"),
                rs.getString("correct_answer"), rs.getBoolean("answered"), correct,
                answeredAt == null ? null : answeredAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}