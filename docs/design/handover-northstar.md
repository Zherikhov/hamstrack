# Hamstrack — North Star (Handover Due-Diligence)

Status: **handover anchor** (2026-08-14). This section is the load-bearing summary a new
team must internalize before touching the core. It is synthesized read-only from `PLAN.md`,
`CLAUDE.md`, `DESIGN.md`, `docs/project-state.md`, and the `docs/design/*-proposal.md` set.
If a change contradicts anything below, stop and re-confirm — the risk is rewriting the core
in the wrong direction. Where this map and a source doc disagree, the source doc wins.

> **Highest-risk assumption flagged up front:** the entire product thesis is *one artifact,
> two deployment modes (DC self-hosted + Cloud SaaS), differences config/profile-gated and
> never forked*. The two headline sellable features (Frontend Extension System, AI-Agent
> infrastructure) are **not yet built** (Phases 4B/5/6). It is therefore very easy for an
> incoming team to build them Cloud-first with cloud-only assumptions and silently break the
> single-codebase promise. Every new subsystem must ship a self-hosted path from day one
> (storage, isolation runtime, AI provider, billing-optional), the same way `FileStorage`
> already does (local FS on DC, S3 on Cloud, one interface).

---

## 1. What Hamstrack IS (and is NOT)

- **Is:** an **open-source, Jira-inspired task tracker** for teams — workspaces, projects,
  issues, boards/backlog, comments, attachments, a governed global taxonomy (statuses /
  priorities / issue types / custom fields bound to projects), issue hierarchy, full-text +
  structured search (HQL), saved filters, notifications, and a delegated admin model.
- **Is NOT a Jira clone.** It must **not copy Jira's implementation, UI, naming, or
  proprietary behavior.** Jira is studied as a *capability* benchmark and a *UX anti-pattern*
  benchmark (scheme-hell, "where is this used?", silent cross-project breakage) — the product
  deliberately flattens that (one layer of named reusable sets + usage-first UX). Naming is
  original ("Hamstrack", "HQL", "Beacon", "workspaces", `StatusCategory`).
- **Positioning:** B2B SaaS / dev-&-ops process tooling, aimed at **project owners and
  workspace admins leaving Jira Data Center or evaluating new trackers** — technical,
  skeptical buyers burned by complexity who **do not trust black-box AI by default**. The
  product's emotional promise (encoded even in the design system) is *"this AI can be trusted
  here"*: inspectable, sandboxed, human-approved, reversible.

## 2. NON-NEGOTIABLE principles (baked into the architecture)

1. **Single codebase, two modes.** One artifact runs as DC (`SPRING_PROFILES_ACTIVE=dc`) or
   Cloud (`cloud`). Mode differences are **config/profile/property-gated behavior, never
   forked code**. Every new toggle needs an env var + a sensible per-profile default, wired
   end to end (properties → compose → `.env.prod.example` → README). No cloud-only assumption
   without an equivalent self-hosted path.
2. **Multi-tenant workspace isolation is the top invariant.** Everything lives under a
   workspace; access is via membership. The highest-severity bug class is a query/service
   that forgets to scope by `workspace_id`/membership — in Cloud that leaks one tenant's data
   to another. **Resources 404 (never 403) when the caller isn't a member** — never reveal
   existence. Nested paths must re-verify the parent chain. (This is why `tenancy-reviewer`
   is a mandatory gate on any backend diff.)
3. **Governed global taxonomy + bindings, resolved in one place.** Statuses / priorities /
   issue types / custom fields are a **global catalog** reached by projects through
   workflows / priority-sets / issue-type-sets / field-sets; `NULL` binding = the
   `is_system_default` row. **`ProjectConfigService` is the single resolver** of effective
   config. New config-like concepts follow this catalog-plus-binding shape — do not
   reintroduce per-workspace copies.
4. **Layered scope / delegated admin is live, not aspirational.** GLOBAL (system `ADMIN`),
   WORKSPACE (`OWNER`/`ADMIN`), PROJECT (`MANAGER`) scopes; mutually exclusive by DB CHECK;
   visibility/ownership via `ScopeResolver`/`ScopeContext`. Usage counts must never span
   tenants.
