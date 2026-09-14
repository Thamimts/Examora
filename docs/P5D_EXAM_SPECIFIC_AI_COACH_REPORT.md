# P5D.5 — Exam-Specific AI Coach

## Status
- **Implemented:** exam-scoped AI coach for a single completed exam the student picks
  - `GET /api/student/ai-coach/exams` — the student's completed-exam list
  - `POST /api/student/ai-coach/exams/{examId}` — a server-verified, per-action pruned
    `ExamCoachContext` (never whole-exam dumps, never unrequested answer keys)
  - Frontend exam picker + suggested actions + grounded chat with the LLM kept on the client
    (Next.js route handler → Groq - `openai/gpt-oss-20b` via `generateObject`)
- **Reuses:** P5D.4 report conventions, `ExamAttemptService.getStudentResultReview` for
  per-exam mistakes, the P5D.3/P5D.4 difficulty mapping (1–5 → EASY/MEDIUM/HARD)
- **Backend tests:** 420 total pass (22 new in `ExamCoachIntegrationTest`)
- **Frontend:** `types/ai-coach.ts`, `services/aiCoachApi.ts`,
  `app/api/student/ai-coach/chat/route.ts`, `features/analytics/ExamSpecificCoach.tsx`,
  `StudentAICoach.tsx` re-wired to the exam-scoped coach. `tsc --noEmit` and `next build` green.
- **Real-PostgreSQL verification:** passed (see §9). **Live Groq end-to-end:** passed (see §10).
- **Committed:** no (working tree intentionally uncommitted)

## 1. Scope
P5D.1–P5D.4 deliver **global** intelligence: aggregated accuracy, strengths/weaknesses,
progress trends, practice recommendations — computed across all of a student's data. P5D.5 is the
opposite axis: the student picks **one completed exam** and gets an AI coach whose entire world is
that single exam. The coach explains that exam's performance, explains why individual questions
were lost, suggests a revision plan for exactly that content, and chats about it — based **only**
on that exam's own verified rows.

Hard constraints honored:
- **One completed exam only.** Every prompt is built from a context the backend derives from a
  single `result` + its mistakes + its questions/options/answers, never from all the student's data.
- **No whole-exam context dumps.** The backend sends only what the requested action needs
  (per-action pruning). `SUGGEST_REVISION`/`CHAT` receive the full question list but *without*
  answer keys; only `GENERATE_SIMILAR_QUESTION` may include the correct answer.
