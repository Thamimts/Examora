# SmartExam Backend Requirements

Concise handoff for the Spring Boot developer. This specification is derived from the current frontend. **Do not add endpoints or fields not listed as required or confirmed with the frontend team.**

## 1. Scope

SmartExam is an online examination platform with three roles:

- `STUDENT`: browse assigned exams, attempt questions, submit answers, view results and analytics.
- `TEACHER`: create/manage exams and questions, publish exams, monitor students.
- `ADMIN`: manage users, question bank, exams, analytics, and system monitoring.

Expected backend: Spring Boot REST API, JWT or the backend-supported session strategy, role-based authorization, validation, persistence, and optional WebSocket/STOMP monitoring. Frontend base URL is configured with `VITE_API_URL`, falling back to `http://localhost:8080/api`.

Labels used below:

- **EXISTING FRONTEND CONTRACT** — endpoint already referenced by frontend code.
- **REQUIRED BACKEND CONTRACT** — frontend feature exists but backend contract is missing or incomplete.
- **OPTIONAL/FUTURE** — not currently required to make existing screens work.

## 2. Confirmed API Inventory

| Status | Method | Endpoint | Auth | Frontend expectation |
|---|---:|---|---|---|
| EXISTING FRONTEND CONTRACT | `POST` | `/auth/login` | Public | Body `{ email, password }`; response `ApiResponse<AuthResponse>` with `token` and `user`. |
| EXISTING FRONTEND CONTRACT | `POST` | `/auth/register` | Public | Body `{ name, email, password }`; response must match the existing auth flow. |
| EXISTING FRONTEND CONTRACT | `POST` | `/auth/logout` | Authenticated | Invalidates server session/token where supported. |
| EXISTING FRONTEND CONTRACT | `GET` | `/users` | `ADMIN` | Response `ApiResponse<User[]>`; current frontend supports loading, retry, empty, and error states. |
| EXISTING FRONTEND CONTRACT | `GET` | `/exams` | Role-dependent | Existing `examApi.list()` reference; confirm response shape before enabling production exam screens. |
| EXISTING FRONTEND CONTRACT | `GET` | `/exams/{id}` | Role-dependent | Existing `examApi.get(id)` reference. |
| EXISTING FRONTEND CONTRACT | `POST` | `/exams` | `TEACHER`/`ADMIN` | Existing create service reference; confirm request/response DTO. |
| EXISTING FRONTEND CONTRACT | `PUT` | `/exams/{id}` | Owner/`ADMIN` | Existing update service reference. |
| EXISTING FRONTEND CONTRACT | `DELETE` | `/exams/{id}` | Owner/`ADMIN` | Existing delete service reference. |
| EXISTING FRONTEND CONTRACT | `POST` | `/exams/{id}/publish` | `TEACHER`/`ADMIN` | Existing publish service reference. |
| EXISTING FRONTEND CONTRACT | `GET` | `/users/{id}/analytics` or configured analytics path | `STUDENT` self / `ADMIN` | Existing AI analytics service reference; confirm exact deployed path. |
| EXISTING FRONTEND CONTRACT | Proctor service paths | `services/proctorApi.ts` | Attempt owner/teacher/admin | Confirm exact paths from the backend implementation before enabling. |

All protected requests use `Authorization: Bearer <token>` when the approved backend strategy is bearer JWT. API responses must use the existing wrapper shape where already typed:

```json
{ "data": {}, "message": "Success", "success": true }
```

## 3. Authentication and Authorization

### Auth DTOs

```json
POST /auth/login
{ "email": "student@example.com", "password": "secret" }

POST /auth/register
{ "name": "Asha Rao", "email": "asha@example.com", "password": "secret" }

AuthResponse
{ "token": "<access-token>", "user": { "id": "u1", "name": "Asha Rao", "email": "asha@example.com", "role": "STUDENT" } }
```

Required behavior:

- Passwords must be hashed; never return password fields.
- Enforce unique, valid email and password policy server-side.
- Return `401` for invalid/expired credentials and `403` for insufficient role.
- Frontend clears auth state and redirects to `/login?expired=1` on `401`.
- Support the backend’s actual token/session strategy. Do not require in-memory auth if the backend does not support it. If JWT is used, define expiry and refresh behavior; if refresh is unsupported, return `401` after access-token expiry.
- `POST /auth/logout` must be safe to retry.
- Add `/auth/forgot-password`, `/auth/reset-password`, email verification, and current-user/profile only if those routes already exist in the backend contract.

### Permission matrix

