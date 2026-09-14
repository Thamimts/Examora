# P5D.1 — Student Learning Intelligence Profile

## Status
- **Implemented:** `GET /api/student/learning-profile` (server-authoritative, read-only)
- **Backend tests:** 353 total pass (14 new in `LearningProfileIntegrationTest`)
- **Frontend:** types + API client only (no UI in this phase)
- **Real-PostgreSQL verification:** passed (see matrix below)
- **Committed:** no (working tree intentionally uncommitted)

## 1. Scope
A single server-authoritative endpoint that computes a student's learning profile from
**real persisted activity** — completed exams, graded answers, submitted attempts, adaptive
practice, and AI practice. Every metric is derived on read; nothing is cached, mocked, or
taken from the client.

Out of scope (future work): topic/subject intelligence, question-level mastery, per-exam
breakdown, study-gram models.

## 2. Endpoint specification

| Attribute | Value |
|---|---|
| Method / path | `GET /api/student/learning-profile` |
| Authentication | Required (JWT). Identity is derived **only** from the principal; the endpoint accepts no `userId`/`email`/`studentId` parameter. |
| Authorization | `StudentAnalyticsController` → `LearningProfileService.requireStudent`; `SecurityConfig` maps `GET /api/student/learning-profile` → `hasRole("STUDENT")`. |
| Anonymous | `401` (entry point) |
| Teacher / Admin | `403` (denied handler) |
| Empty student | `200` with a deterministic zero/null profile (see §4) |

## 3. Response model
```json
{
  "success": true,
  "data": {
    "overall": {
      "averageScore": 80.0, "highestScore": 80, "lowestScore": 80,
      "completedExams": 1, "submittedAttempts": 1
    },
    "examPerformance": {
      "averageAccuracy": 100.0, "recentAccuracy": 100.0, "improvementDelta": null
    },
    "practice": {
      "sessionsCompleted": 2, "questionsAnswered": 3, "correctAnswers": 2,
      "accuracy": 66.67, "recentAccuracy": 66.67
    },
    "aiPractice": {
      "sessionsCompleted": 0, "questionsAnswered": 0, "correctAnswers": 0, "accuracy": null
    },
    "difficulty": {
      "exam":    { "easy": {...}, "medium": {...}, "hard": {...} },
      "practice":{ "easy": {...}, "medium": {...}, "hard": {...} }
    },
    "trend": { "direction": "INSUFFICIENT_DATA", "delta": null },
    "learningSignals": { "strengths": [], "weaknesses": ["INSUFFICIENT_DATA"], "consistency": "INSUFFICIENT_DATA" }
  }
}
```
Every `DifficultyLevel` is `{ attempted, correct, accuracy }` with `accuracy = null` when `attempted == 0`.
Percentages are rounded to 2 decimal places.

## 4. Empty-state contract
For a student with no activity the endpoint returns a deterministic profile: all scores,
accuracies, and deltas are `null`; counts are `0`; `trend.direction = "INSUFFICIENT_DATA"`;
`learningSignals.weaknesses = ["INSUFFICIENT_DATA"]`; `consistency = "INSUFFICIENT_DATA"`.

## 5. Data sources
| Profile block | Source tables/columns |
|---|---|
| overall.completedExams | `results` where `user_id = ? and total > 0` |
| overall.averageScore / highest / lowest | `results.score * 100.0 / results.total` |
| overall.submittedAttempts | `exam_attempts` where `student_id = ? and status = 'SUBMITTED'` |
| examPerformance.averageAccuracy | graded `answers` (`correct is not null`) |
| examPerformance.recentAccuracy | graded `answers` for the 2 most recent exams (chronological `results`) |
| examPerformance.improvementDelta | see §8 |
| difficulty.exam | graded `answers` joined to `questions.difficulty` (1-5) |
| difficulty.practice | `practice_answers.difficulty` (1-5) joined to `practice_sessions` |
| practice | `practice_sessions` (COMPLETED count) + `practice_answers` |
| practice.recentAccuracy | most recent `20` practice answers by `answered_at` |
| aiPractice | `ai_practice_sessions` (COMPLETED) + `ai_practice_answers` |
| trend | chronological `results` |
| learningSignals | derived from the above (§9, §10) |

The AI practice system persists `EASY/MEDIUM/HARD` strings whose semantics differ from the
1-5 exam model; it is therefore reported in a separate `aiPractice` block and mixed into
**neither** `difficulty` nor the signal heuristics.

## 6. SQL approach
All aggregation happens in `AnalyticsRepository` (parameterized `JdbcTemplate` queries reused
from the P5B analytics work where possible):

- `findGradedAccuracy(studentId)` / `findGradedAccuracyForExams(studentId, examIds)` — count graded + correct answers.
- `findDifficultyPerformance(studentId)` — grouped by `questions.difficulty`.
- `findPracticeDifficulty(studentId, null)` — grouped by `practice_answers.difficulty`.
- `countCompletedPracticeSessions(studentId)`, `findPracticeAnswerAggregates(studentId, null)`.
- `findRecentPracticeAccuracy(studentId, 20)` — capped subquery ordered by `answered_at desc limit ?`.
- `countSubmittedAttempts(studentId)` (new), `countCompletedAiPracticeSessions(studentId)` (new),
  `findAiPracticeAnswerAggregates(studentId)` (new).
