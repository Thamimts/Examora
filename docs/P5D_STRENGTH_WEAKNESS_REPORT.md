# P5D.2 — Student Strength & Weakness Intelligence Engine

## Status
- **Implemented:** `GET /api/student/learning-intelligence` (deterministic, backend-only, read-only)
- **Reuses:** P5D.1 `GET /api/student/learning-profile` via `LearningProfileService.build(studentId)`
- **Backend tests:** 367 total pass (14 new in `LearningIntelligenceIntegrationTest`)
- **Frontend:** types + API client only (no UI in this phase)
- **Real-PostgreSQL verification:** passed (see §9)
- **Committed:** no (working tree intentionally uncommitted)

## 1. Scope
P5D.2 turns the P5D.1 learning profile into actionable, ranked intelligence. Given the same
persisted activity (completed exams, graded answers, submitted attempts, adaptive practice),
the engine derives strengths, weaknesses, priority recommendations, and a deterministic
recommendation-code list.

Hard constraints honored:
- **No AI/LLM** anywhere in the pipeline — everything is arithmetic on real data.
- **No fabricated topics/skills** — only dimensions that exist in the P5D.1 profile are used
  (see §3). Topic/subject intelligence is explicitly unavailable (see §11).
- **No change to adaptive-practice selection** — adaptive practice is untouched.
- **JWT-scoped** — identity comes only from the principal; no `userId` parameter; anonymous → `401`;
  cross-student access structurally impossible.
- **No raw/correct answer leakage** — responses expose accuracies, counts, severities, and codes
  only; never question text or correct answers.

## 2. Endpoint specification

| Attribute | Value |
|---|---|
| Method / path | `GET /api/student/learning-intelligence` |
| Authentication | Required (JWT). Identity derived only from the principal; accepts zero parameters. |
| Authorization | `StudentAnalyticsController` → `requireStudent`; `SecurityConfig` maps the GET path → `hasRole("STUDENT")`. |
| Anonymous | `401` (entry point) |
| Teacher / Admin | `403` (denied handler) |
| Empty student | `200` with deterministic empty intelligence + `sufficientData: false` |

## 3. Supported dimensions
Derived exclusively from the P5D.1 profile. No dimension is emitted unless its observation
count meets the minimum sample size.

| Dimension | Source profile block | Min observations |
|---|---|---|
| `EASY_DIFFICULTY` | `difficulty.exam.easy` | 5 |
| `MEDIUM_DIFFICULTY` | `difficulty.exam.medium` | 5 |
| `HARD_DIFFICULTY` | `difficulty.exam.hard` | 5 |
| `EXAM_PERFORMANCE` | `examPerformance.averageAccuracy` + chronological exam answers | 5 |
| `PRACTICE_PERFORMANCE` | `practice` aggregate | 5 |
| `RECENT_PERFORMANCE` | `trend` (recent 2 vs previous 2, ≥ 4 exams) | 4 completed exams |
| `CONSISTENCY` | population stddev of result percentages | 3 completed exams |

## 4. Strength & weakness rules
- **Strength** — accuracy `>= 80` and observation count `>= min` for that dimension.
- **Weakness** — accuracy `<= 60` and observation count `>= min`.
- Positive tier 70–79 is tracked by constants but **not emitted** (`POSITIVE_TIER_MIN = 70`, `WEAKNESS_THRESHOLD = 60`).
- Constant `TARGET_ACCURACY = 80` anchors the gap calculation below.

**Severity** (weaknesses only):
| Accuracy | Severity |
|---|---|
| `< 35` | `HIGH` |
| `< 50` | `MEDIUM` |
| else (`<= 60`) | `LOW` |

**Confidence (answers-based dimensions)**:
| Observation count | Confidence |
|---|---|
| 5–9 | `LOW_CONFIDENCE` |
| 10–19 | `MEDIUM_CONFIDENCE` |
| `>= 20` | `HIGH_CONFIDENCE` |

**Confidence (exam-based dimensions** — `EXAM_PERFORMANCE`, `RECENT_PERFORMANCE`, `CONSISTENCY`):
| Completed exams | Confidence |
|---|---|
| 3–5 | `LOW_CONFIDENCE` |
| 6–11 | `MEDIUM_CONFIDENCE` |
| `>= 12` | `HIGH_CONFIDENCE` |

