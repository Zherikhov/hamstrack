# Dev Team Pipeline — Subagent Orchestration Spec

Status: **approved & built** (2026-08-14). This is a **meta / process-tooling** spec, not an application feature.

> **Decisions taken at build (2026-08-14):** (Q1) **Stop hook = yes** — `.claude/pipeline/check-gates.mjs` written; user pastes the registration into `settings.local.json`. (Q3) **Pruned the generic implementers** — deleted `java-architect`, `backend-developer`, `frontend-developer`, `react-specialist`, `typescript-pro`, `javascript-pro`, `spring-boot-engineer` (7 distinct; §9 double-listed `backend-developer`), leaving 36 agents. (Q2) **Auto-proceed, veto after** — no spec-approval pause. (Q5) hook **recomputes areas from `git diff`**. (Q4) `run.json` gitignored transient. (Q6) orchestrator lives in the **`feature-pipeline` skill**. (Q7) no dedicated FE-reviewer for now. Built: the skill, the hook script, `.gitignore` entry, CLAUDE.md "Dev pipeline" section. It defines how the main Claude Code session ("orchestrator") decomposes an incoming task and delegates to the 43 installed subagents so that the mandatory quality gates (tenancy, security, tests, spec-first analysis) run on every feature — deterministically where the engine allows, by convention where it doesn't.

Because this is tooling, the usual application spec sections (DB schema, REST API, DC/Cloud gating) are **replaced** by sections on **orchestration topology, review gates, and enforcement**.

> **Highest-risk assumption (flagged up front):** the orchestrator is the *main session following a written instruction* (a skill / `CLAUDE.md` rule), not a program. Its adherence to the pipeline is **probabilistic**, exactly like `description`-based auto-dispatch. The *only* deterministic lever the engine gives us is a **Stop / SubagentStop hook** that can block the session from finishing. So the whole design hinges on one thing: **a hook that refuses to let the session end until machine-checkable gate markers exist.** If that hook cannot be added (it lives in `settings.json`, which agents are denied write access to — see §6.4), "mandatory" degrades to "strongly encouraged" and the guarantee is lost. Decide on the hook before building anything else.

---

## 1. Goals / Non-goals

### Goals
- **Put the team on autopilot.** A single top-level prompt ("build issue labels", "fix this 404") should fan out to the right subagents without the user hand-naming each one.
- **Guarantee the mandatory core runs on every *feature*:** `systems-analyst` before code; `tenancy-reviewer` + `security-officer` + `test-runner` after code. These four exist because they cover the project's top failure modes (cross-tenant leaks, auth/upload holes, regressions, under-specified work).
- **Trigger the conditional reviewers automatically** from what the diff actually touched (migrations, config/profile, API surface).
- **Keep project-native builders in control of production code.** Generic/specialist agents are consulting experts, never the primary implementers — auto-routing to a generic builder that doesn't know the tenancy rule is the failure this pipeline must prevent.
- **Cost/latency governance:** run the full multi-agent pipeline only when the task is a "feature"; take a lighter or direct path for small edits.
- **Deterministic where it matters:** encode the non-negotiable gates as a hook, not just prose.

### Non-goals
- Not building a new agent, MCP server, or external orchestration binary. No agent-that-spawns-agents (the engine forbids it — §2).
- Not editing, rewriting, or re-tooling any of the 43 existing agents (out of scope by instruction; a *curation* recommendation is given in §9 but not executed here).
- Not changing CI/CD. Server-side CI (`build.yml`) already re-runs tests and is the true backstop; this pipeline improves the *local authoring* loop, it does not replace CI.
- Not promising a fully hands-off run with zero user turns — the hook can *block and remind*, but it cannot itself launch an agent (§2.3), so at least one extra orchestrator turn is expected when a gate is unmet.

---

## 2. Engine constraints (hard — the design is shaped by these)

These are properties of Claude Code, not choices. Every later section respects them.

