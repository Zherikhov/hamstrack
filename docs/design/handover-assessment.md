# Hamstrack — Handover Technical Due-Diligence Assessment

**Tracking:** HD-72 (epic). **Date:** 2026-08-14. **Method:** feature-pipeline orchestration —
10 read-only reviewers (project-native + specialist), one per dimension, each handed scoped paths
and returning severity-rated findings with `file:line` evidence. **Baseline:** backend suite GREEN
(174/174, 2:49). Companion doc: `docs/design/handover-northstar.md` (product principles & direction).

---

## 1. Executive verdict

**Overall health: STRONG core, safe to take over. Roadmap-readiness: 🟡 YELLOW — targeted
hardening/refactors needed before Phase 4B/5, no rewrite.**

This is an unusually disciplined codebase for its age. The hard-won Hibernate 7 / Jackson 3 /
Spring Boot 4 traps catalogued in `CLAUDE.md` are *actually and consistently applied* at the sites
that matter, with explanatory comments. The two highest-severity surfaces — **cross-tenant
isolation** (the project's top bug class) and **HQL search injection** — are both **structurally
sound**: every workspace-scoped resource is reached through a membership predicate with 404-not-403,
and the HQL engine compiles entirely through the JPA Criteria API with bound parameters (injection
is unreachable by construction).

- **No P0 (Critical) security or tenancy defect was found.** The prior audit's flagship Critical
  cross-tenant leaks (`cross-tenant-data-exposure-audit.md` §4.1–4.4) are **remediated** in current code.
- The real risks cluster in two buckets: **(a) a handful of bounded correctness/security bugs**
  (auto-fixable now), and **(b) scaling & extensibility prerequisites** — the app is excellent on a
  single node but **cannot safely run multi-node Cloud today**, and the two flagship differentiators
  (Extension System, AI Agents) land on seams that don't exist yet.
- **Test coverage is a genuine liability**: 17 classes / 252 files, with zero tests on attachments,
  auth-flows-beyond-login, comments, membership, and the **entire frontend**.

**Go/no-go:** GO on the handover. The core is worth building on; none of the findings justify a
rewrite. Condition: pay down the four architectural seams (event bus, tenancy primitive, extension
slots, agent-capable permissions) *before* Phase 4B/5, and add a shared backplane before scaling
Cloud out.

### Health scorecard

| Dimension | Verdict | Worst finding |
|---|---|---|
| Multi-tenant isolation | 🟢 Sound | none exploitable (2 cosmetic Low) |
| Application security | 🟢 Sound | XFF rate-limit trust (Med), single-node counters (Med) |
| Schema & migrations | 🟢 Sound | HD-13 FK gap (Low, tracked) |
| Code quality / bugs | 🟢 Strong | primitive-boolean DTO 400 trap (Med), SSE catch (Med) |
| DC/Cloud dual-mode | 🟢 Sound | `.env.prod.example` under-lists DC toggles (Med) |
| Architecture / extensibility | 🟡 Yellow | no event bus; no extension slots; role model (High) |
| Resilience / performance | 🟠 Single-node-only | SSE + rate-limit in-memory block multi-node (Crit-for-scale) |
| Tests | 🟠 Narrow | attachments/auth/FE untested |
| API docs | 🟢 Good | `IssueResponse.closedAt` undocumented (High) |
| Product direction | 🟢 Clear | DC agent-isolation (WASM) must stay a real path |

---

## 2. Product principles & direction (the "don't rewrite the core" contract)

Full statement in `docs/design/handover-northstar.md`. The load-bearing summary the incoming team commits to:

- **One codebase, two modes.** DC (self-hosted) + Cloud (SaaS) differences are **config/profile/
  property-gated, never forked**. Every new subsystem ships a self-hosted path from day one — mirror
  `FileStorage` (local FS on DC, S3 on Cloud, one injected interface). This is the #1 assumption most
  easily broken by building 4B/5 "Cloud-first."
- **Multi-tenant workspace isolation is the top invariant** — scope every query by
  `workspace_id`/membership; **404 never 403**; nested paths re-verify parents.
- **Governed global taxonomy + project bindings, resolved in one place** (`ProjectConfigService`).
- **Flyway-only schema, Hibernate `validate`** — UUID v7 app-generated, `VARCHAR` not ENUM/`CHAR`,
  `@CreatedDate`/`@LastModifiedDate`, DB counters `updatable=false`, never edit an applied migration.
- **Beacon design system** — slate + teal tokens; color doubles as a trust/safety-state machine
  (sandbox→pending→production). Read `DESIGN.md` before any UI work.

**Core sellable differentiators whose foundations must not be compromised:**
1. **True DC+Cloud parity from one artifact** (self-hosting is a value prop, not a port).
2. **Frontend Extension System (Phase 4B)** — named UI slots, no-code DB-stored customization,
   tenant-isolated custom-code injection, marketplace. *The Jira-ScriptRunner-killer.*
3. **AI-Agent infrastructure (Phase 5)** — pluggable BYO-key providers, microVM-isolated execution
   (Firecracker on Cloud, **WASM/Wasmtime fallback on DC**), triggers, permissions, audit, and the
   sandbox→approval→production trust loop.

**Where we are:** Phase 4A complete (+ taxonomy/admin M1–M3, custom fields, delegated admin, HQL
search, saved filters, issue hierarchy, accounts/onboarding, deployed Cloud on EC2). Next: 4B → 5 →
6 → 7 (Cloud infra/billing/observability) → 8 (DC packaging). **Single most load-bearing open
assumption:** the DC agent-isolation fallback (WASM) must be a *maintained* path, or differentiator
(a) and (c) silently break.

---

## 3. Findings by dimension

Severity: **Critical** (exploitable/blocking now) · **High** · **Medium** · **Low**. IDs are used by
the remediation backlog (§4).

### 3.1 Multi-tenant isolation — 🟢 no exploitable defect
The core invariant holds everywhere: membership-predicate resolution, 404-not-403, nested-parent
re-verification, HQL scope as an un-widenable outermost conjunction, delegated-admin `ScopeContext`
threaded through every count/list/detail. Prior audit's Criticals closed.
- **T-1 (Low):** delete-in-use counts in `AdminCatalogService` (`countByStatus/Priority/Type`, lines
  111/195/291) are global, not scope-parameterized — *not reachable cross-tenant* (own-scope
  mutation gate), cosmetic. Switch to the existing `countByXScoped(...)`.
- **T-2 (Low):** unscoped replacement-id `findById` on catalog delete (`AdminCatalogService`
  123/209/308) — no tenant data returned; resolve via `findByIdVisibleTo(scope)` for clean 404.
- **T-3 (Low, open item):** the documented `app.admin.system-console-enabled` kill-switch is **not
  implemented**; system console is guarded by `hasRole("ADMIN")` alone.

### 3.2 Application security — 🟢 sound, defense-in-depth
Algorithm-pinned HS256 JWT (fail-fast ≥32-byte secret, DB re-load + `isEnabled()` per request),
hashed rotating refresh tokens (httpOnly/SameSite=Strict, path-scoped), hashed single-use TTL account
tokens, enumeration-safe reset/resend, server-generated storage keys + forced attachment disposition,
frontend uses `sessionStorage` (not localStorage) and no raw-HTML render. HQL injection-proof.
- **S-1 (Medium):** `AuthRateLimitFilter:55-62` trusts `X-Forwarded-For` **unconditionally**. If the
  app port is reachable without Caddy in front (a DC self-host exposing 8080, misconfig, or a second
  untrusted proxy), an attacker rotates a spoofed XFF per request → fresh per-IP budget → bypasses
  the login/register/forgot/reset IP cap (mail-spam + brute-force amplification). **Fix:** gate XFF
  on a configured trusted-proxy / `app.rate-limit.trust-forwarded-for` (default false); else key on
  `getRemoteAddr()`.
- **S-2 (Medium):** in-memory rate-limit/login-backoff counters are per-JVM → multiply by replica
  count under horizontal scaling. (Same root as R-1/R-2 below.)
- **S-3..S-6 (Low):** login user-enumeration (verified-vs-unknown), non-revocable 30-min access token,
  `svg` in the upload allow-list (mitigated by attachment disposition), verify-email GET reflects
  token into a *relative* Location (safe — confirmed not an open redirect).

### 3.3 Schema & migrations — 🟢 sound
No PG ENUM/`CHAR`, UUID v7 everywhere, Spring auditing timestamps, `issue_seq` `updatable=false`,
entity⇄schema parity clean (`validate` passes), FKs/hot columns indexed, GIN on custom-field JSONB,
scoped-taxonomy UNIQUE constraints correct, un-edited V1→V6 chain.
- **M-1 (Low, tracked HD-13):** `issues.type_id`/`status_id` have no FK constraint (dropped by the
  pre-squash cascade, enforced at app layer via `ProjectConfigService` by design). Aware, not urgent.
- **M-2 (Low):** `oauth_accounts` table has no JPA entity — reserved/dead schema (OAuth unimplemented).

### 3.4 Code quality / bugs — 🟢 strong
Every documented gotcha correctly applied (flush-before-reinsert ×4 editors, `updatable=false`
counter, reads-before-mutations `@Version` ordering, boxed-Boolean issue DTO, problemdetails wiring).
- **C-1 (Medium, real bug):** primitive `boolean` in `UpsertFieldSetRequest:16`
  (`required`,`showOnCreate`) and `UpsertPrioritySetRequest:17` (`isDefault`) re-introduces the
  Jackson-3 `FAIL_ON_NULL_FOR_PRIMITIVES` trap → opaque `400 "Failed to read request"` for any client
  omitting the flag. Masked only because the SPA always sends them. **Fix:** boxed `Boolean` +
  null→false coalescing constructor (mirror `UpdateIssueRequest:39-43`).
- **C-2 (Medium, real bug):** `SseRegistry.send` (`:94-101`) catches only `IOException`; a
  stale/completed emitter throws unchecked `IllegalStateException`, aborting the broadcast loop and
  **dropping the event for every later subscriber** in that workspace. **Fix:** catch `Exception`,
  `completeWithError` + remove, continue the loop.
- **C-3..C-5 (Low):** `IssueDetail.loadIssue` stale-response race (no cancellation); raw
  `ResponseStatusException` convention drift (also A-5); silent `.catch(()=>{})` on secondary FE fetches.

### 3.5 DC/Cloud dual-mode — 🟢 sound
Zero `if(isCloud)` forking (grep-verified); only two `@ConditionalOnProperty` storage beans; all mode
differences flow through `AppProperties` + profile defaults; S3 supports MinIO + AWS default-cred
chain (not AWS-locked); SSM/instance-role live only in infra, not the JAR.
- **D-1 (Medium):** `.env.prod.example` omits the DC-behavioral toggles
  (`PUBLIC_SIGNUP_ENABLED`, `ONBOARDING_ENABLED`, `PUBLIC_LANDING_ENABLED`, `DEMO_SEED_ON_FIRST_LOGIN`,
  `RATE_LIMIT_*`, `STORAGE_LOCAL_DIR`, S3-compatible knobs) — the exact levers that make DC behave
  like DC. Covered in `docs/self-hosting.md`, but the operator template is incomplete.
- **D-2 (Low/Medium, hygiene):** `application-local.properties` is committed with a real-looking DB
  password + `jwt.secret` (header says "NOT committed"; also stale per current creds). Gitignore +
  rotate.
- **D-3 (Low):** `MailService:77,82` hardcodes the retired brand hex `#0F6E63` (Beacon is `#0EA5A4`).

### 3.6 Architecture & extensibility — 🟡 YELLOW (sound core, refactor before 4B/5)
Clean package-by-feature, thin controllers over transactional services over scoped repositories,
coherent taxonomy model with a single resolver. Rewrite **not** warranted. Drivers of YELLOW:
- **A-1 (High; Critical for Phase 5):** **no domain-event abstraction.** State changes call
  `sseRegistry.broadcast(...)` and `notificationService.create(...)` **inline** with untyped
  string+Map payloads (`IssueService` 140/352/394, `CommentService`, `AttachmentService`); zero
  `ApplicationEvent`/`@EventListener`. Phase 5's agent trigger bus has no substrate. **Fix (prereq):**
  typed domain events via `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`
  (also cleanly replaces the manual `afterCommit`); SSE/notifications/agents become listeners.
- **A-2 (High):** **tenancy is enforced by ~50× copy-paste across 12 services**, not a primitive.
  Safe today by discipline, but every new phase multiplies the surface. **Fix:** extract one
  `WorkspaceAccessService`/`WorkspaceContext` primitive; route all callers through it.
- **A-3 (High; the 4B differentiator):** **no extension-point/slot abstraction** anywhere in the SPA.
  The *no-code* half (colors/highlighting from DB config) rides the existing config channel — easy,
  GREEN. The *custom-code injection with tenant isolation* half is genuinely greenfield (CSP,
  subdomain, postMessage). **Fix:** split 4B — ship no-code first; add a `SlotRegistry` +
  `<ExtensionSlot>` host now; treat custom-code as a separate security-gated project.
- **A-4 (Medium; High for Phase 5):** three disjoint role enums, no capability/`Principal`
  abstraction; services take a concrete `User actor`. Agents need scoped capabilities. **Fix:** wrap
  roles in an `Actor` + capability seam before Phase 5.
- **A-5 (Medium):** raw `ResponseStatusException` in 15 files (esp. `IssueService`) bypasses the typed
  `AppException` contract (one config toggle from blank error banners). Promote to typed exceptions.
- **A-6 (Medium):** `issue/entity` is overloaded (issues + taxonomy + custom-fields + workflow +
  hierarchy). Consider extracting `taxonomy`/`customfield` packages when next touched; document sub-boundaries.

### 3.7 Resilience / performance — 🟠 excellent single-node, **not multi-node-ready**
- **R-1 (Critical for scale):** **SSE fan-out is single-node in-memory** (`SseRegistry:26`). Behind
  >1 replica, `(N-1)/N` of users never get live updates. *Blocks multi-node Cloud.* **Fix:** shared
  pub/sub backplane (Redis or Postgres `LISTEN/NOTIFY`), profile-gated to keep DC in-memory.
- **R-2 (Critical for scale):** **rate-limit/backoff counters single-node** (`RateLimitService:37-39`)
  — abuse protection ÷ replica count (= S-2). **Fix:** shared store (Redis atomic INCR+TTL), profile-gated.
- **R-3 (High):** blob I/O (S3/SMTP) has **no timeouts** and S3 `putObject` runs **inside the DB
  transaction** (`AttachmentService:85-90`) → a slow/hung S3 pins Hikari connections (default 10) →
  full-app 503 cascade. **Fix:** explicit S3/SMTP timeouts; move blob I/O outside the tx.
- **R-4 (High):** **unbounded board query** — `GET …/issues` with no `size` returns *every* issue
  (`IssueRepository.findByProjectFiltered`, no `Pageable`). OOM risk on large projects. **Fix:** hard
  cap + "load more" / per-column pagination.
- **R-5 (High):** per-project **N+1 config fetch on the hot search path** (`ResolutionContextFactory:68-93`)
  — ~4-5×P queries before search SQL. **Fix:** batch-load by `IN(:projectIds)` + short-TTL cache.
- **R-6 (High):** **unbounded `@Async` mail executor** (`SimpleAsyncTaskExecutor` default) + silent
  loss of failed verification/reset mail. **Fix:** bounded `ThreadPoolTaskExecutor` (or virtual
  threads) + retry/dead-letter for critical mail.
- **R-7 (High):** leading-wildcard `LIKE '%term%'` full scan for text search, no trigram index
  (`HqlCompiler:261-266`). **Fix:** `pg_trgm` GIN on `lower(title)`/`lower(description)` or FTS.
- **R-8..R-11 (Medium):** no rate limit on non-auth endpoints (search/upload); no graceful
  shutdown / liveness-readiness split / JVM `MaxRAMPercentage`; Hikari untuned (no leak-detection);
  attachment download proxies bytes on the request thread (prefer pre-signed URLs on Cloud).

### 3.8 Tests — 🟠 healthy but narrow
GREEN baseline (174/174, 2:49, no flakes). Well-covered: HQL/search (79 tests), issue hierarchy
(incl. real optimistic-lock 409), delegated admin, rate limit, onboarding, documented traps.
- **Q-1 (Critical-risk gap):** **attachments** (upload/download/delete, tenant-isolation, size/content-type)
  — zero tests. (Scoping verified correct by review, but no regression net.)
- **Q-2 (Critical gap):** **auth flows beyond login** — register/verify/refresh/logout/forgot/reset
  untested (security-sensitive token handling).
- **Q-3 (High):** workflow-transition rejection path, comments CRUD/authz, membership/invite lifecycle,
  flat issue create/patch clear-flags — untested.
- **Q-4 (High):** **zero frontend tests, no FE test tooling** (no vitest/jest/testing-library/playwright).

### 3.9 API documentation — 🟢 good (validates clean)
- **DOC-1 (High):** `IssueResponse.closedAt` (`IssueResponse.java:35`, on every issue response) is
  documented in **none** of `openapi.yaml` / `api-cloud.md` / `api-dc.md`. **Fix:** add the field.
- **DOC-2 (Medium):** scoped-admin endpoints (~80) covered via a deliberate "mirror" shorthand — fine
  for humans, empty for client generators. Optional expansion.
- **DOC-3 (Low):** `robots.txt`/`sitemap.xml` not listed as endpoints. DC/Cloud split accurate;
  version claims in CLAUDE.md/project-state.md consistent with code.

---

## 4. Prioritized remediation backlog

**Confirmed engagement policy:** auto-remediate **P0 + P1** through the pipeline (child ticket per
item, gates, tests), then **STOP and hand the P2/P3 backlog back** for prioritization.

### P0 — Critical (exploitable/blocking now)
**None.** No live cross-tenant leak, no Critical security defect, baseline is green.

### P1 — auto-fix now (bounded correctness/security, low-risk)
| ID | Finding | Fix | Areas |
|---|---|---|---|
| **P1-a** | C-1 primitive-boolean DTOs → opaque 400 | boxed `Boolean` + coalescing ctor in `UpsertFieldSetRequest`, `UpsertPrioritySetRequest` | backend |
| **P1-b** | C-2 `SseRegistry.send` catch too narrow → dropped events | broaden catch to `Exception`, `completeWithError`+remove, continue loop | backend |
| **P1-c** | S-1 rate-limiter trusts XFF unconditionally (DC bypass) | `app.rate-limit.trust-forwarded-for` (default false); key on `getRemoteAddr()` when untrusted | backend, config |

### P2 — high-value, needs your steer (scaling / resilience / architecture prep)
- **P2-1 Multi-node backplane** (R-1 + R-2 + S-2): shared SSE pub/sub + shared-store rate limiter,
  profile-gated. *Prerequisite for scaling Cloud past one node.* (Do after P2-5 event bus.)
- **P2-2 Blob-I/O hardening** (R-3 + R-6): S3/SMTP timeouts, move blob I/O outside the tx, bounded
  async mail executor + retry/dead-letter. *Prevents connection-pool cascade even on one node.*
- **P2-3 Board pagination** (R-4): cap the unbounded issue list. *OOM risk on large projects.*
- **P2-4 Search performance** (R-5 + R-7): batch/cache the per-project config N+1; trigram/FTS index.
- **P2-5 Domain-event bus** (A-1): the load-bearing Phase-5 prerequisite; also cleans up SSE/notifications.
- **P2-6 Tenancy access primitive** (A-2): extract before 4B/5 add surface.
- **P2-7 Ops hardening** (R-8..R-11): graceful shutdown, liveness/readiness, JVM mem flags, Hikari tuning.
- **P2-8 Test hardening** (Q-1 + Q-2): attachment tenant-isolation + auth-flow tests first; then Q-3.
- **P2-9 Secret hygiene** (D-2): gitignore + rotate `application-local.properties`.

### P3 — cleanup / docs / lower-risk
- DOC-1 `closedAt` in openapi + both md (quick). · D-1 complete `.env.prod.example`. · A-4 actor/capability
  seam (Phase-5 prep). · A-5/C-4 typed exceptions. · A-6 package extraction. · A-3 `SlotRegistry`
  scaffold (4B prep). · Q-4 introduce FE test tooling. · T-1/T-2/T-3 tenancy cosmetics + kill-switch.
- D-3 brand hex. · M-2 drop/keep `oauth_accounts`. · R-9 non-auth rate limits. · DOC-2/DOC-3 doc coverage.
- **Already fixed:** `is_system` field delete-protection (HD-71, In Testing).

---

## 5. Documentation-accuracy delta
- **API:** one real drift — `IssueResponse.closedAt` missing everywhere (DOC-1). Otherwise openapi
  validates and matches code; DC/Cloud split accurate.
- **Operator:** `.env.prod.example` under-lists DC toggles (D-1) — `docs/self-hosting.md` is complete.
- **Architecture docs:** `CLAUDE.md`/`project-state.md`/`PLAN.md` are accurate to the code (versions,
  gotchas, subsystem internals). The gotcha list is not just documented — it's *upheld* in code.
- **Stale strings:** committed `application-local.properties` creds (D-2), retired brand hex in
  `MailService` (D-3), and the cosmetic `TO DO`→`TODO` comment typo in `V1` (no data impact).

---

## 6. Recommended sequence for the incoming team (fits ~2 months, no rewrite)
1. **Now (this engagement):** land P1-a/b/c.
2. **Sprint 1:** P2-2 blob-I/O hardening + P2-3 board pagination + P2-8 attachment/auth tests
   (correctness & cascade safety on one node).
3. **Sprint 2:** P2-5 domain-event bus → then P2-1 multi-node backplane (unblocks Cloud scale) +
   P2-6 tenancy primitive.
4. **Phase-4B prep:** ship no-code customization on the config channel; scaffold extension slots (A-3);
   FE test tooling (Q-4).
5. **Phase-5 prep:** actor/capability seam (A-4) + typed exceptions (A-5), on top of the event bus.

None of the above is a rewrite — it is paying down four crosscutting seams so the differentiators
bolt onto a clean surface instead of thickening today's inline/copy-paste patterns.
