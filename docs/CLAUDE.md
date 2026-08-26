
# SmartExam - Project Instructions

## Project Overview

SmartExam is an AI-powered online examination platform.

Users:
- STUDENT
- TEACHER
- ADMIN

Frontend:
- React
- TypeScript
- Vite
- Tailwind CSS
- React Router
- Axios
- TanStack Query
- Zustand
- React Hook Form + Zod
- Recharts

Backend:
- Java
- Spring Boot
- Spring Security
- Spring Data JPA
- PostgreSQL

The frontend communicates with the backend through REST APIs and real-time features may use WebSocket/STOMP.

---

# Core Rules

Before changing code:

1. Inspect the existing implementation.
2. Understand frontend and backend contracts.
3. Reuse working code.
4. Do not rebuild the project unnecessarily.
5. Do not change existing UI, theme, colors, layout, routes, or design patterns unless explicitly requested.
6. Do not remove working features.
7. Make focused, minimal changes.
8. Do not invent APIs, database fields, or integrations without evidence from project requirements or existing frontend contracts.

---

# Architecture Rules

Keep responsibilities separated.

Frontend:

```text
components/     -> Reusable UI
features/       -> Feature-specific logic
services/       -> API communication
hooks/          -> TanStack Query and reusable hooks
store/          -> Client state only
types/          -> Shared TypeScript contracts
routes/         -> Routing and guards


Backend:
```text
controller/     -> HTTP/API layer
service/        -> Business logic
repository/     -> Database access
entity/         -> Persistence models
dto/            -> Request/response models
mapper/         -> Entity/DTO mapping
config/         -> Application configuration
security/       -> Authentication/authorization
exception/      -> Error handling