1. **Subagents cannot spawn subagents.** Only the main session has the Task/dispatch capability. The topology is therefore a **star** (one orchestrator hub, worker agents on the spokes), never a tree. Consequence: **the "orchestrator" is the main session plus an instruction**, not a delegated agent. Installing a persona like `agent-organizer` buys nothing — it would be a spoke worker with no power to dispatch. Any plan that assumes an agent sub-delegates is invalid.
2. **Every subagent starts cold.** A spawned agent sees only the prompt it is handed — not the conversation, not previous agents' outputs, not what the user said three turns ago. The orchestrator **must marshal context into each prompt**: the diff/patch, absolute file paths, the spec path, the specific question. An agent given "review the changes" with no diff will re-derive from scratch or hallucinate scope.
3. **Auto-dispatch is probabilistic; only hooks are deterministic.** A subagent's `description` field influences whether the model *chooses* to invoke it — it is a soft nudge, never a guarantee. The single deterministic control is a **hook** in `settings.json` (e.g. `Stop` / `SubagentStop`) that runs a shell command and can **block** the turn from completing, returning a message to the orchestrator. Hooks are the only place "mandatory" can actually be enforced.
4. **A hook cannot launch an agent.** It can *block* the stop and *return text* ("gate X not satisfied — dispatch tenancy-reviewer"), but the actual dispatch still has to come from the orchestrator's next turn. So the enforcement loop is: orchestrator tries to finish → hook blocks with a reminder → orchestrator dispatches the missing agent → retries.
5. **Each cold-context dispatch costs tokens + wall-clock.** A full feature run is `analyst → builder(s) → {tenancy, security, docs?, migration?, dc-cloud?} → test-runner`, i.e. 5–8 separate cold contexts. That is expensive and slow, so the pipeline **must gate itself by task size** (§7) rather than running fully every time.
6. **`settings.json` and `.github/**` are write-protected for agents** (confirmed in `.claude/settings.local.json` `deny` list). Therefore the hook and any settings wiring must be authored **by the user**, not by a builder agent. This spec provides the exact hook logic for the user to paste.

---

## 3. Orchestration model (star)

```
                         (main session = ORCHESTRATOR)
                                     │
        ┌──────────┬──────────┬──────┴─────┬───────────┬───────────┐
        ▼          ▼          ▼            ▼           ▼           ▼
  systems-    backend-   frontend-   tenancy-    security-    test-
  analyst     builder    builder     reviewer    officer      runner   … (conditional / on-demand spokes)
```

- **Hub = the main session.** It holds the durable context (the task, the accumulating diff, the spec path, gate state). It is the *only* node that dispatches.
- **Spokes = subagents.** Stateless, cold-start, single-purpose. They return a result to the hub and disappear.
- **Context passing (mandatory discipline).** For every dispatch the orchestrator hands the spoke a self-contained prompt containing, as applicable:
  - the **task statement** and the **acceptance criteria** from the analyst's spec;
  - the **spec file path** (`docs/design/{feature}-proposal.md`) so the spoke can read it directly;
  - the **diff to review** — either a `git diff` range/paste or an explicit **list of absolute changed-file paths** (reviewers are read-only and re-open the files);
  - the **specific question / checklist** for that spoke (e.g. "verify workspace scoping on every new repository method").
  Reviewers get *paths + diff*, not a summary — a summarized diff hides the exact unscoped query that is the whole point of the review.
- **Shared handoff surface.** Because spokes can't see each other, the hub persists cross-agent artifacts to disk under a per-task working dir (see §6.2): the spec, a gate checklist/marker file, and each reviewer's verdict. This is also what the enforcement hook inspects.
- **No agent named "orchestrator."** Per §2.1 that role cannot be delegated. If a generic orchestration persona (`agent-organizer` or similar) is ever installed, it must be documented as "advisory planning help for the main session," never as a dispatcher.

---

## 4. Role catalog

Legend — **M** = mandatory on every *feature* run; **C** = conditional (auto-triggered by what the diff touched); **O** = on-demand (only when the user or orchestrator explicitly wants deep single-topic help). "Trigger" is what the orchestrator watches to decide whether to dispatch.