**Signals** (deterministic, lowercase-accuracy normalized):
- `HIGH_EASY_ACCURACY`, `LOW_MEDIUM_ACCURACY`, `LOW_HARD_ACCURACY` (difficulty buckets).
- `HIGH_EXAM_ACCURACY` / `LOW_EXAM_ACCURACY` (EXAM_PERFORMANCE).
- `STRONG_PRACTICE_ACCURACY` / `LOW_PRACTICE_ACCURACY` (PRACTICE_PERFORMANCE).
- `RECENT_PERFORMANCE_IMPROVING` / `RECENT_PERFORMANCE_DECLINING` (RECENT_PERFORMANCE).
- `CONSISTENCY_STABLE` / `CONSISTENCY_VARIABLE` (CONSISTENCY).

`CONSISTENCY` uses a **symbolic accuracy** — `100` when `STABLE`, `0` when `VARIABLE` — to keep
a single uniform metric; only VARIABLE (severity `MEDIUM`) is emitted as a weakness.

## 5. Priority algorithm
Each weakness is scored; the score drives priority level; ties are broken deterministically.

```
score = severityWeight + gapWeight + confidenceWeight + recencyWeight
```

| Component | Rule | Weight |
|---|---|---|
| Severity | HIGH / MEDIUM / LOW | 40 / 25 / 10 |
| Gap | min(accuracy − TARGET, 0) absolute, capped at 30 | up to 30 |
| Confidence | HIGH / MEDIUM / LOW | 15 / 10 / 5 |
| Recency | RECENT_PERFORMANCE vs others | 10 / 5 |

- `HIGH >= 55`, `MEDIUM >= 30`, else `LOW`.
- Reason string is built from fixed fragments (dimension, accuracy, observations, gap in points). Example:
  `HARD_DIFFICULTY accuracy 16.7% over 6 observations; performance gap of 63 points below the 80% target`.
- Order: score descending, then dimension name ascending (stable `Comparator`).

## 6. Recommendation codes
Generated from the ordered weakness list, deduplicated, fixed iteration order:
`EASY → MEDIUM → HARD → EXAM → RECENT → PRACTICE → CONSISTENCY`.

| Weakness | Code |
|---|---|
| EASY_DIFFICULTY | `REINFORCE_BASICS` |
| MEDIUM_DIFFICULTY | `PRACTICE_MEDIUM_QUESTIONS` |
| HARD_DIFFICULTY | `PRACTICE_HARD_QUESTIONS` |
| EXAM_PERFORMANCE | `REVIEW_RECENT_EXAMS` |
| RECENT_PERFORMANCE | `REVIEW_RECENT_EXAMS` |
| PRACTICE_PERFORMANCE | `INCREASE_PRACTICE` |
| CONSISTENCY | `COMPLETE_REGULAR_PRACTICE` |

Each `Priority` also carries a single `recommendedAction` code matching its dimension's mapping.

## 7. Response model
```json
{
  "success": true,
  "data": {
    "strengths": [ { "dimension": "EASY_DIFFICULTY", "accuracy": 100.0, "observations": 7,
                     "confidence": "LOW_CONFIDENCE", "signal": "HIGH_EASY_ACCURACY" } ],
    "weaknesses": [ { "dimension": "HARD_DIFFICULTY", "accuracy": 16.67, "observations": 6,
                      "severity": "HIGH", "confidence": "LOW_CONFIDENCE", "signal": "LOW_HARD_ACCURACY" } ],
    "priorities": [ { "dimension": "HARD_DIFFICULTY", "priorityLevel": "HIGH",
                      "reason": "HARD_DIFFICULTY accuracy 16.7% over 6 observations; performance gap of 63 points below the 80% target",
                      "recommendedAction": "PRACTICE_HARD_QUESTIONS" } ],
    "recommendations": [ { "code": "PRACTICE_HARD_QUESTIONS" } ],
    "dataQuality": { "overallAccuracy": 100.0, "observationCount": 11,
                     "strengthCount": 2, "weaknessCount": 0, "sufficientData": true }
  }
}
```
- `strengths`/`weaknesses` sorted by dimension name (fixed order) for determinism.
- `overallAccuracy` is the weighted average of all observations (null when `observationCount == 0`).
- `dataQuality.sufficientData` = `observationCount >= 5`; individual dimensions still require
  their own minimum (so a student can have `sufficientData: true` with no signal).

