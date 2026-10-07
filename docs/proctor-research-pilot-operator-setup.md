# Proctor Research Pilot - Operator Setup

Operational setup guide for the first controlled research pilot. This document covers
the accounts a pilot needs, the exact - and currently API-only - steps to establish
them, and how each account is used for RQ evaluation. It assumes the state described in
[proctor-research-pilot-execution-protocol.md](proctor-research-pilot-execution-protocol.md).

Status: **documentation only**. No code, schema, data-collection, or analysis changes
are made by following this document; creating accounts and driving the research APIs
requires an operator who holds valid ADMIN credentials.

## 1. Roles needed

| Role | Account | Purpose |
| ---- | ------- | ------- |
| ADMIN | "Reviewer A" (existing) | Drives the research API flow (activate experiment, create run, plan samples, observe/capture) and submits review #1. |
| ADMIN | "Reviewer B" (to be created) | Independent second review of each captured sample. |
| STUDENT | test student (e.g. `student@examora.local`) | Runs a real proctored exam attempt in a browser; produces the genuine proctor events and measurements the sample captures. |
| TEACHER or ADMIN | exam monitor (optional) | Observes the live session from the monitoring UI during the attempt. |

### Why two distinct ADMIN accounts are required

`ResearchService.addReview` records `reviewer_id = actor.id()` and the database
enforces a unique `(sample_id, reviewer_id)` constraint. RQ3 (and the independence
guarantees for RQ1/RQ2) require two reviews per sample to come from **two different
user accounts**. The live database currently has exactly one ADMIN
(`admin@example.com`), so a second ADMIN must be provisioned before any review pair
can be collected.

## 2. Baseline state (verified 2026-09-19)

- Users: 16 - 1 ADMIN (`admin@example.com`), 1 TEACHER (`teacher@examora.local`),
  14 STUDENT (including test accounts `student@examora.local` and `verify-*@test.local`).
- Exams: 3 - `week-1` (DSA), `COMPETETIVE PROGRAMMING` (DSA), `IA-2` (OS); all in
  status `UPCOMING` with no start/end window; 5 questions total (week-1: 3,
  COMPETETIVE PROGRAMMING: 1, IA-2: 1). All 3 exam rooms are `ENDED`.
- Attempts: 11 historical attempts between 2026-09-06 and 2026-09-18
  (max per student: `thamim72@gmail.com` = 5; `student@examora.local` = 0).
- Research: 1 experiment (`fusion comparision`, DRAFT), 0 runs, 0 run samples,
  0 research samples, 0 reviews.
- The frontend does **not** provide an admin/user-management UI (see Section 4);
  user creation is API-only.

## 3. Establishing the second ADMIN account (Reviewer B)

User management endpoints live in `UserController` under `/api/users` and all require
`Permission.USER_MANAGE`, which the ADMIN role holds (confirmed in
`RolePermissions.java:50`).

### Prerequisite - bootstrap ADMIN session

The first create call must be authenticated as an existing ADMIN. The pilot cannot
bootstrap itself if the existing ADMIN password is unknown. Note: a prior probe with
`admin123` against the live `POST /api/auth/login` was rejected - the test-default
password is **not** the live password. The operator must obtain the real credential
from the account owner (e.g. `admin@example.com`) or, if authorized, perform an
operations-level password recovery. Do not mint a JWT from `JWT_SECRET`; that is
forged authentication and is explicitly disallowed.

### Step 1 - authenticate as the existing ADMIN

```
POST /api/auth/login
Content-Type: application/json

{"email":"admin@example.com","password":"<real admin password>"}
```

Success response carries the JWT in `data.token` (verify via
`GET /api/users/me` with `Authorization: Bearer <token>`).

### Step 2 - create Reviewer B as a second ADMIN

```
POST /api/users
Authorization: Bearer <Reviewer-A-token>
Content-Type: application/json

{
  "name": "Admin Reviewer B",
  "email": "admin-b@example.com",
  "password": "<distinct password, at least 8 chars>",
  "role": "ADMIN"
}
```

Behaviour (confirmed in `UserService.create`, `UserService.java:36-57`):

- `name` and `email` are required; `email` is lowercased before storage.
- `password` is required and must be at least 8 characters.
- `role` is honored as supplied (defaults to `STUDENT` if omitted) - so `"role":"ADMIN"`
  produces a second ADMIN account. This is the supported path; there is no separate
  "elevate to admin" operation.
- Duplicate email returns HTTP 409 `Email is already registered.`
- Success returns `ApiResponse<User>`:
  `{"success":true,"code":"Created","data":{"id":"<uuid>","name":...,"email":...,"role":"ADMIN",...}}`

Example using curl (substitute the real token):

```bash
curl -sS http://localhost:8080/api/users \
  -H "Authorization: Bearer $REVIEWER_A_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Admin Reviewer B","email":"admin-b@example.com","password":"ChangeMePilot1!","role":"ADMIN"}'
```

### Step 3 - verify both ADMIN sessions independently

```
POST /api/auth/login   {"email":"admin@example.com","password":"..."}   -> JWT A
POST /api/auth/login   {"email":"admin-b@example.com","password":"..."} -> JWT B
```