| Area | Agent | Class | Trigger / when |
|---|---|---|---|
| **Requirements → spec** | `systems-analyst` ★ | **M** (features) | Start of any feature/fuzzy/multi-layer task. Blocks Phase 2 until a spec exists in `docs/design/`. |
| **Backend implementation** | `backend-builder` ★ | **M-when-backend** | Task touches Java / Spring / JPA / services / controllers. The *only* allowed backend implementer. |
| **Frontend implementation** | `frontend-builder` ★ | **M-when-frontend** | Task touches `src/main/frontend/**`. The *only* allowed frontend implementer. Reads `DESIGN.md` first. |
| **Tenant isolation review** | `tenancy-reviewer` ★ | **M** (any backend diff) | After backend code changes to any repository/service/controller/query touching workspace-scoped data. Top bug class. |
| **App-sec review** | `security-officer` ★ | **M** (features) | After implementation — authn/JWT, authz/roles, account & reset flows, rate limiting, upload/download, injection/SSRF, secrets. |
| **Test authoring + run** | `test-runner` ★ | **M** (features) | Final gate. Writes/updates tests and runs the suite with correct env (creds `hamstrack`/`hamstrack`, port 15432, `-Dfrontend.skip=true`). |
| **DB / migrations** | `migration-reviewer` ★ | **C** | Diff adds/edits `src/main/resources/db/migration/**` or any `@Entity` mapping. Checks CHAR/ENUM ban, `updatable=false` counters, UUID v7, `@CreatedDate`, entity⇄schema parity. |
| **DC/Cloud gating** | `dc-cloud-guard` ★ | **C** | Diff touches `*.properties`, profiles, `docker-compose*.yml`, `.env*.example`, or introduces a behavioral toggle. Enforces profile-gating + env-var wiring chain. |
| **API docs sync** | `api-docs-sync` ★ | **C** | Diff changes the REST surface (new/changed endpoint, DTO, status code). Syncs `openapi.yaml` + both `docs/api-*.md`, validates with swagger-cli. |
| **Deep Spring reference** | `spring-boot-engineer`, `java-architect`, `backend-developer`, `api-designer` | **O** | Only for generic Spring/architecture Q&A. **Must not implement Hamstrack code** — route implementation to `backend-builder`. |
| **Deep DB reference** | `sql-pro`, `postgres-pro`, `database-administrator`, `database-optimizer` | **O** | Query tuning, index strategy, Postgres depth. Advisory; migrations still authored by `backend-builder` + reviewed by `migration-reviewer`. |
| **Deep frontend reference** | `react-specialist`, `typescript-pro`, `javascript-pro` | **O** | React 19 / TS depth. Advisory only; implementation via `frontend-builder`. |
| **UI / a11y** | `ui-designer`, `accessibility-tester` | **O** | Explicit design or accessibility pass. Must defer to `DESIGN.md` / Beacon tokens. |
| **Generic quality/sec review** | `code-reviewer`, `security-auditor`, `architect-reviewer`, `security-engineer` | **O** | Supplementary review only. **Never substitutes** for `tenancy-reviewer`/`security-officer` — they don't know the tenancy rule or Boot 4/Jackson gotchas. |
| **Generic QA/test** | `qa-expert`, `test-automator` | **O** | Test-strategy consult. The suite itself is still `test-runner`. |
| **Performance** | `performance-engineer`, `database-optimizer` | **O** | Explicit perf investigation. |
| **Debug / diagnose** | `debugger`, `error-detective` | **O** | Explicit "why is this failing / what threw this" investigation. |
| **Refactor** | `refactoring-specialist` | **O** | Explicit refactor pass; output still goes through the mandatory reviewers. |
| **Infra / deploy** | `docker-expert`, `devops-engineer`, `deployment-engineer`, `cloud-architect`, `sre-engineer`, `build-engineer`, `dependency-manager` | **O** | Infra questions. Note: **`kubernetes-specialist` is inapplicable** (no k8s — Compose on EC2). Prod deploy specifics stay with `dc-cloud-guard` + the CI/CD docs. `.github/**` is write-protected, so CI edits are user-only. |
| **Docs / VCS** | `documentation-engineer`, `git-workflow-manager` | **O** | General docs help / git workflow. API docs specifically = `api-docs-sync`. Commits: the user commits themselves — agents don't offer to. |

**Routing invariant (repeat of the critical rule):** anything that *writes Hamstrack code, config, schema, or API* goes to a **project-native** agent. The generic (piomin) and specialist (VoltAgent) families are **read/advise** roles on autopilot. On automatic routing, a generic builder winning an implementation task is a **defect**, because it silently skips the tenancy/Boot-4 rules.

---

## 5. `/feature` phased pipeline

The full pipeline for a task classified as a **feature** (§7). Each phase lists **inputs → work → outputs**, what is delegated, what runs in parallel, and skip conditions. The orchestrator drives all of it; phase boundaries are where gate markers get written (§6.2).

