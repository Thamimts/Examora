# P5D.4 — Personalized Practice Recommendation Engine

## Status
- **Implemented:** `GET /api/student/recommendations` (deterministic, backend-only, read-only)
- **Reuses:** P5D.1 `LearningProfileService` (difficulty mapping, practice totals), P5D.2
  `LearningIntelligenceService` (strengths, weaknesses, priorities, data quality), P5D.3
  `StudentProgressService` (trend direction, difficulty progression)
- **Backend tests:** 398 total pass (15 new in `StudentRecommendationIntegrationTest`)
- **Frontend:** types + API client only (no UI in this phase)
- **Real-PostgreSQL verification:** passed (see §10)
- **Committed:** no (working tree intentionally uncommitted)

## 1. Scope
P5D.4 turns the P5D.1/2/3 analytics into a small, priority-ordered set of *practice actions* for
the authenticated student. Instead of saying "you are weak at hard questions" it says
"practice hard questions now (10 targets) — only 17% correct recently". The engine is fully
deterministic and decision-oriented:

- One **decision priority tier** per recommendation (1 = most urgent … 7 = nothing to do).
- A human-understandable `reasonCode` for every choice.
- A `targetQuestionCount` that scales with priority (HIGH=10, MEDIUM=8, LOW=5).
- A repetition **cooldown (72h)** that suppresses a *repeat* of an equivalent action the student
  just completed a practice session for, unless the underlying weakness is persistent.

Hard constraints honored (same as P5D.2/P5D.3):
- **No AI/LLM** — AI practice is never a decision input; every value is arithmetic over real rows.
- **No fabricated topics/skills** — no topic taxonomy, no invented performance gaps.
- **Adaptive question selection is untouched** (`AdaptiveSelectionService`, theta, practice
  session creation paths are not modified).
- **No side effects** — the endpoint only reads; requesting recommendations never creates a
  practice session and never writes state.
- **JWT-scoped** — identity comes only from the principal; zero parameters accepted; anonymous →
  `401`; teacher/admin → `403`; cross-student reads structurally impossible.
- **No answer keys / raw answers** — only aggregated accuracies, counts, and codes.
- **Reuse over re-computation** — the profile, intelligence, and progress are computed **once**
  per request and passed through, never rebuilt twice.

## 2. Endpoint specification

| Attribute | Value |
|---|---|
| Method / path | `GET /api/student/recommendations` |
| Authentication | Required (JWT). Identity derived only from the principal; accepts zero parameters. |
| Authorization | `StudentAnalyticsController` → `requireStudent`; `SecurityConfig` maps the GET path → `hasRole("STUDENT")`. |
| Anonymous | `401` (entry point) |
| Teacher / Admin | `403` (denied handler) |
| Empty student | `200` with a single `BUILD_BASELINE` recommendation (see §5) |
| No parameters | any query string (e.g. `?studentId=...`) is ignored; the request is served purely from the JWT |

## 3. Recommendation model

Each recommendation is:

```json
{
  "code": "PRACTICE_HARD_QUESTIONS",
  "priority": "HIGH",
  "action": "PRACTICE",
  "dimension": "HARD_DIFFICULTY",
  "difficulty": "HARD",
  "reasonCode": "LOW_HARD_ACCURACY",
  "confidence": "LOW_CONFIDENCE",
  "targetQuestionCount": 10,
  "supportingMetrics": {
    "accuracy": 16.67,
    "observations": 6,
    "recentAccuracy": null,
    "previousAccuracy": null,
    "recentlyPracticed": false
  }
}
```

| Field | Meaning |
|---|---|
| `code` | the concrete action to take |
| `priority` | `HIGH` / `MEDIUM` / `LOW` (drives `targetQuestionCount`) |
| `action` | `PRACTICE` / `REVIEW` / `MAINTENANCE` / `BASELINE` |
| `dimension` | P5D.2 `Dimension` this recommendation targets; `null` for baseline |
| `difficulty` | `EASY` / `MEDIUM` / `HARD` when the target is a difficulty bucket; otherwise `null` |
| `reasonCode` | fixed machine-readable reason (see §6) |
| `confidence` | P5D.2 confidence band of the evidence |
| `targetQuestionCount` | suggested question volume (HIGH=10, MEDIUM=8, LOW=5; baseline=5) |
| `supportingMetrics` | evidence: `accuracy`, `observations`, recent/previous accuracy, `recentlyPracticed` |

