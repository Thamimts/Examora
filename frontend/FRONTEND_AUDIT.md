# SmartExam Frontend Audit

**Scope:** inspection only. No application code was modified.

## Executive summary

The project is a Next.js App Router shell containing a client-only React Router application. The visual foundation and route-level demo flows are present, but most examination data and dashboard metrics are still mock or locally generated. Login/register/logout and the admin user list have API service calls; the exam engine, results, history, dashboards, AI analytics, adaptive practice, proctoring, question bank, password recovery, verification, profile/settings, and onboarding are not production-backed.

## Status legend

- **WORKING:** implemented and uses an existing API contract evidenced in code.
- **PARTIAL:** route/UI exists, but behavior is incomplete or partly local/mock.
- **BROKEN:** implementation exists but has a concrete correctness/security/runtime flaw.
- **MISSING:** no implementation found.
- **MOCK:** behavior is explicitly driven by hardcoded/local demo data.

## Architecture

| Feature | Status | Severity | Files/evidence | Findings / recommendation |
|---|---|---:|---|---|
| Project structure | PARTIAL | Medium | `app/page.tsx`, `components/shared.tsx`, `features/*`, `hooks/*`, `services/*`, `store/*`, `types/*`, `mock/data.ts` | Requested folders exist, but nearly the entire application is concentrated in `app/page.tsx` (41 dense lines containing all route pages and business logic). Split by feature only after contracts are known; do not redesign UI. |
| Router/runtime | PARTIAL | Medium | `app/page.tsx:1-4,38-41`, `next.config.mjs` | Next serves a client-only React Router app via rewrites. Direct routes depend on rewrite coverage. Add/verify every route in deployment configuration or migrate only if intentionally planned. |
| UI/state/API separation | BROKEN | High | `app/page.tsx:20-38`, `mock/data.ts` | UI components directly own mock data, timer logic, mutations, and navigation. Move server state to services/hooks and keep UI state local; preserve existing presentation. |
| Axios client | PARTIAL | High | `services/api.ts` | Bearer interceptor and 401 redirect exist. Base URL uses `NEXT_PUBLIC_API_URL`, runtime `window.__ENV__.VITE_API_URL`, then localhost; this is inconsistent with the requested `VITE_API_URL` Vite convention. Confirm the deployed frontend environment strategy and use only the configured public URL. |
| TanStack Query | PARTIAL | Medium | `app/page.tsx:13,17,35,40`, `features/ai/AIViews.tsx` | Used for admin users and AI analytics only. Exams, attempts, results, dashboards, and mutations bypass Query. Add hooks only for verified Spring Boot contracts. |
| Error handling | PARTIAL | Medium | `app/page.tsx:35-37`, `services/api.ts` | User list and auth have basic error states; most views have no loading/error/empty states and fall back to hardcoded data. Add contract-backed states, not fabricated fallback data. |
| Mocks/fabricated flows | MOCK | Critical | `mock/data.ts`, `app/page.tsx:20,25-33`, `features/ai/AIViews.tsx` | Mock exams/results/users/analytics, fixed scores, demo credentials, local question arrays, adaptive questions, and local question-bank mutations drive production-visible routes. Remove from production paths or clearly gate as development fixtures. |

## Auth and account

