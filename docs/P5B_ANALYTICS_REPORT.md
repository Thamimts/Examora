# P5B — Exam Intelligence Analytics Core — Final Report

Status: **COMPLETE (uncommitted)** · Backend: 333 tests green · Frontend build green · PostgreSQL 18 verification passed

---

## 1. Overview

P5B delivers deterministic, read-only analytics for Examora:
- **Teacher-side**: exam analytics summary (list) + per-exam detail with score distribution, attempt stats, per-question intelligence (accuracy, attempt rate, unanswered, option distribution, quality flags), and per-student drill-down.
- **Student-side**: upgraded `/api/student/analytics` (summary + per-exam history combining exam result, latest attempt timing, and practice performance) without touching the pre-existing `StudentAnalyticsService` / `StudentPerformanceAnalytics` DTO.
- **Practice analytics**: persisted practice sessions surfaced per-exam and in the student summary.
- **Phase 0 prerequisite security fixes** so these staff-facing analytics cannot be probed across exam ownership boundaries.

All implementation is **JdbcTemplate + portable SQL** (no JPA); all queries verified on real PostgreSQL 18.6 and H2 (`MODE=MySQL`) integration tests. Median percentile is computed in Java because H2 does not implement `percentile_cont`.

## 2. Scope and constraints honored

- **No P5A schema/data migration** — the analytics layer reads the existing schema as-is.
- **No changes** to `ExamAttemptService`, `StudentAnalyticsService`, the pre-existing `StudentPerformanceAnalytics` DTO, grading semantics, exam lifecycle, concurrency, or proctoring.
- **Read-only**: no analytics query writes to the database; existing rows were never adjusted (the only inserts in this phase were the *verification-only rows* documented in Section 15).
- All 333 tests pass (307 pre-existing + 26 new).

## 3. Security fixes delivered (Phase 0)

| Fix | Change |
|---|---|
| Analytics API restricted | `SecurityConfig`: `/api/analytics/**` → `hasAnyRole(TEACHER, ADMIN)` |
| Student analytics restricted | `SecurityConfig`: `GET /api/student/analytics/**` → `hasRole(STUDENT)` |
| Student adaptive GETs restricted | `SecurityConfig`: `GET /api/student/adaptive/**` → `hasRole(STUDENT)` (matchers placed before the open `/api/student/**` permitAll) |
| Teacher result access scoped to owned exams | `ResultService`: `findAllForStaff`, `findByUserIdForStaff`, `findByIdForStaff` all join the actor's owned exam ids (`ExamService.findOwnedExamIds` via `ExamRepository.findIdsByOwner`); `create/update/delete` enforce `requireOwner` |
| Staff `/results/me` must name a user | `ResultController`: staff calling `/results/me` without `userId` gets `400` (students keep their own-result behavior) |
| Teacher answer access scoped to owned exams | `AnswerService`: `findAllForStaff`, `findByUserIdForStaff`, `findByExamIdForStaff`, `findByIdForStaff`; teacher `create/update/delete` enforce ownership |
| Ownership enforcement on staff writes | `ExamService.requireOwner(actor, examId)` used by both services (shared helper) |

## 4. New files

| File | Purpose |
|---|---|
| `src/main/java/com/examora/dto/AnalyticsDtos.java` | All new analytics DTOs (Section 7) |
| `src/main/java/com/examora/repository/AnalyticsRepository.java` | ~20 named JdbcTemplate queries + row records |
| `src/main/java/com/examora/service/AnalyticsService.java` | Aggregation, flag heuristics, Java median, exam scoping, rounding |
| `src/main/java/com/examora/controller/AnalyticsController.java` | Staff analytics endpoints |
| `src/test/java/com/examora/AnalyticsIntegrationTest.java` | 17 tests (metrics, scoping, empty/zero, legacy rows) |
| `src/test/java/com/examora/AnalyticsSecurityIntegrationTest.java` | 9 tests (role × endpoint matrix) |

## 5. Modified files

