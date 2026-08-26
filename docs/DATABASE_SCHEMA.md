# Examora database schema

**Current implementation:** MySQL-oriented `src/main/resources/schema.sql`, initialized on application startup.  
**Production target:** PostgreSQL managed through Flyway migrations.  
**Last reviewed:** 2026-08-26.

## Current tables

| Table | Purpose | Key relationships |
| --- | --- | --- |
| `users` | Accounts and roles; `password_hash` is stored separately from the API model. | Unique `email`. |
| `exams` | Exam metadata, lifecycle string, and denormalized aggregate statistics. | Optional `created_by → users`. Current writes do not populate it. |
| `questions` | Question text and a legacy answer text. | `exam_id → exams` cascade delete. |
| `question_options` | Ordered selectable options and correctness flag. | `question_id → questions` cascade delete. |
| `answers` | Submitted/manual answer rows. | User nullable; exam/question cascade delete; option set null. |
| `results` | Score summary for a user/exam. | User/exam set null on deletion. |
| `proctor_events` | Minimal reported proctor events. | No foreign key to an attempt because attempts do not yet exist. |

All current primary keys are `varchar(36)` UUID strings. The schema includes `created_at`/`updated_at` on most entities, but MySQL-specific `ON UPDATE CURRENT_TIMESTAMP` is used and repository updates do not consistently maintain timestamps.

## Current relationship map

```text
users 1 ──< exams.created_by          (optional; currently not enforced by writes)
users 1 ──< answers.user_id
users 1 ──< results.user_id
exams 1 ──< questions 1 ──< question_options
exams 1 ──< answers
exams 1 ──< results
question_options 1 ──< answers.option_id
```

## Production target model

The existing schema cannot provide authoritative timers or secure attempt ownership. Retain the authoring tables, then add:

| Table | Required columns and constraints |
| --- | --- |
| `exam_attempts` | `id uuid PK`, `exam_id`, `student_id`, `attempt_number`, `status`, `started_at timestamptz`, `expires_at timestamptz`, `submitted_at timestamptz`, `content_version`, `version`; unique `(exam_id, student_id, attempt_number)`. |
| `attempt_answers` | `attempt_id`, `question_id`, `option_id`, `value`, `answered_at`, `version`; unique `(attempt_id, question_id)`. Do not reuse the current generic `answers` table without this scope. |
| `exam_content_versions` / snapshots | Immutable version or serialized snapshot referenced by an attempt, so author edits cannot change an in-progress assessment. |
| `results` enhancement | Add non-null `attempt_id` unique FK, `graded_at`, grading version, and result visibility/state. Finalized rows should be immutable. |
| `proctor_events` enhancement | Add `attempt_id UUID FK`, `event_id` unique per attempt, `occurred_at timestamptz`, `received_at timestamptz`, structured `metadata jsonb`, and validation/retention controls. |
| `audit_log` | Actor, action, entity type/id, timestamp, request/correlation ID, and sanitized before/after summary for privileged actions. |
| `idempotency_keys` (or equivalent) | Principal, key, request hash, response/status, created/expiry timestamps; unique per principal/key. |

Use database `CHECK` constraints or PostgreSQL enums for roles and carefully managed lifecycle values. Typical states are `DRAFT`, `PUBLISHED`, `CLOSED`, `ARCHIVED` for an exam, and `STARTED`, `SUBMITTED`, `EXPIRED`, `EVALUATED`, `VOIDED` for an attempt. State transitions are enforced in application transactions, not by client input.

## Required integrity and indexes

- `users.email` must use case-insensitive uniqueness (for example a unique index on `lower(email)` or `citext`).
- Enforce exactly one correct option per multiple-choice question using application validation plus a deferred constraint/trigger where appropriate.
- Index foreign keys and primary access paths: `exams(created_by,status,scheduled_at)`, `questions(exam_id,display_order)`, `exam_attempts(student_id,status)`, `exam_attempts(exam_id,status)`, `attempt_answers(attempt_id)`, `results(student_id,exam_id)`, and `proctor_events(attempt_id,occurred_at)`.
- Do not cascade-delete completed attempts, answers, results, or audit records. Archive/soft-delete parent business records instead.
- Keep timestamps in `timestamptz` and store UTC. Replace string date/time columns with typed values.
- Use `numeric` for scored percentages if persisted; compute or store integer score and total rather than relying on floats.

## Migration and operations policy

1. Add Flyway and move all schema changes to ordered, immutable migrations under `src/main/resources/db/migration`.
2. Create a PostgreSQL baseline from the current model, fixing MySQL syntax (`ON UPDATE`, backticked `value`, default behavior) and explicitly maintaining `updated_at` via trigger/application SQL.
3. Run migrations automatically only through a controlled deployment process; application service accounts must not be database owners in production.
4. Back up encrypted PostgreSQL data on a tested schedule, monitor backup completion, and routinely prove a point-in-time restore in a separate environment.
5. Establish data retention, especially for answers and proctoring metadata, before production ingestion.

The exact DDL should be generated in migrations and reviewed alongside code; this document defines the invariants those migrations must enforce.