| Feature | Status | Severity | Files/evidence | Findings / recommendation |
|---|---|---:|---|---|
| Login | PARTIAL | High | `app/page.tsx:37`, `services/authApi.ts` | Real `authApi.login` call and validation exist in `RealAuth`; legacy `Auth` still contains demo login behavior and `demoUser`. Ensure only `RealAuth` is reachable and remove demo behavior from production routes. Required contract: existing `POST /auth/login` response must match `ApiResponse<AuthResponse>` with `token` and `user`. |
| Register | PARTIAL | High | `app/page.tsx:37`, `services/authApi.ts` | Real register call exists. No server-side verification/onboarding flow is evidenced. Required contract: existing `POST /auth/register` request/response fields. |
| Email verification | MISSING | High | No matching route/service found | Add only after the Spring Boot verification endpoint, token transport, and response contract are provided. |
| Forgot/reset password | MISSING | High | Public routes are not defined in `app/page.tsx:38` | Add only with existing backend contracts for request/reset token and password update. |
| Logout | PARTIAL | Medium | `app/page.tsx:22`, `services/authApi.ts`, `store/authStore.ts` | Calls `POST /auth/logout`, then clears client state. Server logout contract and token invalidation semantics are not documented in the frontend. |
| Session expiry / 401 | WORKING | Medium | `services/api.ts` | Axios 401 clears Zustand state and assigns `/login?expired=1`. Verify backend uses bearer JWT expiry and that redirect does not loop for public requests. |
| Protected routes | WORKING | Medium | `app/page.tsx:34,38` | Unauthenticated users redirect to `/login`; roles redirect to their dashboard. Hydration handling is not used in `Protected`, so persisted auth can briefly redirect before Zustand rehydrates. Gate on `hydrated` before redirecting. |
| Role authorization | PARTIAL | High | `app/page.tsx:34,38`, `store/authStore.ts` | Client role guard exists but is not a security boundary. Backend must enforce STUDENT/TEACHER/ADMIN authorization on every protected endpoint. |
| Secure token/session handling | PARTIAL | Critical | `store/authStore.ts` | Token is persisted by Zustand under `examwise-auth`, which means a JWT is stored in browser storage and is exposed to XSS. Do not replace with “secure in-memory” unless the Spring Boot authentication strategy supports it; prefer the backend’s supported HttpOnly cookie/session strategy, or document the approved bearer-token storage contract and threat model. |
| Profile/settings | MISSING | Medium | No profile/settings routes or services found | Add only against existing user profile endpoints. |
| Onboarding | MISSING | Low | No matching route/service found | Backend contract and product requirements needed. |

## Student

| Feature | Status | Severity | Files/evidence | Findings / recommendation |
|---|---|---:|---|---|
| Dashboard | MOCK | High | `app/page.tsx:36` | Fixed “12”, “86%”, “3”, and static activity copy. Replace with existing dashboard/summary endpoint only; otherwise show unavailable state. |
| Available exams | MOCK | High | `app/page.tsx:25`, `mock/data.ts` | Renders `mockExams`; no `examApi.list` query is used. Wire only the existing Spring Boot exam-list contract. |
| History/results | MOCK | High | `app/page.tsx:28-29`, `mock/data.ts` | Fixed result score `86%` and `mockResults`. `resultApi` exists but is not consumed here. Required contract: existing result list/detail response fields. |
| Profile | MISSING | Medium | No route/service/view found | Requires existing user profile contract. |
| AI analytics | MOCK/PARTIAL | High | `features/ai/AIViews.tsx`, `services/aiApi.ts` | Query call exists, but `fallbackStudentAnalytics` supplies hardcoded production-visible analytics and admin analytics uses fixed `overview`. Remove fallback fixture from production; use an explicit unavailable state when backend data is absent. |

## Exam engine

| Feature | Status | Severity | Files/evidence | Findings / recommendation |
|---|---|---:|---|---|
| Real exam/question loading | MOCK | Critical | `app/page.tsx:20,25-27`, `mock/data.ts` | Questions and exam metadata are hardcoded. Existing `examApi`/`questionApi` must be used only according to their actual Spring Boot contracts. |
| Attempt creation/server attemptId | MISSING | Critical | `store/examStore.ts`, `app/page.tsx:27` | `start` creates a client-local attempt keyed by exam id; no create-attempt request or server attempt ID exists. Required existing endpoint contract: create attempt request/response including `attemptId`, question/session data, and server `endAt`. |
| Attempt restore/refresh recovery | PARTIAL | Critical | `store/examStore.ts:1-38`, `app/page.tsx:27` | Store is not persisted, so refresh loses answers. `start` creates a local end time and only preserves state during the current runtime. Implement restore using the existing backend attempt endpoint; do not invent it. |
| Answer selection/navigation | PARTIAL | High | `app/page.tsx:27`, `store/examStore.ts` | Local MCQ/textarea navigation exists. Store actions select the first attempt via `Object.values(...)[0]`, not the route attempt id, causing incorrect behavior with multiple attempts. Pass exam/attempt identity explicitly. |
| Mark review/clear answer | PARTIAL | High | `store/examStore.ts`, `app/page.tsx:27` | Local controls exist, but state is client-only and autosave is only a boolean. Required contract: existing answer/review save endpoint and payload. |
| Autosave/retry/reconnect | MISSING | Critical | No autosave service/hook found | `autosaving` is a local flag; no network request, retry queue, reconnect handling, or server acknowledgment exists. Required existing endpoint and retry semantics needed. |
| Server endAt/timer/expiry | BROKEN | Critical | `app/page.tsx:27`, `store/examStore.ts` | Timer defaults to `Date.now()+1800000` in the browser. It is not server-authoritative and can be manipulated. Use server-provided `endAt`, resync clock as supported by backend, and handle expired responses. |
| Submit confirmation/idempotency | BROKEN | Critical | `app/page.tsx:27-28`, `store/examStore.ts` | `submit` only flips a local boolean and navigates; no backend submission, confirmation modal, idempotency key, or retry-safe response exists. Required existing submit contract and idempotency mechanism. |
| Real results/history after submit | MOCK | Critical | `app/page.tsx:28-29`, `services/resultApi.ts` | Result page is fixed content and history is mock data. Use backend result response; do not calculate or invent scores in the frontend. |
| Descriptive answers | PARTIAL | High | `app/page.tsx:27`, `types/exam.ts` | Textarea exists, but no save/submit API or grading status is wired. Required answer contract must support text values and grading state. |

