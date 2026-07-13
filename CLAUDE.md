# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About the project

Hamstrack is an open-source task tracker inspired by Jira, but it must not copy Jira's implementation, UI, naming, or proprietary behavior.

It must support two deployment models from the same core codebase:
- self-hosted installation (DC)
- hosted Cloud version

Performance, maintainability, and simple deployment are core requirements. When proposing architecture, libraries, or infra (auth, storage, multi-tenancy, billing, etc.), favor options that work in both deployment models — avoid baking in cloud-only assumptions without an equivalent self-hosted path.

See `PLAN.md` for the full development roadmap and stack decisions.

## Project state

Phase 4A complete. Full-stack app is running — React frontend ships as a single JAR via Maven.

- **Phase 2** — Auth (register, verify email, resend verification, login, refresh, logout, forgot/reset password) and Workspace (create, list, get, members, invite, accept invite — invite is bound to the invited email). Email verification doubles as login: the (HTML, branded) email links to the SPA page `/verify-email?token=`, which POSTs to `/api/auth/verify-email` and receives an `AuthResponse` — never link emails straight to a GET API endpoint, mail scanners prefetch GETs and would burn the one-time token (a legacy GET on the same API path 302-redirects to the SPA page).
- **Phase 3** — Projects (CRUD, archive, member roles), Issue Types and Statuses (workspace-scoped, seeded on workspace create), Issues (CRUD, filters, project-scoped sequence numbers, optimistic locking), Comments (CRUD, soft delete), Attachments (per-issue upload/download/delete; delete allowed to uploader or project MANAGER; blobs cleaned up on issue delete).
- **Compliance/SEO (2026-07-12)** — public landing page at `/` for anonymous visitors (signed-in users still redirect to `/workspaces`); legal pages `/terms`, `/privacy`, `/cookies` (draft texts — lawyer review before public launch); required terms checkbox on Register (`RegisterRequest.termsAccepted` → `users.terms_accepted_at`, null for pre-existing users); informational cookie banner (the only cookie is the strictly-necessary `refresh_token`, so no consent UI — dismissal stored in localStorage `hamstrack.cookie-notice-dismissed`); favicon + meta/OG tags in `index.html`; `SeoController` serves `/robots.txt` and `/sitemap.xml` generated from `app.base-url`. Toggles under `app.legal.*` (env `PUBLIC_LANDING_ENABLED`, `TERMS_ACCEPTANCE_REQUIRED`, both default `true`) — DC installs can disable the landing (then `/` → `/login`, robots disallows all, sitemap 404s) and the terms requirement. The SPA reads these from public `GET /api/meta` (`publicLandingEnabled`, `termsAcceptanceRequired`, `publicSignupEnabled`) into `useConfigStore` with fail-safe defaults.
- **Phase 4A** — React + TypeScript + Vite frontend. Pages: Login, Register, Workspaces, WorkspaceHome (project grid / auto-redirect), Board (kanban: status columns ordered by position, draggable issue cards — native HTML5 DnD, drop PATCHes `statusId`+`version` with optimistic cache update and rollback on error; drop targets respect workflow transitions), Backlog (`p/:projectId/backlog` — flat table of issues whose status category ≠ DONE), issue side panel for view/edit/comments on both; issue creation goes through `CreateIssueModal` (Jira-style dialog with a Project selector defaulting to the current project) opened via the top-bar Create button or `uiStore.openCreateIssue()` — the modal is rendered by `TopBar`. Project pages are remounted on `wsId`/`projectId` change (`ParamKeyed` in `App.tsx`) so panel/filter state never leaks across projects. App shell is two-level navigation (see DESIGN.md decision log 2026-07-09): global dark top bar (`TopBar` — logo, `ProjectSwitcher` dropdown, global search stub for future HQL, Create button, `NotificationBell`, user menu, SSE subscription) + contextual light project sidebar (`Sidebar` — Board/Backlog links, Reports/Settings placeholders), rendered only on project routes. Maven `frontend-maven-plugin` builds frontend into `src/main/resources/static/` during `generate-resources`. SPA fallback (`SpaController`) forwards all non-API, non-file paths to `index.html`.

Stack: Spring Boot 4.1.0 / Java 21, Spring Web MVC, Spring Data JPA, Spring Security, PostgreSQL, Flyway, Lombok, jjwt.

