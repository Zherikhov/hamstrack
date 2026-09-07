# ADR-0020: Capacity is measured on production — in a declared window, with a snapshot and a configuration fingerprint, not on a clone

Record date: 2026-08-28
Status: Proposed
Source: `docs/design/load-capacity-measurement-proposal.md` §2, §4.9, §5 (HD-186);
measurements of the box `i-019fe684b25ad831f` from 2026-08-28; `docs/ops-prod-hardening.md` §5
("what has never been measured"); `docs/design/config-delivery-proposal.md` §9 (the memory limit)

## Context

The box has never been put under load. Every capacity claim in the documentation — "~1 GB RAM for the
application", "2 vCPU / 2 GB is enough for a small team", "`REPORTS_MAX_ROWS` is ~38 MB per request",
"raise `DB_POOL_MAX_SIZE` for concurrency" — was derived arithmetically from the configuration and has
never once been checked against a running process. `docs/ops-prod-hardening.md` §5 says this about
itself in plain text: until HD-186 is done, "1 GB is enough" is a belief.

What makes the choice of venue a fork rather than a detail of procedure:

- The ticket's question is not only "how many people" but **which resource runs out first**. "The box
  is small" and "the application serialises" call for opposite actions, and they can be told apart only
  by attributing the signals to a specific machine: its memory, its burst credits, its page cache, its
  volumes, its neighbours on the host.
- The production box is **not isolated**: on the same 1909 MB live seven observability containers
  (~466 MiB in fact), postgres and caddy — and **neither app nor postgres nor caddy has a `mem_limit`**
  (measured 2026-08-28). 341 MB free, the 7-day minimum is 227 MB. A clone without those neighbours
  measures a different machine.
- This is a `t3.small` — burstable. A sustained load spends the credit budget, and what happens when it
  runs out depends on the credit specification. A clone with a different credit balance gives a
  different answer.
- On 2026-08-28 **1023 MB of swap** (`swappiness=10`) was added to the box, which had not been there
  before. That changes not the number but the **kind of failure**: without swap, running out of memory
  is an OOM-kill by the kernel (exit `137`); with swap it is catastrophic latency with live containers
  and green healthchecks.
- Disk: 79% used, 1.7 GB free; the `DiskFilling` alert fires below 15% free.
- The owner chose production and explicitly declined a temporary clone.

The price of the choice is real too: this is a machine that is about to be shown to outsiders, and it
holds the data of live (if few) users.

## Decision

**The load measurement is carried out on the production machine**, in a declared window, and is wrapped
so that the decision is safe by construction rather than by the executor's carefulness:

- **The preconditions are hard gates, not recommendations.** The container memory limit is already in
  effect and has been read off `docker inspect` (not off `.env`); the configuration drift
  `hamstrack_config_drift` is 0 across every scope; **the EBS snapshot is complete** and its id is
  recorded; a fresh backup exists; the volume is grown so that ≥ 5 GB is free and ≥ 3× the expected
  fixture; teardown has been rehearsed on a non-production database; a 1 VU run and the emergency-stop
  path have already been done by hand.
- **The fixture is generated and deleted per tenant.** The data live in dedicated load
  workspaces and in accounts on the `@load.invalid` domain; deletion goes by `workspace_id`, and
  completeness is checked by a claim **about the category** — a walk over `information_schema.columns`
  across every table with a `workspace_id` column. The list of tables is not maintained by hand: it
  would go stale one migration later.
- **The emergency-stop conditions are declared in advance and include things that are not about
  capacity**: free disk below 500 MB, any container dying, host available memory below 150 MB or swap
  above 512 MB, degradation of the real-user probe, growth of
  `hamstrack_role_scope_violation_total`, and — separately — **any answer other than 404 from the
  cross-tenant isolation canary**, which is a security incident, not a data point.
- **The limiters are not switched off.** Saturation is reached with a sufficient number of distinct
  principals (budgets are keyed on user id), and the limiters' behaviour is a result of the run, not an
  obstacle to it.
- **No application or observability setting is changed for the convenience of the run.** In particular
  the remote-write receiver is not enabled on production: `observability/` is a synced path, and an edit
  on the box would raise `ConfigDrift` and be wiped by the next deploy.
- **Every published number carries a configuration fingerprint**, taken from command output rather than
  from the repository: the image digest and `.deployed-sha`, `HostConfig.Memory` of every container,
  `MaxHeapSize` from inside the container, the pool size and the statement/lock budgets in effect, the
  instance type and the credit specification with its balance, the volume type and size, `free -m` with
  swap, the fixture seed, and what else was running on the box.

**The rule that outlives the ticket and is phrased about the category rather than about ticket
numbers:**

> The box that is measured is the box that is running. The result is valid only for the configuration
> against which it was taken, and is voided by any change to the container memory limit, the heap
> ceiling, the instance type or size, the connection pool size, the statement/lock budgets, the report
> and search ceilings, or the make-up of what else lives on the machine. Such a change is grounds to
> re-measure, not to re-interpret.

The ordering follows from the same rule: the run goes **after** the delivery of the memory limit
(HD-199) and after the already planned resize of the box (HD-189), because a number for a configuration
that is about to disappear is a number that will be quoted later anyway.

## Consequences

+ What gets measured is exactly the machine the question was asked about: with its neighbours, its
  swap, its credits, its page cache and its volume. Attributing "which resource ran out" becomes
  possible.
+ Along the way the run checks what cannot be checked on a clone: the real chain up to Caddy, the real
  observability stack as a competitor for memory, the real alert rules as the bar ("the threshold is
  crossed" is defined through the conditions of already provisioned rules rather than through invented
  numbers).
+ The isolation canary turns the load run into a regression check of the project's top bug class as
  well.
+ The configuration fingerprint makes the result's going stale visible rather than silent.
− The production machine is deliberately driven to failure. For part of the window the site may be slow
  or unavailable; this is announced in advance rather than discovered by users.
− It requires growing the EBS volume — an irreversible operation (a volume grows but does not shrink)
  and a small permanent cost.
− After the fixture is deleted the space returns to PostgreSQL but not to the file system: `VACUUM FULL`
  takes an exclusive lock and is not run on production for this. The residual bloat is absorbed by the
  grown volume.
− The procedure costs more than a clone: the snapshot, the teardown rehearsal, a human on duty at
  Grafana, the window.
− The result is one measurement of one configuration. It generalises by category ("this class of box,
  this shape of tenant") and never as a promise.

## Alternatives

- **A temporary clone of the box (restoring the snapshot onto a second instance)** — rejected: it
  measures a machine nobody uses. A different burst-credit balance, a different page cache state, a
  different volume, no live traffic and no real-user probe — and the number would come out with the same
  confident typesetting and none of the validity. It is appropriate if a requirement to break the box
  regularly appears.
- **A separate staging rig** — rejected for the same reason plus the cost: a permanent machine that has
  to be kept in a state identical to production, and a divergence between the rig and production is
  exactly the class of failure that HD-199 dissects with six weeks of undelivered config.
- **Extrapolation from a local run** — rejected: a local machine reproduces neither the container
  limit, nor the neighbours, nor the credits, nor the swap. A local run remains a way to develop and
  debug the harness and to rehearse teardown, but not a source of published numbers.
- **Switch off the limiters to reach saturation faster** — rejected: it measures a product nobody
  ships, and destroys exactly the signal (`429` with a `kind`) that tells an exhausted resource from an
  exhausted budget.
- **Not measure at all and leave the arithmetic** — rejected: that is the status quo, and it ships a
  public release with a sizing guide nobody has checked.