## Teacher/admin and other audited surfaces

| Feature | Status | Severity | Files/evidence | Findings / recommendation |
|---|---|---:|---|---|
| Teacher exam management | MOCK | High | `app/page.tsx:30-32`, `services/examApi.ts` | Create/delete/publish/question edits are local state only; buttons do not call API. Wire only existing create/update/delete/publish/question contracts. |
| Question bank | MOCK | High | `features/admin/QuestionBank.tsx` | Seed questions, delete, and file selection are local. Upload explicitly says persistence awaits backend contract. Keep unavailable state until verified import endpoint exists. |
| Admin users | WORKING/PARTIAL | Medium | `app/page.tsx:35`, `services/userApi.ts` | Uses `GET /users` with Query, retry, loading/error/empty state. Backend response must match `ApiResponse<User[]>`; pagination/filtering and admin mutation authorization are not evidenced. |
| Admin analytics | MOCK | High | `features/ai/AIViews.tsx` | Fixed overview/chart values; no verified analytics query shown. Add only against an existing endpoint. |
| Adaptive practice | MOCK | High | `features/ai/AIViews.tsx` | Hardcoded questions and local difficulty increment; no adaptive backend decision or attempt persistence. Required existing adaptive/session contract. |
| Proctoring monitor | PARTIAL/MOCK | Critical | `features/ai/AIViews.tsx`, `hooks/useExamMonitor.ts`, `services/proctorApi.ts`, `store/monitorStore.ts` | Browser events/webcam readiness are locally observed, but risk/connection metrics and events are not proven server-backed. Student attempt event emission is not evidenced. Required event ingestion and monitor subscription contracts. |
| WebSocket live updates | MISSING | High | No STOMP/WebSocket client usage found in inspected files | Add only when Spring Boot broker URL, destination, authentication, event schema, reconnect, and authorization contracts exist. |
| Email notifications | MISSING | Low | No notification service found | Requires backend contract. |

## Recommended remediation order

1. Confirm and document the existing Spring Boot contracts for auth/session, exams/questions, attempts, answers/autosave, submit, results, users, analytics, adaptive, and proctoring. Do not invent routes or payloads.
2. Remove production-visible mock/fallback data from student exams, history/results, dashboards, AI analytics, adaptive practice, teacher CRUD, question bank, and admin analytics; replace with verified API states.
3. Fix attempt identity and server-authoritative timing before enabling real submissions.
4. Implement backend-backed autosave/restore/retry and idempotent submit using the backend’s supported mechanism.
5. Complete account flows only for endpoints that already exist, including verification/password recovery/profile if available.
6. Re-run type checks/build and browser-test every protected role route, refresh recovery, expired session, failed API, empty API, submit retry, and mobile layout.

## Audit conclusion

The frontend is a useful visual/API scaffolding prototype, not a production-ready online examination system. Authentication has a real integration path and the admin user list is the strongest backend-backed surface; the core exam lifecycle and most analytics are mock or client-only. Production completion requires the actual Spring Boot endpoint contracts and successful end-to-end tests against those contracts, while preserving the current UI/design.

## Clear completion condition

This audit is complete as an inspection artifact when `FRONTEND_AUDIT.md` is reviewed alongside the Spring Boot API specification. No application code was changed by this audit.
```markdown
Source files inspected: app/page.tsx, components/shared.tsx, features/admin/QuestionBank.tsx, features/ai/AIViews.tsx, hooks/useExamMonitor.ts, mock/data.ts, services/*.ts, store/*.ts, types/*.ts, package.json.
```