### Phase 0 — Intake & classification (orchestrator, no dispatch)
- **In:** user's raw request.
- **Work:** classify size — **feature** vs **light** vs **trivial** (§7). Identify likely touched areas (backend/frontend/db/api/config). Create the per-task working dir + empty gate checklist.
- **Out:** classification decision; `run.json` gate file initialized. If **light/trivial**, jump to the reduced flow in §7 and skip Phases 1–6.

### Phase 1 — Analysis (dispatch `systems-analyst`)  **[GATE: SPEC]**
- **In:** task statement.
- **Work:** analyst produces `docs/design/{feature}-proposal.md` (scope, actors/permissions, rules, edge cases, data-model/API/frontend impact, DC/Cloud implications, acceptance criteria, open questions).
- **Out:** spec file path recorded in `run.json`; **acceptance criteria extracted** for later phases.
- **Skip if:** the task is a pure bug-fix with an already-clear reproduction *and* no new surface — then a one-line internal spec note suffices (still recorded). Never skip for anything adding an entity, endpoint, or config toggle.
- **Human checkpoint (recommended):** pause for user approval of the spec before building, mirroring how `admin-console-proposal.md` was "approved" before M1. On full autopilot this can be a soft pause; see Open Questions.

### Phase 2 — Implementation (dispatch `backend-builder` and/or `frontend-builder`)
- **In:** approved spec + acceptance criteria.
- **Work:** the native builder(s) implement per the spec. Backend and frontend can run **in parallel** when the spec cleanly separates them (shared DTO/endpoint contract must be pinned in the spec first, else run backend → frontend sequentially so the FE builds against a real contract).
- **Out:** code diff; list of changed absolute paths captured into `run.json` (this is the review payload).
- **Rule:** implementation is **only** ever these two native builders. Generic/specialist builders are not dispatched here.

### Phase 3 — Conditional reviews (parallel, dispatched by diff content)  **[GATE: CONDITIONALS]**
Computed from the Phase-2 changed-file list; independent, so **run in parallel**:
- `migration-reviewer` — if `db/migration/**` or any `@Entity` changed.
- `dc-cloud-guard` — if `*.properties` / profile / `docker-compose*` / `.env*.example` / new toggle changed.
- `api-docs-sync` — if the REST surface changed (this one may *edit* `openapi.yaml` + `docs/api-*.md`).
- **Out:** each writes a verdict marker (pass / findings) into `run.json`. Un-triggered reviewers are recorded as "n/a".
- **Skip:** any reviewer whose trigger file-globs did not match.

### Phase 4 — Mandatory security gates (parallel)  **[GATE: TENANCY, SECURITY]**
- `tenancy-reviewer` — always, whenever Phase 2 produced a **backend** diff. Fed the diff + changed paths; hunts unscoped queries / missing membership / 403-instead-of-404 / parent re-verification.
- `security-officer` — always on a feature. Fed the diff + the auth/upload/config-relevant paths.
- These are **read-only** and independent → run in parallel with each other and with Phase 3.
- **Out:** verdict markers. Non-empty findings → Phase 6 fix loop before proceeding.

### Phase 5 — Tests (dispatch `test-runner`)  **[GATE: TESTS]**
- **In:** final code (post-fix), acceptance criteria.
- **Work:** author/adjust tests for the new behavior and **run the suite green** with the documented env. Frontend-only diffs may use `-Dfrontend.skip=true` for the backend suite plus the FE build/test.
- **Out:** test result marker (must be **green**) in `run.json`.
- **Never skipped on a feature.** A red or absent test run keeps the SPEC/TESTS gate closed.

### Phase 6 — Fix loop (§8)
- Triggered whenever any reviewer/test gate returns findings. Route the finding back to the **owning builder** with the reviewer's exact text + paths, then **re-run only the gate that failed** (not the whole pipeline). Bounded iterations (§8).

### Phase 7 — Finalize (orchestrator)
- All gate markers green ⇒ hook (§6) permits stop. Summarize what shipped, list changed files, surface any deferred Open Questions. **Do not offer to commit** (project rule — the user commits).

**Parallelism summary:** Phases 3 and 4 are mutually parallel (all read-only except `api-docs-sync`, which edits only docs, not code under review). Phase 2 BE/FE parallel only when contract-pinned. Everything else is sequential because of data dependencies (spec → code → review → tests).

