# P5D.3 — Student Improvement & Progress Intelligence

## Status
- **Implemented:** `GET /api/student/progress` (deterministic, backend-only, read-only)
- **Reuses:** P5D.1 `LearningProfileService.build(studentId)` and P5D.2 `Dimension`/rules for difficulty mapping and thresholds
- **Backend tests:** 383 total pass (16 new in `StudentProgressIntegrationTest`)
- **Frontend:** types + API client only (no UI in this phase)
- **Real-PostgreSQL verification:** passed (see §9)
- **Committed:** no (working tree intentionally uncommitted)

## 1. Scope
P5D.3 derives how a student is *changing over time*: overall performance trend, per-exam
progression history, practice-accuracy trend, difficulty-level progression, learning-intelligence
delts (weakness improved/persisted, strength maintained, new weakness, recent decline), and
deterministic milestones. Everything is computed on read from persisted rows.

Hard constraints honored:
- **No AI/LLM** — every value is arithmetic over real rows.
- **No fabricated metrics** — no synthetic topics, skills, or scores.
- **Adaptive-practice selection is untouched.**
- **JWT-scoped** — identity comes only from the principal; zero query parameters accepted;
  anonymous → `401`; teacher/admin → `403`; cross-student access structurally impossible.
- **No answer keys / raw answers** — responses expose aggregated accuracies, counts, and codes.
- **Data-quality honesty** — insufficient data is never relabeled `STABLE`; missing history is
  never treated as zero.

## 2. Endpoint specification

| Attribute | Value |
|---|---|
| Method / path | `GET /api/student/progress` |
| Authentication | Required (JWT). Identity derived only from the principal; accepts zero parameters. |
| Authorization | `StudentAnalyticsController` → `requireStudent`; `SecurityConfig` maps the GET path → `hasRole("STUDENT")`. |
| Anonymous | `401` (entry point) |
| Teacher / Admin | `403` (denied handler) |
| Empty student | `200` with `INSUFFICIENT_DATA` directions, null averages, empty history/changes/milestones, `sufficientForTrend: false` |

## 3. Overall & exam performance trend
Bounded **recent-vs-previous** comparison over *completed real exams*:
- A valid trend requires `>= 4` completed exams (`MIN_COMPLETED_EXAMS_FOR_TREND = 4`).
- **Recent window** = the 2 most recent completed exams; **previous window** = the 2 before those.
- `recentAverage` / `previousAverage` = mean of result percentage (`score / total * 100`).
- `delta` = `recentAverage - previousAverage` (points, may be negative).
- Direction: `IMPROVING` if `delta > +2.0`; `DECLINING` if `delta < -2.0`; `STABLE` otherwise
  (`MEANINGFUL_DELTA = 2.0`).
- **Insufficient history is never converted to `STABLE`** — fewer than 4 exams yields
  `INSUFFICIENT_DATA` with null averages/delta (empty `history` still lists each submitted exam).
- The canonical direction reported in both `overall` and `examProgress` is the P5D.1
  `profile.trend().direction()`; `recentAverage`/`previousAverage`/`delta` are recomputed here,
  so the two blocks are always consistent.

**Exam history** (`examProgress.history`, bounded to the newest `HISTORY_LIMIT = 10`):
- Chronological ascending order (`results.date asc`, then submission order).
- Each point: `examId`, `examTitle`, `subject`, `date`, `score`, `total`, `percentage`,
  `accuracy` (% of graded answers correct for that exam, from the latest attempt), and
  `attemptNumber` (highest submitted attempt number). Excludes only another student's info and
  answer keys. `attemptNumber` and `accuracy` are `null` when absent.

## 4. Practice progress
Comparison of recent practice vs earlier practice, over **submitted real practice answers** only
(never exam answers):
- Bounded sample: the newest `PRACTICE_SPLIT_LIMIT = 40` answers, ordered by
  `answered_at desc, id desc`, split at the midpoint into **recent** (newest half) and
  **previous** (older half).
- Each half needs `MIN_PRACTICE_OBSERVATIONS_PER_WINDOW = 5` answers or the whole block is
  `INSUFFICIENT_DATA` (no accuracy is reported).
- `recentAccuracy` / `previousAccuracy` = % correct per half; `delta` in points; direction uses
  the same ±2.0 threshold.
- Empty sessions (`answered_count = 0`) never count as performance; completed practice sessions
  are only used for the `practiceObservationCount` data-quality total and the
  `FIRST_PRACTICE_SESSION` milestone.

## 5. Difficulty progression (EASY / MEDIUM / HARD)
- Difficulty mapped with the P5D.1 rule from `questions.difficulty` (1–5):
  `EASY` = 1–2, `MEDIUM` = 3, `HARD` = 4–5.
- Windows use the same recent-2 / previous-2 completed exams as the overall trend.
- Per bucket, per window: `recentObservations`/`previousObservations` = graded answers in those
  exams, and accuracy = % correct.
