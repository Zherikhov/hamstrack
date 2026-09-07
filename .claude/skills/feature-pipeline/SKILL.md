---
name: feature-pipeline
description: "Orchestrate a task through the Hamstrack dev-team pipeline — classify size, delegate to the eleven project-native subagents, run the mandatory review gates (analyst/tenancy/security/tests) and the conditional ones (migration/dc-cloud/api-docs/ops-witness/ui-qa), fill the category block, collect the negative control, and only finish when all required gates are green. Use for any non-trivial feature or change; the Stop hook enforces the gates. Full spec: docs/design/dev-team-pipeline.md (2026-09-07 revision)."
---

# Feature pipeline (dev-team orchestrator)

You (the main session) are the **orchestrator** — the only node that can dispatch subagents (a subagent cannot
spawn another). Drive a task through the phases below. The `Stop` hook (`.claude/pipeline/check-gates.mjs`)
refuses to let you finish while a required gate, the `category` block or the negative control is missing, so
keep `.claude/pipeline/run.json` truthful. Rationale for every rule here: `docs/retro/2026-09-bug-rca.md`.

## The bench (eleven agents, nothing else)
Builders **`backend-builder`**, **`frontend-builder`** — the only agents that write Hamstrack code / config / schema /
API. Reviewers `tenancy-reviewer`, `security-officer`, `dc-cloud-guard`, `migration-reviewer`, `ops-reviewer`
(read-only). Verifiers `test-runner`, `browser-qa`. Analyst `systems-analyst`. Docs `api-docs-sync`. Each carries its
own `model:` / `effort:` (`docs/retro/2026-09-agent-models.md`) — never override them per call. There are no imported
or generic agents any more; do not dispatch built-in general-purpose agents to implement anything.

Context is cold for every spoke: hand each one the **diff / absolute changed-file paths / spec path / the specific
question**. Reviewers are told to leave the diff for the siblings; give them the category, not just the change.

## Three rules that apply to every phase
- **Category, not instance (X1).** A rule, bound, guard, normalisation or shape change is applied to every member of
  its category in the same change, or the change ships the category test that enumerates the members. The builder
  writes the `category` block; a reviewer verifies the member list against the code; the hook refuses a one-member
  category with a new bound in the diff.
- **Evidence labels (X6).** Every claim in a ticket, a spec, a builder report or a review is **measured** (executed,
  output quoted), **read** (file:line) or **inferred**. A ticket premise marked *inferred* is checked by the builder
  before code. Each review contains at least one *measured* item.
- **Prose budget.** A failure message is ≤ 25 lines and names the action; history goes into javadoc on the constant.
  A ticket carries the evidence and the acceptance criteria, not the argument. Everything in the tracker is English.

## Phase 0 — Classify (no dispatch)
Search the backlog first and record it in the ticket ("searched: …, no match / related: HD-…"). Pick a tier and write
`run.json`:
- **feature** — new/changed entity, migration, endpoint, DTO, property/env toggle, auth/upload/admin surface, ops
  artefact, or >~5 files / >2 layers → full pipeline.
- **light** — localized 1–2 file fix, no new surface → skip the spec; still run tenancy (backend), tests, and any
  conditional gate the diff arms.
- **trivial** — typo/comment/formatting/doc-only, no logic → edit directly, no gate file. The hook blocks a "trivial"
  run whose diff touches code, config, ops or tests.
When unsure, pick the heavier tier. **Escalate on discovery**, never downgrade an armed gate.

## Phase 1 — Analysis [gate: spec] *(feature only)*
Dispatch **`systems-analyst`** → `docs/design/{feature}-proposal.md`. It must contain the category members, acceptance
criteria phrased over the category, the observability contract (§13) and evidence labels on every premise. Autopilot
policy: proceed without an approval pause; the owner vetoes after. Extract the acceptance criteria and the member list.

## Phase 2 — Implement
Dispatch **`backend-builder`** and/or **`frontend-builder`** (parallel only when the spec pins the shared contract). The
builder's **first report is the measured premise** — any discrepancy with the ticket stops the work until resolved.
Capture the changed absolute paths into `run.json.changed` and the builder's `category` block into `run.json.category`.