Frontend (`src/main/frontend/`): React 19, TypeScript, Vite 6, Tailwind v4 (`@tailwindcss/vite`), React Router v7, TanStack Query v5, Zustand v5, lucide-react.

## DC vs Cloud (single codebase)

Hamstrack ships as one codebase in two modes, controlled by Spring profile `dc` or `cloud` (`SPRING_PROFILES_ACTIVE=cloud`). Differences between modes must be config/profile-gated behavior, never forked code.

The highest-severity bug class here is a query/service that forgets to scope by `workspace_id`/membership — in Cloud that leaks one tenant's data to another. Always resolve resources through workspace membership checks; return 404 whether the workspace doesn't exist or the caller isn't a member — never reveal existence via a 403.

**File storage** (`common.storage.FileStorage`): backend selected by `app.storage.type` (`local` | `s3`), profile defaults — `dc` → local FS (`app.storage.local.base-dir`, default `./data/attachments`, gitignored), `cloud` → S3 (`app.storage.s3.*`: bucket, region, optional `endpoint` + `path-style-access` for MinIO/S3-compatible stores, optional static keys — empty falls back to the AWS SDK default credentials chain). Implementations are `@ConditionalOnProperty`-gated beans (`LocalFileStorage` / `S3FileStorage`); inject the `FileStorage` interface, never a concrete class. Storage keys are server-generated (`ws/{wsId}/issues/{issueId}/{uuid}`) — original filenames live only in the DB. Upload size limit via `spring.servlet.multipart.max-file-size` (env `ATTACHMENT_MAX_FILE_SIZE`, default 25MB).

## Local dev environment

Port `5432` is taken by a native PostgreSQL install on this machine — project Postgres runs in Docker on **port 15432**:

The existing container is `hamstrack-postgres` (user `postgres`, password `1q2w#E`, DB `hamstrack`, port 15432). MailHog runs as `hamstrack-mailhog` (SMTP 1025, UI 8025). To start:
```
docker start hamstrack-postgres hamstrack-mailhog
```
Run the app / tests:
```
$env:DB_URL="jdbc:postgresql://localhost:15432/hamstrack"; $env:DB_USERNAME="postgres"; $env:DB_PASSWORD="1q2w#E"; $env:JWT_SECRET="dev-only-jwt-secret-hamstrack-0123456789abcdef"; .\mvnw.cmd spring-boot:run
```
`JWT_SECRET` must be at least 32 bytes — `JwtService` fails fast at startup otherwise (HMAC-SHA256 key size requirement).

Note: the DB credentials in `application-local.properties` (user `hamstrack`) are stale — that role doesn't exist in the container; use env vars as above. Skip the frontend build with `-Dfrontend.skip=true` (in PowerShell prefix args with `--%` so `-D` flags aren't mangled).

## CI/CD