- A bucket is `INSUFFICIENT_DATA` unless **both** windows have `>= 3` observations
  (`MIN_DIFFICULTY_OBSERVATIONS_PER_WINDOW = 3`); aggregations below that store only the counts.
- Direction from `delta` with the ±2.0 threshold.

## 6. Learning-intelligence changes
Prev-window (previous 2 exams) vs recent-window (recent 2 exams). A change is emitted only when
**both** windows meet their minimums (exam `>= 3` answers per window; practice `>= 5` answers per
window; difficulty `>= 3` answers per window per bucket) and the classification rules match:

| Type | Rule |
|---|---|
| `NEW_WEAKNESS` | prev above weakness threshold, now `<= 60` |
| `WEAKNESS_IMPROVED` | prev `<= 60`, now above it, or still weak but gained `>= 10` points |
| `WEAKNESS_PERSISTED` | stayed `<= 60` without a `>= 10` point gain |
| `STRENGTH_MAINTAINED` | both windows `>= 80` |
| `RECENT_DECLINE` | recent window fell `>= 10` points and is neither strength nor weakness |

Rules are checked in this order, so a `WEAKNESS_IMPROVED` that is still weak is never also
`WEAKNESS_PERSISTED`, and `STRENGTH_MAINTAINED` wins over a strong-but-slightly-declining window.
`detail` is a fixed-fragment sentence. Output is sorted by a fixed change-type order, then
dimension name ascending (deterministic). A prior weakness that improved is emitted as
`WEAKNESS_IMPROVED` (not silently dropped). If data cannot support a classification the change is
**omitted** — no change is ever classified as `STABLE` on insufficient data.

## 7. Milestones
Compute-on-read, deterministic, each with `code`, `occurredAt`, and `metric`:

| Code | Rule | Metric |
|---|---|---|
| `FIRST_COMPLETED_EXAM` | earliest completed exam | percentage |
| `IMPROVED_EXAM_PERFORMANCE` | each time a completed exam beats the running best | percentage |
| `FIRST_PRACTICE_SESSION` | first completed practice session with `answered_count > 0` | % correct |
| `FIRST_HARD_QUESTION_SUCCESS` | first correct answer to a hard (4–5) question | running hard accuracy |
| `IMPROVED_HARD_DIFFICULTY` | each time running hard accuracy beats its previous best | running hard accuracy |

`occurredAt` is the exam date for exam milestones and the session completion timestamp for the
practice milestone. Milestones are emitted in chronological order (exam date asc, then timestamp
asc); same-instant entries are tie-broken by code. On insufficient history no milestone is
invented.

## 8. Response model
```json
{
  "success": true,
  "data": {
    "overall": { "direction": "IMPROVING", "recentAverage": 80.0, "previousAverage": 40.0, "delta": 40.0 },
    "examProgress": {
      "direction": "IMPROVING", "recentAverage": 80.0, "previousAverage": 40.0, "delta": 40.0,
      "history": [ { "examId": "e1", "examTitle": "Midterm", "subject": "Math", "date": "2026-01-01",
                     "score": 30, "total": 100, "percentage": 30.0, "accuracy": 50.0, "attemptNumber": 1 } ]
    },
    "practiceProgress": {
      "direction": "IMPROVING", "recentAccuracy": 80.0, "previousAccuracy": 20.0, "delta": 60.0,
      "recentQuestions": 5, "previousQuestions": 5
    },
    "difficultyProgress": {
      "hard": { "direction": "IMPROVING", "recentAccuracy": 75.0, "previousAccuracy": 0.0,
                "delta": 75.0, "recentObservations": 4, "previousObservations": 4 }
    },
    "intelligenceChanges": [
      { "type": "WEAKNESS_IMPROVED", "dimension": "HARD_DIFFICULTY",
        "previousAccuracy": 0.0, "currentAccuracy": 75.0,
        "detail": "HARD_DIFFICULTY improved from 0.0% to 75.0% and is no longer below the 60% weakness threshold." }
    ],
    "milestones": [
      { "code": "FIRST_COMPLETED_EXAM", "occurredAt": "2026-01-01", "metric": 30.0 }
    ],
    "dataQuality": { "sufficientForTrend": true, "completedExamCount": 4, "practiceObservationCount": 10 }
  }
}
```
- `difficultyProgress` always contains all three buckets (`easy`, `medium`, `hard`).
- `dataQuality.completedExamCount` counts distinct completed (submitted) real exams with results;
  `practiceObservationCount` counts practice answers (the `questionsAnswered` total from P5D.1).

## 9. Implementation
- `dto/StudentProgressDtos.java` — records `StudentProgress`, `ProgressTrend`, `ExamProgress`,
  `ExamHistoryPoint`, `PracticeProgress`, `DifficultyProgress`, `DifficultyProgression`,
  `IntelligenceChange`, `Milestone`, `ProgressDataQuality`.
