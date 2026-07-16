# Hamstrack — Development Plan

## Stack

| Layer | Choice | Reason |
|---|---|---|
| Backend | Spring Boot 4.1.0 / Java 21 | Stable, fast, Cloud-friendly |
| Frontend | React + TypeScript + Vite | Wide adoption, good plugin/slot architecture, build artifact served by Spring Boot |
| Database | PostgreSQL + Flyway | Multi-tenant, Cloud-first schema |
| Agent isolation | Firecracker (Cloud) / Wasmtime (DC fallback) | KVM microVM; WASM fallback where KVM unavailable |
| AI providers | Pluggable | User supplies their own API key/endpoint (OpenAI, Claude, Ollama, custom) |

> **Firecracker note:** Requires Linux + KVM hardware virtualization. Will not run in standard Docker containers without nested virt — Cloud infra must account for this (bare metal or KVM-enabled VMs).

## Deployment Models

- **Cloud** — hosted SaaS, multi-tenant, billing, managed infra on **AWS**. Primary focus now.
- **DC (self-hosted)** — single-artifact Docker Compose / Helm, prepared later.

Differences between modes are config/profile-gated, never forked code.

---

## Phase 1 — Foundation ✅

- [x] Repository structure (backend + frontend monorepo)
- [x] Docker Compose dev environment
- [x] DB schema design from scratch (multi-tenant, Cloud-first, UUID v7)
- [x] Environment config: profiles `local` / `cloud` / `dc`
- [x] Package: `com.hamstrack`, entry point `HamstrackApplication`

## Phase 2 — Auth & Workspace ✅

- [x] Registration + email verification (token hashed SHA-256, expiry, resend)
- [x] JWT access tokens + refresh tokens (cookie-based, hashed, revocable)
- [x] Password reset flow (forgot / reset via email link)
- [x] Workspace (tenant) — create, list, get; slug auto-generated
- [x] Workspace members — list, invite by email, accept invite
- [x] Roles: `OWNER` / `MEMBER` at workspace level; `MANAGER` / `MEMBER` at project level
- [ ] OAuth 2.0 (Google, GitHub) — schema ready (`oauth_accounts` table), not yet implemented

## Phase 3 — Core Task Tracker ✅ (partial)

- [x] Project management — create, list, get, update, archive; project key (e.g. HMS); `issue_seq` counter
- [x] Project members — add, remove, list; MANAGER-only mutations
- [x] Issue types — workspace-scoped CRUD, ordered by position; seeded on workspace create (Bug, Task, Story, Epic)
- [x] Statuses — workspace-scoped CRUD with `StatusCategory` (TODO / IN_PROGRESS / DONE), ordered by position; seeded on workspace create
- [x] Issues — CRUD, project-scoped sequence numbers, priorities, assignee, due date, optimistic locking (`@Version`)
- [x] Issue filters — by `statusId`, `assigneeId`, `priority`
- [x] Comments — create, list (soft-deleted excluded), update (author only), soft delete (author only)
- [x] Issue history / audit trail (field-level change log: status, priority, type, assignee, title, due date)
- [x] Mentions in comments (`@user` — autocomplete in UI, backend parses & notifies)
- [x] In-app notifications (bell icon in sidebar with unread badge, real-time via SSE)
- [x] Real-time updates (SSE — issue create/update/delete, comment added, notification push)
- [x] Workflow engine (status transition rules per workspace; open by default, restrict by defining allowed transitions)
- [x] File attachments (per-issue upload/download/delete; storage backend is profile-gated — local FS for DC, S3 for Cloud via `app.storage.type`)
- [ ] Email notifications (requires Phase 7 mail infra)

## Phase 4A — Basic React Frontend ✅

- [x] React 19 + TypeScript + Vite 6 + Tailwind v4 (`@tailwindcss/vite`)
- [x] React Router v7, TanStack Query v5, Zustand v5, lucide-react
- [x] Pages: Login, Register, Workspaces, WorkspaceHome (project grid + auto-redirect), Board
- [x] Board page: issue list table, side panel for create / view / edit / comments
- [x] Dark sidebar with workspace switcher and user dropdown (profile, logout)
- [x] Dark mode support; design tokens from `DESIGN.md`
- [x] Maven `frontend-maven-plugin` builds frontend into `src/main/resources/static/` during `generate-resources`
- [x] `SpaController` — SPA fallback forwards all non-API, non-static paths to `index.html`