Keep JWT A and JWT B in separate sessions/browsers. Every `sample_id` must be reviewed
once with A and once with B so that the two `reviewer_id` values differ.

## 4. Frontend user-management UI - finding

There is **no** frontend user-management UI:

- `frontend/app/page.tsx` routes `/admin/users` (line 853) to `<AdminUsers />`, but
  `AdminUsers()` (page.tsx:534) renders a **read-only** list of users
  (name/email/role) with no create/update/delete actions.
- `frontend/services/userApi.ts` exposes only `me()` and `list()`; no create/update/
  delete client methods exist.
- The sidebar `/admin/users` link is therefore a dead-end for provisioning.

Consequence: establishing Reviewer B requires the API call in Section 3 (or a direct
DB insert, which is not supported by the application). The app's supported flow for
creating any account - STUDENT, TEACHER, or ADMIN - is `POST /api/users` by an
authenticated ADMIN.

## 5. Preparing the live exam attempt the run will reference

A research sample attaches to a real attempt id (`exam_attempts.id`), so a live
proctored attempt must exist before samples are planned.

1. **Publish an exam with a window.** All current exams are `UPCOMING` with no window.
   Publish one (e.g. `IA-2` / `week-1`) via the ADMIN/teacher session:

   ```
   POST /api/exams/{examId}/publish        (EXAM_MANAGE)
   ```

   Confirm the exam has at least one question first:
   `GET /api/exams/{examId}/questions`. (Counts today: week-1 = 3,
   COMPETETIVE PROGRAMMING = 1, IA-2 = 1.)

2. **Create a fresh room.** All three existing rooms are `ENDED`; create a new one so
   the live session is isolated:

   ```
   POST /api/exam-rooms                      (create room; returns room with code)
   POST /api/exam-rooms/{roomId}/start       (open the proctoring session)
   ```

3. **Student joins and starts a real attempt in a browser.** Using the STUDENT session
   (a test account with 0 attempts, e.g. `student@examora.local`):

   ```
   POST /api/exam-rooms/join   {"roomCode":"<code>"}          -> joins the room
   POST /api/exams/{examId}/start                             -> creates the attempt
   ```

   The response contains the new `exam_attempts.id` - record it; this becomes the
   `attemptId` used when planning a research sample.

## 6. How each account is used for RQ evaluation

The research API surface and lifecycle are specified in
[proctor-research-pilot-execution-protocol.md](proctor-research-pilot-execution-protocol.md).

- Reviewer A (existing ADMIN) drives the suite while the attempt is live:
  1. `POST /api/proctor/research/experiments/{id}/status` -> ACTIVE
  2. `POST /api/proctor/research/experiments/{id}/runs` -> create run
  3. `POST /api/proctor/research/runs/{runId}/samples` -> plan a sample with the live
     `attemptId`, scenario, and conditions
  4. `POST /api/proctor/research/runs/{runId}/start` -> RUNNING
  5. `POST /api/proctor/research/runs/{runId}/samples/{runSampleId}/observe`
     (server stamps `started_at` at first observation)
  6. After the observed proctor event, `POST .../capture` with the genuine
     `endedAt`, `measuredLatencyMs`, `rawMediaBytes`, and `signalBytes` from the live
     browser session (capture window 1-30 s).
- Reviewer A then submits review #1: `POST /api/proctor/research/samples/{sampleId}/review`.
- Reviewer B (created per Section 3) submits review #2 on the **same** sample with
  their **own** login and a distinct reviewer identity. Independence is enforced by the
  unique `(sample_id, reviewer_id)` constraint.
- RQ1 (signal-detection accuracy) - drawn from per-sample reviews against the expected
  signal for the planned scenario.
- RQ2 (latency and payload size) - drawn from the real `measuredLatencyMs`,
  `rawMediaBytes`, and `signalBytes` captured values, cross-checked by both reviews.
- RQ3 (evaluator agreement) - `GET /api/proctor/research/runs/{runId}/evaluation` and
  `GET /api/proctor/research/experiments/{id}/analysis` compare the two reviews; both
  reviewers must have reviewed the sample or the sample is excluded from agreement
  metrics.
- The STUDENT account appears only as the source of the live attempt; the monitor
  account (optional) is a passive observer.

## 7. Hard rules

- **No fabricated capture data.** `measuredLatencyMs`, `rawMediaBytes`, and
  `signalBytes` must come from real proctor events observed during the live browser
  session. No synthetic payloads.
- **No forged authentication.** JWT_SECRET must never be used to mint a token; all
  calls use normal `POST /api/auth/login` sessions.
- **No account creation without operator authorization.** The second ADMIN must be
  provisioned by an operator holding valid credentials, per Section 3.
- Reviewer A and Reviewer B must never share a session.

## 8. Remaining manual prerequisites before the first capture

1. Real ADMIN credential for `admin@example.com` (bootstrap session).
2. Second ADMIN created and verified (Section 3).
3. Exam published, window set, fresh room opened (Section 5).
4. Live student attempt running in a real browser with a known `attemptId`.
5. Operator present during the session to observe events and capture real values
   within the 1-30 s window.