## 8. Implementation
- `dto/LearningIntelligenceDtos.java` — enums `Dimension`, `Severity`, `Confidence`,
  `PriorityLevel`, `RecommendationCode`; records `LearningIntelligence`, `Strength`, `Weakness`,
  `Priority`, `Recommendation`, `DataQuality`.
- `service/LearningIntelligenceService.java` — the complete deterministic engine; consumes
  `LearningProfileService.build(studentId)`; documents every constant and invariants.
- `controller/StudentAnalyticsController.java` — `GET /learning-intelligence` + injected service.
- `config/SecurityConfig.java` — `STUDENT` role matcher for the new path.
- `src/test/java/com/examora/LearningIntelligenceIntegrationTest.java` — 14 H2 tests.

## 9. Verification
Integration tests (H2, 14): returns 200 for student; anonymous 401; two students differ;
empty-state contract (zeros/nulls, `sufficientData: false`); strength from `>= 80` accuracy
(EASY bucket, 6 obs); weakness from `<= 60` (MEDIUM, 5 obs); HIGH-severity weakness from
`< 35` (HARD, 25 answers, 28%); MEDIUM/LOW severities; low-confidence from 5–9 observations
and high-confidence from `>= 20`; declining recent performance (90/85/60/40) → RECENT weakness
@ 50%; deterministic recommendation codes (REVIEW_RECENT_EXAMS / PRACTICE_HARD_QUESTIONS /
INCREASE_PRACTICE); no cross-student leakage (other student empty, `?studentId=` ignored).

Real PostgreSQL (`POSTGRES 18.6`, DB `examora`) live checks:

| Case | Result |
|---|---|
| Anonymous | `401` |
| Teacher token | `403` |
| demo-student before seeding | `200` · `sufficientData: true` (5 obs) but no dimension reached its own minimum → strengths/weaknesses/priorities empty |
| demo-student + 6 correct easy answers | `EXAM_PERFORMANCE` strength 100%/8 obs + `EASY_DIFFICULTY` strength 100%/7 obs, both `LOW_CONFIDENCE` |
| demo-student + 6 hard answers (1 correct) | `HARD_DIFFICULTY` weakness 16.67%/6 obs, severity HIGH, priority HIGH, action `PRACTICE_HARD_QUESTIONS`, 1 recommendation code |
| Empty student (`verify1789128106@test.local`) | all empty · `overallAccuracy: null` · `sufficientData: false` |

Seeded verification rows were removed afterward; the DB is back to its pre-check state.

Frontend: `types/learning-intelligence.ts` + `services/learningIntelligenceApi.ts` added (shared
axios instance, auto-Bearer, `ApiResponse<LearningIntelligence>`). `tsc --noEmit` and
`pnpm exec next build` both green. No UI is added in this phase.

## 10. Determinism & consistency
- Every value is derived on read from persisted rows; nothing is cached or randomized.
- Percentages normalized to 2 decimals; confidence/severity/signal from documented ranges.
- Ordering everywhere via stable comparators (dimension name; priority score desc + dimension).
- Recommendation codes generated in fixed dimensional order after deduplication.
- The engine is pure: given identical DB state it returns a byte-identical payload.

## 11. Limitations
- **Topic/subject intelligence is unavailable.** Question text, `results.subject`, and practice
  prompts carry no normalized topic taxonomy and no AI analysis is permitted, so the engine
  cannot attribute weaknesses to topics or skills. This is a deliberate boundary of P5D.2.
- Observations below a dimension's minimum are reported only in `dataQuality.observationCount`.
- `CONSISTENCY` severity is fixed at `MEDIUM` for `VARIABLE`; it has no HIGH/LOW graduation.
- Confidence bands are coarse by design (LOW/MEDIUM/HIGH); fine-grained error bars are not modeled.
- AI practice (EASY/MEDIUM/HARD string scale) stays excluded from difficulty intelligence,
  consistent with P5D.1.

## Future extensions
- Topic/skill taxonomy ingested into the schema, enabling per-topic strengths/weaknesses.
- Recommendation-action payloads with linkable resources (exams, practice sets).
- Time-decayed accuracy in place of coarse recency weighting.