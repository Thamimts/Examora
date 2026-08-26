# Backend production gap analysis

**Assessment date:** 2026-08-26. This assessment is based on the repository source, configuration, schema, and integration tests—not a deployed-environment audit.

## Executive summary

The backend has a good functional foundation: Spring Security protects many routes, registration/login use BCrypt, question creation validates answer options, student question responses hide the legacy `answer` field, and integration tests cover basic roles plus a happy-path exam submission. It is **not production-ready** for a real examination workflow. The largest risks are missing persistent attempts/timers, incomplete ownership authorization, MySQL/startup schema usage, and unvalidated proctoring/answer APIs.

## Findings

| Priority | Gap | Evidence | Required remediation |
| --- | --- | --- | --- |
| P0 | No persistent exam attempts or server timer | `start` returns `STARTED` without database write; no `exam_attempts` table. | Introduce attempt state/table and expiry calculated from server time. Scope all answers, proctor events, and submission to it. |
| P0 | Ownership controls are missing | Teachers can call write/publish routes for any ID; generic results/answers are role-gated but not entity-scoped. | Pass authenticated actor into services and enforce teacher ownership/admin override and student self-only access. |
| P0 | Production database target mismatches code | Maven/config/schema are MySQL-specific; requirements identify PostgreSQL as target. | Move to PostgreSQL driver/config and Flyway migrations; test real PostgreSQL, not only H2 MySQL compatibility mode. |
| P0 | Sensitive proctor endpoints trust caller-supplied attempt IDs | No attempt validation; start/stop return placeholder maps. Metadata is ignored. | Bind events to authenticated active attempt, validate/dedupe batch, store controlled metadata, set retention/access policy. |
| P1 | Submission integrity is incomplete | Result lookup prevents a second result, but no unique result constraint; answers are created per submission and no attempt/question uniqueness exists. | Add transactional constraints, idempotency keys, unique finalized result per attempt, and retry tests. |
| P1 | Current schema allows destructive loss of assessment data | Questions/exams cascade delete answers; results are nullable on deleted parent records. | Archive entities, preserve finalized attempts/results/audit data, and define retention. |
| P1 | Secret and CORS defaults are unsafe for production | A default JWT secret exists; CORS origins are hard-coded localhost values. | Fail closed on secret/config validation; use secret manager and environment-specific origin allow-list. |
| P1 | Input and error contracts are weak | Controllers use raw models with no `@Valid`; errors have only mutable prose messages. | Introduce request DTOs, bean validation, stable error codes/details/request IDs, and API versioning. |
| P1 | User/admin endpoints can create users without a password flow and change roles freely | `POST /users` accepts `User`; service permits supplied role; admin-only route is present. | Separate account provisioning from profile updates; audit role changes and require explicit password/invite workflow. |
| P2 | Operational readiness is absent | No profiles, health groups, structured logging, metrics/traces, rate limiting, backup or deployment documentation. | Add production profile, Actuator/observability, runbooks, load test, backup/restore test, and CI gates. |
| P2 | Query/list scalability needs work | List endpoints are unpaginated; question repository creates N+1 option queries. | Add pagination/cursors, proper indexes, query batching, and performance tests. |
| P2 | Test coverage is limited | Tests cover H2 happy paths and selected role checks. | Add PostgreSQL integration tests, authorization matrix, expiry/concurrency/idempotency, migration, and negative payload tests. |

## Current strengths worth preserving

- Stateless Spring Security setup and clear `401`/`403` handling.
- Password verification migrates legacy PBKDF2 hashes to BCrypt on successful login.
- Submission checks student role, exam question membership, duplicate question entries, and option/question membership.
- Question services intentionally remove `answer` from student-facing question payloads.
- Existing integration tests are a useful starting point for a broader security and lifecycle suite.

## Recommended implementation sequence

1. Establish PostgreSQL, Flyway, environment profiles, configuration validation, and a PostgreSQL-backed test fixture.
2. Add `created_by` write support and ownership checks; lock down generic answer/result/proctor routes while replacing them with scoped routes.
3. Build persistent attempts, server time windows, answer upserts, immutable content snapshots, and idempotent finalization backed by database constraints.
4. Implement proctor event authorization/deduplication and privacy controls.
5. Version and document the API, add validation/pagination/error codes, and migrate frontend calls deliberately.
6. Add observability, rate limiting, audit logs, CI/CD gates, load testing, and backup/restore/rollback runbooks.

## Release decision

**Do not launch an institution-facing production exam service until P0 findings are closed and P1 items are either closed or explicitly risk-accepted by accountable owners.** The present code can continue as a development/demo baseline while those controls are implemented.