- **No IDOR.** `examId` is always checked against the principal's own completed results.
  Cross-student examId → `404` (not `403`, so no existence oracle for others' rows).
- **JWT-scoped.** Anonymous → `401`; teacher/admin → `403`; no query params are read.
- **Answers are human-verified.** LLM output is produced from backend-verified
  `selectedOptionText` / `correctOptionText`; no raw IDs or enum codes leak into prompts.
- **LLM never writes authoritative data.** No practice sessions, no profile mutation; only the
  chat history lives in the client's cache.
- **Global intelligence untouched.** P5D.1–P5D.4 services, endpoints, and the dashboard's global
  analysis are not modified.

## 2. Endpoints

### `GET /api/student/ai-coach/exams`
Authentication: required. Authorization: `STUDENT` (SecurityConfig matcher
`.requestMatchers("/api/student/ai-coach/**").hasRole("STUDENT")`).

Returns the student's **completed** exams (`results`, one per exam) joined with the latest
submitted attempt to carry `attemptNumber` (a retake sees the second attempt, not the first) and
the attempt's submitted-at timestamp. Newest first. Safe fields only:

```json
{
  "examId": "p5d5c-ex-sci",
  "title": "Science Quiz",
  "subject": "Science",
  "score": 1,
  "percentage": 33.33,
  "completedAt": "2026-09-01T03:00:00Z",
  "attemptNumber": 1
}
```

### `POST /api/student/ai-coach/exams/{examId}`
Body: `{ "action": "<ACTION>", "questionId": "..." }`. Returns the pruned context:

| Action | Stats | Mistakes | Question | Answer key | Offers |
|---|---|---|---|---|---|
| `EXPLAIN_PERFORMANCE` | ✅ | — | — | — | why the result = what it is |
| `EXPLAIN_MISTAKES` | ✅ | ✅ | — | — | explain each lost question |
| `EXPLAIN_QUESTION` | — | — | ✅ (that one) | — | explain that question |
| `REVIEW_WEAK_AREAS` | ✅ | ✅ | — | — | weakest topics of the exam |
| `SUGGEST_REVISION` | ✅ | — | all questions | — | an exam-scoped revision plan |
| `GENERATE_SIMILAR_QUESTION` | — | — | ✅ (that one) | ✅ (only this) | a new practice question |
| `CHAT` | ✅ | — | all questions | — | ground a conversation |

Exam summary fields (`examTitle`, `score`, `total`, `percentage`, `attemptNumber`) are always
present on the context; `correctAnswer` is only populated for `GENERATE_SIMILAR_QUESTION`.

## 3. Error semantics

| Case | HTTP |
|---|---|
| Anonymous | `401` |
| Teacher / admin | `403` |
| No completed result for the examId (never taken, unfinished, or someone else's) | `404` |
| Unknown / missing action | `400` |
| Question action without `questionId` (`EXPLAIN_QUESTION`, `GENERATE_SIMILAR_QUESTION`) | `400` |
| `questionId` not belonging to this exam result | `400` |

`404` (not `403`) for cross-student ids keeps the existence of other students' rows unobservable.

## 4. Context model (`ExamCoachContextDto`)
```json
{
  "examTitle": "Math Midterm",
  "score": 3, "total": 4, "percentage": 75.0, "attemptNumber": 1,
  "action": "EXPLAIN_PERFORMANCE",
  "stats": {
    "correct": 3, "incorrect": 1, "unanswered": 0, "accuracy": 75.0,
    "easyCorrect": 1, "easyTotal": 2,
    "mediumCorrect": 1, "mediumTotal": 1,
    "hardCorrect": 1, "hardTotal": 1
  },
  "mistakes": null | [ { "number": 2, "questionId": "p5d5c-q2",
                          "text": "Simplify the fraction 4/8.",
                          "difficulty": 1, "difficultyName": "EASY",
                          "selectedOptionText": "One quarter",
                          "correctOptionText": "One half" } ],
  "questions": null | [ { "number", "questionId", "text", "difficultyName", "correct" } ],
  "question": null | { "number", "questionId", "text", "difficultyName", "correct" },
  "correctAnswer": null | "..."
}
```
- `stats` are computed from the exam's own answer rows (`unanswered` when no answer exists for the
  attempt), accuracy = correct/total.
- `mistakes` = the exam's answered-but-wrong questions with the student's selected text and the
  correct option text (by `question_option.correct_answer`).
- `questions` carry a boolean `correct` — enough for a scoped chat, without answer text.
- Difficulty bucketing: 1–2 → `EASY`, 3 → `MEDIUM`, 4–5 → `HARD`.
- `correct` handling for a question with no answer row in the exam = user didn't answer; it is
  marked `correct=false` but counted as `unanswered` in stats.

## 5. Frontend flow
1. `services/aiCoachApi.ts` — browser calls the backend directly (shared axios instance +
   auto-Bearer), mirroring the P5D.4 `studentRecommendationApi` split.
2. `features/analytics/ExamSpecificCoach.tsx` — exam picker grid → selected-exam summary card →
   four suggested action buttons (Explain performance / Mistakes / Weak areas / Revision) →
   question list with per-question *Explain* and *Similar question* buttons → chat area with
   history; everything keys off the **one selected exam** held in component state.
3. `app/api/student/ai-coach/chat/route.ts` — the only path to the LLM:
   - Authenticates via the session token (JWKS-verified).
   - Zod-validates `{ examId, action, questionId?, message? }`.
   - Has the Groq key (else `503`, like the ai-tutor route).
   - Rate-limits **15 requests / 10 min per user** (existing frontend limiter).
   - Calls the backend context endpoint with the caller's own bearer header, validates the
     returned context against the logged-in user's identity + exam.
   - Caches `cached: userKey:coach-exam:{examId}` (40-entry LRU, 30-min TTL) keyed on a **sha256
     fingerprint of the pruned context + action + questionId + message + history** — user identity
     + exam selected + a data fingerprint, never the raw JWT, so Exam A is never served for Exam B.
   - Builds a strict prompt and calls `generateObject` → `{ answer }`; errors surface via
     `aiErrorResponse`.

## 6. Prompt rules (enforced server-side before hand-off)
The route builds the prompt with fixed, non-model-invented constraints:
1. The student has one exam open; **only facts from the provided context may be used**.
2. Never claim data from other exams, the whole account, or "recent trends".
3. Never mention the model, other services, or unreachable student history.
4. Never reveal the correct answer except when the action is exactly
   `GENERATE_SIMILAR_QUESTION` (that action returns the new question *and* its answer).
5. For `EXPLAIN_QUESTION`/`EXPLAIN_MISTAKES`: explain the concept and how to reason to the
   answer, but do not spell out the correct option text.
6. Ground every number in the context; no fabricated accuracy percentages or question counts.
7. `SUGGEST_REVISION` must prioritize the exam's actual mistakes before repeating strengths.
8. Keep answers short and concrete ("review the concept, then try these steps").
9. Stay within the subject of the open exam; never drift out of scope.
10. If inadequate information is present, say so instead of guessing.
11. Be encouraging, non-sarcastic, flat tone.
12. Respond in the student's message language when a message exists.

## 7. Implementation
- `dto/ExamCoachDtos.java` — request record (`CoachExamRequest`), response records
  (`CoachExamSummary`, `ExamCoachContextDto`, `CoachStatsDto`, `CoachQuestionDto`), `CoachAction`
  enum (7 values incl. `CHAT`).
- `repository/ResultRepository.java` — `findLatestByUserIdAndExamId` (the single-completed-exam
  gate); `findCompletedExamSummariesByUserId` ordered newest-first with the latest attempt's
  number/timestamp.
- `repository/ExamAttemptRepository.java` — `findSubmittedByStudent` returning
  `SubmittedAttemptRow(examId, attemptNumber, submittedAt)`.
- `service/ExamCoachService.java` — completed-exam list + `buildContext`; per-action pruning;
  one or two bounded row-fetches per context; computes `correct / incorrect / unanswered` and
  difficulty buckets from the exam's own rows.
- `controller/ExamCoachController.java` — the two endpoints; validation of action/`questionId`
  membership; `404` for missing/cross-student/unfinished, `403` handled by Spring Security.
- `config/SecurityConfig.java` — `STUDENT` matcher for `/api/student/ai-coach/**`.
- `src/test/java/com/examora/ExamCoachIntegrationTest.java` — 22 H2 tests (§8).
- Frontend — files in *Status* above.

## 8. Integration tests (22, H2)
list empty for a fresh student; two completed exams → newest-first, correct attempt numbers;
unfinished-only → empty; retake → second attempt number with the first result score; teacher `403`
and admin `403`; anonymous `401`; wrong student's examId → `404`; never-taken examId → `404`;
unknown action → `400`; missing `questionId` → `400`; cross-exam questionId → `400`;
`EXPLAIN_PERFORMANCE` → stats-only, exam fields, no questions/mistakes/answer; `EXPLAIN_MISTAKES`
→ mistakes only the wrong row + stats, no answer keys; `EXPLAIN_QUESTION` → single question only,
no correct answer and no stats; `EXPLAIN_QUESTION` on an easy wrong question surfaces the selected
option text; `REVIEW_WEAK_AREAS` → stats + mistakes; `SUGGEST_REVISION` → stats + all questions
with correct flags, **no** correct answers; `GENERATE_SIMILAR_QUESTION` → question + correctAnswer,
nothing else; `CHAT` → stats + all questions, no answer keys; stats math (correct/incorrect/
unanswered, accuracy, easy/medium/hard buckets); percentage rounding (1/3 → 33.33).

Full suite: **420 tests, 0 failures, 0 errors, 0 skipped** (was 398).

## 9. Real-PostgreSQL verification (DB `examora`, backend built fresh)
Seeded for `student@examora.local` (`demo-student`): "Math Midterm" (4 questions, difficulties
1/1/3/5, corrects T/F/T/T → 3/4 = 75%, completed 2026-08-01) and "Science Quiz" (3 questions,
difficulties 3/5/5, corrects T/F/F → 1/3 ≈ 33.33%, completed 2026-09-01), plus one
STARTED/unfinished "History Check" with **no** result.

| Case | Result |
|---|---|
| List (student) | `200` → exactly the two completed exams, newest first (`Science Quiz` → `Math Midterm`), `attemptNumber=1`, correct percentages; unfinished not shown |
| List (teacher) | `403` |
| List (anonymous) | `401` |
| `EXPLAIN_PERFORMANCE` (math) | stats `{3,1,0,75.0, easy 1/2, medium 1/1, hard 1/1}`; no questions/mistakes/answer key |
| `EXPLAIN_MISTAKES` (math) | exactly row 2 (`One quarter` selected), correct option text present in mistake row, no answer keys elsewhere |
| `EXPLAIN_QUESTION` q2 | single question `EASY correct=false`, `correctAnswer=null` |
| `EXPLAIN_QUESTION` q6 (from Science) on math | `400` |
| `GENERATE_SIMILAR_QUESTION` q2 | `correctAnswer="One half"` (+ question only) |
| `CHAT` (math) | stats + all 4 questions with correct flags, no answer keys |
| `SUGGEST_REVISION` (science) | only science questions (q5 MEDIUM ✓, q6 HARD ✗, q7 HARD ✗) |
| Other student's examId | `404` |
| Teacher context call | `403` |
| Unfinished exam context | `404` |
| Unknown action | `400` |
| Missing `questionId` | `400` |

All `p5d5c-*` seeded rows (answers, results, attempts, options, questions, exams) and the
throwaway registered student were deleted afterward; DB back to pre-check state; backend stopped.

## 10. Live Groq end-to-end (Next dev server + real backend + real key)
Calls to `POST /api/student/ai-coach/chat` with the demo student's token:

| Prompt input | Answer observed (verified) |
|---|---|
| `EXPLAIN_PERFORMANCE` math | "3 of 4 … 75% accuracy … all medium and hard correct … the one easy question you missed" — matches the exam's own rows, nothing global |
| `CHAT` "What should I focus on next?" | "3 out of 4 correct … the only question missed … simplifying the fraction 4/8 … practice 6/9, 12/18…" — grounded, exam-scoped |
| `EXPLAIN_QUESTION` q2 | explains GCD/simplification without ever printing "One half"; names the student's selection "One quarter" |
| `EXPLAIN_PERFORMANCE` science | "1 … of the 3 available points … 33.33% … medium correct, both hard missed" — isolated from the math exam |
| Anonymous chat | `401` |
| Unfinished exam chat | `404` |

## 11. Notes / limitations
- The route-level cache is a **context+action fingerprint**, so two chat turns that only change
  the message are distinct requests but identical *context* answers still hit the fingerprint
  cache — safe because the fingerprint includes the message and history.
- The LLM stays client-side, consistent with P5D.1–P5D.4 architecture; the backend remains fully
  deterministic and read-only.
- `SUGGEST_REVISION`/`CHAT` include the exam's full question list (text + correct flag) but never
  option text or answers, so the coach can reference "the fraction question" precisely without
  being able to parrot answer keys. `EXPLAIN_MISTAKES`/`EXPLAIN_QUESTION` hand the concept goal
  without the answer — prompt rules additionally forbid spelling it out.
- Retakes: the list uses the latest attempt's number/time; results are one per exam, so the coach
  scores reflect the *stored* result (which, per the existing submit flow, is replaced on each
  new submission).
- difficulty bands reused from P5D.3/P5D.4 so the whole P5D line speaks one difficulty language.

## Future extensions
- Optional "compare with your previous attempt" context once retake results are retained.
- TTFT/grounding tests via a deterministic passthrough model in CI.
- Extraction of a reusable plan/PDF from `SUGGEST_REVISION` output.