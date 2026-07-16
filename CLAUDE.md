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
- **Compliance/SEO (2026-07-12)** — public landing page at `/` for anonymous visitors (signed-in users redirect to their last active project — localStorage recency journal `src/recentProjects.ts`, keyed per user id — falling back to `/workspaces`); legal pages `/terms`, `/privacy`, `/cookies` (draft texts — lawyer review before public launch); required terms checkbox on Register (`RegisterRequest.termsAccepted` → `users.terms_accepted_at`, null for pre-existing users); informational cookie banner (the only cookie is the strictly-necessary `refresh_token`, so no consent UI — dismissal stored in localStorage `hamstrack.cookie-notice-dismissed`); favicon + meta/OG tags in `index.html`; `SeoController` serves `/robots.txt` and `/sitemap.xml` generated from `app.base-url`. Toggles under `app.legal.*` (env `PUBLIC_LANDING_ENABLED`, `TERMS_ACCEPTANCE_REQUIRED`, both default `true`) — DC installs can disable the landing (then `/` → `/login`, robots disallows all, sitemap 404s) and the terms requirement. The SPA reads these from public `GET /api/meta` (`publicLandingEnabled`, `termsAcceptanceRequired`, `publicSignupEnabled`, `version`) into `useConfigStore` with fail-safe defaults. `version` comes from `pom.xml` via the `build-info` goal of `spring-boot-maven-plugin` (`BuildProperties` bean; falls back to `dev` when launched from an IDE without a Maven build) and is shown in the About dialog (`AboutModal`, opened from the top-bar user menu — also has a summary, docs/source links (docs are a temporary link to the GitHub README), and legal links).
- **Phase 4A** — React + TypeScript + Vite frontend. Pages: Login, Register, Workspaces, WorkspaceHome (project grid / auto-redirect), Board (kanban: status columns ordered by position, draggable issue cards — native HTML5 DnD, drop PATCHes `statusId`+`version` with optimistic cache update and rollback on error; drop targets respect workflow transitions), Backlog (`p/:projectId/backlog` — flat table of issues whose status category ≠ DONE), issue side panel for view/edit/comments on both; issue creation goes through `CreateIssueModal` (Jira-style dialog with a Project selector defaulting to the current project; a Workspace selector appears when opened outside a workspace) opened via the top-bar Create button or `uiStore.openCreateIssue()` — the modal is rendered by `TopBar`. The top-bar Create always creates an issue, never a project (projects: "New project" on WorkspaceHome / in `ProjectSwitcher`). `TopBar` is shared by every authenticated page including `/workspaces` (`wsId` optional — without it the project switcher and search are hidden). Project pages are remounted on `wsId`/`projectId` change (`ParamKeyed` in `App.tsx`) so panel/filter state never leaks across projects. App shell is two-level navigation (see DESIGN.md decision log 2026-07-09): global dark top bar (`TopBar`, 56px navy gradient — logo, `ProjectSwitcher` dropdown (last-5 recently visited projects grouped by workspace from `recentProjects.ts`, padded with current-ws projects; View all projects navigates with `state.showAll` which suppresses WorkspaceHome's single-project auto-redirect; View all workspaces; New project), global search stub for future HQL, Create button, `NotificationBell`, user menu, SSE subscription) + contextual light project sidebar (`Sidebar` — Board/Backlog links, Reports/Settings placeholders), rendered only on project routes. Maven `frontend-maven-plugin` builds frontend into `src/main/resources/static/` during `generate-resources`. SPA fallback (`SpaController`) forwards all non-API, non-file paths to `index.html`.

Stack: Spring Boot 4.1.0 / Java 21, Spring Web MVC, Spring Data JPA, Spring Security, PostgreSQL, Flyway, Lombok, jjwt.

Frontend (`src/main/frontend/`): React 19, TypeScript, Vite 6, Tailwind v4 (`@tailwindcss/vite`), React Router v7, TanStack Query v5, Zustand v5, lucide-react.

## Admin console & global taxonomy (M1, 2026-07-15)

**System role**: `users.system_role` (`ADMIN`/`USER`, enum `SystemRole`, extensible). `User.getAuthorities()` → `ROLE_<role>`; all of `/api/admin/**` is guarded by one `hasRole("ADMIN")` line in SecurityConfig. The `seed.admin` account is promoted to ADMIN by `DataSeeder` (idempotent). `/auth/me` returns `systemRole`; the SPA shows "System administration" in the user menu and guards `/admin/**` client-side.

**Taxonomy model** (V6, replaces per-workspace copies): global catalog — `statuses`, `priorities` (NEW entity, was the `IssuePriority` Java enum; `issues.priority_id` FK), `issue_types` — each with `scope_workspace_id NULL` (=global; column reserved for future workspace-admin delegation) and `archived_at`. Reusable bindings: `workflows` (+`workflow_statuses` position = board column order, +`workflow_transitions`, `from_status_id NULL` = "from any") and `priority_sets` (+items with `is_default`). `projects.workflow_id`/`priority_set_id` NULL = the `is_system_default` row. `ProjectConfigService` is the ONLY place that resolves effective config (statuses/transitions/priorities/default/validation) — issue paths and the public endpoint go through it. Transition semantics: a source status with no source-specific rules is open; with rules, allowed = its targets + wildcard targets (wildcards grant, never restrict).

**API**: public `GET /api/workspaces/{ws}/projects/{p}/config` (effective taxonomy — the SPA renders board/forms exclusively from it); admin CRUD under `/api/admin/{statuses,priorities,issue-types}` (+`/archive`,`/unarchive`, `DELETE ?replaceWithId=` remaps issues), `/api/admin/{workflows,priority-sets}` (upserts replace children wholesale), `/api/admin/projects` matrix + `PATCH /{id}/bindings`. Integrity guards (409): delete-in-use without replacement, empty workflow/set, workflow change/status removal that would strand issues in statuses invisible to the board. Removed: workspace-scoped `/statuses`, `/issue-types`, `/status-transitions`; `priority` filter/fields became `priorityId`, `IssueResponse.priority` is now an object (id/name/color/icon/position). Workspace creation no longer seeds taxonomy.

**Frontend**: `/admin/**` (lazy `pages/admin/AdminArea.tsx` — own light sidebar under the global top bar; pages: Statuses, Priorities+sets, Issue types, Workflows, Projects matrix; shared bits in `pages/admin/common.tsx` incl. usage chips and the delete-with-remap dialog). Priority icons = lucide names in the catalog `icon` column, rendered by `PriorityIcon`/`PriorityBadge` (`components/ui.tsx`).

**Gotchas**: don't use `CHAR(n)` in migrations — Hibernate validate fails on bpchar (bit us again in V6; V2 had fixed the same thing). Boot 4: no auto-configured `ObjectMapper` bean; `AutoConfigureMockMvc` lives in `org.springframework.boot.webmvc.test.autoconfigure`. Frontend: **never use Tailwind `max-w-2xs…max-w-3xl`** — our `@theme --spacing-{2xs..3xl}` scale shadows them in Tailwind v4, so `max-w-xl` resolves to `var(--spacing-xl)` = **32px** (word-per-line paragraphs); use inline `maxWidth`. React Router: inside a splat route (`/admin/*`) relative `<Link>`/`<NavLink>` paths resolve AFTER the splat — use absolute paths.

## Custom fields (M2, 2026-07-16)

**Model** (V7, additive — no data reset): `field_defs` (global catalog; `key` snake_case + `type` immutable after creation — stored values depend on them; `config` JSONB: `options[{id,label,color}]` for selects, `min`/`max` for numbers; `archived_at`), `field_sets` + `field_set_items` (position = display order, `required`, `show_on_create`; required forces show_on_create — enforced in service AND UI), `projects.field_set_id` (NULL = the `is_system_default` "No fields" empty set), `issue_field_values` (issue×field UNIQUE, `value` JSONB). V7 seeds sample fields (story_points/severity/environment) + an "Engineering fields" set, bound to nothing.

**Semantics**: value shapes per `FieldType` javadoc (TEXT/TEXTAREA/URL string, NUMBER number, DATE "YYYY-MM-DD", SELECT option id, MULTI_SELECT id[], USER member UUID, CHECKBOX bool). `issue.FieldValueService` is the ONLY writer of `issue_field_values` (`applyValues` — partial map, JSON null clears, 422 on unknown/archived field, required can't be cleared and must be present on create when shown there; changes go to issue history with the field's display *name* and human-readable values). No remap on delete: a field with values 409s unless `?dropValues=true`; archive is the safe path. Select option ids referenced by stored values — removing an option leaves old values rendering the raw id (UI warns).

**API**: admin `/api/admin/fields` (+`/archive|unarchive`, `DELETE ?dropValues=`) and `/api/admin/field-sets` (upsert replaces items wholesale); `PATCH /admin/projects/{id}/bindings` now takes `fieldSetId` too. Issue payloads: `fields` map (create/update) keyed by field id; `IssueResponse.fields` = `[{fieldId, value}]` (filled only); project `config` response carries `fields` (display order + required/showOnCreate).

**Frontend**: `pages/admin/AdminFieldsPage.tsx` (catalog + sets, custom drop-values delete dialog instead of the remap one); Projects matrix got a third select. Shared renderers in `components/fields.tsx` — `FieldInput` (editor per type; USER options = workspace members) + `FieldValueDisplay` (compact read-only) + `FIELD_TYPE_LABELS`. CreateIssueModal renders `showOnCreate` fields and blocks submit on missing required; IssueSidePanel shows filled fields in details and all set fields in edit (diff-only partial `fields` on save, null to clear); Backlog appends one column per set field.

## Admin polish & issue type sets (M3, 2026-07-16)

**Issue type sets** (V8, additive): same one-layer set model as priorities/fields — `issue_type_sets` + `issue_type_set_items` (position = display order), `projects.issue_type_set_id` (NULL = the seeded "All types" `is_system_default` set, which preserves pre-M3 behavior; new catalog types are NOT auto-added to it). Semantics differ from workflows deliberately: a type set restricts only issue **creation and type changes** (`ProjectConfigService.requireTypeInSet` in `IssueService` create/update) — existing issues keep a type that left the set, so binding/set changes need no stranded-issues guard. Config endpoint types now come from `ProjectConfigService.types(project)` (set order, archived filtered). Catalog delete guards: type in use needs remap, no set may be left empty, ≥1 catalog type must remain. API: `/api/admin/issue-type-sets` (upsert `{name, typeIds}` replaces items wholesale), `issueTypeSetId` in bindings.

**Usage detail**: `GET /api/admin/{statuses|priorities|issue-types|fields}/{id}/usage` → `UsageDetailResponse` (names of containing workflows/sets + deduped projects reached through them — incl. unbound projects when a system default is involved — + issue count). Frontend: `UsageChip` accepts `fetchDetail` and expands into a lazy-loading popover.

**Matrix bulk ops** (frontend-only): row checkboxes + bulk bar in `AdminProjectsPage` with a per-dimension "keep / system default / named set" choice; applies via parallel per-project `PATCH /bindings` (`Promise.allSettled`, partial-failure message). `adminProjects.updateBindings` now takes a full `ProjectBindings` object.

**Polish**: `ImpactBanner` ("used by N projects — changes apply immediately") in workflow/priority-set/field-set/type-set editors; `ArchivedToggle` on all four catalog pages (archived rows hidden by default, toggle appears only when something is archived).

## Auth rate limiting (2026-07-14)

`common.ratelimit` — two in-memory mechanisms (single-node; move to Redis if Cloud scales out), both configurable via `app.rate-limit.*` (env `RATE_LIMIT_*`, `enabled` default `true`): **per-IP fixed window** (default 15 req/min) across login/register/verify-email/resend-verification/forgot-password/reset-password, enforced by `AuthRateLimitFilter` — registered via `FilterRegistrationBean` with explicit URL patterns (NOT `@Component`, see filter gotcha) at highest precedence, POST only (`/refresh`+`/logout` excluded: cookie-driven, called on every page load); and **per-account exponential login backoff** (5 fails → 30s, doubling, cap 15 min; success resets) in `AuthService.login`, keyed by submitted email even for unknown accounts (no enumeration via the limiter). Both raise `RateLimitedException` (429 + `Retry-After`, dedicated `GlobalExceptionHandler` handler). Client IP = rightmost `X-Forwarded-For` entry (Caddy ≥2.5 discards client-supplied XFF; only Caddy is exposed on prod) else `remoteAddr`. Counter eviction via `@Scheduled` (`@EnableScheduling` on the app class). Tests: `AuthRateLimitTest` (Boot 4 note: `AutoConfigureMockMvc` lives in `org.springframework.boot.webmvc.test.autoconfigure`).

## Docs (2026-07-14)

`README.md` (public GitHub face — quick start, config env table, links) and `docs/api-cloud.md` / `docs/api-dc.md` (user-facing REST API reference, one file per deployment model; identical structure, DC adds an "Operator settings that affect the API" section). In-app docs hub at SPA route `/docs` (`pages/docs/DocsPage.tsx`, lazy-loaded — Swagger UI is a 1.4MB chunk): tabbed layout (single "REST API" tab so far, admin/user guides planned), renders `swagger-ui-dist` (import `swagger-ui-dist/swagger-ui-bundle` directly — the package main entry requires Node `path` and breaks browser builds) against the hand-written OpenAPI 3.0 spec at `src/main/frontend/public/openapi.yaml`, served statically at `/openapi.yaml` (root-level dotted filename bypasses the SPA fallback; deeper paths like `/docs/x.yaml` would NOT — SpaController's multi-segment pattern only checks the first segment for dots). About modal "Documentation" and the public Footer "Docs" link here. springdoc doesn't support Boot 4 yet — revisit generating the spec when it does. **When the API surface or behavior changes, update `openapi.yaml` AND both api-*.md files** (validate: `npx @apidevtools/swagger-cli validate src/main/frontend/public/openapi.yaml`; beware YAML flow-map `{}` values containing commas/colons — quote them). All controllers carry class-level javadoc; keep it when adding endpoints.

## DC vs Cloud (single codebase)

Hamstrack ships as one codebase in two modes, controlled by Spring profile `dc` or `cloud` (`SPRING_PROFILES_ACTIVE=cloud`). Differences between modes must be config/profile-gated behavior, never forked code.

The highest-severity bug class here is a query/service that forgets to scope by `workspace_id`/membership — in Cloud that leaks one tenant's data to another. Always resolve resources through workspace membership checks; return 404 whether the workspace doesn't exist or the caller isn't a member — never reveal existence via a 403.

**File storage** (`common.storage.FileStorage`): backend selected by `app.storage.type` (`local` | `s3`), profile defaults — `dc` → local FS (`app.storage.local.base-dir`, default `./data/attachments`, gitignored), `cloud` → S3 (`app.storage.s3.*`: bucket, region, optional `endpoint` + `path-style-access` for MinIO/S3-compatible stores, optional static keys — empty falls back to the AWS SDK default credentials chain). Implementations are `@ConditionalOnProperty`-gated beans (`LocalFileStorage` / `S3FileStorage`); inject the `FileStorage` interface, never a concrete class. Storage keys are server-generated (`ws/{wsId}/issues/{issueId}/{uuid}`) — original filenames live only in the DB. Upload size limit via `spring.servlet.multipart.max-file-size` (env `ATTACHMENT_MAX_FILE_SIZE`, default 25MB).

## Demo data (2026-07-14)

Every user gets a "Demo Workspace" with a "Demo Project" (key `DEMO`, 20 dev-team-style issues — 2 epics with children, mixed types/statuses/priorities/due dates, some assigned to the user) seeded on their **first successful authentication**. `common.seed.DemoDataService.seedOnFirstLogin(userId)` is called from `AuthController` after `verify-email`, `login` **and** `refresh` (refresh matters: after a data reset, users with a live session never hit `/login`). It runs *after* the auth transaction commits and is wrapped in try/catch — a seeding failure never fails the authentication, and the claim rolls back so the next auth retries. Idempotency/race safety: `users.demo_seeded_at` (`NULL` = pending) is stamped by the atomic claim `UserRepository.claimDemoSeed` (`UPDATE … WHERE demo_seeded_at IS NULL`); only the winner seeds. Seeding reuses `WorkspaceService`/`ProjectService`/`IssueService`, so all normal invariants hold. Toggle: `app.demo.seed-on-first-login` (env `DEMO_SEED_ON_FIRST_LOGIN`, default `true`).

**Test-mode data reset** (repeatable while the app is in test mode): `V5__demo_data_reset.sql` wipes all user data and re-arms seeding. To repeat, add a new migration with the same block: `DELETE FROM issues;` (first — `issues.workspace_id` has no cascade), `DELETE FROM workspaces;`, `DELETE FROM notifications;` (they only reference users and would point at deleted issues), `UPDATE users SET demo_seeded_at = NULL;`. Do **not** rewrite existing migration files — Flyway checksum validation would fail on every already-migrated DB (prod, local) unless its volume is dropped. Attachment blobs are orphaned by a reset (rows cascade, files don't) — clear the storage dir/volume manually if it matters.

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

GET    /api/workspaces/{wsId}/projects/{pId}/config        # effective taxonomy (statuses/transitions/priorities/types) — see Admin console section

POST   /api/workspaces/{wsId}/projects/{pId}/issues                        # create
GET    /api/workspaces/{wsId}/projects/{pId}/issues?statusId=&assigneeId=&priorityId=  # list + filter
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

Since M1 the taxonomy is NOT seeded per workspace — it lives in the global catalog (V6 seeds Bug/Task/Story/Epic, To Do/In Progress/Done, Urgent…None, "Default workflow", "Default priorities") and reaches projects through bindings; see the Admin console section.

## Gotchas — don't re-debug these

- **`createdAt`/`updatedAt` null after `save()` with `@CreationTimestamp`**: In Hibernate 7, `@CreationTimestamp` sets values at flush time, not at `persist()`. Fix: use Spring Data JPA `@CreatedDate`/`@LastModifiedDate` + `@EntityListeners(AuditingEntityListener.class)` + `@EnableJpaAuditing` — these fire during `@PrePersist`/`@PreUpdate` and values are immediately available after `save()`.
- **Custom `@Component` Filter silently not authenticating**: Spring Boot auto-registers any `Filter` bean as a generic servlet filter *in addition to* `addFilterBefore`. Fix: `FilterRegistrationBean<YourFilter>` with `setEnabled(false)`.
- **404 on unmapped endpoint shows as 403**: container dispatches to `/error` which re-enters Spring Security unauthenticated. Fix: `.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` in `SecurityConfig`.
- **"Access Denied … response is already committed" ERROR when an SSE emitter times out**: emitter completion/timeout triggers a Tomcat ASYNC dispatch that re-enters the security filter chain without a security context; the committed event-stream can't take a 401, so Spring logs an ERROR. Fix: include `DispatcherType.ASYNC` in the `dispatcherTypeMatchers(...).permitAll()` line (safe — an async dispatch only follows an already-authorized request).
- **`@Version` jumps by more than 1**: mutating entity fields before a repository query in the same method causes Hibernate AUTO flush to write the entity twice. Fix: run all reads first, then apply all mutations right before the final `save`/`saveAndFlush`.
- **Files hand-placed in `src/main/resources/static/` disappear**: the Vite build (`outDir: '../resources/static'`, `emptyOutDir: true`) wipes that directory on every frontend build. Static public files (favicon etc.) go in `src/main/frontend/public/` — Vite copies them to the static root; dynamic ones (robots.txt, sitemap.xml) are served by controllers.
- **`@Modifying` JPQL update not visible to subsequent `findById` in same transaction**: Hibernate L1 cache returns the stale entity after a bulk UPDATE. Fix: `@Modifying(clearAutomatically = true)` — clears the first-level cache after the update so the next read hits the DB. Note: for increment-then-read (issue sequence numbers) even that is racy — two transactions can re-read the same final value. `ProjectRepository.incrementAndGetIssueSeq` uses a native `UPDATE ... RETURNING` instead, which gives each transaction its own value.
