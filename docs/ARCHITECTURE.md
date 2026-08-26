# Examora architecture

**Status:** current architecture and approved production direction.  
**Last reviewed:** 2026-08-26.

## System overview

Examora is a web application with a Next.js frontend and a Spring Boot REST backend. The backend is currently a layered monolith using Spring Security, custom HS256 JWT handling, `JdbcTemplate`, startup SQL scripts, and MySQL. The production direction remains a modular monolith backed by PostgreSQL; splitting services is not justified until operational scale or independent deployment needs demand it.

```text
Browser (Next.js)
    │ HTTPS + JSON / Bearer JWT
    ▼
Spring Boot API
    ├── Controllers: HTTP/envelope mapping
    ├── Security: JWT filter + route authorization
    ├── Services: business rules / transactions
    ├── Repositories: JDBC SQL
    ▼
PostgreSQL (production) / MySQL (current code)
```

## Backend module boundaries

| Module | Current responsibility | Production boundary |
| --- | --- | --- |
| Auth/security | Register, login, JWT validation, Spring Security route rules. | Identity/session policy, secure token lifecycle, rate limiting, and authorization helpers. |
| Users | Admin CRUD and current-user lookup. | Account lifecycle and role administration with audit trail. |
| Exams | Exam CRUD, publication, aggregate statistics. | Ownership-aware authoring, scheduling, publication validation, content versioning. |
| Questions/options | Question CRUD and option management. | Authoring validation and immutable assessment snapshots. |
| Attempts | Start and submit service currently creates only final answers/results. | Persistent authoritative attempt lifecycle, timer, autosave, and idempotency. |
| Results | Result CRUD and “my results” lookup. | Immutable grading/result visibility, access-scoped reporting. |
| Proctoring | Batch event persistence. | Verified attempt event ingestion, deduplication, privacy controls, monitoring workflow. |

Controllers should remain thin. Services own authorization decisions that depend on resource relationships; repositories own persistence only. DTOs are the public API boundary—do not expose database records/answer keys directly as the contract matures.

## Request and exam-finalization flow

```text
Request → JWT filter → route authorization → controller → service
        → ownership/state validation → transactional repository operations
        → ApiResponse → client

Student submit → verify active, unexpired attempt and idempotency key
               → validate submitted answers against attempt snapshot
               → upsert/finalize answers + result atomically
               → emit audit/metrics event → repeatable response
```

The second flow is the production target. The current `start` endpoint returns a response but does not persist an attempt; current submit is result-idempotent only through a lookup, not an explicit attempt or idempotency record.

## Deployment topology

Production deployment should comprise:

- A static/SSR Next.js deployment and horizontally scalable stateless API instances behind an HTTPS reverse proxy or load balancer.
- Managed PostgreSQL in a private network boundary, encrypted at rest and in transit, with least-privilege application credentials.
- Secret manager integration for database credentials and JWT material.
- Centralized structured logs, metrics, traces, health/readiness checks, alerting, and an error tracker.
- CI/CD that runs tests, dependency/security checks, migration validation, deployment smoke tests, and a documented rollback procedure.

Do not expose the database directly to the browser. Do not place long-lived JWT secrets, database passwords, answer keys, or raw proctor recordings in source control or client bundles.

## Security model

Authentication identifies a user from a signed bearer token. Route rules provide coarse role control; domain services must additionally authorize the requested entity (teacher owns the exam; student owns the attempt/result). The database supplies a final integrity boundary through foreign keys, uniqueness, state/version fields, and transactions.

Current development CORS settings permit localhost frontend origins. Production requires an environment-controlled explicit origin allow-list and TLS only. CSRF is disabled because authentication is bearer-token based; if the app moves to cookie credentials, CSRF protection must be redesigned.

## Observability and resilience

Every request should carry or receive a correlation ID, propagated to structured logs and error responses. Capture latency, status, authentication failures, database pool health, submission outcomes, and proctor batch rejections without recording secrets or answer content. Readiness must verify essential dependencies; liveness must only establish that the process can run.

Submission is the highest-risk transaction: enforce unique database keys, short transactions, idempotent retries, and durable audit events. Backups are insufficient without regular restore verification. Establish RPO/RTO targets with the institution before launch.

## Architecture decisions and evolution

- Use PostgreSQL + Flyway before production; startup `schema.sql` is suitable only for the present development workflow.
- Prefer API versioning before introducing breaking schemas such as attempt-scoped resources.
- Add a queue/worker only for asynchronous work (notifications, heavy analytics, media processing); preserve synchronous, transactional submission in the API/database path.
- Extract a service only when a module has independent scaling, ownership, data, and deployment needs. Until then, modular package boundaries and tests are cheaper and safer.

See [database schema](DATABASE_SCHEMA.md), [API contract](API_CONTRACT.md), and [gap analysis](BACKEND_GAP_ANALYSIS.md) for implementation-level decisions.