5. **Flyway-only schema, validate-only Hibernate.** `ddl-auto=validate`; schema is owned by
   Flyway migrations (current baseline `V1__init_schema.sql`, new work continues from `V2+`).
   Never edit an applied migration. **UUID v7** app-generated everywhere (never `IDENTITY`/
   `BIGSERIAL`). **`VARCHAR(n)`, never `CHAR`/PG `ENUM`** (Hibernate 7 cast errors). Spring
   Data `@CreatedDate`/`@LastModifiedDate` (not Hibernate `@Creation/UpdateTimestamp`).
   DB-maintained counters are `updatable=false` on the entity.
6. **Performance, maintainability, simple deployment** are first-class requirements, equal to
   features. Favor options that work in both deployment models; avoid architecture that only
   makes sense at Cloud scale.
7. **Package-by-feature.** `com.hamstrack.{auth,workspace,project,issue,search,...}` + `common`
   for cross-cutting infra. Layer subpackages nested per feature.
8. **The Beacon design system is the visual contract.** Slate neutrals + single teal accent
   (`--color-brand #0EA5A4`), dark navigation rail (`--color-ink #101828`), Inter everywhere
   (IBM Plex Mono for keys/tables/audit = "inspectable, not magic"; JetBrains Mono for code),
   work-centric dashboard **Home** as the default post-login screen. **Color doubles as a
   safety-state machine** (sandbox/slate → pending/amber → production/teal) — that is a
   product-trust differentiator, not decoration. Read `DESIGN.md`; use tokens, never
   hardcoded hex.

## 3. CORE SELLABLE DIFFERENTIATORS (foundations that must NOT be compromised)

These are what the product is sold on. Two of the three are still ahead — protect their
*preconditions* now.

- **(a) True DC + Cloud parity from one artifact.** The self-hosted story is not a
  second-class port; it is a primary value proposition for the target buyer (ex-Jira DC).
  Proven pattern to imitate: `FileStorage` (local FS on DC / S3 on Cloud, `@ConditionalOnProperty`
  beans behind one interface, profile-defaulted). Auth, storage, email, and future billing
  must all keep a self-hosted path. **Don't compromise:** never bake a cloud-only dependency
  into the core request path without a DC fallback.

