# Hamstrack

[![CI/CD](https://github.com/Zherikhov/easyTask/actions/workflows/pipeline.yml/badge.svg)](https://github.com/Zherikhov/easyTask/actions/workflows/pipeline.yml)
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
- **Issues** — types (Bug, Task, Story, Epic), statuses, priorities, assignees, due dates, sub-task links, change history and optimistic locking
- **Kanban board** — drag-and-drop between status columns, workflow-transition rules enforced on drop
- **Backlog** — flat table of all unfinished work
- **Comments & attachments** — with @mentions, soft delete and per-issue file uploads
- **Notifications** — in-app bell plus live updates over Server-Sent Events; email for verification, invites and password recovery
- **Auth** — email registration with verification, JWT access tokens, rotating refresh-token cookie, password recovery

## Quick start (self-hosted / DC)

Requirements: Docker with Compose.

```yaml
# docker-compose.yml
services:
  app:
    image: ghcr.io/zherikhov/hamstrack:latest
    environment:
      SPRING_PROFILES_ACTIVE: dc
      DB_URL: jdbc:postgresql://postgres:5432/hamstrack
      DB_USERNAME: hamstrack
      DB_PASSWORD: change-me
      JWT_SECRET: change-me-to-a-random-string-of-32-plus-bytes
      APP_BASE_URL: https://tracker.example.com
      MAIL_HOST: smtp.example.com
      MAIL_PORT: "587"
      MAIL_USERNAME: tracker@example.com
      MAIL_PASSWORD: change-me
      MAIL_SMTP_AUTH: "true"
      MAIL_STARTTLS: "true"
    ports:
      - "8080:8080"
    volumes:
      - attachments_data:/app/data/attachments
    depends_on:
      postgres:
        condition: service_healthy
    restart: unless-stopped

  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: hamstrack
      POSTGRES_USER: hamstrack
      POSTGRES_PASSWORD: change-me
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U hamstrack -d hamstrack"]
      interval: 5s
      timeout: 5s
      retries: 10
    restart: unless-stopped

volumes:
  postgres_data:
  attachments_data:
```

```bash
docker compose up -d
```

Open `http://localhost:8080` (or your `APP_BASE_URL` behind a TLS-terminating reverse proxy such as Caddy or nginx), register the first account, verify it via the email link — done. Database schema is created and migrated automatically on startup (Flyway).

### Key configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | — | `dc` (self-hosted) or `cloud` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | — | PostgreSQL connection (required) |
| `JWT_SECRET` | — | HMAC key for access tokens, **min 32 bytes** (required) |
| `APP_BASE_URL` | `http://localhost:8080` | Public URL; used in emails, cookies (`Secure` when https), robots/sitemap |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_SMTP_AUTH` / `MAIL_STARTTLS` / `MAIL_FROM` | localhost:1025 | Outgoing SMTP (verification, invites, password reset) |
| `STORAGE_TYPE` | `local` (dc) / `s3` (cloud) | Attachment storage backend |
| `STORAGE_LOCAL_DIR` | `./data/attachments` | Local storage path (mount a volume) |
| `STORAGE_S3_BUCKET` / `STORAGE_S3_REGION` / `STORAGE_S3_ENDPOINT` / `STORAGE_S3_PATH_STYLE` / `STORAGE_S3_ACCESS_KEY` / `STORAGE_S3_SECRET_KEY` | — | S3 or S3-compatible storage (MinIO etc.); empty keys fall back to the AWS default credentials chain |
| `ATTACHMENT_MAX_FILE_SIZE` | `25MB` | Upload size limit |
| `PUBLIC_LANDING_ENABLED` | `true` | `false` hides the public landing page (`/` redirects to login, crawlers disallowed) |
| `TERMS_ACCEPTANCE_REQUIRED` | `true` | `false` removes the required terms checkbox at registration |
| `DEMO_SEED_ON_FIRST_LOGIN` | `true` | `false` disables the demo workspace seeded on a user's first login |
| `RATE_LIMIT_ENABLED` (+ `RATE_LIMIT_AUTH_IP_PER_MINUTE`, `RATE_LIMIT_LOGIN_FAILURE_THRESHOLD`, `RATE_LIMIT_LOGIN_BACKOFF_BASE_SECONDS`, `RATE_LIMIT_LOGIN_BACKOFF_MAX_SECONDS`) | `true` (15 / 5 / 30 / 900) | Brute-force protection on auth endpoints: per-IP budget + per-account login backoff, `429` + `Retry-After` |
| `SEED_ADMIN_EMAIL` / `SEED_ADMIN_DISPLAY_NAME` / `SEED_ADMIN_PASSWORD` | — | Optionally create an admin account on startup |

## Documentation & REST API

In-app documentation lives at **`/docs`** on every instance ([hamstrack.com/docs](https://hamstrack.com/docs)) — an interactive Swagger UI reference rendered from the OpenAPI spec served at `/openapi.yaml` (import it into Postman or a code generator directly).

Markdown references per deployment model:

- **[API reference — Cloud](docs/api-cloud.md)** (`https://hamstrack.com/api`)
- **[API reference — DC / self-hosted](docs/api-dc.md)** (`https://your-host/api`)

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