| File | Change |
|---|---|
| `src/main/java/com/examora/config/SecurityConfig.java` | New matchers (Section 3) |
| `src/main/java/com/examora/controller/ResultController.java` | Staff scoping on list/get, `userId` required on staff `/results/me`, actor enforced on create/update/delete |
| `src/main/java/com/examora/controller/AnswerController.java` | Staff scoping on list/get/delete with actor |
| `src/main/java/com/examora/controller/StudentAnalyticsController.java` | Added `GET /summary` and `GET /exams` (student-facing) |
| `src/main/java/com/examora/service/ResultService.java` | Staff-scoped methods + `requireOwner` |
| `src/main/java/com/examora/service/AnswerService.java` | Same pattern; teacher create/update/delete ownership |
| `src/main/java/com/examora/service/ExamService.java` | Added `findOwnedExamIds` |
| `src/main/java/com/examora/repository/ResultRepository.java` | `findAllByExamIds`, `findByUserIdInExamIds` |
| `src/main/java/com/examora/repository/AnswerRepository.java` | `findAllByExamIds`, `findByUserIdInExamIds` |
| `src/main/java/com/examora/repository/ExamRepository.java` | `findIdsByOwner` |
| `src/main/resources/schema.sql` | 4 additive indexes (Section 12) |
| `frontend/types/analytics.ts` | New analytics/analytics-related types |
| `frontend/services/analyticsApi.ts` | `examSummaries`, `examDetail`, `studentDrilldown`, `studentSummary`, `studentExams` (wraps `ApiResponse<T>`); `performance()` preserved |

## 6. Endpoints

Staff (`TEACHER`/`ADMIN`):
- `GET /api/analytics/exams` — list of `ExamAnalyticsSummary` for exams owned by the actor
- `GET /api/analytics/exams/{examId}` — `ExamAnalyticsDetail` (owned only; `403` otherwise)
- `GET /api/analytics/students/{studentId}` — `StudentDrilldownAnalytics` for any student

Student:
- `GET /api/student/analytics/summary` — `StudentAnalyticsSummary`
- `GET /api/student/analytics/exams` — list of `StudentExamAnalytics`

## 7. DTOs (`AnalyticsDtos.java`)

`ExamAnalyticsSummary`, `ScoreDistributionBucket`, `OptionDistribution`, `QuestionAnalytics`, `ExamAnalyticsDetail`, `RecentExamPoint`, `PerExamStudentResult`, `ExamAttemptInfo` (attemptNumber, durationSeconds, submittedAt), `StudentDrilldownAnalytics` (studentId, name, completedExams, averagePercentage, highest/lowestScore, trueAccuracy, recentTrend, examHistory), `SubjectPerformanceSummary`, `DifficultyPerformance`, `PracticeDifficultyAnalytics`, `PracticeAnalytics` (incl. `.empty()`), `StudentAnalyticsSummary` (examCount, averagePercentage, highest/lowest, gradedQuestions, correctGradedQuestions, trueAccuracy, examTrend, subjectPerformance, difficultyPerformance, perExamPractices), `StudentExamAnalytics` (result summary + attempt + practice).

## 8. SQL portability

- **JdbcTemplate only**; parameterized placeholders + dynamic `IN` clauses.
- H2-unfriendly constructs avoided: no `percentile_cont`, no `FILTER (WHERE …)` — all conditional aggregation via `SUM(CASE WHEN … THEN 1 ELSE 0 END)`.
- Portability proven by the same code running green under H2 (`MODE=MySQL`) tests and PostgreSQL 18 (Section 15–16).

## 9. Metrics & formulas

| Metric | Definition |
|---|---|
| participants | distinct `user_id` in `results` for the exam |
| submissions | `results` rows for the exam (with `total > 0`) |
| averageScore | `avg(score*100/total)` all submissions, rounded 2dp |
| medianScore | Java median of rounded per-submission percentages (50th percentile); H2 lacks `percentile_cont` |
| highest / lowest | `max / min(score*100/total)` |
| attemptsStarted / attemptsSubmitted | `exam_attempts` count / count `status='SUBMITTED'` |
| completionRate | submitted / started × 100 (null-safe; `0` when no attempts) |
| passRate | **null** — no authoritative pass threshold exists in the schema |
| per-question `eligibleAttempts` | total submitted attempts for the exam minus null-attempt answers (data sanity) |
| `gradedAttempts` | answers with `correct IS NOT NULL` |
| `correct` / `incorrect` | `correct=true` / `correct=false` graded answers |
| `ungradedAnswers` | answers with `correct IS NULL` (autosave/legacy) |
| `unanswered` | eligible − graded (attempted candidates who left it blank) |
| `accuracy` | correct / graded × 100 (null-safe → 0 when nothing graded) |
| `attemptRate` | graded / eligible × 100 |
| optionDistribution | `optionId` + count of selections (intentionally no text / correctAnswer leakage) |
| `trueAccuracy` (drilldown/summary) | graded correct / graded across all of the student's answers |
| practice | sessions total / COMPLETED, answers, correct, accuracy, `accuracyByDifficulty`, `mostRecentActivity` (max `practice_answers.answered_at`; falls back to `practice_sessions.last_activity_at`) |