- **(b) Frontend Extension System (Phase 4B) — the customizability play vs Jira ScriptRunner.**
  Named UI **extension slots** (`issue-header`, `board-card`, `sidebar`, …), **no-code
  customization via UI** (status/transition/field colors and highlighting, stored in DB),
  **custom module injection** (HTML/CSS/JS) for power users, **Cloud isolation for custom
  code** (iframe sandbox / dedicated subdomain so custom code can't XSS across tenants), and
  an **extension library / marketplace**. **Don't compromise:** the slot architecture must be
  first-class in the SPA (not bolted on), and the tenant-isolation story for injected code is
  non-negotiable — it is the Cloud-safety counterpart to workspace data isolation.

- **(c) AI-Agent infrastructure (Phase 5/6) — the trust-differentiated automation play.**
  An agent model (type, config, credentials, triggers, permissions, audit); **pluggable AI
  providers** where the customer supplies their own key/endpoint (OpenAI / Claude / Ollama /
  custom — never a hardwired vendor); an **agent permission model** bounding what an agent may
  do inside the tracker; **microVM-isolated script execution** (Firecracker on Cloud where
  KVM exists, **Wasmtime/WASM fallback on DC** where it doesn't); event triggers; and a full
  **agent audit log**. The *whole* design system was drawn around this feature's trust promise
  (sandbox → pending-approval → production, dry-run, human approval, post-promotion rollback).
  **Don't compromise:** (i) provider must stay pluggable and bring-your-own-key; (ii) script
  execution must stay sandboxed with a DC-viable isolation runtime; (iii) the safety-state /
  approval / audit / rollback loop is the sellable trust story — do not ship agents that act
  unsandboxed, unapproved, or unaudited.

## 4. Direction / roadmap — where we ARE and "don't break this"

**NOW: Phase 4A complete.** Full-stack app runs; React SPA ships as a single JAR via Maven.
Delivered well beyond the original 4A line: global taxonomy + admin console (M1–M3), custom
fields, delegated/scoped admin, HQL search + saved filters, issue hierarchy, system fields /
`closed_at`, the redesigned issue-detail surfaces, user accounts / onboarding / registration
gating, auth rate limiting, demo data, and a deployed Cloud instance (EC2 + SSM-only + S3 +
Cloudflare + CD). Schema was squashed to a single `V1` baseline (zero real users at the time).

**Sequence ahead:** **4B** Frontend Extension System → **5** Agent infrastructure → **6**
Agent features → **7** Cloud infrastructure (billing/metering/email/observability; auth rate
limiting already partially done) → **8** DC packaging (Compose/Helm, single-artifact, WASM
fallback, local LLM/Ollama, update mechanism). Cloud is the current primary focus; DC is
"prepared later" **but its constraints are designed in now, not retrofitted.**

**"Don't break this" constraints that follow for the incoming team:**
- Don't couple new work to Cloud infra that has no DC equivalent — every subsystem needs a
  self-hosted path even if DC packaging (Phase 8) ships later.
- Don't undermine 4B/5 preconditions: keep the SPA extensible (clean slot points), keep
  isolation boundaries (iframe/subdomain for custom code; microVM/WASM for agent scripts),
  keep AI providers pluggable and BYO-key, keep the audit/approval/rollback loop.
- Don't weaken the invariants in §2 for short-term speed: tenancy 404s, `ProjectConfigService`
  as the single resolver, catalog-plus-binding shape, Flyway-only + UUID v7 + `VARCHAR`,
  Beacon tokens + safety-state color.
- Don't re-fork behavior by mode — reach for a profile/property gate + env var + per-profile
  default instead.
- Respect the enforced dev pipeline: `systems-analyst` spec before code; mandatory gates
  (`tenancy-reviewer`, `security-officer`, `test-runner`) and conditional gates
  (`migration-reviewer`, `dc-cloud-guard`, `api-docs-sync`); only `backend-builder`/
  `frontend-builder` write code. On any API change, sync `openapi.yaml` + both `docs/api-*.md`.

## 5. Principles the incoming team commits to upholding (checklist)

- [ ] **One codebase, two modes** — no forked logic; every mode difference is a
      profile/property gate with an env var + per-profile default, wired to compose +
      `.env.prod.example` + README.
- [ ] **Tenant isolation first** — every workspace-scoped query/service checks membership;
      missing-or-unauthorized returns **404, never 403**; nested paths re-verify the parent.
- [ ] **Taxonomy through the catalog + bindings** — no per-workspace copies; effective config
      resolved only via `ProjectConfigService`; new config concepts follow the same shape.
- [ ] **Delegated-admin scope respected** — GLOBAL/WORKSPACE/PROJECT ownership + visibility;
      usage/aggregation never spans tenants.
- [ ] **Schema discipline** — Flyway-only, `validate` mode, never edit an applied migration;
      UUID v7; `VARCHAR` (no `CHAR`/`ENUM`); `@CreatedDate`/`@LastModifiedDate`;
      DB counters `updatable=false`.
- [ ] **Not a Jira clone** — original UI/naming/behavior; Jira used only as capability/UX
      reference, never copied.
- [ ] **Beacon design system honored** — tokens not hardcoded hex; safety-state color meaning
      preserved; `DESIGN.md` read before any UI change.
- [ ] **Differentiator preconditions protected** — extensible SPA slots + isolated custom
      code (4B); pluggable BYO-key AI + sandboxed (microVM/WASM) execution + audit/approval/
      rollback (5/6).
- [ ] **Self-hosted parity by construction** — no cloud-only dependency in a core path
      without a DC fallback (mirror the `FileStorage` pattern).
- [ ] **Pipeline honored** — spec before code; mandatory + conditional review gates; only the
      two builder agents write code; API docs synced on every surface change.
