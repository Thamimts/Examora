# Examora staging deployment checklist

This checklist reflects the current Spring Boot, Flyway, PostgreSQL, Next.js, Render, and Vercel configuration. It is a deployment preparation guide; it does not deploy the application.

## Render backend

Create a **Web Service** using the **Docker** runtime with the repository root (`.`) as its root/build context. The root [Dockerfile](../Dockerfile) builds the Spring Boot JAR with Maven and Java 21, then runs it on a Java 21 JRE as a non-root user. Leave the Docker command empty so the image entrypoint runs. Render supplies `PORT` (default `10000`); the backend now uses `PORT` first, then `SERVER_PORT`, then `8080`. The server binds to the container interface by default. Do not set a separate start/build command.

In the Render service environment, set the backend variables in the table below. For Render Postgres in the same region, use its internal host and database name to form `DB_URL=jdbc:postgresql://<internal-host>:5432/<database>`; add `?sslmode=require` if you require TLS on the internal connection. Put the database username and password in `DB_USERNAME` and `DB_PASSWORD`. Render provides a `postgresql://...` URL, which is not itself a JDBC URL, so do not copy it unchanged into `DB_URL`. Keep the database and web service in the same Render region to use the private network.

Render references: [Docker services](https://render.com/docs/docker), [web services and port binding](https://render.com/docs/web-services), [Render Postgres connections](https://render.com/docs/postgresql-creating-connecting).

## Backend environment

Set these in the backend staging service:

| Variable | Required | Value / guidance |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Yes | `prod`; this disables `schema.sql` initialization and enables Flyway. |
| `DB_URL` | Yes | PostgreSQL JDBC URL, for example `jdbc:postgresql://<host>:5432/<database>?sslmode=require` when the provider requires TLS. |
| `DB_USERNAME` | Yes | Dedicated staging database role with only the permissions needed to run migrations and operate the app. |
| `DB_PASSWORD` | Yes | Supply through the host's secret manager. Never use a `NEXT_PUBLIC_` name. |
| `JWT_SECRET` | Yes | Unique staging secret, at least 32 characters; generate a random value (for example `openssl rand -base64 48`). Never reuse a development or production secret. |
| `CORS_ALLOWED_ORIGINS` | Yes | Exact comma-separated HTTPS frontend origins, with no path or trailing slash. This same list controls HTTP CORS and WebSocket handshakes. |
| `EXAMORA_INITIAL_ADMIN_EMAIL` | First bootstrap | Set with the password below to create the first admin if one does not already exist. Existing accounts are left unchanged. |
| `EXAMORA_INITIAL_ADMIN_PASSWORD` | First bootstrap | Set with the email above; at least 12 characters. Remove both bootstrap values after the first successful startup. |

Set these deliberately as needed:

| Variable | When needed | Value / guidance |
| --- | --- | --- |
| `PORT` | Render sets this automatically | Spring uses Render’s `PORT` first (Render default `10000`). Usually do not set it manually. |
| `SERVER_PORT` | Local or non-Render fallback | Used only if `PORT` is unset; defaults to `8080`. |
| `JWT_EXPIRATION_HOURS` | Always review | Defaults to 24 hours. Set the staging session lifetime according to your policy. |
| `EXAMORA_API_URL` | Optional Vercel server route override | Backend base URL ending in `/api` that is reachable from Vercel. A Render private hostname is not reachable from Vercel; use the public Render URL or leave unset to use `NEXT_PUBLIC_API_URL`. |
| `OAUTH_ENABLED` | OAuth sign-in is offered | Set `true` only after at least one provider has its client ID, client secret, and exact callback URI configured. Configure `OAUTH_FRONTEND_BASE` as the staging frontend URL. |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI` | Google OAuth enabled | Store the secret privately; callback should be `<backend-origin>/api/auth/oauth/google/callback`. |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `GITHUB_REDIRECT_URI` | GitHub OAuth enabled | Store the secret privately; callback should be `<backend-origin>/api/auth/oauth/github/callback`. |
| `GROQ_API_KEY` | AI coach, AI practice, or roadmap enabled | Server-only secret; do not prefix with `NEXT_PUBLIC_`. |
| `OPENAI_API_KEY` | AI provider fallback is deliberately used | Server-only secret; do not prefix with `NEXT_PUBLIC_`. |
| `TRUSTED_PROXY` | App is reachable only through a proxy that sanitizes `X-Forwarded-For` | Set `true` only for that topology. Restrict accepted peers with `TRUSTED_PROXY_ADDRESSES` where practical. |

`ADMIN_TWO_FACTOR_MANDATORY` defaults to `true`; keep it enabled for staging. Administrators must enroll in TOTP before receiving a normal session. Do not activate the `demo` profile. `SQL_INIT_MODE` and `FLYWAY_ENABLED` are overridden by the `prod` profile: SQL initialization is disabled and Flyway is enabled. The profile baselines an unversioned existing schema at version `0`, then applies migration `1`; this was verified against a disposable populated database, with an existing synthetic row preserved.


Optional tuning keys read by the backend retain these code defaults unless you override them: `ADMIN_TWO_FACTOR_MANDATORY=true`; `OAUTH_DEFAULT_ROLE=STUDENT`; `OAUTH_STATE_TTL_SECONDS=600`; `TWO_FACTOR_ISSUER=Examora`; `TWO_FACTOR_DIGITS=6`; `TWO_FACTOR_PERIOD_SECONDS=30`; `TWO_FACTOR_WINDOW=1`; `TWO_FACTOR_CHALLENGE_TTL_SECONDS=300`; `TWO_FACTOR_RECOVERY_CODE_COUNT=10`; `LOGIN_MAX_PER_IP_EMAIL=10`; `LOGIN_MAX_PER_IP=60`; `LOGIN_WINDOW_SECONDS=60`; `LOGIN_MAX_BUCKETS=100000`; `LOGIN_CLEANUP_INTERVAL_MS=60000`; `ONE_TIME_MAX_ATTEMPTS=5`; `ONE_TIME_WINDOW_SECONDS=900`; `ONE_TIME_MAX_PER_IP=30`; `ONE_TIME_MAX_BUCKETS=100000`; and `ONE_TIME_CLEANUP_INTERVAL_MS=60000`. These are optional operational tuning values, not required connection settings.

`SPRING_PROFILES_ACTIVE=prod` is the profile selector. `APP_ENV=prod` by itself is not consumed by the Spring configuration. The app expects `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`; map any hosting platform's `DATABASE_URL` name to those values yourself. The PostgreSQL role must be able to connect to the selected database and create/update the schema during Flyway migration. The frontend derives WebSocket transport from `NEXT_PUBLIC_API_URL` and connects at the API host's `/ws` endpoint (`wss://.../ws` for HTTPS).

## Vercel frontend

Create a Vercel project linked to this repository and set **Root Directory** to `frontend` (relative to the repository). Select Next.js if it is not detected. The lockfile and `pnpm-workspace.yaml` are inside that directory, so Vercel should use pnpm. Use `pnpm install --frozen-lockfile` for install and `pnpm build` for build if overriding the detected commands. Leave the output directory at the Next.js default. Use Node.js 22.x (Next.js 16 requires Node.js 20.9 or newer).

Set variables in Vercel Project Settings → Environment Variables, scoped appropriately to Production, Preview, and Development. `NEXT_PUBLIC_API_URL` must point to the matching Render API for each scope, and is required at build time. Add each Vercel frontend origin that should call the backend to Render’s `CORS_ALLOWED_ORIGINS`; use a stable preview domain if preview builds need CORS access.

Vercel references: [monorepo root directory](https://vercel.com/docs/monorepos), [Next.js on Vercel](https://vercel.com/docs/frameworks/full-stack/nextjs), [environment variables](https://vercel.com/docs/environment-variables).

## Frontend environment

Set these in the Next.js build environment (and keep the public URL available to the runtime if the platform loads `next.config.mjs` at startup):

| Variable | Required | Value / guidance |
| --- | --- | --- |
| `NEXT_PUBLIC_API_URL` | Yes | HTTPS backend base URL ending in `/api`, for example `https://api.staging.example.com/api`. It is compiled into the browser bundle and is not secret. |
| `EXAMORA_API_URL` | Conditional | Reachable backend base URL ending in `/api` for server-side AI routes; otherwise they use `NEXT_PUBLIC_API_URL`. On Vercel, use Render’s public service URL because Render’s private hostname is not reachable from Vercel. |
| `GROQ_API_KEY` / `OPENAI_API_KEY` | Conditional | Only if an AI feature is enabled; server-only secrets. |

Never expose database credentials, JWT signing material, or provider client secrets as `NEXT_PUBLIC_*` values.

## Staging verification

- [ ] Create a fresh staging database and confirm its backup and restore procedure before applying migrations.
- [ ] Start with `SPRING_PROFILES_ACTIVE=prod`; confirm startup applies Flyway migrations and `schema.sql` is not run.
- [ ] Inspect Flyway history and verify all expected tables and constraints exist. Keep a backup before any migration on a database containing data.
- [ ] Confirm `/api/status` and `/api/db/health` succeed.
- [ ] Send a CORS preflight from the configured frontend origin and confirm it is accepted; confirm an unrelated origin is rejected.
- [ ] Connect to `/ws` from the configured HTTPS origin, verify WebSocket upgrade and STOMP `CONNECT` authentication, and check that the staging proxy permits WebSocket upgrades.
- [ ] Build and load the frontend with `NEXT_PUBLIC_API_URL` set to the staging API. Confirm login and API requests target that host and no request falls back to localhost.
- [ ] Teacher path: log in, create an exam and questions, publish it, create/start its exam room, and verify room monitoring updates.
- [ ] Student path: register or use a provisioned student, join the active room, start the published exam, verify question payloads omit answer keys, save an answer, refresh and restore progress, submit, and view the result.
- [ ] Teacher follow-up: verify the submitted result and exam analytics are visible to the owning teacher and inaccessible to unrelated roles.
- [ ] Verify HTTPS, logs/alerts, resource limits, backup retention, restore, and rollback procedures before inviting staging users.

## Local verification record

Record results and date here after validation. Unit/integration tests alone do not prove connectivity through the actual hosting provider, proxy, browser, or real staging database.

- Disposable PostgreSQL migrations and `prod` startup: passed locally on 2026-10-06. Three fresh throwaway databases were used. A populated schema without Flyway history was also adopted at baseline version 0, migration 1 ran, and a synthetic existing row remained present. The existing local PostgreSQL service was not touched.
- HTTP CORS and WebSocket: passed locally on 2026-10-06. The configured origin passed, an unrelated origin was rejected, and an authenticated STOMP `CONNECT` succeeded.
- Student/teacher exam API workflow: passed locally on 2026-10-06 against a fresh throwaway PostgreSQL database, including autosave/restore and teacher analytics.
- Browser student/teacher end-to-end flow: not run.
- Render/Vercel account settings, database connectivity, TLS termination, and network path: unverified; no provider resources were accessed or deployed.

- Render/Vercel preparation on 2026-10-07: Java 21 Docker image build passed locally, its runtime reports Java 21, and a production-profile startup using only `PORT` (with `SERVER_PORT` unset) returned database health 200. Vercel root directory and environment settings are documented. Actual Render/Vercel account settings and provider connectivity remain unverified.