## Phase 3 — Conditional reviews (parallel) [gates: migration, dc_cloud, api_docs, ops_witness, ui_qa]
The hook derives these from the real diff:
- `migration-reviewer` — `db/migration/**`, an `@Entity` change, or a new `FieldRegistry` name.
- `dc-cloud-guard` — `*.properties` / profile / `docker-compose*` / `.env*.example` / `Caddyfile` / a new toggle.
- `api-docs-sync` — REST surface changed. *May edit* the spec, both references, controller javadoc.
- **`ops-reviewer`** — `ops/**`, `observability/**`, `.github/workflows/**`, `Dockerfile`, `Caddyfile`, `pom.xml`,
  `docker-compose*`, `ops/CHANGELOG-console.md` (gate `ops_witness`): what observes it in production, was the effect
  read back from the running system, was the fix verified through the path that failed.
- **`browser-qa`** — a frontend diff touching `src/pages/**`, `src/components/**`, `index.css` or `DESIGN.md`
  (gate `ui_qa`): measured render / dialog / contrast / font-scale facts against the dev app.
Un-triggered ones → `"n/a"`. Console actions (AWS, Cloudflare, Resend) are recorded in `ops/CHANGELOG-console.md`
with the read-back that proved the effect, which arms `ops_witness`.

## Phase 4 — Mandatory security gates (parallel) [gates: tenancy, security]
- `tenancy-reviewer` — whenever Phase 2 produced a **backend** diff.
- `security-officer` — every feature.
Both ask the four questions (category, silence, claims, observed-or-remembered) and execute at least one probe.

## Phase 5 — Tests [gate: tests] *(never skipped on feature/light; also armed by any `src/test` or `*.test.*` change)*
Dispatch **`test-runner`**: it owns surefire **and** vitest. `gates.tests` is written as
`{"status": "pass", "negativeControl": "seen: <test> red against <what was planted/reverted>"}` — the red line is pasted
before the green one, with the class count and the vitest file/test counts. `"n/a: <reason>"` is legal only with the
reason. The hook refuses a tests gate without it.

## Phase 6 — Fix loop and deferral policy (X5)
A finding **inside the category the diff touches** (a sibling door, a copy, a missing witness on a path the change
added) goes back to the owning builder and the gate is re-run — it is **not** deferrable. A finding **outside** the
category may be filed as a follow-up only if the ticket (a) names the category and (b) names an existing category test
that will catch the next instance, or carries the label `no-seal`. Every follow-up filed from a gate carries the label
`deferred-from-gate`. **Closing budget: no more than 10 open `deferred-from-gate` items per epic** before the next audit
or review sweep on that epic starts; if the budget is full, the finding is fixed now. Cap: 3 rounds per gate, then
escalate to the owner with the finding and what was tried. Reviewer conflicts resolve in favour of the project-native
mandatory reviewer. Environmental test failures → fix the environment, don't bounce to a builder.

## Phase 7 — Finalize
All required gates `pass` (or `n/a` for conditionals) and the hook allows stop. Summarize what shipped, the changed
files, the `category` block, the negative control, and any deferred items with their labels. Then two mandatory lines:
1. **Which agent checklist grows from this ticket, or why none** — a lesson lives in the agent that needs it (and,
   for a framework trap, in an observation test under `common/framework/`); CLAUDE.md gets a one-line pointer at most.
2. **ADR self-check** — did this settle a hard-to-reverse fork? Flip the analyst's `Proposed` ADR to `Accepted` or
   write one; otherwise say "no ADR".
Do not offer to commit — the owner commits.

## `run.json` schema
```jsonc
{
  "task": "HD-123 short name",
  "class": "feature",                      // feature | light | trivial
  "spec": "docs/design/x-proposal.md",
  "changed": ["<absolute changed paths>"],
  "category": {                            // X1 — or {"n/a": "<why this change adds no rule>"}
    "rule": "every request-reachable text field is bounded to its column",
    "members": ["RegisterRequest.email", "InviteMemberRequest.email", "..."],
    "sealedBy": "RequestFieldLengthBoundTest"
  },
  "gates": {                               // pass | fail | pending | n/a
    "spec": "pass", "tenancy": "pass", "security": "pass",
    "migration": "n/a", "dc_cloud": "n/a", "api_docs": "pass", "ops_witness": "n/a", "ui_qa": "n/a",
    "tests": { "status": "pass", "negativeControl": "seen: RequestFieldLengthBoundTest red with @Size removed from InviteMemberRequest" }
  }
}
```
Write a gate `"pass"` only after reading the agent's verdict. The hook derives the required set from the real diff, so a
forgotten gate still blocks the finish. `run.json` is gitignored transient state.