---

## 6. Enforcement design

Two layers: **soft** (a skill / prose the orchestrator *should* follow) and **hard** (a hook that *makes* it follow). The soft layer does the routing; the hard layer prevents a run from being declared "done" with gates unmet.

### 6.1 Soft layer — orchestrator skill (convention)
A repository skill (e.g. `.claude/skills/feature-pipeline`) or a `CLAUDE.md` "How to run a feature" block encoding: the classifier (§7), the phase order (§5), the routing invariant (§4), and the context-passing discipline (§3). This is what makes autopilot *usually* do the right thing — but per §2.3 it is not a guarantee, which is why the hard layer exists.

### 6.2 The shared gate file (machine-checkable state)
A per-task JSON the orchestrator maintains and the hook reads. Suggested location: `.claude/pipeline/run.json` (gitignored — it is transient run state, not a deliverable).

```jsonc
{
  "task": "issue-labels",
  "class": "feature",              // feature | light | trivial
  "spec": "docs/design/issue-labels-proposal.md",
  "changed": ["...absolute paths..."],
  "areas": { "backend": true, "frontend": true, "migration": true,
             "config": false, "api": true },
  "gates": {
    "spec":        "pass",         // pass | fail | pending | n/a
    "tenancy":     "pass",
    "security":    "pass",
    "migration":   "pass",         // set only because areas.migration=true
    "dc_cloud":    "n/a",
    "api_docs":    "pass",
    "tests":       "pass"
  }
}
```
Each dispatched reviewer's **last instruction from the orchestrator** is to write its verdict here (the orchestrator does the write after reading the agent's return, since reviewers are read-only for source but can be told to emit a one-line verdict the hub records). Marker granularity is per-gate so the fix loop can re-open exactly one.

