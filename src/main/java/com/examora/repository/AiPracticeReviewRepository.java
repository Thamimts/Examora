package com.examora.repository;

import com.examora.model.AiPracticeReview;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiPracticeReviewRepository {
    private final JdbcTemplate jdbc;

    public AiPracticeReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AiPracticeReview> findBySessionId(String sessionId) {
        return jdbc.query(
                "select * from ai_practice_reviews where session_id = ? order by created_at",
                this::map, sessionId);
    }

    public void create(AiPracticeReview review) {
        jdbc.update(
                "insert into ai_practice_reviews (id, session_id, question_id, explanation) values (?, ?, ?, ?)",
                review.id(), review.sessionId(), review.questionId(), review.explanation());
    }

    public void createBatch(List<AiPracticeReview> reviews) {
        for (AiPracticeReview review : reviews) {
            create(review);
        }
    }

    public void replace(AiPracticeReview review) {
        jdbc.update("delete from ai_practice_reviews where session_id = ? and question_id = ?",
                review.sessionId(), review.questionId());
        create(review);
    }

    private AiPracticeReview map(ResultSet rs, int rowNum) throws SQLException {
        return new AiPracticeReview(
                rs.getString("id"), rs.getString("session_id"),
                rs.getString("question_id"), rs.getString("explanation"),
                rs.getTimestamp("created_at").toInstant());
    }
}