## Phase 4B — Frontend Extension System

The key differentiator for customizability — similar to Jira's ScriptRunner Fragments.

- [ ] Extension Points architecture (named slots: `issue-header`, `board-card`, `sidebar`, etc.)
- [ ] No-code customization via UI (status colors, field highlighting, transition colors — stored in DB)
- [ ] Custom module injection (HTML/CSS/JS) for power users
- [ ] Cloud isolation for custom code (iframe sandbox / dedicated subdomain — prevents XSS across tenants)
- [ ] Extension library / marketplace

## Phase 5 — Agent Infrastructure

- [ ] Agent model (type, config, credentials, triggers, permissions)
- [ ] Pluggable AI provider (user connects their own: OpenAI, Claude, Ollama, custom endpoint)
- [ ] Agent permission model (what an agent can do inside the tracker)
- [ ] MicroVM runtime (Firecracker): VMM process, network/resource/filesystem isolation
- [ ] Script executor: run agent-generated scripts inside microVM
- [ ] Agent audit log

## Phase 6 — Agent Features

- [ ] Event triggers (issue created/updated, transition, comment added, scheduled)
- [ ] Automatic script generation by agents
- [ ] Agent configuration UI
- [ ] Agent presets / templates
- [ ] Agent action history & replay

## Phase 7 — Cloud Infrastructure

- [ ] Billing & subscriptions (Stripe)
- [ ] Usage metering (agent runs, storage, seats)
- [ ] S3-compatible file storage
- [ ] Email service (SES / Postmark)
- [ ] Monitoring & observability
- [x] Rate limiting & abuse protection — **auth endpoints done 2026-07-14** (per-IP window + per-account login backoff, `common.ratelimit`, env `RATE_LIMIT_*`); rest of the API still unlimited — revisit with usage metering

### Prod hardening backlog (deployed 2026-07-11: https://hamstrack.com, EC2 + CD pipeline)

- [x] **SMTP on prod** — done 2026-07-11 via Resend (domain verified with MX/SPF/DKIM records on Cloudflare, sender `noreply@hamstrack.com`). `MAIL_*` vars flow through `docker-compose.prod.yml` → server `.env`; SMTP creds: host `smtp.resend.com:587`, username `resend`, password = API key. Free tier: 3000 emails/mo — revisit (SES?) if volume grows.
- [x] **Switch attachments to S3** — done 2026-07-14: bucket `hamstrack-attachments-prod` (private, eu-north-1), instance role `hamstrack-ec2` (S3 policy + `AmazonSSMManagedInstanceCore`) on `i-019fe684b25ad831f`, IMDS hop limit was already 2, `.env` → `STORAGE_TYPE=s3`; verified e2e (upload/download через прод-API, объект в бакете). Local volume was empty — no migration needed.
- [x] **Cloudflare proxy (orange cloud)** — done 2026-07-14: SSL mode Full (strict), both DNS records Proxied, Caddyfile got a global `servers { trusted_proxies static <CF ranges> }` block and `reverse_proxy` now sends `header_up X-Forwarded-For {client_ip}` (single resolved real client IP — the auth rate limiter keys on the rightmost XFF entry). Verified: site serves via CF (CF-RAY), Caddy logs show `client_ip` = real visitor while `remote_ip` = CF edge, and a burst through CF trips 429 exactly at the 16th request. CF ranges are pinned statically — re-check https://www.cloudflare.com/ips/ if client IPs ever look wrong.
- [x] **Close SSH port 22** — done 2026-07-14. Deploys go through SSM (`hamstrack-deploy` IAM user, GitHub secrets `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`; instance id is inlined in pipeline.yml), first green SSM deploy verified, then the 0.0.0.0/0:22 rule was revoked (SG `sg-060970351b3f5c950` now allows only 80/443). Server access henceforth: SSM only — `aws ssm start-session --target i-019fe684b25ad831f` or send-command (compose plugin installed system-wide since SSM runs as root). Old `SERVER_*` GitHub secrets are obsolete.

## Phase 8 — DC Version *(later)*

- [ ] Docker Compose / Helm packaging
- [ ] Single-artifact deployment
- [ ] WASM fallback for agent scripts (when KVM unavailable)
- [ ] Local LLM support (Ollama)
- [ ] Update mechanism