- `service/StudentProgressService.java` — the complete deterministic engine; consumes
  `LearningProfileService.build(studentId)` for the canonical trend/direction and practice totals;
  documents every constant and invariant.
- `repository/AnalyticsRepository.java` — four new **bounded** single-query reads
  (`findPerExamGradedAccuracy` for requests' exam ids, `findDifficultyCountsByExam`,
  `findRecentPracticeAnswerRows` with `LIMIT 40`, `findFirstCompletedPracticeSession`). No N+1
  iteration, no materialized views, no caching; each endpoint run is a fixed small number of
  aggregate SQL queries.
- `controller/StudentAnalyticsController.java` — `GET /progress` + injected service.
- `config/SecurityConfig.java` — `STUDENT` role matcher for the new path.
- `src/test/java/com/examora/StudentProgressIntegrationTest.java` — 16 H2 tests.

## 10. Verification
Integration tests (H2, 16): empty student full insufficient contract; single completed exam →
`INSUFFICIENT_DATA` trend + `FIRST_COMPLETED_EXAM` milestone; four exams → correct
recent/previous/delta (75/55/20); improving history (70/45/25); declining history (55/75/−20)
with a `RECENT_DECLINE` change; stable history (0 delta, no changes); practice improvement
(100/0/+100) and decline (0/100/−100); difficulty improvement (HARD 25→75, 8 obs/window) and
insufficient difficulty (2 obs/window, no accuracy); 12-exam chronological history bounded to 10
with ascending dates and attempt numbers; milestone set (all five codes, exact occurredAt and
metric); anonymous `401`; teacher and admin `403`; no cross-student leakage with `?studentId=`
ignored and byte-identical other-with/without-param responses; repeated calls byte-identical.

Real PostgreSQL (DB `examora`) live checks:

| Case | Result |
|---|---|
| Anonymous | `401` |
| Teacher token | `403` |
| demo-student (1 leftover completed exam, no practice before checks) | `200` · `INSUFFICIENT_DATA` trend, `sufficientForTrend: false`, `FIRST_COMPLETED_EXAM` + `FIRST_PRACTICE_SESSION` milestones |
| demo-student seeded + 4 exams, scores 30/50/70/90, hard answers 0/4→3/4 correct, easy 4/4→4/4 | overall & exam `IMPROVING` (80.0 / 40.0 / +40.0); history chronological with correct accuracy per exam; HARD `IMPROVING` (0→75, 4 obs/window); EASY `STABLE` (100→100, 4 obs/window); `WEAKNESS_IMPROVED` for `EXAM_PERFORMANCE` and `HARD_DIFFICULTY`, `STRENGTH_MAINTAINED` for `EASY_DIFFICULTY`; milestones incl. `FIRST_HARD_QUESTION_SUCCESS` and `IMPROVED_HARD_DIFFICULTY`; `sufficientForTrend: true` |

Seeded verification rows were removed afterward; the older `verify-exam-1` verification leftovers
were removed too; the DB is back to its pre-check state.

Frontend: `types/student-progress.ts` + `services/studentProgressApi.ts` added (shared axios
instance, auto-Bearer, `ApiResponse<StudentProgress>`). `tsc --noEmit` and
`pnpm exec next build` both green. No UI is added in this phase.

## 11. Determinism & consistency
- Every value is derived on read from persisted rows; nothing is cached or randomized.
- Percentages normalized to 2 decimals; the ±2.0 meaningful-delta threshold is enforced identically
  across exam, practice, and difficulty blocks.
- Precise direction strings (`IMPROVING` / `DECLINING` / `STABLE` / `INSUFFICIENT_DATA`) are never
  downgraded when data is insufficient.
- Ordering everywhere is deterministic (chronological history; fixed change-type order then
  dimension; chronological milestones then code tie-break).
- The engine is pure: given identical DB state it returns a byte-identical payload.

## 12. Limitations
- **No multi-dimensional regression** — changes are reported per dimension; cross-dimension
  causality (e.g. "better practice caused better exams") is not asserted.
- Difficulty windows are bounded by the recent-versus-previous exam pair; a bucket with ample data
  overall but too little inside the two windows stays `INSUFFICIENT_DATA`.
- Practice trend uses a split of the newest 40 answers, so a student with very heavy practice only
  sees the tail; older history is intentionally not compared.
- `occurredAt` for the practice milestone is the stored session timestamp rendered in UTC ISO;
  exam milestones use the exam date string as stored.

## Future extensions
- Rolling-window accuracy in place of two-window dichotomy.
- Per-topic improvement once a normalized topic taxonomy exists (see P5D.2 limitations).
- Milestone newsletter / fetch-on-read caching strategy (currently intentionally cache-free).