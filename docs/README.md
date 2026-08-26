# Examora Production Readiness Guide

This document is a full, step-by-step production overview for the current Examora repository.
It is based on the existing backend/frontend implementation and your requirement to run PostgreSQL with a modular layered monolith backend.

## 1. What this project is

Examora is an online exam platform with role-based access:
- STUDENT
- TEACHER
- ADMIN

Current stack:
- Backend: Spring Boot 3, Java 21, JDBC, JWT-based stateless auth
- Frontend: Next.js app shell with client-side React routes
- Database in current code: MySQL-oriented SQL/scripts
- Target database for production (your requirement): PostgreSQL

## 2. Current implementation status

### Already fixed in current codebase

From current fixes:
- Duplicate submission protection added for exam submit (same student + exam re-submit returns existing result).
- Question validation improved (minimum 2 options, correct answer must match options).
- Demo teacher and student seed users added for local demonstration.

### Current production blockers

1. Database configuration and schema are MySQL-specific, not PostgreSQL-ready.
2. Several frontend flows still use mock/local state for exam lifecycle.
3. No migration/versioning tool in place (Flyway/Liquibase) for safe production schema evolution.
4. CORS and environment setup are development oriented.
5. No explicit production runbook for monitoring, backup, and operational maintenance.

## 3. Backend architecture standard (modular layered monolith)

Use and enforce this structure per module:

- controller layer
  - HTTP request/response handling only
  - Auth context extraction only
  - No business rules

- service layer
  - Business rules
  - Validation and orchestration across repositories
  - Transaction boundaries

- repository layer
  - SQL and persistence logic only
  - No business policy decisions

- model/dto layer
  - Domain records/entities and API DTO contracts

Current package structure already follows this direction:
- src/main/java/com/examora/controller
- src/main/java/com/examora/service
- src/main/java/com/examora/repository
- src/main/java/com/examora/model
- src/main/java/com/examora/dto

## 4. Existing API surface (implemented)

Auth:
- POST /api/auth/login
- POST /api/auth/register
- POST /api/auth/logout

Users:
- GET /api/users
- GET /api/users/{id}
- GET /api/users/me
- POST /api/users
- PUT /api/users/{id}
- DELETE /api/users/{id}

Exams:
- GET /api/exams
- GET /api/exams/{id}
- POST /api/exams
- PUT /api/exams/{id}
- DELETE /api/exams/{id}
- POST /api/exams/{id}/publish
- POST /api/exams/{id}/start
- POST /api/exams/{id}/submit

Questions and options:
- GET /api/questions
- GET /api/questions/{id}
- GET /api/exams/{examId}/questions
- POST /api/questions
- POST /api/exams/{examId}/questions
- PUT /api/questions/{id}
- DELETE /api/questions/{id}
- GET /api/options
- GET /api/options/{id}
- GET /api/questions/{questionId}/options
- POST /api/options
- POST /api/questions/{questionId}/options
- PUT /api/options/{id}
- DELETE /api/options/{id}

Results:
- GET /api/results
- GET /api/results/me
- GET /api/results/{id}
- POST /api/results
- PUT /api/results/{id}
- DELETE /api/results/{id}

Answers:
- GET /api/answers
- GET /api/answers/{id}
- POST /api/answers
- PUT /api/answers/{id}
- DELETE /api/answers/{id}

Proctoring:
- POST /api/proctor/events/batch
- POST /api/proctor/attempts/{attemptId}/start
- POST /api/proctor/attempts/{attemptId}/stop

Health:
- GET /api/status
- GET /api/db/health

## 5. Production changes required (high priority)

## 5.1 PostgreSQL migration (must do)

Current code still has MySQL dependency and MySQL datasource defaults.

### A) Dependency changes

In pom.xml:
- Remove MySQL connector dependency.
- Add PostgreSQL JDBC driver dependency.

### B) Datasource changes

In application properties for production profile:
- spring.datasource.url should be PostgreSQL format:
  - jdbc:postgresql://<host>:5432/<database>
- spring.datasource.driver-class-name should be:
  - org.postgresql.Driver
- Use environment variables for user/password and do not hardcode secrets.

### C) SQL compatibility updates

Current schema.sql contains MySQL-specific syntax. Update these:
1. Replace "on update current_timestamp" usage.
2. Replace MySQL-style quoted column name for value field.
3. Review timestamp/date column types:
   - exam date should become DATE or TIMESTAMPTZ (not VARCHAR).
   - occurred_at should become TIMESTAMPTZ (not VARCHAR).
4. Validate decimal/int types and default expressions for PostgreSQL.

### D) Add DB migration tool

Introduce Flyway (recommended) or Liquibase:
- Create baseline migration from current schema.
- Move seed data into controlled migration scripts.
- Make schema change history auditable and repeatable.

