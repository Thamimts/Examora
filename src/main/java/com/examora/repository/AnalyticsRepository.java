package com.examora.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalyticsRepository {
    private final JdbcTemplate jdbc;

    public AnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ExamAggregateRow> findExamAggregates(List<String> examIds) {
        if (examIds == null || examIds.isEmpty()) {
            return List.of();
        }
        String in = inClause(examIds.size());
        return jdbc.query(
                "select r.exam_id as exam_id, count(*) as submissions, count(distinct r.user_id) as participants, "
                        + "avg(round(r.score * 100.0 / r.total, 2)) as average_score, "
                        + "min(r.score * 100.0 / r.total) as min_score, max(r.score * 100.0 / r.total) as max_score "
                        + "from results r where r.exam_id in (" + in + ") and r.total > 0 "
                        + "group by r.exam_id",
                this::mapExamAggregate,
                examIds.toArray());
    }

    public List<ScoreRow> findScoresByExams(List<String> examIds) {
        if (examIds == null || examIds.isEmpty()) {
            return List.of();
        }
        String in = inClause(examIds.size());
        return jdbc.query(
                "select r.exam_id as exam_id, round(r.score * 100.0 / r.total, 2) as percentage "
                        + "from results r where r.exam_id in (" + in + ") and r.total > 0 order by r.exam_id",
                this::mapScore,
                examIds.toArray());
    }

    public List<AttemptCountRow> findAttemptCountsByExams(List<String> examIds) {
        if (examIds == null || examIds.isEmpty()) {
            return List.of();
        }
        String in = inClause(examIds.size());
        return jdbc.query(
                "select ea.exam_id as exam_id, count(*) as started, "
                        + "sum(case when ea.status = 'SUBMITTED' then 1 else 0 end) as submitted "
                        + "from exam_attempts ea where ea.exam_id in (" + in + ") group by ea.exam_id",
                this::mapAttemptCount,
                examIds.toArray());
    }

    public List<ScoreDistributionRow> findScoreDistribution(String examId) {
        return jdbc.query(
                "select case "
                        + "when round(r.score * 100.0 / r.total) <= 19 then '0-19' "
                        + "when round(r.score * 100.0 / r.total) <= 39 then '20-39' "
                        + "when round(r.score * 100.0 / r.total) <= 59 then '40-59' "
                        + "when round(r.score * 100.0 / r.total) <= 79 then '60-79' "
                        + "else '80-100' end as range, count(*) as count "
                        + "from results r where r.exam_id = ? and r.total > 0 group by range",
                this::mapScoreDistribution,
                examId);
    }

    public List<QuestionCountRow> findQuestionCounts(String examId) {
        return jdbc.query(
                "select q.id as question_id, q.difficulty as difficulty, "
                        + "count(a.id) as total_answers, "
                        + "sum(case when a.correct is not null then 1 else 0 end) as graded, "
                        + "sum(case when a.correct = true then 1 else 0 end) as correct, "
                        + "sum(case when a.correct = false then 1 else 0 end) as incorrect, "
                        + "sum(case when a.correct is null then 1 else 0 end) as ungraded "
                        + "from questions q "
                        + "left join answers a on a.question_id = q.id and a.exam_id = q.exam_id "
                        + "where q.exam_id = ? group by q.id, q.difficulty order by q.id",
                this::mapQuestionCount,
                examId);
    }

    public List<OptionCountRow> findOptionCounts(String examId) {
        return jdbc.query(
                "select a.question_id as question_id, a.option_id as option_id, count(*) as option_count "
                        + "from answers a where a.exam_id = ? and a.option_id is not null "
                        + "group by a.question_id, a.option_id order by a.question_id, a.option_id",
                this::mapOptionCount,
                examId);
    }

    public GradedAccuracyRow findGradedAccuracyForExams(String userId, List<String> examIds) {
        if (userId == null || userId.isBlank() || examIds == null || examIds.isEmpty()) {
            return new GradedAccuracyRow(0, 0);
        }
        String in = inClause(examIds.size());
        Object[] params = prepend(userId, examIds);
        return jdbc.query(
                "select count(*) as graded, sum(case when a.correct = true then 1 else 0 end) as correct "
                        + "from answers a where a.user_id = ? and a.exam_id in (" + in + ") and a.correct is not null",
                this::mapGradedAccuracy,
                params).stream().findFirst().orElse(new GradedAccuracyRow(0, 0));
    }

    public GradedAccuracyRow findGradedAccuracy(String userId) {
        return jdbc.query(
                "select count(*) as graded, sum(case when a.correct = true then 1 else 0 end) as correct "
                        + "from answers a where a.user_id = ? and a.correct is not null",
                this::mapGradedAccuracy,
                userId).stream().findFirst().orElse(new GradedAccuracyRow(0, 0));
    }

    public List<DifficultyRow> findDifficultyPerformance(String userId) {
        return jdbc.query(
                "select q.difficulty as difficulty, count(a.id) as graded, "
                        + "sum(case when a.correct = true then 1 else 0 end) as correct "
                        + "from answers a join questions q on q.id = a.question_id "
                        + "where a.user_id = ? and a.correct is not null "
                        + "group by q.difficulty order by q.difficulty",
                this::mapDifficulty,
                userId);
    }

    public List<DifficultyRow> findDifficultyPerformanceForExams(String userId, List<String> examIds) {
        if (userId == null || userId.isBlank() || examIds == null || examIds.isEmpty()) {
            return List.of();
        }
        String in = inClause(examIds.size());
        Object[] params = prepend(userId, examIds);
        return jdbc.query(
                "select q.difficulty as difficulty, count(a.id) as graded, "
                        + "sum(case when a.correct = true then 1 else 0 end) as correct "
                        + "from answers a join questions q on q.id = a.question_id "
                        + "where a.user_id = ? and a.exam_id in (" + in + ") and a.correct is not null "
                        + "group by q.difficulty order by q.difficulty",
                this::mapDifficulty,
                params);
    }

    public List<ExamAccuracyRow> findPerExamGradedAccuracy(String userId, List<String> examIds) {
        if (userId == null || userId.isBlank() || examIds == null || examIds.isEmpty()) {
            return List.of();
        }
        String in = inClause(examIds.size());
        Object[] params = prepend(userId, examIds);
        return jdbc.query(
                "select a.exam_id as exam_id, count(*) as graded, "
                        + "sum(case when a.correct = true then 1 else 0 end) as correct "
                        + "from answers a where a.user_id = ? and a.exam_id in (" + in + ") and a.correct is not null "
                        + "group by a.exam_id",
                this::mapExamAccuracy,
                params);
    }

    public List<ExamDifficultyRow> findDifficultyCountsByExam(String userId) {
        return jdbc.query(
                "select a.exam_id as exam_id, q.difficulty as difficulty, count(a.id) as graded, "
                        + "sum(case when a.correct = true then 1 else 0 end) as correct "
                        + "from answers a join questions q on q.id = a.question_id "
                        + "where a.user_id = ? and a.correct is not null "
                        + "group by a.exam_id, q.difficulty order by a.exam_id, q.difficulty",
                this::mapExamDifficulty,
                userId);
    }

    public List<SubjectRow> findSubjectPerformance(String userId) {
        return jdbc.query(
                "select r.subject as subject, count(*) as completed, "
                        + "round(avg(r.score * 100.0 / r.total), 2) as average_percentage "
                        + "from results r where r.user_id = ? and r.total > 0 and r.exam_id is not null "
                        + "group by r.subject order by r.subject",
                this::mapSubject,
                userId);
    }

    public List<AttemptTimingRow> findSubmittedAttemptTimings(String studentId) {
        return jdbc.query(
                "select exam_id, attempt_number, started_at, submitted_at from exam_attempts "
                        + "where student_id = ? and status = 'SUBMITTED' and submitted_at is not null "
                        + "order by started_at desc",
                this::mapAttemptTiming,
                studentId);
    }

    public List<AttemptTimingRow> findSubmittedAttemptTimingsForExams(String studentId, List<String> examIds) {
        if (studentId == null || studentId.isBlank() || examIds == null || examIds.isEmpty()) {
            return List.of();
        }
        String in = inClause(examIds.size());
        Object[] params = prepend(studentId, examIds);
        return jdbc.query(
                "select exam_id, attempt_number, started_at, submitted_at from exam_attempts "
                        + "where student_id = ? and exam_id in (" + in + ") and status = 'SUBMITTED' "
                        + "and submitted_at is not null order by started_at desc",
                this::mapAttemptTiming,
                params);
    }

    public boolean hasAttemptsInExams(String studentId, List<String> examIds) {
        if (studentId == null || studentId.isBlank() || examIds == null || examIds.isEmpty()) {
            return false;
        }
        String in = inClause(examIds.size());
        Object[] params = prepend(studentId, examIds);
        Integer count = jdbc.queryForObject(
                "select count(*) from exam_attempts where student_id = ? and exam_id in (" + in + ")",
                Integer.class,
                params);
        return count != null && count > 0;
    }

    public int countPracticeSessions(String studentId) {
        return countPracticeSessions(studentId, null);
    }

    public int countPracticeSessions(String studentId, String examId) {
        String sql = examId == null || examId.isBlank()
                ? "select count(*) from practice_sessions where student_id = ?"
                : "select count(*) from practice_sessions where student_id = ? and exam_id = ?";
        Integer count = examId == null || examId.isBlank()
                ? jdbc.queryForObject(sql, Integer.class, studentId)
                : jdbc.queryForObject(sql, Integer.class, studentId, examId);
        return count == null ? 0 : count;
    }

    public int countSubmittedAttempts(String studentId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from exam_attempts where student_id = ? and status = 'SUBMITTED'",
                Integer.class,
                studentId);
        return count == null ? 0 : count;
    }

    public int countCompletedPracticeSessions(String studentId) {
        return countCompletedPracticeSessions(studentId, null);
    }

    public int countCompletedPracticeSessions(String studentId, String examId) {
        String sql = examId == null || examId.isBlank()
                ? "select count(*) from practice_sessions where student_id = ? and status = 'COMPLETED'"
                : "select count(*) from practice_sessions where student_id = ? and exam_id = ? and status = 'COMPLETED'";
        Integer count = examId == null || examId.isBlank()
                ? jdbc.queryForObject(sql, Integer.class, studentId)
                : jdbc.queryForObject(sql, Integer.class, studentId, examId);
        return count == null ? 0 : count;
    }

    public PracticeAnswerRow findPracticeAnswerAggregates(String studentId, String examId) {
        Object[] params;
        String sql;
        if (examId == null || examId.isBlank()) {
            sql = "select count(*) as questions, sum(case when pa.correct = true then 1 else 0 end) as correct "
                    + "from practice_answers pa join practice_sessions ps on ps.id = pa.session_id "
                    + "where ps.student_id = ?";
            params = new Object[]{studentId};
        } else {
            sql = "select count(*) as questions, sum(case when pa.correct = true then 1 else 0 end) as correct "
                    + "from practice_answers pa join practice_sessions ps on ps.id = pa.session_id "
                    + "where ps.student_id = ? and ps.exam_id = ?";
            params = new Object[]{studentId, examId};
        }
        return jdbc.query(sql, this::mapPracticeAnswer, params)
                .stream().findFirst().orElse(new PracticeAnswerRow(0, 0));
    }

    public List<PracticeDifficultyRow> findPracticeDifficulty(String studentId, String examId) {
        Object[] params;
        String sql;
        if (examId == null || examId.isBlank()) {
            sql = "select pa.difficulty as difficulty, count(*) as questions, "
                    + "sum(case when pa.correct = true then 1 else 0 end) as correct "
                    + "from practice_answers pa join practice_sessions ps on ps.id = pa.session_id "
                    + "where ps.student_id = ? group by pa.difficulty order by pa.difficulty";
            params = new Object[]{studentId};
        } else {
            sql = "select pa.difficulty as difficulty, count(*) as questions, "
                    + "sum(case when pa.correct = true then 1 else 0 end) as correct "
                    + "from practice_answers pa join practice_sessions ps on ps.id = pa.session_id "
                    + "where ps.student_id = ? and ps.exam_id = ? group by pa.difficulty order by pa.difficulty";
            params = new Object[]{studentId, examId};
        }
        return jdbc.query(sql, this::mapPracticeDifficulty, params);
    }

    public PracticeAnswerRow findRecentPracticeAccuracy(String studentId, int limit) {
        List<PracticeAnswerRow> rows = jdbc.query(
                "select count(*) as questions, sum(case when pa.correct = true then 1 else 0 end) as correct "
                        + "from (select pa.correct from practice_answers pa "
                        + "join practice_sessions ps on ps.id = pa.session_id "
                        + "where ps.student_id = ? order by pa.answered_at desc limit ?) pa",
                this::mapPracticeAnswer,
                studentId, limit);
        return rows.stream().findFirst().orElse(new PracticeAnswerRow(0, 0));
    }

    /** Newest-first bounded sample of practice answers (actual answered questions only). */
    public List<PracticeAnswerSampleRow> findRecentPracticeAnswerRows(String studentId, int limit) {
        return jdbc.query(
                "select pa.correct as correct, pa.answered_at as answered_at from practice_answers pa "
                        + "join practice_sessions ps on ps.id = pa.session_id "
                        + "where ps.student_id = ? order by pa.answered_at desc, pa.id desc limit ?",
                this::mapPracticeAnswerSample,
                studentId, limit);
    }

    public FirstPracticeSessionRow findFirstCompletedPracticeSession(String studentId) {
        return jdbc.query(
                "select completed_at, answered_count, correct_count from practice_sessions "
                        + "where student_id = ? and status = 'COMPLETED' and answered_count > 0 "
                        + "order by completed_at asc limit 1",
                this::mapFirstPracticeSession,
                studentId).stream().findFirst().orElse(null);
    }

    /**
     * Most recent completion timestamp per practice-answer difficulty (1-5), over COMPLETED
     * practice sessions only. Used for the repetition/cooldown check; bounded to 5 rows.
     */
    public List<PracticeCompletionRow> findLastPracticeCompletionByDifficulty(String studentId) {
        return jdbc.query(
                "select pa.difficulty as difficulty, max(ps.completed_at) as last_completed_at "
                        + "from practice_sessions ps "
                        + "join practice_answers pa on pa.session_id = ps.id "
                        + "where ps.student_id = ? and ps.status = 'COMPLETED' "
                        + "group by pa.difficulty order by pa.difficulty",
                this::mapPracticeCompletion,
                studentId);
    }

    public int countCompletedAiPracticeSessions(String studentId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from ai_practice_sessions where student_id = ? and status = 'COMPLETED'",
                Integer.class,
                studentId);
        return count == null ? 0 : count;
    }

    public PracticeAnswerRow findAiPracticeAnswerAggregates(String studentId) {
        List<PracticeAnswerRow> rows = jdbc.query(
                "select count(*) as questions, sum(case when apa.correct = true then 1 else 0 end) as correct "
                        + "from ai_practice_answers apa "
                        + "join ai_practice_sessions aps on aps.id = apa.session_id "
                        + "where aps.student_id = ?",
                this::mapPracticeAnswer,
                studentId);
        return rows.stream().findFirst().orElse(new PracticeAnswerRow(0, 0));
    }

    public Instant findMostRecentPracticeActivity(String studentId, String examId) {
        if (examId == null || examId.isBlank()) {
            String sql = "select max(pa.answered_at) as at from practice_answers pa "
                    + "join practice_sessions ps on ps.id = pa.session_id where ps.student_id = ?";
            List<Instant> fromAnswers = jdbc.query(sql, (rs, row) -> nullableInstant(rs, "at"), studentId);
            Instant fromAnswer = firstNotNull(fromAnswers);
            if (fromAnswer != null) {
                return fromAnswer;
            }
            return firstNotNull(jdbc.query(
                    "select max(last_activity_at) as at from practice_sessions where student_id = ?",
                    (rs, row) -> nullableInstant(rs, "at"), studentId));
        }
        String sql = "select max(pa.answered_at) as at from practice_answers pa "
                + "join practice_sessions ps on ps.id = pa.session_id "
                + "where ps.student_id = ? and ps.exam_id = ?";
        List<Instant> fromAnswers = jdbc.query(sql, (rs, row) -> nullableInstant(rs, "at"), studentId, examId);
        Instant fromAnswer = firstNotNull(fromAnswers);
        if (fromAnswer != null) {
            return fromAnswer;
        }
        return firstNotNull(jdbc.query(
                "select max(last_activity_at) as at from practice_sessions "
                        + "where student_id = ? and exam_id = ?",
                (rs, row) -> nullableInstant(rs, "at"), studentId, examId));
    }

    public List<OptionLabelRow> findOptionLabels(String examId) {
        return jdbc.query(
                "select qo.question_id as question_id, qo.id as option_id, qo.text as option_text, "
                        + "qo.display_order as display_order from question_options qo "
                        + "where qo.question_id in (select id from questions where exam_id = ?) "
                        + "order by qo.question_id, qo.display_order",
                this::mapOptionLabel,
                examId);
    }

    public List<StudentPerformanceRow> findStudentPerformanceRows(String examId) {
        return jdbc.query(
                "select u.id as student_id, u.name as student_name, "
                        + "r.score as score, r.total as total, r.date as submitted_on, "
                        + "ea.attempt_number as attempt_number, ea.status as attempt_status, "
                        + "ea.started_at as started_at, ea.submitted_at as submitted_at, "
                        + "ea.expires_at as expires_at "
                        + "from users u "
                        + "left join exam_attempts ea on ea.student_id = u.id and ea.exam_id = ? "
                        + "and ea.attempt_number = (select max(ea2.attempt_number) from exam_attempts ea2 "
                        + "where ea2.student_id = u.id and ea2.exam_id = ?) "
                        + "left join results r on r.user_id = u.id and r.exam_id = ? and r.total > 0 "
                        + "where u.id in (select student_id from exam_attempts where exam_id = ? "
                        + "union select user_id from results where exam_id = ?) "
                        + "order by u.name",
                this::mapStudentPerformance,
                examId, examId, examId, examId, examId);
    }

    public List<StudentPracticeRow> findPracticeAggregatesForExam(String examId) {
        return jdbc.query(
                "select ps.student_id as student_id, count(pa.id) as questions, "
                        + "sum(case when pa.correct = true then 1 else 0 end) as correct "
                        + "from practice_answers pa join practice_sessions ps on ps.id = pa.session_id "
                        + "where ps.exam_id = ? group by ps.student_id",
                this::mapStudentPractice,
                examId);
    }

    private OptionLabelRow mapOptionLabel(ResultSet rs, int row) throws SQLException {
        return new OptionLabelRow(
                rs.getString("question_id"),
                rs.getString("option_id"),
                rs.getString("option_text"),
                rs.getInt("display_order"));
    }

    private StudentPerformanceRow mapStudentPerformance(ResultSet rs, int row) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp submittedAt = rs.getTimestamp("submitted_at");
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        Integer score = rs.getObject("score") == null ? null : rs.getInt("score");
        Integer total = rs.getObject("total") == null ? null : rs.getInt("total");
        Integer attemptNumber = rs.getObject("attempt_number") == null ? null : rs.getInt("attempt_number");
        String attemptStatus = rs.getString("attempt_status");
        boolean activeNow = "STARTED".equals(attemptStatus) && expiresAt != null
                && expiresAt.toInstant().isAfter(Instant.now());
        return new StudentPerformanceRow(
                rs.getString("student_id"),
                rs.getString("student_name"),
                score != null,
                score,
                total,
                rs.getString("submitted_on"),
                startedAt == null ? null : startedAt.toInstant(),
                submittedAt == null ? null : submittedAt.toInstant(),
                attemptNumber,
                attemptStatus,
                activeNow);
    }

    private StudentPracticeRow mapStudentPractice(ResultSet rs, int row) throws SQLException {
        return new StudentPracticeRow(rs.getString("student_id"), rs.getInt("questions"), rs.getInt("correct"));
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private Instant firstNotNull(List<Instant> instants) {
        for (Instant instant : instants) {
            if (instant != null) {
                return instant;
            }
        }
        return null;
    }

    private String inClause(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    private Object[] prepend(Object first, List<?> rest) {
        Object[] params = new Object[rest.size() + 1];
        params[0] = first;
        System.arraycopy(rest.toArray(), 0, params, 1, rest.size());
        return params;
    }

    private ExamAggregateRow mapExamAggregate(ResultSet rs, int row) throws SQLException {
        return new ExamAggregateRow(
                rs.getString("exam_id"),
                rs.getInt("submissions"),
                rs.getInt("participants"),
                nullableDouble(rs, "average_score"),
                nullableDouble(rs, "min_score"),
                nullableDouble(rs, "max_score"));
    }

    private ScoreRow mapScore(ResultSet rs, int row) throws SQLException {
        return new ScoreRow(rs.getString("exam_id"), nullableDouble(rs, "percentage"));
    }

    private AttemptCountRow mapAttemptCount(ResultSet rs, int row) throws SQLException {
        return new AttemptCountRow(rs.getString("exam_id"), rs.getInt("started"), rs.getInt("submitted"));
    }

    private ScoreDistributionRow mapScoreDistribution(ResultSet rs, int row) throws SQLException {
        return new ScoreDistributionRow(rs.getString("range"), rs.getInt("count"));
    }

    private QuestionCountRow mapQuestionCount(ResultSet rs, int row) throws SQLException {
        return new QuestionCountRow(
                rs.getString("question_id"),
                rs.getInt("difficulty"),
                rs.getInt("total_answers"),
                rs.getInt("graded"),
                rs.getInt("correct"),
                rs.getInt("incorrect"),
                rs.getInt("ungraded"));
    }

    private OptionCountRow mapOptionCount(ResultSet rs, int row) throws SQLException {
        return new OptionCountRow(rs.getString("question_id"), rs.getString("option_id"), rs.getInt("option_count"));
    }

    private GradedAccuracyRow mapGradedAccuracy(ResultSet rs, int row) throws SQLException {
        return new GradedAccuracyRow(rs.getInt("graded"), rs.getInt("correct"));
    }

    private DifficultyRow mapDifficulty(ResultSet rs, int row) throws SQLException {
        return new DifficultyRow(rs.getInt("difficulty"), rs.getInt("graded"), rs.getInt("correct"));
    }

    private SubjectRow mapSubject(ResultSet rs, int row) throws SQLException {
        return new SubjectRow(rs.getString("subject"), rs.getInt("completed"), nullableDouble(rs, "average_percentage"));
    }

    private ExamAccuracyRow mapExamAccuracy(ResultSet rs, int row) throws SQLException {
        return new ExamAccuracyRow(rs.getString("exam_id"), rs.getInt("graded"), rs.getInt("correct"));
    }

    private ExamDifficultyRow mapExamDifficulty(ResultSet rs, int row) throws SQLException {
        return new ExamDifficultyRow(rs.getString("exam_id"), rs.getInt("difficulty"), rs.getInt("graded"), rs.getInt("correct"));
    }

    private PracticeAnswerSampleRow mapPracticeAnswerSample(ResultSet rs, int row) throws SQLException {
        Timestamp answeredAt = rs.getTimestamp("answered_at");
        return new PracticeAnswerSampleRow(rs.getBoolean("correct"),
                answeredAt == null ? null : answeredAt.toInstant());
    }

    private FirstPracticeSessionRow mapFirstPracticeSession(ResultSet rs, int row) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new FirstPracticeSessionRow(
                completedAt == null ? null : completedAt.toInstant(),
                rs.getInt("answered_count"),
                rs.getInt("correct_count"));
    }

    private PracticeCompletionRow mapPracticeCompletion(ResultSet rs, int row) throws SQLException {
        Timestamp lastCompletedAt = rs.getTimestamp("last_completed_at");
        return new PracticeCompletionRow(
                rs.getInt("difficulty"),
                lastCompletedAt == null ? null : lastCompletedAt.toInstant());
    }

    private AttemptTimingRow mapAttemptTiming(ResultSet rs, int row) throws SQLException {
        Timestamp submitted = rs.getTimestamp("submitted_at");
        return new AttemptTimingRow(
                rs.getString("exam_id"),
                rs.getInt("attempt_number"),
                rs.getTimestamp("started_at").toInstant(),
                submitted == null ? null : submitted.toInstant());
    }

    private PracticeAnswerRow mapPracticeAnswer(ResultSet rs, int row) throws SQLException {
        return new PracticeAnswerRow(rs.getInt("questions"), rs.getInt("correct"));
    }

    private PracticeDifficultyRow mapPracticeDifficulty(ResultSet rs, int row) throws SQLException {
        return new PracticeDifficultyRow(rs.getInt("difficulty"), rs.getInt("questions"), rs.getInt("correct"));
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    public record ExamAggregateRow(String examId, int submissions, int participants,
                                   Double averageScore, Double minScore, Double maxScore) {
    }

    public record ScoreRow(String examId, Double percentage) {
    }

    public record AttemptCountRow(String examId, int started, int submitted) {
    }

    public record ScoreDistributionRow(String range, int count) {
    }

    public record QuestionCountRow(String questionId, int difficulty, int totalAnswers, int graded,
                                   int correct, int incorrect, int ungraded) {
    }

    public record OptionCountRow(String questionId, String optionId, int count) {
    }

    public record GradedAccuracyRow(int graded, int correct) {
    }

    public record DifficultyRow(int difficulty, int graded, int correct) {
    }

    public record SubjectRow(String subject, int completed, Double averagePercentage) {
    }

    public record ExamAccuracyRow(String examId, int graded, int correct) {
    }

    public record ExamDifficultyRow(String examId, int difficulty, int graded, int correct) {
    }

    public record PracticeAnswerSampleRow(boolean correct, Instant answeredAt) {
    }

    public record FirstPracticeSessionRow(Instant completedAt, int answeredCount, int correctCount) {
    }

    public record PracticeCompletionRow(int difficulty, Instant lastCompletedAt) {
    }

    public record AttemptTimingRow(String examId, int attemptNumber, Instant startedAt, Instant submittedAt) {
    }

    public record PracticeAnswerRow(int questions, int correct) {
    }

    public record PracticeDifficultyRow(int difficulty, int questions, int correct) {
    }

    public record OptionLabelRow(String questionId, String optionId, String text, int displayOrder) {
    }

    public record StudentPracticeRow(String studentId, int questions, int correct) {
    }

    public record StudentPerformanceRow(String studentId, String studentName, boolean hasResult,
                                        Integer score, Integer total, String submittedOn,
                                        Instant startedAt, Instant submittedAt,
                                        Integer attemptNumber, String attemptStatus, boolean activeNow) {
    }
}