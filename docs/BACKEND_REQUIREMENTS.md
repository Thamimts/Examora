# Examora backend requirements

**Status:** production target; not a statement that every requirement is implemented.  
**Source of truth for current behavior:** the Spring Boot code and [API contract](API_CONTRACT.md).  
**Last reviewed:** 2026-08-26.

## Purpose and scope

Examora is an online examination platform for students, teachers, and administrators. The backend must provide authenticated, auditable, reliable exam delivery and administration. It is the authority for identity, authorization, published content, time limits, attempt state, grading, and final results.

The initial production architecture is a modular layered monolith: Spring Boot REST API, PostgreSQL, and a separately deployed Next.js client. This is deliberately simpler than microservices while retaining clear module boundaries.

## Functional requirements

### Identity and access

- Roles are `STUDENT`, `TEACHER`, and `ADMIN`.
- Passwords must be stored only as adaptive one-way hashes (BCrypt is currently used); plaintext passwords and password hashes must never be returned.
- Login issues a signed, expiring access token. Production secrets must be supplied through a secret manager or environment, never a checked-in default.
- Every protected request uses `Authorization: Bearer <token>`. Invalid, expired, malformed, or revoked credentials return `401`; authenticated callers lacking permission return `403`.
- Authorization must combine route-level roles with ownership checks. A teacher may administer only exams they own (unless they are an admin); a student may access only their own attempts, answers, results, and proctor data.
- Registration creates students only. Role assignment, user deletion, and account administration are admin-only and must be audited.
- Logout must be idempotent. If token revocation is introduced, use a `jti`/session record with bounded retention; deleting a client token alone is not server-side revocation.

### Exam authoring and publication

- Teachers can create, edit, and delete their draft exams and question banks; admins can operate across owners.
- An exam must have a title, subject, scheduled window (UTC), positive duration, owner, and an explicit lifecycle state.
- A multiple-choice question requires at least two ordered options and exactly one correct option. Correct answers are write-only to students.
- Publishing validates that the exam is complete and immutable content is versioned or snapshotted for active attempts.
- An exam must not be destructively edited or deleted when attempts/results exist. Use archival/soft deletion and an explicit retention policy.

### Attempt, submission, and grading

- Persist an attempt at start, with a unique `(exam_id, student_id, attempt_number)` constraint, server start/end timestamps, state, and content snapshot/version.
- The server calculates availability, remaining time, and automatic expiry. Client clocks are informational only.
- Save-answer is an authenticated upsert scoped to the caller’s active attempt and question, and is retry-safe through an idempotency key or unique attempt/question constraint.
- Submission is transactional and idempotent. Duplicate submissions return the already finalized result without creating duplicate answers/results.
- Submission validates that submitted questions/options belong to the attempt snapshot, records unanswered questions, grades consistently, and creates one immutable result per finalized attempt.
- Expose result visibility separately from grading so assessment policies can defer results.

### Proctoring

- Proctor events are scoped to a verified active attempt and its student. Clients may report events but cannot choose another attempt.
- Batches require schema validation, size limits, event identifiers for deduplication, server receipt time, client event time, type, and sanitized metadata.
- Proctor data is sensitive personal data: define consent, access roles, retention/deletion, encryption, and incident review procedures before collection.

## Non-functional requirements

| Area | Requirement |
| --- | --- |
| Data store | PostgreSQL in production, with versioned Flyway migrations. H2 is test-only. |
| Availability | Stateless API instances behind TLS termination; database backups and tested restore procedures. |
| Security | HTTPS, secret rotation, restrictive CORS allow-list, rate limits for auth, secure headers, dependency scanning, and least-privilege database credentials. |
| Observability | JSON logs with request/correlation ID, metrics, traces, readiness/liveness probes, and alerting for auth failures, errors, latency, and submission failures. |
| API quality | Versioned `/api/v1` contract (or documented compatibility policy), validation errors, pagination on unbounded lists, OpenAPI, and backward-compatible changes only within a version. |
| Reliability | Transactions around finalization, database uniqueness constraints, optimistic locking/versioning where concurrent edits are possible, and explicit retry semantics. |
| Privacy | Data classification, minimization, retention schedule, export/deletion workflow, and audit trail for sensitive operations. |

## Required production configuration

| Setting | Requirement |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection; credentials are secret-managed and have only required privileges. |
| `JWT_SECRET` | High-entropy secret, at least 256 bits, unique per environment, rotated by process. Startup must fail if it is absent or weak. |
| `JWT_EXPIRATION_HOURS` | Short-lived access-token policy appropriate to the institution; document refresh/re-auth behavior. |
| CORS origins | Configured per environment; localhost origins are development-only. |
| Logging | Never log tokens, passwords, answer keys, or unredacted proctor metadata. |

## Delivery gates

Before production release, the team must demonstrate:

1. PostgreSQL migrations apply cleanly to an empty database and an upgrade fixture; no MySQL-specific DDL/query syntax remains.
2. All sensitive endpoints have role and ownership tests, including negative cases.
3. Start, save, submit, timeout, and duplicate-submit flows are integration-tested against PostgreSQL.
4. Student API payloads cannot expose answers, answer keys, or teacher-only proctor data.
5. A load/error test validates the expected concurrent-exam volume and submission burst.
6. Monitoring, backup restoration, deployment rollback, secret rotation, and incident runbooks have been exercised.

See [gap analysis](BACKEND_GAP_ANALYSIS.md) for the current delta and recommended order of work.