- `.github/workflows/pipeline.yml` — single CI/CD workflow. `build-and-test` runs on every push / PR to main (builds + tests against a Postgres service container). On push to main only, and only after tests pass (`needs:`), `build-and-push` builds the Docker image (multi-stage `Dockerfile`) and pushes to `ghcr.io/zherikhov/hamstrack` (`latest` + `sha-<commit>` tags), then `deploy` SSHes to the server (`/opt/hamstrack`) and runs `docker compose -f docker-compose.prod.yml pull && up -d`. A red test run blocks the deploy. Required repo secrets: `SERVER_HOST`, `SERVER_USER`, `SERVER_SSH_PRIVATE_KEY`. The server keeps `docker-compose.prod.yml`, `Caddyfile`, and `.env` (`GITHUB_OWNER` — lowercase! — `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `SEED_ADMIN_PASSWORD`, plus storage: `STORAGE_TYPE=local` until an S3 bucket exists, then `STORAGE_TYPE=s3` + `STORAGE_S3_BUCKET`/`STORAGE_S3_REGION`) in `/opt/hamstrack`; compose auto-loads `.env` from that directory. Local-storage attachments persist in the `attachments_data` volume. Prod is served at https://hamstrack.com — DNS on Cloudflare (A records for `@`/`www`, **DNS only** / grey cloud; switching to the orange-cloud proxy requires SSL mode Full (strict)); Caddy auto-manages Let's Encrypt certs (persisted in the `caddy_data` volume). If the ghcr package is private, the server needs a one-time `docker login ghcr.io` with a PAT (`read:packages`).

## Commands

Use the Maven wrapper (`mvnw.cmd` on Windows / `./mvnw` in bash).

```
mvnw.cmd clean package          # build
mvnw.cmd spring-boot:run        # run (needs DB_URL/DB_USERNAME/DB_PASSWORD)
mvnw.cmd test                   # full test suite (needs running Postgres)
mvnw.cmd test -Dtest=HamstrackApplicationTests              # single test class
mvnw.cmd test -Dtest=HamstrackApplicationTests#contextLoads # single test method
```

## Architecture

Package-by-feature under `com.hamstrack`, one top-level package per business area — `auth`, `workspace`, `project`, `issue` — plus `common` for cross-cutting infrastructure. Each feature package nests layer subpackages: `entity`, `repository`, `service`, `controller`, `dto`, `exception` (not every feature has all six).

- `common.entity` — `BaseEntity`/`CreatedOnlyEntity` mapped superclasses for `id`/`createdAt`/`updatedAt`. Use Spring Data JPA auditing (`@CreatedDate`/`@LastModifiedDate` + `@EntityListeners(AuditingEntityListener.class)`); `@EnableJpaAuditing` is on `HamstrackApplication`.
- `common.exception` — `AppException` (each subclass carries an `HttpStatus`), `GlobalExceptionHandler`.
- `common.security` — `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`.
- `common.config` — `@ConfigurationProperties` classes.

Schema managed entirely by Flyway; `spring.jpa.hibernate.ddl-auto=validate` only. `spring.jpa.open-in-view=false` — service methods touching lazy associations need `@Transactional`.

**IDs:** UUID v7 everywhere, generated by the application via `@UuidGenerator(style = UuidGenerator.Style.TIME)` (Hibernate 6/7). Never use `@GeneratedValue(strategy = IDENTITY)` or BIGSERIAL.

**Timestamps:** Use Spring Data JPA `@CreatedDate`/`@LastModifiedDate` (NOT Hibernate `@CreationTimestamp`/`@UpdateTimestamp`). In Hibernate 7, `@CreationTimestamp` does not set the value until flush, so values are null after `save()`. With `@CreatedDate` + `@EnableJpaAuditing`, Spring sets values during `@PrePersist`/`@PreUpdate` — immediately available after `save()`. The schema also has `DEFAULT NOW()` + DB triggers as a safety net for raw SQL writes outside JPA.

**PostgreSQL ENUMs:** Do NOT use `CREATE TYPE ... AS ENUM`. Hibernate 7 + PostgreSQL ENUM types cause JDBC type cast errors on INSERT (`column is of type X but expression is of type character varying`). Use `VARCHAR(N)` columns instead — enum values are validated at the application layer by Java enums.

**Jackson:** Spring Boot 4 does not include Jackson in `spring-boot-starter-web` by default. Add `jackson-databind` explicitly to `pom.xml`.

## Design System

Always read `DESIGN.md` before making any visual or UI decisions. All font choices, colors, spacing, and aesthetic direction are defined there. Do not deviate without explicit user approval.

## Phase 3 API surface

```
POST   /api/workspaces/{wsId}/projects                     # create; creator gets MANAGER
GET    /api/workspaces/{wsId}/projects?includeArchived=    # list (workspace members only; archived hidden by default)
GET    /api/workspaces/{wsId}/projects/{id}                # get
PATCH  /api/workspaces/{wsId}/projects/{id}                # update name/description (MANAGER)
POST   /api/workspaces/{wsId}/projects/{id}/archive        # archive (MANAGER)
POST   /api/workspaces/{wsId}/projects/{id}/unarchive      # unarchive (MANAGER)
GET    /api/workspaces/{wsId}/projects/{id}/members        # list members
POST   /api/workspaces/{wsId}/projects/{id}/members        # add member (MANAGER)
DELETE /api/workspaces/{wsId}/projects/{id}/members/{uid}  # remove member (MANAGER)

GET    /api/workspaces/{wsId}/issue-types                  # list (ordered by position)
POST   /api/workspaces/{wsId}/issue-types                  # create
PATCH  /api/workspaces/{wsId}/issue-types/{id}             # update
DELETE /api/workspaces/{wsId}/issue-types/{id}             # delete

GET    /api/workspaces/{wsId}/statuses                     # list (ordered by position)
POST   /api/workspaces/{wsId}/statuses                     # create
PATCH  /api/workspaces/{wsId}/statuses/{id}                # update
DELETE /api/workspaces/{wsId}/statuses/{id}                # delete

POST   /api/workspaces/{wsId}/projects/{pId}/issues                        # create
GET    /api/workspaces/{wsId}/projects/{pId}/issues?statusId=&assigneeId=&priority=  # list + filter
GET    /api/workspaces/{wsId}/projects/{pId}/issues/{number}               # get by project-scoped number
PATCH  /api/workspaces/{wsId}/projects/{pId}/issues/{number}               # update
DELETE /api/workspaces/{wsId}/projects/{pId}/issues/{number}               # delete (MANAGER)

POST   /api/workspaces/{wsId}/projects/{pId}/issues/{n}/comments           # create
GET    /api/workspaces/{wsId}/projects/{pId}/issues/{n}/comments           # list (excludes soft-deleted)
PATCH  /api/workspaces/{wsId}/projects/{pId}/issues/{n}/comments/{id}      # update (author only)
DELETE /api/workspaces/{wsId}/projects/{pId}/issues/{n}/comments/{id}      # soft delete (author only)

POST   /api/workspaces/{wsId}/projects/{pId}/issues/{n}/attachments        # upload (multipart, field "file")
GET    /api/workspaces/{wsId}/projects/{pId}/issues/{n}/attachments        # list
GET    /api/workspaces/{wsId}/projects/{pId}/issues/{n}/attachments/{id}   # download (Content-Disposition: attachment)
DELETE /api/workspaces/{wsId}/projects/{pId}/issues/{n}/attachments/{id}   # delete (uploader or project MANAGER)
```

Workspace creation auto-seeds 4 issue types (Bug, Task, Story, Epic) and 3 statuses (To Do/TODO, In Progress/IN_PROGRESS, Done/DONE).

## Gotchas — don't re-debug these

- **`createdAt`/`updatedAt` null after `save()` with `@CreationTimestamp`**: In Hibernate 7, `@CreationTimestamp` sets values at flush time, not at `persist()`. Fix: use Spring Data JPA `@CreatedDate`/`@LastModifiedDate` + `@EntityListeners(AuditingEntityListener.class)` + `@EnableJpaAuditing` — these fire during `@PrePersist`/`@PreUpdate` and values are immediately available after `save()`.
- **Custom `@Component` Filter silently not authenticating**: Spring Boot auto-registers any `Filter` bean as a generic servlet filter *in addition to* `addFilterBefore`. Fix: `FilterRegistrationBean<YourFilter>` with `setEnabled(false)`.
- **404 on unmapped endpoint shows as 403**: container dispatches to `/error` which re-enters Spring Security unauthenticated. Fix: `.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` in `SecurityConfig`.
- **"Access Denied … response is already committed" ERROR when an SSE emitter times out**: emitter completion/timeout triggers a Tomcat ASYNC dispatch that re-enters the security filter chain without a security context; the committed event-stream can't take a 401, so Spring logs an ERROR. Fix: include `DispatcherType.ASYNC` in the `dispatcherTypeMatchers(...).permitAll()` line (safe — an async dispatch only follows an already-authorized request).
- **`@Version` jumps by more than 1**: mutating entity fields before a repository query in the same method causes Hibernate AUTO flush to write the entity twice. Fix: run all reads first, then apply all mutations right before the final `save`/`saveAndFlush`.
- **Files hand-placed in `src/main/resources/static/` disappear**: the Vite build (`outDir: '../resources/static'`, `emptyOutDir: true`) wipes that directory on every frontend build. Static public files (favicon etc.) go in `src/main/frontend/public/` — Vite copies them to the static root; dynamic ones (robots.txt, sitemap.xml) are served by controllers.
- **`@Modifying` JPQL update not visible to subsequent `findById` in same transaction**: Hibernate L1 cache returns the stale entity after a bulk UPDATE. Fix: `@Modifying(clearAutomatically = true)` — clears the first-level cache after the update so the next read hits the DB. Note: for increment-then-read (issue sequence numbers) even that is racy — two transactions can re-read the same final value. `ProjectRepository.incrementAndGetIssueSeq` uses a native `UPDATE ... RETURNING` instead, which gives each transaction its own value.