## Contract checklist for backend handoff

- [ ] Auth login/register/logout response and token/session strategy
- [ ] Verification and password recovery contracts
- [ ] User profile/settings contract
- [ ] Exam list/detail/create/update/delete/publish contracts
- [ ] Question list/create/update/delete/import contracts
- [ ] Attempt create/restore contract with server `attemptId` and `endAt`
- [ ] Answer save/autosave/retry contract
- [ ] Submit contract with idempotency/retry semantics
- [ ] Result detail/history contract
- [ ] Student/teacher/admin dashboard and analytics contracts
- [ ] Adaptive question/decision contract
- [ ] Proctoring event ingestion, webcam/connection, risk, and live-update contracts
- [ ] Authorization rules for every role and endpoint
- [ ] CORS/base URL/deployment environment contract

**No endpoints were invented in this audit.**

## Critical production bug follow-up — 2026-08-22

| Feature | Status | Severity | Route/file evidence | Current implementation | Exact flaw or fix | Required Spring Boot contract |
|---|---|---:|---|---|---|---|
| Register navigation | WORKING/PARTIAL | High | `app/page.tsx:37-38` | `/register` renders the existing `RealAuth` form and now exposes `Create an account` / `Sign in` links. | Registration remains dependent on the existing API response shape; no verification flow is assumed. | Existing `POST /auth/register` request `{ name, email, password }` and the currently typed `ApiResponse<AuthResponse>` response, or a documented alternate success flow. |
| Auth error handling | PARTIAL | High | `app/page.tsx:37`, `services/authApi.ts` | Client validation, loading, disabled submit, and generic API error state are present. | Backend-specific validation messages and verification-required responses are not distinguishable because the existing contract does not expose a documented error model. | Existing error response schema and registration verification semantics. |
| Teacher/Admin CRUD | MOCK | Critical | `app/page.tsx:30-32`, `features/admin/QuestionBank.tsx` | UI mutations still modify local state or select files locally. | Do not treat local mutation as production persistence; wire only when existing contracts are supplied. | Existing exam/question CRUD, publish, import, validation, assignment, and bulk-import contracts. |
| Student exam lifecycle | MOCK/BROKEN | Critical | `app/page.tsx:25-29`, `store/examStore.ts` | Exam/questions/results/timing are still local or mocked. | No production submission, server attempt identity, authoritative timer, restore, autosave, or idempotency can be claimed. | Existing attempt create/restore, answer save, submit/idempotency, result, and history contracts. |
| AI/adaptive analytics | MOCK/PARTIAL | High | `features/ai/AIViews.tsx`, `services/aiApi.ts` | Analytics view has a service/query path but documented fallback/mock paths remain. | Backend must control recommendations and adaptive next-question decisions; frontend must not invent scores or progression. | Existing analytics, recommendation, question-generation, and adaptive-session contracts. |
| Proctoring/realtime | PARTIAL/MISSING | Critical | `hooks/useExamMonitor.ts`, `store/monitorStore.ts`, `services/proctorApi.ts` | Browser event detection and local webcam/status surfaces exist. | Event linkage, backend ingestion, attempt association, authorization, and WebSocket/SSE/STOMP updates are not proven. | Existing proctor-event ingestion, attempt linkage, live-monitor subscription, and broker auth/event contracts. |
| Legal/trust | MISSING | Medium | No dedicated policy routes/components found | Privacy, terms, cookie preferences, accessibility, security, disclosure, and other policy pages are not implemented. | Classify and implement only the policies applicable to the deployed SmartExam business model; do not add legal claims as filler. | Not applicable unless content is supplied by product/legal. |
| Billing/support | NOT APPLICABLE/ MISSING | Low | No billing or support routes/services found | No billing, subscription, payment, help center, or contact contract is evidenced. | Confirm whether SmartExam is paid and whether support is handled externally before implementation. | Existing billing/payment/support contracts only if applicable. |

### Clear completion condition for the requested fix
The register fix is complete when `/login` visibly links to `/register`, `/register` visibly links back to `/login`, both routes preserve the existing UI/design, registration uses the existing typed `authApi.register` contract with RHF/Zod validation and loading/error states, and TypeScript/build plus browser route checks pass. The broader audit is complete only after the actual Spring Boot API specification is supplied and every backend-dependent item above is verified end-to-end; until then, unsupported flows remain explicitly marked rather than fabricated.

**No additional backend endpoints were invented.**