## 5.2 Security hardening

1. Change JWT secret immediately in production.
2. Reduce JWT lifetime and define refresh/re-login behavior.
3. Remove or disable demo accounts in production.
4. Add rate limiting for login and sensitive endpoints.
5. Ensure role + ownership checks for every read/write path.
6. Keep CORS origins strict to real frontend domain(s) only.

## 5.3 API and lifecycle hardening

1. Add server-authoritative exam attempt lifecycle:
   - CREATED -> IN_PROGRESS -> SUBMITTED/EXPIRED -> EVALUATED
2. Add idempotency protection for all critical write operations (submit, autosave retries).
3. Add clear error contracts for validation and business-rule errors.
4. Ensure no answer keys leak to student question payloads before submission.

## 5.4 Frontend and backend contract alignment

1. Remove production-visible mock data paths.
2. Ensure exam start, autosave, restore, submit, and result screens use real API contracts.
3. Keep fallback behavior as explicit "data unavailable" states, not fabricated values.

## 6. What still needs to be implemented

## 6.1 Core exam lifecycle completeness

Must implement and verify:
- Attempt restore after refresh/reconnect.
- Server-side timer authority and expiry enforcement.
- Autosave with retry semantics.
- Final submit confirmation and retry-safe behavior.

## 6.2 Analytics and AI production path

- Replace hardcoded analytics fallbacks with backend-owned outputs.
- Ensure recommendation/adaptive decisions are server-driven.
- Persist adaptive session state on backend.

## 6.3 Proctoring reliability

- Enforce event association to authenticated attempt and user.
- Add deduplication strategy for repeated client events.
- Add moderation/risk scoring policy with audit logging.

## 6.4 Operational completeness

- Add OpenAPI/Swagger documentation.
- Add structured logging and correlation IDs.
- Add alerts for auth failures, DB failures, and submit errors.

## 7. Maintenance responsibilities

## Daily
- Check API error rates and auth failures.
- Check exam submit success rates.
- Check DB health and connection pool metrics.

## Weekly
- Review security logs and suspicious proctoring patterns.
- Review slow SQL queries and optimize indexes.
- Verify backup success and restore test sample.

## Monthly
- Rotate secrets and review JWT configuration.
- Dependency updates and CVE scanning.
- Capacity planning and load/performance review.

## Each release
- Run integration tests.
- Run schema migration in staging.
- Verify role-access matrix for critical endpoints.
- Run smoke tests for login/start/save/submit/results.

## 8. Step-by-step production rollout plan

1. Freeze API contract and DTO definitions between frontend and backend.
2. Add PostgreSQL driver and production profile settings.
3. Convert schema to PostgreSQL-compatible DDL.
4. Introduce Flyway migrations and baseline existing schema.
5. Disable demo seed users for production profile.
6. Add strict env-based CORS and security headers.
7. Finalize attempt lifecycle and autosave/restore APIs.
8. Remove frontend mock production paths and wire all critical flows to API.
9. Add test layers:
   - unit tests (service)
   - integration tests (controller + DB)
   - end-to-end flow tests (student and teacher journeys)
10. Deploy to staging with PostgreSQL and run full UAT.
11. Configure observability, alerts, and backup/restore policy.
12. Production go-live with rollback plan and post-release monitoring.

## 9. Suggested environment variables (production)

Required:
- SERVER_PORT
- DB_URL
- DB_USERNAME
- DB_PASSWORD
- JWT_SECRET
- JWT_EXPIRATION_HOURS

Recommended additional:
- APP_ENV=prod
- CORS_ALLOWED_ORIGINS
- LOG_LEVEL

## 10. Definition of done for production readiness

Examora is production-ready when all conditions below are true:

1. Backend runs against PostgreSQL with migration scripts and no MySQL-only SQL.
2. All critical student flows are backend-driven:
   - login
   - list exams
   - start attempt
   - autosave
   - restore
   - submit
   - view result/history
3. Teacher/Admin CRUD and publish flows are fully persisted and authorized.
4. Mock/fallback fabricated data is removed from production paths.
5. Security checklist is completed (JWT secret rotation, CORS hardening, rate limiting, role checks).
6. Monitoring, backup, and incident runbook are documented and tested.

## 11. Immediate next actions for this repository

1. Create application-prod.properties for PostgreSQL and production CORS.
2. Update pom.xml to PostgreSQL driver and add Flyway.
3. Convert schema.sql/data.sql into Flyway migrations for PostgreSQL.
4. Remove production demo accounts or gate them by local profile only.
5. Run integration tests against PostgreSQL test container/staging database.

---

If you want, the next step can be implementation-focused: I can now apply the actual PostgreSQL migration changes in code/config files and create a production profile plus Flyway migration scripts in this repo.
