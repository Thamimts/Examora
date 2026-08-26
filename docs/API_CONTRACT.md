# Examora API contract

**Status:** current implementation inventory plus production contract guidance.  
**Base URL:** `/api`. All examples omit the host.  
**Last reviewed:** 2026-08-26.

## Conventions

Protected endpoints require `Authorization: Bearer <JWT>`. Current responses use this envelope:

```json
{ "success": true, "message": "Success", "data": {} }
```

Errors use `success: false`, a human-readable `message`, and `data: null`. Current validation is service-based rather than bean validation; clients must not parse error text. HTTP status meanings are `400` invalid input/business rule, `401` missing/invalid/expired authentication, `403` insufficient role/access, `404` absent resource, `409` conflict, and `500` unexpected server failure.

All JSON uses camelCase. IDs are UUID-shaped strings. Dates in the current API are strings; production must use ISO-8601 UTC timestamps for instants and ISO-8601 dates only where a date-without-time is intentional.

## Authentication

| Method and path | Auth | Request | Response `data` |
| --- | --- | --- | --- |
| `POST /auth/register` | Public | `{name,email,password}` | `{token,user}`; always creates `STUDENT` |
| `POST /auth/login` | Public | `{email,password}` | `{token,user}` |
| `POST /auth/logout` | Public/idempotent | Optional bearer token | `null` |
| `GET /users/me` | Any authenticated user | — | `User` |

`password` must currently be at least eight characters during registration. `User` is `{id,name,email,role,avatar}`; no password hash is exposed.

## Resource shapes

```json
// Exam
{ "id":"uuid", "title":"Math", "subject":"Mathematics", "date":"2026-09-01", "duration":60, "status":"DRAFT", "participants":0, "averageScore":null }

// Question; `answer` is omitted/null for students
{ "id":"uuid", "examId":"uuid", "text":"2 + 2?", "options":["3","4"], "answer":"4" }

// Option; correctAnswer is teacher/admin-only by policy
{ "id":"uuid", "questionId":"uuid", "text":"4", "displayOrder":1, "correctAnswer":true }

// Result
{ "id":"uuid", "userId":"uuid", "examId":"uuid", "examTitle":"Math", "subject":"Mathematics", "score":8, "total":10, "date":"2026-09-01" }
```

## Current endpoint inventory

| Area | Endpoints | Access in current security configuration |
| --- | --- | --- |
| Status | `GET /status`, `GET /db/health` | Public |
| Users | `GET/POST /users`, `GET/PUT/DELETE /users/{id}` | Admin; `/users/me` authenticated |
| Exams | `GET/POST /exams`, `GET/PUT/DELETE /exams/{id}`, `POST /exams/{id}/publish` | GET student/teacher/admin; write teacher/admin |
| Questions | `GET/POST /questions`, `GET/PUT/DELETE /questions/{id}`, `GET/POST /exams/{examId}/questions` | teacher/admin for global question endpoints; exam-question GET allows student/teacher/admin |
| Options | `GET/POST /options`, `GET/PUT/DELETE /options/{id}`, `GET/POST /questions/{questionId}/options` | teacher/admin |
| Attempts | `POST /exams/{id}/start`, `POST /exams/{id}/submit` | Student |
| Answers | `GET /answers[?userId&examId]`, `GET /answers/{id}`, `POST /answers`, `PUT/DELETE /answers/{id}` | mixed; see known limitations below |
| Results | `GET /results`, `GET /results/{id}`, `POST/PUT/DELETE /results/{id}`, `GET /results/me[?userId]` | teacher/admin except `/results/me` allows all roles |
| Proctoring | `POST /proctor/events/batch`, `POST /proctor/attempts/{attemptId}/start|stop` | Student/teacher/admin |

`POST /exams/{id}/start` returns `{examId,studentId,status,exam}`. It does **not** currently return an attempt ID. `POST /exams/{id}/submit` accepts `{answers:[{questionId,optionId,value}]}` and returns `{result,score,total,percentage}`. Sending an option ID is preferred; it is verified as belonging to the question.

## Production contract changes required

The current API must be versioned before breaking changes. The following target endpoints replace unsafe/generic behavior:

| Capability | Target |
| --- | --- |
| Attempts | `POST /v1/exams/{examId}/attempts`, `GET /v1/attempts/{attemptId}`, `PUT /v1/attempts/{attemptId}/answers/{questionId}`, `POST /v1/attempts/{attemptId}/submit` |
| Retry safety | `Idempotency-Key` required for start and submit; response repeats for the same key and payload. |
| Lists | Cursor or page/size input and a `{items,page}` response; never return an unbounded collection. |
| Errors | Stable `code`, `message`, `details`, and `requestId`; do not require clients to match prose. |
| Concurrency | ETag/version on mutable admin resources; `412` or `409` for stale updates. |
| Time | `startedAt`, `expiresAt`, `submittedAt` as UTC instants; server supplies remaining time. |
| Proctoring | `POST /v1/attempts/{attemptId}/proctor-events:batch` with client event IDs and accepted/rejected counts. |

## Important current limitations

- Generic answer, result, user, question, option, and exam write routes do not perform resource ownership checks in the controller/service layer.
- `publish` is guarded only by role; a teacher can publish another teacher’s exam.
- `GET /exams/{id}/questions` hides `Question.answer` for students, but it does not represent a persisted attempt or an availability window.
- Proctor endpoints accept an arbitrary `attemptId`; no attempt table or ownership validation exists.
- `POST /answers` is not an attempt-scoped upsert. The exam submit path inserts answers, and only the result lookup makes duplicate submit appear idempotent; a database uniqueness constraint is missing.

Consumers should use this document with [backend requirements](BACKEND_REQUIREMENTS.md) and avoid depending on undocumented fields or messages.
