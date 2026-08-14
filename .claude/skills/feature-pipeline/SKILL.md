---
name: feature-pipeline
description: "Orchestrate a task through the Hamstrack dev-team pipeline — classify size, delegate to the project-native subagents, run the mandatory review gates (analyst/tenancy/security/tests) and conditional ones (migration/dc-cloud/api-docs), and only finish when all required gates are green. Use for any non-trivial feature or change; the Stop hook enforces the gates. Full spec: docs/design/dev-team-pipeline.md."
---

# Feature pipeline (dev-team orchestrator)

You (the main session) are the **orchestrator** — the only node that can dispatch subagents
(a subagent cannot spawn another). Drive a task through the phases below. The `Stop` hook
(`.claude/pipeline/check-gates.mjs`) will refuse to let you finish while a required gate is
unmet, so keep `.claude/pipeline/run.json` truthful. Full rationale: `docs/design/dev-team-pipeline.md`.

## Routing invariant (never violate)
Only **`backend-builder`** and **`frontend-builder`** write Hamstrack code / config / schema / API.
The general/specialist agents are **read/advise only** — dispatching a generic agent to *implement*
is a defect (it skips the tenancy + Boot 4/Hibernate/Jackson rules). The 7 generic implementers were
removed for exactly this reason; don't reintroduce that pattern.

Context is cold for every spoke: hand each one the **diff / absolute changed-file paths / spec path /
the specific question** — never "review the changes" with nothing attached. Reviewers re-open files.

## Phase 0 — Classify (no dispatch)
Pick a tier and write `.claude/pipeline/run.json` (see schema below):
- **feature** — new/changed entity, migration, endpoint, DTO, `*.properties`/env toggle, auth/upload/admin
  surface, or >~5 files / >2 layers. → full pipeline.
- **light** — localized 1–2 file fix, no new surface. → skip the spec; still run tenancy (if backend) + tests.
- **trivial** — typo/comment/formatting/doc-only, no logic. → edit directly, no gate file, no dispatch.

When unsure, pick the heavier tier. **Escalate on discovery:** if a light change turns out to touch a
workspace-scoped query, a migration, or the API, upgrade `class` in `run.json` (never downgrade an armed gate).
The hook recomputes areas from the real `git diff`, so under-declaring won't get past it.

## Phase 1 — Analysis  [gate: spec]  *(feature only)*
Dispatch **`systems-analyst`** → spec in `docs/design/{feature}-proposal.md` (scope, actors, rules, edge
cases, acceptance criteria, open questions). Record the path + set `gates.spec="pass"`.
**Autopilot policy (user choice): auto-proceed, no approval pause** — the user vetoes after. Extract the
acceptance criteria for later phases.

## Phase 2 — Implement
Dispatch **`backend-builder`** and/or **`frontend-builder`** per the spec. Run them in parallel only when
the spec pins a shared DTO/endpoint contract; otherwise backend → frontend. Capture the changed absolute
paths into `run.json.changed`. **Implementation is only ever these two agents.**

## Phase 3 — Conditional reviews (parallel)  [gate: migration, dc_cloud, api_docs]
Dispatch by what the diff touched:
- `migration-reviewer` — `src/main/resources/db/migration/**` or an `@Entity` change.
- `dc-cloud-guard` — `*.properties` / profile / `docker-compose*` / `.env*.example` / new toggle.
- `api-docs-sync` — REST surface changed (new/changed endpoint, DTO, status). *May edit* `openapi.yaml` + `docs/api-*.md`.
Un-triggered ones → set their gate to `"n/a"`.

## Phase 4 — Mandatory security gates (parallel)  [gate: tenancy, security]
- `tenancy-reviewer` — whenever Phase 2 produced a **backend** diff (unscoped queries / missing membership /
  403-instead-of-404 / parent re-verification). Feed it the diff + paths.
- `security-officer` — every feature (authn/JWT, authz, reset flows, rate limiting, upload, injection, secrets).
Both read-only → parallel with each other and Phase 3. Findings → Phase 6 before proceeding.

## Phase 5 — Tests  [gate: tests]  *(never skipped on feature/light)*
Dispatch **`test-runner`**: author/adjust tests, run the suite green (creds `hamstrack`/`hamstrack`, port 15432;
`-Dfrontend.skip=true` for backend-only). Set `gates.tests="pass"` only with real green surefire output.

## Phase 6 — Fix loop
A gate with findings → route the **verbatim finding + paths + violated spec section** back to the **owning
builder** (backend/frontend; migration issues also go to `backend-builder` — reviewers are read-only). Then
**re-run only the failed gate** (plus `test-runner` if code changed). **Cap: 3 rounds per gate** — after that,
stop and escalate to the user with the finding and what was tried. Conflicts resolve in favor of the
project-native mandatory reviewer. Environmental test failures (DB down) → fix env and re-run, don't bounce to a builder.

## Phase 7 — Finalize
All required gates `pass`/`n/a` ⇒ the hook allows stop. Summarize what shipped + changed files + any deferred
open questions. **Do not offer to commit** (the user commits themselves).

## `run.json` schema
```jsonc
{
  "task": "issue-labels",
  "class": "feature",                 // feature | light | trivial
  "spec": "docs/design/issue-labels-proposal.md",
  "changed": ["<absolute changed paths>"],
  "areas": { "backend": true, "frontend": false, "migration": true, "config": false, "api": true },
  "gates": {                          // pass | fail | pending | n/a
    "spec": "pass", "tenancy": "pass", "security": "pass",
    "migration": "pass", "dc_cloud": "n/a", "api_docs": "pass", "tests": "pass"
  }
}
```
Write a gate `"pass"` only after you've read the agent's verdict. The hook derives the *required* set from the
real diff, so a forgotten `tenancy`/`tests` still blocks the finish. `run.json` is gitignored transient state.
