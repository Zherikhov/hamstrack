# Hamstrack

[![Build](https://github.com/Zherikhov/easyTask/actions/workflows/build.yml/badge.svg)](https://github.com/Zherikhov/easyTask/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**Hamstrack** is an open-source task tracker for software teams: workspaces, projects, kanban boards, backlog, workflows, comments, attachments and real-time updates — in a single deployable JAR.

One codebase ships in two deployment models:

| | **Cloud** | **DC (self-hosted)** |
|---|---|---|
| Where | [hamstrack.com](https://hamstrack.com) | Your own server |
| Setup | Sign up and go | Docker Compose, one container + PostgreSQL |
| File storage | S3 | Local filesystem (or any S3-compatible store) |
| Configuration | Managed | Fully under your control via environment variables |

> **Status:** Hamstrack is in active development (beta). While in test mode, data on the Cloud instance may periodically be reset; every account gets a pre-populated demo project to explore.

## Features

- **Workspaces** — the tenancy boundary; invite members by email with role-based access (OWNER / ADMIN / MEMBER)
- **Projects** — per-workspace, with keys (`DEMO-42`), archiving and project-level roles (MANAGER / MEMBER / VIEWER)
- **Issues** — issue types, statuses, priorities and custom fields, plus assignees, due dates, sub-task links, change history and optimistic locking
- **Kanban board** — drag-and-drop between status columns, workflow-transition rules enforced on drop
- **Backlog** — flat table of all unfinished work
- **Comments & attachments** — with @mentions, soft delete and per-issue file uploads
- **Admin console** — a system administrator manages a global catalog of statuses, priorities, issue types and custom fields, bundles them into reusable workflows and sets, and assigns those to projects from a bulk-editable matrix (`/admin`)
- **Notifications** — in-app bell plus live updates over Server-Sent Events; email for verification, invites and password recovery
- **Auth** — email registration with verification, JWT access tokens, rotating refresh-token cookie, password recovery

## Self-hosting (DC)

Hamstrack self-hosts as a single Docker image (`ghcr.io/zherikhov/hamstrack`) plus PostgreSQL — `SPRING_PROFILES_ACTIVE=dc`, everything else via environment variables. Pin a released image line (e.g. `:0.4`, not `latest`) in a Compose file and run `docker compose up -d`; the schema migrates itself on startup (Flyway).

**→ Full walkthrough: [Self-hosting guide](docs/self-hosting.md)** — the Compose file, complete configuration reference, TLS / reverse proxy, email (SMTP), attachment storage, upgrades and backups.

## Documentation & REST API

In-app documentation lives at **`/docs`** on every instance ([hamstrack.com/docs](https://hamstrack.com/docs)) — an interactive Swagger UI reference rendered from the OpenAPI spec served at `/openapi.yaml` (import it into Postman or a code generator directly).

Markdown references per deployment model:

- **[API reference — Cloud](docs/api-cloud.md)** (`https://hamstrack.com/api`)
- **[API reference — DC / self-hosted](docs/api-dc.md)** (`https://your-host/api`)
- **[Observability guide](docs/observability.md)** — logs, metrics, dashboards & alerts (Cloud + DC), backend internals, and the dev setup.

## Development

Requirements: Java 21, Docker (PostgreSQL + [MailHog](https://github.com/mailhog/MailHog) for local email), Node is installed automatically by the Maven build.

```bash
docker compose up -d postgres mailhog

# run the backend + built frontend (http://localhost:8080)
DB_URL=jdbc:postgresql://localhost:15432/hamstrack \
DB_USERNAME=hamstrack DB_PASSWORD=hamstrack \
JWT_SECRET=dev-only-jwt-secret-0123456789abcdef-32b \
./mvnw spring-boot:run

# tests (needs the postgres container)
./mvnw test

# full build → single executable JAR with the frontend inside
./mvnw clean package
```

Local email (verification links, invites) lands in the MailHog UI at `http://localhost:8025`. The React frontend lives in `src/main/frontend/` and is built into the JAR by `frontend-maven-plugin`; append `-Dfrontend.skip=true` to Maven commands to skip it during backend-only iterations.

### Stack

Spring Boot / Java 21 · PostgreSQL + Flyway · Spring Security (JWT) · React 19 + TypeScript + Vite · Tailwind CSS v4 · TanStack Query · single-JAR deployment, Docker image published to `ghcr.io/zherikhov/hamstrack`.

## Contributing

Issues and pull requests are welcome. CI runs the full test suite on every PR; deploys to Cloud happen automatically from green builds on `main`.

## License

[Apache 2.0](LICENSE)