## 10. Flag heuristics (automatic attention markers)

- `LOW_SAMPLE_SIZE` — 1 ≤ graded < 5
- `UNUSUALLY_LOW_ACCURACY` — accuracy < 30% AND graded ≥ 5
- `UNUSUALLY_HIGH_ACCURACY` — accuracy > 85% AND graded ≥ 5
- `HIGH_UNANSWERED_RATE` — unanswered > 40% of eligible AND eligible ≥ 5

(Verified live: `qv-1` graded=4 → `LOW_SAMPLE_SIZE`; `qv-3` accuracy 33.33 is NOT flagged low-correct because graded=3 < 5 — per spec.)

## 11. Legacy data handling

- `results.exam_id` is nullable; legacy rows (e.g. week-1's 7 results with NULL `exam_id`) are **excluded** from exam metrics (still surfaced in per-student summaries under their result data). Covered by `legacyResultsWithoutExamIdAreExcludedFromExamMetrics`.
- `answers.correct = NULL` (autosave / undocumented legacy rows) stop being treated as wrong: they increment `ungradedAnswers` only.
- Questions without options are excluded from question analytics (no meaningless 0% accuracy).
- Student summary difficulty/subject aggregates only include graded answers and non-null `exam_id` results respectively.

## 12. Schema / index changes (additive only)

Four idempotent indexes appended to `schema.sql` (created on PG startup; verified present):

```
idx_results_exam_id on results (exam_id)
idx_answers_exam_question on answers (exam_id, question_id)
idx_answers_user_exam on answers (user_id, exam_id)
idx_answers_attempt on answers (attempt_id)
```

## 13. Tests

- `AnalyticsIntegrationTest` (17): list summary values; detail (distribution buckets, question metrics, attempt counts, completionRate); drill-down (trend, history, trueAccuracy, durations); practice analytics; empty/zero submissions; zero attempt-rate; questions without options; ungraded answers; legacy NULL-exam rows excluded; median parity.
- `AnalyticsSecurityIntegrationTest` (9): role × endpoint matrix (ANONYMOUS 401, STUDENT 403 on staff analytics, TEACHER/ADMIN 200, staff drill-down of students, non-owned exam 403, per-exam ownership isolation).
- **Backend suite**: `mvn -o test` → **333 tests, 0 failures** (307 pre-existing + 26 new).

## 14. Frontend (Phase 12 — API/types only, no UI, no mock)

- `types/analytics.ts` extended with all Section 7 shapes; `services/analyticsApi.ts` exposes the five methods and reuses `ApiResponse<T>` from `@/types`; base URL already carries `/api`.
- `pnpm exec tsc --noEmit` → clean.
- `pnpm build` (`next build` 16.3.0 / Turbopack) → compiled successfully, 3/3 static pages generated, routes intact.

## 15. Real PostgreSQL verification — setup

PostgreSQL 18.6 at `localhost:5432/examora`, started from the built jar (env from `.env`; `SQL_INIT_MODE=always` re-ran schema.sql, creating the four indexes). The prior P4 backend still held port 8080 and was stopped; the new jar started clean, log free of analytics errors (the single log ERROR was a deliberately-probed non-existent route).

Verification-only seed rows were inserted **additionally** (original P4 rows untouched — week-1 exam, its 3 questions, 11 legacy results, 3 attempts, 6 answers, 7 practice sessions, 11 practice answers preserved):

- 3 new students (`v-student-1..3`, role STUDENT)
- exam `verify-exam-1` owned by `demo-teacher`, subject Algorithms, 3 questions (`qv-1` d2, `qv-2` d3, `qv-3` d2), 4 options each
- 4 results (90, 60, 45, 80 / 100), 5 attempts (4 SUBMITTED + 1 STARTED), 10 graded answers
- 2 COMPLETED practice sessions (demo-student) with 3 practice answers

Final counts: users 14, exams 2, results 11, attempts 8, answers 16, practice_sessions 9, practice_answers 14.

## 16. Real-PG API vs direct-SQL comparison (all match)

| Metric | API | Direct SQL |
|---|---|---|
| participants / submissions | 4 / 4 | 4 / 4 |
| avg / median / high / low | 68.75 / 70.0 / 90 / 45 | 68.75 / (median [45,60,80,90]=70) / 90 / 45 |
| distribution 0-19,20-39,40-59,60-79,80-100 | 0,0,1,1,2 | 0,0,1,1,2 |
| attempts started/submitted, completionRate | 5 / 4, 80.0% | 5 / 4, 80% |
| `qv-1` d2 | graded 4 · corr 3 · acc 75.0 · rate 100 · unans 0 · opt{opt1:1,opt2:3} | identical |
| `qv-2` d3 | graded 3 · corr 2 · acc 66.67 · rate 75 · unans 1 · opt{opt1:1,opt2:2} | identical |
| `qv-3` d2 | graded 3 · corr 1 · acc 33.33 · rate 75 · unans 1 · opts 1/1/1 | identical |
| drilldown `v-student-1` | completedExams 1 · avg 90 · trueAccuracy 100 (3/3) · latest duration 900s | identical |
| drilldown `demo-student` | completedExams 1 · avg 80 · trueAccuracy 100 (2/2) | identical |
| student summary (demo-student) | examCount 1 · avg 80 · graded 2/2 · 100% · subject Algorithms 80 · d2 1/1 · d3 1/1 | identical |
| student exams (demo-student) | attempt #1 · 900s · practice 2 sessions/2 completed · 3q·2c·66.67 · d2 1/1 · d3 2/1 · mostRecent `2026-09-11T04:05:00Z` | identical |

Security matrix on real PG: non-owned exam detail → **403**; student→staff analytics → **403**; teacher→student analytics → **403**; teacher→`/api/student/adaptive/sessions` → **403**; student→same → **200**; anonymous → **401**. (Admin login password unavailable in this environment; ADMIN acceptance rests on the H2 integration matrix + identical `SecurityConfig`.)

## 17. EXPLAIN ANALYZE findings (PostgreSQL 18, current scale)

Every analytics query executes in **≤0.25 ms**:

| Query | Plan | Exec time | Rows |
|---|---|---|---|
| exam aggregation (`results`) | Seq scan on 11-row results + GroupAggregate (planner-correct) | 0.199 ms | 4 |
| question counts (questions ⋈ answers) | Nested Loop Left Join (seq both) | 0.232 ms | 3 |
| option counts | Seq scan answers + group | 0.217 ms | 7 |
| attempt counts | **Index scan** `uq_exam_attempt_number` (leading col exam_id) | 0.135 ms | 5 |
| student graded accuracy | Seq scan answers | 0.137 ms | 2 |
| practice aggregates | **Index scans** `idx_practice_sessions_active` + `idx_practice_answers_session` | 0.175 ms | 2 |
| subject performance | Seq scan results | 0.134 ms | 1 |
| difficulty performance | Seq + Index scan `questions_pkey` | 0.227 ms | 2 |

Note: at 11–16 rows the planner correctly prefers sequential scans; the new `idx_results_exam_id` / `idx_answers_exam_question` / `idx_answers_user_exam` / `idx_answers_attempt` indexes exist for lookup/join selectivity as data grows. All four were auto-created by schema.sql at startup (verified in `pg_indexes`).

## 18. Limitations

- **passRate** is deliberately `null` (no authoritative threshold in schema).
- `averageScore` reflects submissions only; stats columns on `exams` (`participants`, `average_score`) are **not** kept in sync — analytics recomputes and is the source of truth (flagged for consolidation in a later phase).
- Practice analytics only counts persisted sessions (`COMPLETED`-trackable) created by the existing adaptive practice flow.
- Option distribution returns ids/counts only, by design (no text/answer leakage to analytics).
- Drill-down is per-student and staff-scoped; there is no class/cohort rollup yet.
- The old admin demo password was not available for a live ADMIN check (matrix covered in tests).

## 19. P5A dependencies & recommended next phase

**Dependencies introduced by this phase (none blocking):**
- None — analytics is additive and reads the existing schema. The index set is the only schema delta.
- If downstream wants *per-question correctness text*, `answer_value` interplay and `question_options.text` exposure would need a deliberate DTO decision.

**Recommended next phase (P5C — Intelligence Frontend + Scale):**
1. Wire `analyticsApi` into the teacher dashboard (per-exam detail page, score distribution chart, question-intelligence table with flag badges, student drill-down drawer) and the student analytics page (summary cards + per-exam practice/attempt tiles) — no mocks, real API.
2. Backfill/repair `results.exam_id` for legacy rows via a documented P5A-style migration and deprecate the side tables (`participants`, `average_score`) in favor of recomputed analytics.
3. Cohort/class rollups, pass threshold configuration, and CSV export for the corrected teacher view.
4. A scale/latency pass (index usage re-verified at >10k rows) plus a read replica for analytics reads in production topology.