`summary` = `{ primaryRecommendation, recommendationCount, dataSufficient }`; `dataSufficient` is
the P5D.2 `dataQuality.sufficientData()` value. `generatedAt` is the request timestamp.

## 4. Priority tiers (decision order)

| Tier | Meaning | Priority | Example |
|---|---|---|---|
| 1 | **Severe weakness** (P5D.2 `severity == HIGH`) | HIGH | hard accuracy 16.7% |
| 2 | **High-priority weakness** (P5D.2 priority `HIGH` but not severe) | HIGH | medium accuracy 40% |
| 3 | **Recent decline** — overall trend `DECLINING` | HIGH if |delta| ≥ 10, else MEDIUM | 50→87.5 recent → review |
| 4 | **Persistent / plain weakness** (no severe rating, no high priority) | MEDIUM | medium accuracy 50% |
| 5 | **Improvement opportunity** (60 < accuracy < 80, ≥ 5 obs, not recently practiced) | LOW | hard accuracy 66.7% |
| 6 | **Maintenance** (strength ≥ 80 accuracy) | LOW | easy accuracy 100% |
| 7 | **No recommendation / insufficient data** → `BUILD_BASELINE` | LOW | new or extremely sparse student |

Sorting is stable and total: `tier` asc, priority-weight desc (HIGH=3), `code` asc, `dimension`
asc, `reasonCode` asc. Identical inputs therefore always produce an identical ordered list.

## 5. `BUILD_BASELINE`
Emitted when no candidate survives the tiers above (typically a new student or one with too few
observations). It is deliberately conservative:
- `code=BUILD_BASELINE`, `action=BASELINE`, `priority=LOW`.
- `reasonCode=INSUFFICIENT_DATA`, `confidence=INSUFFICIENT_DATA`, `targetQuestionCount=5`.
- `supportingMetrics.observations` = the P5D.2 observation count (0 for a fresh account).
No weakness is fabricated when there are fewer than `MIN_OBSERVATIONS = 5` graded observations.

## 6. Code & reason mapping
Reuse rule: an evaluated **weakness/strength** (P5D.2) is only mapped to a recommendation if its
dimension is actionable in P5D.4.

| Source signal | Recommendation code | reasonCode |
|---|---|---|
| `EASY_DIFFICULTY` weakness | `PRACTICE_EASY_QUESTIONS` | `LOW_EASY_ACCURACY` |
| `MEDIUM_DIFFICULTY` weakness | `PRACTICE_MEDIUM_QUESTIONS` | `LOW_MEDIUM_ACCURACY` |
| `HARD_DIFFICULTY` weakness | `PRACTICE_HARD_QUESTIONS` | `LOW_HARD_ACCURACY` |
| `EXAM_PERFORMANCE` weakness | `REVIEW_RECENT_EXAMS` | `LOW_EXAM_ACCURACY` |
| overall trend `DECLINING` | `REVIEW_RECENT_EXAMS` | `RECENT_DECLINE` |
| `PRACTICE_PERFORMANCE` weakness | `INCREASE_PRACTICE` | `LOW_PRACTICE_ACCURACY` |
| strength (any dimension) | `MAINTAIN_STRENGTH` | `STRENGTH_MAINTAINED` |
| 60 < accuracy < 80 with ≥ 5 obs | `PRACTICE_*_QUESTIONS` | `IMPROVEMENT_OPPORTUNITY` |
| no candidates | `BUILD_BASELINE` | `INSUFFICIENT_DATA` |

A `RECENT_PERFORMANCE` weakness or a `CONSISTENCY` signal is *consumed* (it can raise other
recommendations – e.g. `RECENT_DECLINE`) but is never mapped to a standalone action, since
"initiate practice because you are inconsistent" is not directly actionable here.

## 7. Difficulty & thresholds (reused, not re-derived)
- `questions.difficulty` 1–5 → `EASY` = 1–2, `MEDIUM` = 3, `HARD` = 4–5.
- Weakness ≤ 60% accuracy, strength ≥ 80%, `MIN_OBSERVATIONS = 5`.
- Confidence bands from P5D.2: `< 10` → `LOW_CONFIDENCE`, `10–19` → `MEDIUM_CONFIDENCE`,
  `≥ 20` → `HIGH_CONFIDENCE`.