| Feature | STUDENT | TEACHER | ADMIN |
|---|---:|---:|---:|
| Login/register/logout | Yes | Yes | Yes |
| Own available exams/attempts/results | Yes | No | No |
| Create/edit/publish own exams | No | Yes | Yes |
| Manage all exams/questions | No | Scoped | Yes |
| User directory | No | No | Yes |
| Student analytics | Own | Assigned students | All |
| Proctor event submission | Own attempt | No | No |
| Live monitoring | No | Assigned exams | Yes |

Client guards are not a security boundary. Enforce authorization on every API and object ownership check on every request.

## 4. Core Data Models

```text
Role = STUDENT | TEACHER | ADMIN
QuestionType = MCQ | DESCRIPTIVE
Difficulty = EASY | MEDIUM | HARD
ExamStatus = DRAFT | PUBLISHED | CLOSED | ARCHIVED
AttemptStatus = CREATED | IN_PROGRESS | SUBMITTED | EXPIRED | EVALUATED
AssignmentType = ALL_STUDENTS | SELECTED_STUDENTS | GROUP
```

Minimum DTOs:

- `User`: `id`, `name`, `email`, `role`, optional `avatar`, `createdAt`, `status`.
- `Exam`: `id`, `title`, `description`, `subject`, `durationMinutes`, `questionCount`, `status`, `startAt`, `endAt`, `createdBy`, `assignment`.
- `Question`: `id`, `examId`, `text`, `type`, `difficulty`, `points`, `topic`, `options`.
- `QuestionOption`: `id`, `text`, `order`; correct answer is teacher/admin-only.
- `Result`: `id`, `attemptId`, `examId`, `score`, `percentage`, `passed`, `submittedAt`, `status`, topic breakdown.
- `Answer`: `questionId`, `value` (option id or text), `markedForReview`, `savedAt`.
- `ApiResponse<T>`: existing generic wrapper; preserve its actual field names.

Never expose correct answers, answer keys, or grading explanations in student question payloads before submission. Use separate student/admin/teacher response DTOs.

## 5. Required Exam Lifecycle Contracts

The frontend currently has exam UI but no verified production contract for the complete lifecycle. These are **REQUIRED BACKEND CONTRACTS** and must be agreed before wiring:

```text
Teacher creates exam
  -> adds questions
  -> publishes and assigns
Student lists eligible exams
  -> starts attempt
  -> receives server attemptId + endAt
  -> loads safe questions
  -> saves answers/review flags
  -> restores after refresh/reconnect
  -> submits once or backend auto-expires
  -> receives result
  -> views history/analytics
```

Minimum endpoints to confirm:

| Method | Endpoint | Required behavior |
|---:|---|---|
| `GET` | `/exams` | Role-scoped, published/assigned filtering; no unauthorized exams. |
| `GET` | `/exams/{id}` | Metadata and role-safe question data. |
| `POST` | `/exams/{id}/attempts` | Creates/resumes one eligible attempt; returns `attemptId`, `status`, `startedAt`, server `endAt`, and safe questions. |
| `GET` | `/attempts/{attemptId}` | Restores attempt and answers after refresh; validates ownership. |
| `PUT/PATCH` | Existing agreed answer-save path | Idempotent answer/review autosave with `attemptId` and `questionId`. |
| `POST` | Existing agreed submit path | Server evaluates, is idempotent, and returns the same result for duplicate retries. |
| `GET` | Existing result/history paths | Own student results; scoped teacher/admin views. |

Backend is authoritative for timing. Never accept a client-provided deadline as truth. Reject writes after `endAt`, mark expired attempts, and make submission retry-safe using an idempotency key or deterministic attempt status transition.

Valid transitions:

```text
CREATED -> IN_PROGRESS -> SUBMITTED -> EVALUATED
CREATED -> EXPIRED
IN_PROGRESS -> EXPIRED
IN_PROGRESS -> SUBMITTED
```

## 6. Question Bank and Import

**REQUIRED BACKEND CONTRACT** for the existing question-bank UI:

- `GET /questions`: pagination, search, subject/topic/type/difficulty filters, sorting.
- `POST /questions`, `PUT /questions/{id}`, `DELETE /questions/{id}`: teacher/admin authorization and validation.
- Import endpoint only when confirmed: multipart field `file`, CSV/XLSX validation, size limit, preview, row-level errors, and atomic or explicitly reported partial behavior.

Suggested query parameters must match the frontend once agreed: `page`, `size`, `sort`, `direction`, `search`, `subject`, `topic`, `type`, `difficulty`.

Example validation errors:

```json
{ "success": false, "message": "Validation failed", "errors": { "file": "Unsupported format", "rows[3].text": "Required" } }
```

## 7. Assignment

**REQUIRED BACKEND CONTRACT:** published exams must appear only for eligible students.

```json
{
  "type": "SELECTED_STUDENTS",
  "studentIds": ["s1", "s2"],
  "groupId": null
}
```