- Result ordering uses `ResultRepository.findByUserId` sorted chronologically by
  `results.date` (a `"YYYY-MM-DD"` string, lexicographically sortable) then `exam_id` as a
  stable tie-breaker, filtered to `total > 0`.

## 7. Formulas
- Percentage for a result: `score * 100.0 / total` (guarded for `total <= 0`).
- Accuracy: `correct * 100.0 / attempted` (needs `attempted > 0`, else `null`).
- All values rounded to 2 decimals: `Math.round(x * 100.0) / 100.0`.
- Consistency: population standard deviation of result percentages.

## 8. Trend algorithm
- Constants: `RECENT_WINDOW = 2`, `MIN_COMPLETED_EXAMS_FOR_TREND = 4`, `MEANINGFUL_DELTA = 2.0` pp.
- Percentages are sorted chronologically.
- `recentAverage = mean(last 2)`, `previousAverage = mean(2 before that)`.
- `delta = recentAverage - previousAverage` (rounded).
- `direction`: `IMPROVING` if `delta >= +2.0`, `DECLINING` if `delta <= -2.0`, else `STABLE`.
- Fewer than 4 exams → `trend.direction = "INSUFFICIENT_DATA"`, `delta = null`.
- `examPerformance.improvementDelta` uses the identical window math; `null` below 4 exams.

## 9. Difficulty mapping (1-5 → bucket)
| Bucket | Difficulty values |
|---|---|
| EASY | 1, 2 |
| MEDIUM | 3 |
| HARD | 4, 5 |

Applied identically to `difficulty.exam` and `difficulty.practice`. Buckets with no data report
`{ attempted: 0, correct: 0, accuracy: null }`.

## 10. Learning signals
| Signal | Rule |
|---|---|
| HIGH_EASY_ACCURACY (strength) | exam easy, `attempted >= 5`, `accuracy >= 80` |
| LOW_MEDIUM_ACCURACY (weakness) | exam medium, `attempted >= 5`, `accuracy <= 60` |
| LOW_HARD_ACCURACY (weakness) | exam hard, `attempted >= 5`, `accuracy <= 60` |
| STRONG_PRACTICE_PERFORMANCE (strength) | practice answers `>= 5` and `accuracy >= 80` |
| WEAK_PRACTICE_PERFORMANCE (weakness) | practice answers `>= 5` and `accuracy <= 60` |
| RECENT_PERFORMANCE_IMPROVING (strength) | trend direction = IMPROVING |
| RECENT_PERFORMANCE_DECLINING (weakness) | trend direction = DECLINING |
| INSUFFICIENT_DATA (weakness) | no results, no practice answers, and no graded exam answers |

Consistency: `STABLE` if ≥ 3 exams and stddev `<= 10` pp; `VARIABLE` if ≥ 3 exams and stddev
`> 10` pp; `INSUFFICIENT_DATA` otherwise.

## 11. Security model
- One endpoint, **zero** query/path parameters — the profile is scoped exclusively to the
  authenticated JWT principal (`(User) authentication.getPrincipal()` → `student.id()`), so
  cross-student access is structurally impossible (no IDOR surface).
- `SecurityConfig`: `hasRole("STUDENT")` matcher before the `authenticated()` fallback.
- Defense in depth: `requireStudent(User)` re-checks role and throws `403` if not a student.
- Anonymous → `401`; teacher/admin → `403` (verified live).

## 12. Verification
Integration tests (`LearningProfileIntegrationTest`, H2, 14 tests):
own profile, anonymous 401, two students differ, empty-state contract, exam metrics match DB,
practice metrics match DB, difficulty metrics match DB (bucket counts + correct), trend matches
seeded history (IMPROVING delta 20), insufficient history → INSUFFICIENT_DATA, role isolation
(teacher/admin 403), no cross-student leakage, submitted attempts counted, volatile consistency.

Real PostgreSQL (`POSTGRES 18.6`, DB `examora`) live checks:

| Case | Result |
|---|---|
| Anonymous | `401` |
| Teacher token | `403` |
| demo-student (`student@examora.local`) | avg 80 · 1 exam · 1 submitted · practice 2/2 sessions 3q/2c 66.67 · exam easy 1/1 medium 1/1 · practice easy 1/1 medium 2/1 |
| v-student-1 (90/100) | avg 90 · exam easy 2/2 medium 1/1 · no practice |
| v-student-2 (60/100) | avg 60 · exam easy 0/2 medium 0/1 (0% accuracy) |
| v-student-3 (45/100, EXPIRED attempt #2) | avg 45 · submittedAttempts 1 (EXPIRED excluded) · easy 1/2 (50%) |
| Empty student (`verify1789128106@test.local`) | all zeros/nulls · INSUFFICIENT_DATA trend + weaknesses |

Frontend: `types/learning-profile.ts` + `services/learningProfileApi.ts` added and wired to the
shared axios instance (auto-Bearer). `tsc --noEmit` and `pnpm exec next build` both green.
No UI is added in this phase.

## Future extensions
- Topic/subject-level intelligence from `results.subject` and question text.
- Question-level mastery and per-exam trend lines.
- AI-practice difficulty aligned to the 1-5 model once one canonical model exists.
- Optional `?subject=` scoped profile.