- `confidenceForExams` for the decline reviewer uses completed-exam counts with the same bands.
- Supporting `recentAccuracy` / `previousAccuracy` on difficulty recommendations come from the
  P5D.3 difficulty progression (recent-2 vs previous-2 completed exams per bucket); when the
  trend windows are insufficient they are `null`, never zero.

## 8. Repetition cooldown (72h), compute-on-read
Each request computes, in **one bounded SQL query**, the last completed-practice time **per
difficulty bucket** for the student:

```sql
select pa.difficulty, max(ps.completed_at) last_completed_at
  from practice_sessions ps
  join practice_answers pa on pa.session_id = ps.id
 where ps.student_id = :id and ps.status = 'COMPLETED'
 group by pa.difficulty
```

- `difficulty` 1–2 → `easy`, 3 → `medium`, 4–5 → `hard`; ≤ 5 rows returned.
- A difficulty bucket is **recently practiced** when its last completion is within
  `REPETITION_COOLDOWN = 72h`.
- **Weakness persists:** a tier-1/2/4 weakness is *kept* even when recently practiced, and is
  flagged `recentlyPracticed: true` so the client can show "you practiced this recently".
- **Improvement/maintenance are suppressed** when the bucket was recently practiced — there is no
  point re-aiming at a bucket the student just worked.
- The cooldown never suppresses `RECENT_DECLINE` or the exam-level review: those are not
  difficulty-bucket actions.
- Nothing about practice is mutated by calling the endpoint; the "cooldown" is purely a function
  of rows that were already persisted by the practice flow.

## 9. Response model
```json
{
  "success": true,
  "data": {
    "recommendations": [
      {
        "code": "PRACTICE_HARD_QUESTIONS",
        "priority": "HIGH",
        "action": "PRACTICE",
        "dimension": "HARD_DIFFICULTY",
        "difficulty": "HARD",
        "reasonCode": "LOW_HARD_ACCURACY",
        "confidence": "LOW_CONFIDENCE",
        "targetQuestionCount": 10,
        "supportingMetrics": { "accuracy": 16.67, "observations": 6,
                               "recentAccuracy": null, "previousAccuracy": null,
                               "recentlyPracticed": false }
      }
    ],
    "summary": { "primaryRecommendation": "PRACTICE_HARD_QUESTIONS",
                 "recommendationCount": 1, "dataSufficient": true },
    "generatedAt": "2026-09-14T02:06:32Z"
  }
}
```

## 10. Verification
Integration tests (H2, 15) in `StudentRecommendationIntegrationTest`:
new student → single `BUILD_BASELINE` (full contract, `dataSufficient=false`); strong EASY + weak
HARD → `PRACTICE_HARD_QUESTIONS` ordered first with correct evidence; medium weakness → MEDIUM
code; severe (16.7%) weakness → HIGH priority, `LOW_HARD_ACCURACY`, target 10; recent decline
(90,85,60,40) → `REVIEW_RECENT_EXAMS` HIGH, `RECENT_DECLINE`, recent 50.0 / previous 87.5; recent
improvement → only `MAINTAIN_STRENGTH`, no fabricated weakness; 3 observations → baseline, no
invention; persistent weakness after a recent practice session → still recommended with
`recentlyPracticed=true`; deterministic 3-tier ordering; exact target counts 10/8/5; medium
improvement opportunity suppressed after a recent medium practice while strengths remain;
anonymous `401`; teacher and admin `403`; no cross-student leakage including `?studentId=` ignored
with identical (recommendations+summary) payloads; repeated requests deterministically identical.

Full suite: **398 tests, 0 failures** (was 383).

Real PostgreSQL (DB `examora`) live checks (backend built fresh, started against the real DB):