Support only assignment types implemented by the backend. Enforce that draft exams cannot be started and that assignment changes do not expose attempts to unauthorized users.

## 8. Dashboards and Analytics

Existing dashboards display these backend-owned values:

- Student: upcoming exams, recent results, average/score statistics, performance trend, topic strengths/weaknesses, recommendations.
- Teacher: exam count, student count, average score, recent exams, attempt/monitoring status.
- Admin: users by role, exam counts, active attempts, pass rate, system/proctoring analytics.

**REQUIRED BACKEND CONTRACT:** return aggregate DTOs optimized for each dashboard. Do not make the frontend calculate official scores, pass rates, risk, recommendations, or adaptive progression. If data is unavailable, return a typed empty result or documented `404`, not fabricated values.

## 9. AI and Adaptive Features

The backend owns provider credentials and AI execution.

**REQUIRED BACKEND CONTRACTS** for existing AI UI:

- Question generation: input subject/topic/difficulty/type/count; output editable generated questions with validation status.
- Student analysis: output readiness, strengths, weak areas, topic analysis, trend, recommendations, and recent results.
- Descriptive evaluation: input answer plus rubric/question context; output score, feedback, and grading status.
- Adaptive attempt: student submits current answer; backend evaluates and returns the next safe question, difficulty, progress, and attempt status.

Frontend must display backend decisions and must not increment difficulty, compute scores, or generate recommendations locally in production.

## 10. Proctoring and Real-Time Monitoring

Existing frontend detection includes tab switch, window blur/focus, fullscreen exit where present, copy, paste, right-click, webcam permission/stop, and connection changes. These are signals, not proof of misconduct.

**REQUIRED BACKEND CONTRACT:** event ingestion associated with `attemptId`:

```json
{
  "attemptId": "a1",
  "type": "TAB_SWITCH",
  "occurredAt": "2026-08-22T12:00:00Z",
  "metadata": { "visibilityState": "hidden" }
}
```

Store events immutably, authorize the attempt owner, deduplicate client retries, and expose neutral status/risk summaries. Do not automatically label a student dishonest from one event.

WebSocket/STOMP is **REQUIRED BACKEND CONTRACT** only if live monitoring is enabled. Confirm broker URL, auth method, destinations, reconnect behavior, and payloads first. Minimum teacher update payload: `attemptId`, student identity, progress, remaining time, connection status, recent proctoring activity, submitted/expired status.

## 11. Pagination, Errors, CORS

Use a consistent paged response where pagination is enabled:

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }
```

Required status semantics:

- `200/201`: success.
- `400/422`: validation/business-rule failure with field errors where applicable.
- `401`: missing/invalid/expired auth.
- `403`: authenticated but forbidden.
- `404`: resource not found or not visible.
- `409`: duplicate email, conflicting attempt, already submitted, or invalid state.
- `429`: rate limit for auth/import/AI-sensitive endpoints.
- `500`: generic safe message; never leak stack traces or secrets.

Configure CORS for the deployed frontend origin and localhost development origin. Never use unrestricted credentials-enabled CORS.

## 12. Security Checklist

- Hash passwords and protect tokens/cookies according to the approved auth strategy.
- Enforce role and ownership checks server-side.
- Never send answer keys to students before evaluation.
- Validate all IDs, enums, timestamps, pagination, and file contents.
- Server-authoritative attempt timing; no client deadline extension.
- Idempotent answer saves and submission.
- Rate-limit login, password reset, imports, and AI generation.
- Audit exam publication, grading, user-role changes, and proctoring events.
- Return safe errors and use parameterized persistence queries.

## 13. Implementation Priority

1. **Foundation:** database, auth, roles, DTOs, validation, common errors, CORS.
2. **Core exams:** questions, exams, assignment, attempts, server timer, autosave, submit, results.
3. **Teacher/Admin:** CRUD, users, question bank, CSV/XLSX import.
4. **Analytics/AI:** dashboard aggregates, generation, evaluation, recommendations.
5. **Proctoring/realtime:** event storage, monitoring, WebSocket/STOMP.
6. **Production readiness:** security review, integration tests, logging, OpenAPI documentation, deployment configuration.

## Completion Condition

Backend handoff is complete when every **REQUIRED BACKEND CONTRACT** above has an agreed Spring Boot endpoint, request/response DTO, authorization rule, validation rule, and integration test; the frontend can authenticate, list assigned exams, create/restore an attempt, autosave, submit idempotently, show real results, and load role dashboards without mock data. No API or response field should be implemented from assumption; unresolved items must remain explicitly marked as contract gaps.

**Source:** current SmartExam frontend services, routes, stores, features, hooks, types, mock data, and `FRONTEND_AUDIT.md`.