### 6.3 Hard layer — the Stop / SubagentStop hook (deterministic)
A `Stop` hook (fires when the main session tries to end its turn/finish) runs a small script that:
1. Reads `.claude/pipeline/run.json`. If absent → **allow** (no active pipeline; don't block ad-hoc chat).
2. If `class` is `trivial` → **allow**.
3. Compute the **required** gate set from `class` + `areas`:
   - always for a feature: `spec`, `security`, `tests`;
   - `tenancy` required iff `areas.backend`;
   - `migration` required iff `areas.migration`; `dc_cloud` iff `areas.config`; `api_docs` iff `areas.api`.
4. If every required gate is `pass` (or legitimately `n/a`) → **allow** (exit 0).
5. Otherwise → **block**: exit non-zero / emit the block decision with a message listing the unmet gates, e.g.
   `Pipeline gate not satisfied: tenancy=pending, tests=fail. Dispatch tenancy-reviewer on the changed files and get a green test-runner before finishing.`

Because the hook **cannot dispatch** (§2.4), it only blocks + reminds; the orchestrator's next turn must dispatch the missing agent. That loop is the enforcement.

**Sketch (illustrative — to be authored by the user in `settings.json`):**
```jsonc
"hooks": {
  "Stop": [{
    "matcher": "*",
    "hooks": [{ "type": "command",
      "command": "node .claude/pipeline/check-gates.mjs" }]
  }]
}
```
`check-gates.mjs` implements steps 1–5 and prints the reminder to stderr with a blocking exit code. Keep it dependency-free and fast (it runs on every stop). A parallel `SubagentStop` hook can *record* a spoke's completion, but gate evaluation belongs on `Stop` where the whole run is visible.

### 6.4 Who writes the hook
`settings.json` and `.github/**` are in the agent **deny** list, so **the user must add the hook and the skill-permission entries.** A builder agent physically cannot. This is the single manual bootstrap step and the crux of the risk flagged at the top.

### 6.5 Anti-gaming
The hook checks *markers*, so a lazy orchestrator could in principle stamp `pass` without dispatching. Mitigations: (a) the verdict marker should carry a short evidence string the reviewer actually returned; (b) `test-runner`'s green is backed by real surefire output the orchestrator can be told to echo; (c) server CI (`build.yml`) re-runs tests regardless, so a faked `tests=pass` still gets caught before merge. Full tamper-proofing is out of scope — this pipeline optimizes the honest-but-forgetful case, not an adversarial orchestrator.

---

## 7. Cost / latency governance — when to run what

Three tiers, decided in Phase 0:

| Tier | What it is | Pipeline |
|---|---|---|
| **Feature** | New entity/endpoint/toggle; multi-file, multi-layer; anything with new surface or user-visible behavior; anything touching auth/tenancy/migrations/config. | **Full §5** (5–8 cold contexts). Hook enforces all required gates. |
| **Light** | Small, localized change with limited blast radius — a bug fix touching 1–2 files in one layer, a copy tweak, a non-schema refactor. | **Reduced:** skip Phase 1 spec (record a one-line note). Implement via the native builder. **Still run `tenancy-reviewer` if the backend diff touches a workspace-scoped query, and `test-runner`.** Skip `security-officer`/docs/migration unless their triggers fire. |
| **Trivial** | Typos, comments, formatting, doc-only edits, a single-line non-logic change with no test impact. | **Direct:** orchestrator edits, no dispatch, no gate file (`class:"trivial"` → hook allows). |

**Classifier heuristics (Phase 0):**
- Any of these forces **Feature**: new/changed `@Entity` or migration; new/changed endpoint or DTO; new `*.properties`/env toggle; auth/reset/upload/admin surface; >~5 files or >2 layers.
- **Escalate on discovery:** if a "light" change is found mid-flight to touch a workspace-scoped query, a migration, or the API, the orchestrator **upgrades the class** in `run.json` (which re-arms the corresponding gates). Downgrading is not allowed once a heavier gate has been armed.
- When unsure, prefer the heavier tier — a redundant tenancy review is far cheaper than a leaked tenant.

**Latency levers:** run independent reviewers (Phases 3+4) in parallel; re-run only the failed gate in the fix loop (§8); scope `test-runner` to the affected suite where safe (`-Dfrontend.skip=true` for backend-only, FE build/test for FE-only).

---

## 8. Failure handling — the fix loop

When a gate returns findings:
1. **Route back to the owner.** Backend findings → `backend-builder`; frontend → `frontend-builder`; docs drift → `api-docs-sync`; migration issues → `backend-builder` (migration-reviewer is read-only). Hand the builder the reviewer's **verbatim finding + exact file paths + the spec section** it violates.
2. **Re-review only the failed gate.** After the fix, re-dispatch just that reviewer (and `test-runner` if code changed), not the whole pipeline. Update its marker.
3. **Iteration cap: 3 rounds per gate.** If a gate is still red after 3 fix attempts, **stop and escalate to the user** with: the finding, what was tried, and the reviewer's latest verdict. Do not loop indefinitely (cost) and do not silently ship past a red gate.
4. **Conflicting verdicts** (e.g. a perf suggestion that would break tenancy scoping) resolve in favor of the **project-native mandatory** reviewer; note the trade-off for the user.
5. **Test flakiness:** a `test-runner` failure that is environmental (DB down, port 15432) is not a code finding — the orchestrator fixes the env and re-runs; it does not bounce to a builder.

---

## 9. Agent curation recommendation

On autopilot, every extra generic agent is a **mis-routing surface** — the model can pick a generic implementer that skips the tenancy rule. The 9 project-native (★) agents are the spine; the 34 imported agents should be **pruned to a small advisory bench** and the rest removed so auto-dispatch can't reach them. Recommendation:

**Keep (on-demand advisory bench — genuinely fill gaps the native agents don't):**
- `debugger`, `error-detective` — diagnostic depth; no native equivalent.
- `performance-engineer`, `database-optimizer`, `postgres-pro` — perf/index depth for real Postgres tuning.
- `accessibility-tester` — a11y pass; native agents don't cover it.
- `refactoring-specialist` — structured refactors (output still gated).
- `docker-expert`, `devops-engineer` — infra reference for the Compose/EC2/SSM setup.
- `dependency-manager` — dependency/CVE hygiene.

**Remove (redundant with a ★ native agent, or inapplicable — deletion prevents mis-routing):**
- Implementers that could hijack code work: `backend-developer`, `spring-boot-engineer`, `frontend-developer`, `react-specialist`, `javascript-pro`, `typescript-pro`, `java-architect`, `backend-developer` → all shadowed by `backend-builder`/`frontend-builder`. **Highest mis-routing risk — remove first.**
- Redundant reviewers: `code-reviewer`, `security-auditor`, `security-engineer`, `architect-reviewer` → shadowed by `tenancy-reviewer` + `security-officer` + `migration-reviewer`.
- Redundant QA: `qa-expert`, `test-automator` → shadowed by `test-runner`.
- Redundant design/API: `ui-designer` (defer to `DESIGN.md`), `api-designer` (defer to spec + `api-docs-sync`), `documentation-engineer` (defer to `api-docs-sync`), `git-workflow-manager` (user owns commits).
- Redundant DB: `sql-pro`, `database-administrator` → keep only `postgres-pro`/`database-optimizer` from that cluster.
- **Inapplicable:** `kubernetes-specialist` (no k8s), `cloud-architect`, `deployment-engineer`, `sre-engineer`, `build-engineer` (prod is Compose-on-EC2 + fixed CI; `dc-cloud-guard` + docs cover the real setup).

> This §9 is a **recommendation only** — this task does not delete agents. If the user wants a leaner bench, that is a follow-up. Until then, the routing invariant (§4) + the hook (§6) are what keep generic agents from stealing implementation.

---

## 10. Acceptance criteria

The pipeline is "working" when, for a representative feature task run on autopilot:
1. A spec file appears in `docs/design/` **before** any code is written (SPEC gate).
2. Implementation is done **only** by `backend-builder` / `frontend-builder` — no generic implementer is dispatched for code.
3. On a backend diff, `tenancy-reviewer` runs **without** the user naming it; `security-officer` and `test-runner` run on every feature.
4. Conditional reviewers fire **iff** their triggers match: a migration diff invokes `migration-reviewer`; a `*.properties`/toggle diff invokes `dc-cloud-guard`; an endpoint/DTO change invokes `api-docs-sync`.
5. The **Stop hook blocks** a finish attempt while any required gate is `pending`/`fail`, returning a reminder naming the missing gate; it **allows** finish once all required gates are `pass`/`n/a`.
6. A reviewer finding is routed back to the owning builder and the specific gate is re-run; the run does not end red; the loop stops and escalates after 3 rounds.
7. A **trivial** task takes the direct path (no gate file, no dispatch, no block).
8. A **light** task skips the spec but still runs tenancy (if backend-scoped) + tests.
9. Each dispatched spoke received the diff/paths/spec in its prompt (no "review the changes" with no changes attached).
10. `test-runner`'s green is backed by real surefire output; server CI agrees on the same commit.

---

## 11. Open questions (decide before building)

1. **Hook adoption — the make-or-break.** Are you willing to add a `Stop` hook + `check-gates.mjs` to `settings.json` yourself (agents are denied write there)? If **no**, "mandatory" is only convention and we should be honest that gates can be skipped. **Recommended default: yes — add the hook; it is the only real guarantee.**
2. **Spec approval on autopilot.** Should Phase 1 **hard-pause** for your approval of the spec (as `admin-console-proposal` was approved before building), or proceed automatically and let you veto after? **Recommended: soft-pause on features that add an entity/endpoint/toggle; auto-proceed on bug-fix-shaped work.**
3. **Curation now or later (§9).** Prune the 34 imported agents to the ~10 advisory bench now (reduces mis-routing on autopilot), or keep all 43 and rely on the routing invariant + hook? **Recommended: prune the 8 generic *implementers* immediately** (highest hijack risk), defer the rest.
4. **Gate-file location & gitignore.** `.claude/pipeline/run.json` transient and gitignored — acceptable, or do you want run artifacts kept per-branch for audit? **Recommended: gitignored transient.**
5. **Anti-gaming strength.** Is marker-based enforcement + CI backstop enough, or do you want the hook to independently re-derive `areas` from `git diff` (so the orchestrator can't under-declare touched areas)? **Recommended: have the hook recompute `areas` from `git diff --name-only` and compare against the declared set — cheap and closes the biggest gaming hole.**
6. **Where the orchestrator instruction lives.** A dedicated `.claude/skills/feature-pipeline` skill vs a `## Dev pipeline` block in `CLAUDE.md`. **Recommended: a skill (invocable, self-contained), with a one-line pointer from `CLAUDE.md`.**
7. **Frontend gate coverage.** There is no native "frontend-reviewer" — FE correctness rides on `frontend-builder` + `test-runner` + optional `accessibility-tester`. Accept that, or add a FE review gate later? **Recommended: accept for now; revisit if FE regressions recur.**