| Case | Result |
|---|---|
| Anonymous | `401` |
| Teacher token | `403` |
| Fresh student, no rows | `200` · `BUILD_BASELINE` (LOW, `INSUFFICIENT_DATA`, 0 obs, `dataSufficient=false`); `?studentId=demo-student` ignored, identical |
| Seeded strong EASY (6/6) + weak HARD (1/6), score 40 | `PRACTICE_HARD_QUESTIONS` HIGH/10 first (16.67%, 6 obs) → `REVIEW_RECENT_EXAMS` MEDIUM/8 (`LOW_EXAM_ACCURACY`) → `MAINTAIN_STRENGTH` EASY |
| Added 4 more results 90/85/60/40 | `REVIEW_RECENT_EXAMS` HIGH/10, `RECENT_DECLINE`, recent 50.0 / prev 87.5, delta flagged; hard weakness still first |
| Second student, 4 rising exams (70,70,72,75), easy 100% + medium 75% (8 obs/exam) | no false weakness/decline; `PRACTICE_MEDIUM_QUESTIONS` LOW/5 `IMPROVEMENT_OPPORTUNITY` + `MAINTAIN_STRENGTH` (EASY, CONSISTENCY) |
| Same student after a recent COMPLETED medium practice session | `PRACTICE_MEDIUM_QUESTIONS` suppressed (cooldown); `MAINTAIN_STRENGTH` remain; `dataSufficient=true` |
| First student after a recent COMPLETED hard practice session | persistent weakness *kept* — `PRACTICE_HARD_QUESTIONS` present with `recentlyPracticed=true` |
| Repeated calls | `recommendations` + `summary` byte-identical (only `generatedAt` differs) |

All `p5d4-*` seeded rows (practice answers/sessions, answers, results, attempts, options,
questions, exams, users) were removed afterward; the DB is back to its pre-check state and the
backend process was stopped.

Frontend: `types/student-recommendation.ts` + `services/studentRecommendationApi.ts` added
(shared axios instance, auto-Bearer, `ApiResponse<StudentRecommendations>`). `tsc --noEmit` and
`pnpm exec next build` both green. No UI is added in this phase.

## 11. Implementation
- `dto/StudentRecommendationDtos.java` — `StudentRecommendations`, `Recommendation`,
  `RecommendationSummary`, `SupportingMetrics`; enums `RecommendationCode`, `Action`.
- `service/StudentRecommendationService.java` — the deterministic engine. Builds the P5D.1
  profile **once**, then hands it to P5D.2 intelligence and P5D.3 progress so no profile is
  rebuilt; consumes weaknesses/strengths/priorities/data-quality + progress trend/`difficultyProgress`
  + the cooldown map; emits prioritized candidates; falls back to `BUILD_BASELINE`.
- `repository/AnalyticsRepository.java` — one new **bounded** query
  (`findLastPracticeCompletionByDifficulty`, `LIMIT`-safe group-by → ≤ 5 rows) for the cooldown.
- `service/LearningIntelligenceService.java` / `service/StudentProgressService.java` — new
  public `build(LearningProfile)` overloads so the engine can pass an already-built profile
  (the existing `build(String)` delegates to them).
- `controller/StudentAnalyticsController.java` — `GET /recommendations` + injected service.
- `config/SecurityConfig.java` — `STUDENT` role matcher for the new path.
- `src/test/java/com/examora/StudentRecommendationIntegrationTest.java` — 15 H2 tests.

## 12. Determinism & consistency
- Pure compute-on-read: zero caching, zero randomness, no mutation.
- One canonical priority-tier table orders everything; identical state ⇒ identical payload
  (verified live with repeated calls).
- Percentages normalized to 2 decimals; thresholds identical to P5D.2 (60/80, ±2.0, quinquennial
  min-observation bands).
- `dataSufficient` always mirrors P5D.2 — the engine never overclaims evidence quality.
- Difficulty buckets in evidence and recommendations use the same 1–5 → EASY/MEDIUM/HARD mapping.

## 13. Limitations
- **Cooldown is difficulty-bucket scoped**, not question/topic scoped (no topic taxonomy exists —
  see P5D.2). An `INCREASE_PRACTICE`/review action is not reused-cooldown-suppressed.
- Weakness recommendations persist through the cooldown by design (they are still true); only the
  *repeat* of an equivalent improvement/maintenance action is suppressed.
- `BUILD_BASELINE` is also used as the fallback when a student with data has every candidate
  suppressed by the cooldown; the summary's `dataSufficient` will still report the true (possibly
  `true`) data quality in that corner case.
- The engine does not currently differentiate *new* from *persistent* weakness numerically, it
  only reflects persistence through P5D.3 `intelligenceChanges`/progression evidence in
  `supportingMetrics`.

## Future extensions
- Topic-aware recommendations once a normalized taxonomy exists.
- Session-planner integration: turning `targetQuestionCount` into a concrete practice plan is a
  frontend/UX concern, out of scope here.
- Smarter cooldown (per-code instead of per-bucket) and supporting evidence for *why* a weakness
  persists.