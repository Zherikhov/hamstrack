# Cutting a release

**Push order no longer matters** (HD-115, 2026-08-17). Tag before `main`, tag
after `main`, merge locally or through the GitHub PR button — production ends up
on the tagged version either way. If you are looking for the old "the tag must
exist before the `main` build starts" rule: it is gone, and so is the failure it
guarded against.

## The sequence

```bash
# 0. Pre-flight, if this release carries a migration that DELETES ROWS. 0.17.0 does
#    (V20, HD-135), and it is blocking: the number it prints is knowable only
#    BEFORE the deploy. See "Releases carrying a destructive migration" below for
#    the query, what a non-zero answer means, and which non-zero answers still
#    release the block.

# 1. Merge — locally or via the PR button, whichever you prefer
git checkout main
git pull
git merge --no-ff feat/<branch> -m "Merge <what> (HD-XX)"

# 2. Tag main's current HEAD. Annotated (-a) is required: --follow-tags only
#    pushes annotated tags, and pushing the tag is what stamps the version.
git tag -a vX.Y.Z -m "X.Y.Z"

# 3. Push. One command for both refs is still the tidiest, but two pushes in
#    either order work identically.
git push origin main --follow-tags
```

Both `vX.Y.Z` and bare `X.Y.Z` tags are accepted; everything downstream strips
the leading `v`.

**The one rule that remains: tag the commit that is `main`'s tip.** See
"What still fails" below.

## Why the order stopped mattering

The version is still **baked into the image at build time** — `pom.xml` carries a
`0.0.0-DEV` placeholder, `build.yml` computes `APP_VERSION` (the tag for a tag
build, else `git describe --tags`) and passes it as a Docker build-arg that the
build stage applies with `versions:set`. That is what `/api/meta` and the About
dialog report.

What changed is which builds may publish the `latest` tag that prod pulls:

- **Both** a `main` push and a stable release tag publish `latest` now (it used
  to be `enable={{is_default_branch}}`, i.e. `main` only).
- **Both** chain `deploy.yml`, which was previously filtered to `main`.

So:

| you push | what happens |
|---|---|
| `main`, then the tag | main build publishes `latest` = `X.Y.Z-N-gsha` and deploys; the tag build then publishes `latest` = `X.Y.Z` and deploys. Prod ends on `X.Y.Z`. |
| the tag, then `main` | tag build publishes `latest` = `X.Y.Z` and deploys; the main build runs on the same commit, where `git describe` now resolves to the exact tag, so it publishes `X.Y.Z` too. Prod ends on `X.Y.Z`. |
| both at once (`--follow-tags`) | two builds, same version string, two deploys, second is a no-op. Prod ends on `X.Y.Z`. |

The two builds are in different `concurrency` groups and run in parallel, so
they can finish in either order — harmless, because when the tag sits on main's
tip **both compute the same string**. The deploys are serialised by a
`concurrency: deploy-production` group, so they never fight over the host.

Historical note, for anyone who finds an old image: before this change a tag
pushed after `main` produced a correctly-labelled `X.Y.Z` image that prod never
pulled, while prod kept serving `<previous-tag>-<N>-g<sha>`. On 2026-08-16 prod
served `0.12.5-16-g94fac7d` for a tree that was already 0.13.0.

## What still fails (deliberately)

**Tagging a commit that is not `main`'s tip.** The tag build publishes its
`X.Y.Z` / `X.Y` images and cuts the GitHub Release, but it refuses to move
`latest` — otherwise it would publish an *older* tree as `latest` and roll
production back. The same guard trips if `main` moved on while the release was
building. The Build's job summary prints the reason:

> `APP_VERSION=0.13.2 · publish latest: false (tagged commit <sha> is not main's tip <sha>)`

Recovery: tag the real tip (`vX.Y.Z+1`, or delete and re-push the tag), or run
Actions → Build → **Run workflow** on `main`.

**Pre-release tags** (`v1.0.0-rc1`, `0.13.0-beta` — anything containing `-`)
publish their semver images and a pre-release GitHub Release, but never touch
`latest` and never deploy. That is the point of them.

**A red tag Build.** If the tag's `build-and-test` fails (flaky test) while
main's passed, prod keeps the `git describe` label. Fix: Actions → Build → *Run
workflow* on the tag.

**One residual race, and it is irreducible.** Each build bakes the version it
computed *at the start of its `build-and-push` job* — roughly 6 minutes after
the push, when tests finish. Push `main`, then push the tag more than ~6 minutes
later, and the `main` build has already resolved `git describe` to a suffixed
version; prod ends up correct only because that build also finishes earlier and
the tag build overwrites `latest` afterwards. If the two ever finished out of
order (a queued or unusually slow `main` run overtaken by the tag run), `latest`
would land on the suffixed label. Nothing can narrow this further while the
version is baked into the image — the string is fixed before the image exists.
In practice: push both together, and if the *Report deployed version* step shows
a suffix, Actions → Build → *Run workflow* on the tag fixes it in one click.

## Verify, don't assume

1. **Actions → Build → job summary** shows `APP_VERSION=X.Y.Z · publish latest:
   true`. Visible ~6 minutes in, long before the deploy.
2. **Actions → Deploy to production → "Report deployed version"** prints prod's
   live `/api/meta`. It is informational (`continue-on-error`), so a red X there
   means the check could not reach the site, not that the deploy failed.
3. Or by hand: `curl -s https://hamstrack.com/api/meta` → `"version":"X.Y.Z"`.

**If the version still comes out wrong:** Actions → Build → **Run workflow** on
`main` (the `workflow_dispatch` trigger, added with this change). No new commit,
no new tag, no need for an existing run to re-run. The deploy chains off it.

## Releases that register a new HQL field name

A `FieldRegistry` entry **reserves** a name: from that release on it outranks any
workspace's custom field of the same key (`FieldResolver`). For an affected
tenant the loud half is that `key = "…"` stops resolving their stored values
(422); the **silent** half is that `/schema` omits a registry-claimed key, so
their field disappears from search vocabulary with no error, no log line and no
UI affordance, while continuing to work everywhere else in the product.

Nothing detects that after the fact, so run this **before** the release and
record the answer in the release notes — once per name the release registers:

```sql
SELECT id, key, name, scope_workspace_id, scope_project_id
  FROM field_defs
 WHERE lower(key) = '<the new field name>' AND archived_at IS NULL;
```

Archived defs are already out of resolution and are harmless. A row with
`scope_workspace_id IS NULL` is a **global** def: the blast radius is every
workspace on the instance at once, not one tenant. Nobody's stored filter text
is ever rewritten, and a field's key is immutable, so the honest remedy for an
affected tenant is a new field under a different key.

`AdminFieldService` refuses to *create* a field under a claimed key (409, checked
after slugification — a field called "Project" auto-slugs to `project`). That is
the whole of its reach: **it covers fields created through the admin service, and
nothing that reaches `field_defs` by any other route** — not rows that already
exist, and not a row written by a migration, a seeder, or any future path that
inserts without going through the service. Our own migrations are such a route:
`V3__system_fields.sql` seeds `labels`, `sprint` and `components`, all three of
them registry-claimed names. They are harmless only because `V8`, `V11` and `V9`
respectively archive each placeholder once the real feature superseded it, and
archived defs are out of resolution — that is an outcome of those migrations, not
something the create-time guard could have produced. So the query above is the
check; the 409 narrows how often it finds anything.

## Releases that change how a stored value is derived

A release that changes the *function* producing a stored value — a case fold, a slug, a
hash, any normalisation — has a failure mode that is not a crash and not an error log. The
old build wrote rows through the old function; the new build looks them up through the new
one, misses, and **creates a second row** instead. Both rows are valid, both are live, and
nothing says there are now two. A destructive migration at least breaks loudly (next
section); this does not break at all.

**The operator-facing note does not belong in this file.** This is a maintainer runbook
about tagging and rollback; nobody running a self-hosted instance reads it. The check
queries, the remedy and the JVM flags go in **`docs/self-hosting.md` under `## Upgrading`**,
which is the DC operator manual and states its audience in the first lines. 0.16.0's is
[Duplicate accounts after an upgrade](self-hosting.md#duplicate-accounts-after-an-upgrade-locale-dependent-email-folding);
copy its shape.

So for a release in this class, three things:

1. **Write the operator section** in `docs/self-hosting.md`, with a `## Contents` entry and
   a pointer from any other section the failure touches (0.16.0's also hangs off
   `## First run & the admin account`, because a duplicated seed admin is a first-run
   problem).
2. **Verify it, three checks, five minutes** — not "confirm it shipped", which is the
   sentence a reviewer skips. 0.16.0 passed a review with the anchor swallowed and the
   remedy SQL wrong, so this item failed its own release:
   1. **Open the rendered page** and click the `## Contents` entry and every pointer you
      added. Watch the *callouts* especially: a `>` block whose last line runs into
      unprefixed text is silently swallowed by lazy continuation, taking the next
      paragraph into the quote with it — which is how a pointer aimed at upgraders ends
      up eating the instructions everybody else needs.
   2. **Try every remedy the section prescribes — including the ones written as
      prose.** For SQL that means running each statement in the order printed against
      a seeded fixture holding both a normal row and an affected one, including the
      query you expect to return nothing, plus one run in the *wrong* order to confirm
      the order warning is real; and checking whether the block assumes one session,
      since a temp table does not survive the one-shot `psql -c` calls the rest of the
      page demonstrates. But the ones to distrust most are the sentences that name no
      SQL at all — "have them use forgot password", "delete it and re-create it",
      "just re-register" — because they read as obviously fine and are therefore the
      only remedies nobody ever executes. 0.16.0 shipped a paragraph offering two of
      them: one was a silent no-op on precisely the account it was for, the other died
      on a foreign key, and a correct one-line fix sat unnoticed behind both. Walk a
      prose remedy against the real code and schema, or delete it.
   3. **Confirm the account is usable afterwards** — log in as it. Not that each
      statement succeeded: a normalisation remedy can report `UPDATE 1`, change nothing,
      and leave the user locked out, because `UPDATE 1` counts rows matched and says
      nothing about whether the value it wrote is the one the application looks up.
3. **Say it in the release notes.** `docs/self-hosting.md` sends every upgrader to the
   Releases page before a minor upgrade, so the Release body is what routes them to the
   section. See "Release notes" below.

**Two traps, both of which cost a review round on 0.16.0.** A remedy must be *performable
by its reader*: check the SQL you prescribe actually runs, in the order you prescribe it —
`users.email` is `NOT NULL UNIQUE`, so "rename the survivor" before "retire the duplicate"
dies on a unique violation halfway through, and a tombstone built as `email || suffix`
overflows `VARCHAR(255)` for a long address. And a detection query must be able to return
**nothing** as a real all-clear: a query that finds one row of a pair leaves the operator
matching spellings by eye, whereas one that groups by the folded form and returns groups of
more than one either finds the pair or proves there is none.

## Releases that change a resource default

A release that changes a **default the operator never set** — how much memory the container
may have, how many connections the pool opens, how big an upload may be — lands on installs
that took no decision to revisit. Nothing in their `.env` names the setting, so nothing in
their `.env` will remind them. Unlike a derived-value change (previous section) there is no
wrong row to find and no query that finds it: the instance is correct, merely differently
sized, and the only symptom is that it behaves worse than it did yesterday with nothing
tying that to the upgrade. The test for this class is not "did behaviour change" but
**"would the change look like a fault to somebody who was not told"**.

Watch for the shape where a default is *better* on the machine it was reasoned against and
quietly *takes something away* from every larger one — those releases read as an
improvement in the PR and as a regression on the box. 0.17.0 is the worked example several
times over, and its changes compound: `HD-152` bounded the JVM heap, which raises it on a 1 GB host and halves it on
a 4 GB one ([The heap is bounded from 0.17.0](self-hosting.md#the-heap-is-bounded-from-0170));
`HD-151` bounded how long a statement may run, which is invisible on a small install and turns
a slow report into a `422` on a large one
([Statements are bounded from 0.17.0](self-hosting.md#statements-are-bounded-from-0170)); and
the same ticket widened `DB_LOCK_TIMEOUT_MS` from the handful of transactions that locked
deliberately to **every** transaction, so a contended write that used to wait indefinitely now
answers a retryable `409` — a changed default whose *value* never moved, which is why it is the
easiest of the three to leave out of a list —
*and the heap cut makes the statement bound easier to hit*, so one release produced two causes
for one symptom. This sentence said "0.17.0's is the worked example", singular, for the day
between the first two landing, then "two worked examples" until the lock bound made that wrong
as well: a count goes stale one entry before its list does, in the paragraph that says so.

Three steps, and **step 3 is the one that gets skipped**, because the first two feel like
the work:

1. **Write the operator section** in `docs/self-hosting.md` under `## Upgrading`, with a
   `## Contents` entry. Say which direction the change moves *for which size of host*: a
   default is not one change, it is one change per box it lands on. Give a break-even so a
   reader can tell in a single line whether they are affected, and give the remedy as a
   value they can type rather than a method they must apply — a worked table beats a
   subtraction rule, and a rule that disagrees with its own worked numbers is worse than
   no rule.
2. **Route to it from where the reader already is.** The setting's row in the configuration
   table, `## Requirements`, and — the one that is always forgotten — the `## Upgrading`
   prose carrying the `docker compose pull` command, because that command is what an
   upgrader copies *instead of* reading on.
3. **Write the line into the GitHub Release body by hand.** `generate_release_notes: true`
   lists merged PRs and says nothing about behaviour, and `docs/self-hosting.md` sends every
   upgrader to the Releases page before a minor upgrade — so this is the only text that
   reaches somebody who upgrades without opening a manual. Everything else in steps 1 and 2
   is read by people who were already looking.

0.17.0 ships **several** changes in this class — one line each, below. (Note the shape, which
this section has now got wrong twice about itself: it first said "0.17.0's line", singular, and
was corrected to "two lines" on the day the second landed, which was stale again by the third.
Each time the number was written by somebody looking straight at the list. Count nothing you
are about to enumerate.

**And a positional reference is a count in a costume.** `.env.prod.example` said a setting was
"four lines above" another; the release grew the block between them to thirty-six, silently,
because a release is precisely the thing that makes a file longer. It goes stale the same way a
number does and is worse in one respect: no grep anybody would think to run for a stale count —
"two", "three", "the only" — matches "four lines above". Point at names, never at distances.)

Ready to paste:

> **Every database statement is now bounded (`DB_STATEMENT_TIMEOUT_MS`, default 10 s).**
> Before 0.17.0 a single query could run for ever while holding one of the ten pooled
> connections; ten of them stopped the instance. From now on a statement still running after
> 10 seconds is cancelled and the request answers **`422`** with
> `errorType: STATEMENT_BUDGET_EXCEEDED` and no `Retry-After` — an identical retry costs
> identical time. **On a large install this can turn a slow report, search or member removal
> into an error**; a report can be narrowed, a member removal cannot. If it happens, raise
> `DB_STATEMENT_TIMEOUT_MS` in `.env` (minimum twice `DB_LOCK_TIMEOUT_MS`, so ≥ 6000) and
> `docker compose up -d`. Database migrations are deliberately not bounded.
>
> **Lock waits are bounded with it, and this half is a status-code change.** A write that
> collides with a long-running change — most often removing a member with a lot of assigned
> work — used to wait indefinitely and eventually succeed. It now gives up after
> `DB_LOCK_TIMEOUT_MS` (3 s) with **`409` + `Retry-After`**, which is retryable and which clients
> should retry. The two bounds ship together on purpose: `statement_timeout` counts lock-wait
> time, so bounding statements alone would have answered that same collision with the
> non-retryable `422` above. Details:
> [Statements are bounded from 0.17.0](https://github.com/Zherikhov/easyTask/blob/main/docs/self-hosting.md#statements-are-bounded-from-0170).

And the heap line:

> **The JVM heap is now bounded (`APP_MEMORY_LIMIT`, default `1g` → 512 MB heap).** Before
> 0.17.0 the JVM claimed ~25% of *host* RAM, so on a host larger than 2 GB this is **less
> heap than you had** — a 4 GB host drops from ~1 GB to 512 MB, an 8 GB host from ~2 GB.
> There is no error; it shows up as reports and searches getting slower. Set
> `APP_MEMORY_LIMIT` in `.env` to about half the host (4 GB → `2g`, 8 GB → `4g`) and
> `docker compose up -d`. On a host of 2 GB or less you gain heap and need do nothing.
> Details:
> [The heap is bounded from 0.17.0](https://github.com/Zherikhov/easyTask/blob/main/docs/self-hosting.md#the-heap-is-bounded-from-0170).

And the taxonomy foreign keys, which is the one line here that can stop a **startup** — so it
carries the repair, not just the diagnosis. A DC operator whose data is clean never sees any of
it; one whose data is not meets Flyway failing `V19` with no repair SQL anywhere they would
think to look, and "a refusal must name an action its reader can perform" applies to a failed
boot at least as much as to a `409`:

> **`issues.type_id` and `issues.status_id` now have foreign keys.** The database enforces that
> no issue can point at a status or issue type that does not exist. **If your database already
> contains such a row, the upgrade will not start** — Flyway fails `V19__issues_taxonomy_fk.sql`
> and the container exits. This is deliberate: those rows are already broken (the issue renders
> as a blank board column and disappears from every status filter), and the fix needs a decision
> only you can make. To check **before** upgrading, and to find any offenders afterwards:
>
> ```sql
> SELECT i.id, i.workspace_id, i.project_id, i.number, i.type_id
>   FROM issues i LEFT JOIN issue_types t ON t.id = i.type_id
>  WHERE t.id IS NULL;
>
> SELECT i.id, i.workspace_id, i.project_id, i.number, i.status_id
>   FROM issues i LEFT JOIN statuses s ON s.id = i.status_id
>  WHERE s.id IS NULL;
> ```
>
> Both must return **no rows**. If either does not, repoint those issues at a catalog row that
> does exist — pick one your project already offers, e.g.
> `UPDATE issues SET status_id = '<a real status id>' WHERE id = '<the issue id>';` — then
> upgrade. Nothing else changes: a delete that would strand an issue was already refused by the
> application, and remains so.
>
> **A delete the database refuses is a `409`, not a `500`.** Related: catalog deletes that
> collide with a reference now answer `409` with
> `errorType: REFERENCE_CONSTRAINT_VIOLATION` instead of a stack trace.

## Constraints on a populated table, and why they are free right now

Adding a constraint to a table that already holds rows makes PostgreSQL validate
every one of them, under a lock. `ADD CONSTRAINT … FOREIGN KEY` takes
`SHARE ROW EXCLUSIVE`; building a `UNIQUE` index takes `ACCESS EXCLUSIVE`. Both
block writes to that table for the duration, and the duration is linear in the
row count — measured on a 1M-row table: `ADD COLUMN` 2.9 ms, FK validation 64 ms,
unique index ~55 MB and 1–3 s.

**This costs nothing today, and the reason is the deploy shape, not the SQL.**
Both deployment models run a single application container. `docker compose up -d`
stops the old one before the new one starts, so migrations execute with no
concurrent writer and the lock falls inside a window where nothing is serving.
Decision recorded 2026-08-21 (HD-93): **rolling deploys are not planned.**

**What changes the answer is a rolling deploy, and nothing else.** The moment the
old instance keeps serving and writing while the new one migrates, every lock
above becomes a stall on live traffic. Flyway's advisory lock keeps the migration
itself safe; it does nothing for the writers waiting behind the table lock.

### The inventory, so it is not re-derived

If a rolling deploy is ever put on the table, these are the applied constraints
that would have to be converted to `ADD CONSTRAINT … NOT VALID` followed by a
separate `VALIDATE CONSTRAINT` — **in a new migration, never as a retrofit into an
applied one**:

| migration | constraint | on | lock |
|---|---|---|---|
| `V8__labels.sql` | `issues_id_workspace_id_key` UNIQUE `(id, workspace_id)` | `issues` | `ACCESS EXCLUSIVE` (index build) |
| `V9__components.sql` | `issues_component_fk` | `issues` | `SHARE ROW EXCLUSIVE` |
| `V11__sprints.sql` | `issues_sprint_fk` | `issues` | `SHARE ROW EXCLUSIVE` |
| `V11__sprints.sql` | `issues_story_points_ck` CHECK | `issues` | `SHARE ROW EXCLUSIVE` |
| `V11__sprints.sql` | the `position` rescale | `issues` | rewrites every row |
| `V19__issues_taxonomy_fk.sql` | `issues_type_id_fkey` | `issues` (+ `issue_types`) | `SHARE ROW EXCLUSIVE` |
| `V19__issues_taxonomy_fk.sql` | `issues_status_id_fkey` | `issues` (+ `statuses`) | `SHARE ROW EXCLUSIVE` |

`ADD CONSTRAINT … FOREIGN KEY` takes its lock on **both** tables, not only the one
named in the `on` column — `SHARE ROW EXCLUSIVE` on the child *and* on the parent,
so writes to the referenced table are blocked for the same window. The V19 rows
say so explicitly; it was equally true of `V9` and `V11` and simply went unwritten,
which is the kind of omission this section exists to stop repeating.

Two things that look like they belong on that list and do not, because the
distinction is the whole point and is easy to get backwards:

- **A constraint declared inside `CREATE TABLE`** validates an empty table. Free
  at any size, forever. Most of what a migration adds is this.
- **`ADD COLUMN … REFERENCES` in a single statement** is *not* the same as
  `ADD COLUMN` followed by `ADD CONSTRAINT`. PostgreSQL knows the new column is
  definitionally all-NULL and skips the scan. `V14__role_assignments.sql` adds five
  such columns and pays nothing; `V9` splits them across two statements and pays a
  full scan for a column that is equally all-NULL.

`CREATE INDEX CONCURRENTLY` is **not** an escape hatch here: Flyway wraps each
migration in a transaction, and `CONCURRENTLY` cannot run inside one.

### The rule this implies

While deploys stop the old container first, write the plain form — it is shorter,
it is atomic, and the two-step form buys nothing. What must not happen is that the
decision gets re-derived from scratch under time pressure: if the deploy shape
changes, this section is the list, and the rule becomes *every constraint added to
a populated table is two-step*, applied to new migrations and to the five above.

## Releases carrying a destructive migration

Most releases need nothing here. A release needs this section when one of its
migrations **destroys something the deploy cannot put back** — and there are two
independent ways to be in that category, so read both before deciding you are in
neither:

- **destructive to the schema** — a column or table the previous image still reads
  is dropped or renamed. Costs a snapshot and a stop-the-world deploy (below). The
  roles release (**V13–V15**, HD-123) is the first: `V15` drops
  `workspace_members.role`, `workspace_invites.role` and `project_members.role`.
- **destructive to the data** — rows are deleted, or a value is overwritten with
  one the old value cannot be derived from, while every column stays exactly where
  it was. Costs a **pre-flight run against production before the deploy**, because
  the number involved stops being knowable the moment the migration commits. The
  0.17.0 notification release (**V20**, HD-135) is the first, and it drops nothing
  at all — which is precisely why it needs saying here.

A release can be in both categories, one, or neither. The two costs are separate.

**For a schema-destructive release, two extra things:**

1. **Snapshot the database first.** Once `V15` has run, rollback is a *restore*,
   not a re-deploy — the old image cannot read the new schema, and `latest` only
   moves forward anyway. Take the snapshot between the tag push and the deploy,
   or immediately before running `docker compose up -d` by hand.
2. **Deploy stop-the-world, not rolling.** Flyway runs on the *new* container's
   startup while any *old* container is still serving; from that moment the old
   one is querying columns that no longer exist, and every request it handles
   500s. Single-instance DC is unaffected (compose replaces the one container),
   and prod is single-instance today — but multi-node Cloud is a stated
   deployment model, so a rolling/blue-green deploy of this release must be
   drained to zero old instances *before* the new one starts, or split across
   two releases (N adds and backfills, N+1 drops) so no image ever runs against
   a schema it does not know.

A migration that only **adds** tables or columns (`V13`, `V14`) is rolling-safe
and needs neither.

### A migration that deletes rows needs its count read *before* the deploy — 0.17.0 / `V20`

A migration can be destructive to **data** without dropping anything: `V20` (HD-135) adds
`notifications.workspace_id NOT NULL`, fills it by reading the workspace id out of each
row's `link`, and **removes any row it cannot fill**, because such a row is unshowable
under the new rule and nothing in the schema records the workspace it belonged to. So the
number of rows it removes is knowable in advance and is *best* knowable in advance —
afterwards, Flyway's `RAISE NOTICE` is buried in container logs nobody reads.

`V20` does copy those rows into `notifications_unresolvable_v20` before deleting them (and
creates that table **only** if there are any), so a missed pre-flight is recoverable rather
than final. That is a backstop for the backstop; it is not a reason to skip the step, and
the table is yours to `DROP` once you have read it.

**Blocking, and the owner is the only person who can do it.** Production is EC2 with
SSM-only access, so run this against production before the deploy:

```bash
aws ssm start-session --target <INSTANCE_ID>
docker exec -it hamstrack-postgres-1 psql -U hamstrack hamstrack
```

Then paste the query at the `hamstrack=#` prompt — **on one line**, and leave with `\q`:

```sql
SELECT count(*) AS unresolvable FROM notifications n LEFT JOIN workspaces w ON w.id = substring(n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/')::uuid WHERE w.id IS NULL;
```

Three things in that recipe are deliberate, and each one is a way the earlier version of
this section failed when it was first run against the real box on 2026-08-26:

- **`docker exec` on the container, not `docker compose exec` on the service.** Bare
  `docker compose` looks for `compose.yaml` / `docker-compose.yml` and prod keeps its file
  under another name, so the documented command answered `no configuration file provided:
  not found` — from inside the right directory. `docker exec` depends on neither the
  filename nor the working directory. If the container is not named
  `hamstrack-postgres-1`, `docker ps --format '{{.Names}}'` gives the real name; note that
  `hamstrack-postgres-exporter-1` is the Prometheus exporter and has no `psql` in it.
- **A psql prompt rather than a heredoc.** `<<'SQL'` needs a line containing exactly `SQL`
  to terminate, and the SSM web console mangles a pasted multi-line block — the shell then
  sits at a `>` prompt looking like it hung. Ctrl+C is the way out.
- **The query on one line.** Same reason: multi-line SQL survives the paste no better than
  the heredoc did.

Substitute the real role name for `-U hamstrack` if `DB_USERNAME` in `.env` says otherwise;
the database is always `hamstrack`.

**Run the query even if you believe the table is empty, and read the total alongside it.**
A count over an empty table returns `0` for every predicate, including a false one, so a
bare `0` does not distinguish "nothing to delete" from "nothing there at all". Both release
the block — but only one of them is evidence, and the ticket deserves to know which it got:

```sql
SELECT count(*) AS total, count(*) FILTER (WHERE substring(link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/') IS NOT NULL) AS parses FROM notifications;
```

**`0` releases the block.** A non-zero answer does **not** automatically hold it: the query
above tests two things at once — that `link` *parses*, and that the workspace it names
*still exists* — and those come apart into two causes wanting opposite actions. Split them,
at the same psql prompt and again on one line:

```sql
SELECT n.link IS NULL OR substring(n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/') IS NULL AS link_did_not_parse, count(*) FROM notifications n LEFT JOIN workspaces w ON w.id = substring(n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/')::uuid WHERE w.id IS NULL GROUP BY 1;
```

- **`link_did_not_parse = false`** — the link parsed and the workspace it names is gone.
  This is the ordinary way to get a non-zero number and it is **not** evidence of an unknown
  producer: before `V20` there was no foreign key on this table, so rows orphaned by a
  workspace removed with operator SQL, by a partial restore, or by a dump-and-reload
  accumulated silently. Those rows were already unrenderable. **Deploy** — the deletion is
  the correct outcome and the block is released.
- **`link_did_not_parse = true`** — `link` is absent or is not the shape `V20` reads, which
  means some producer wrote a row this migration does not know how to interpret. Deploy is
  still fine (the rows are quarantined), but **file the count and this split on HD-135**: it
  is the one answer no environment the authors could reach was able to give.

The single-producer premise was checked against the whole of git history — one commit, one
link literal — so the second bullet is not expected. It is written down because "not
expected" is exactly the state the first version of this section mistook for "impossible",
and it prescribed an action (reopen the design question) that no DC operator can perform.

`docs/self-hosting.md` carries the same queries, an export statement and the user-facing
wording for DC operators.

**A zero from an empty table is not evidence.** The development database held **no
notification rows at all** when `V20` was written, so its pre-flight returned `0` for the
same reason it would have returned `0` for any predicate whatsoever. That number says
nothing about production and must not be quoted as if it did — the honest sentence is
"the backfill has not been exercised by data anywhere we can observe", which is precisely
why the production run is a release blocker and why
`V20NotificationsWorkspaceScopeTest` replays a link produced by the real code path through
the real migration instead.

**What the 0.17.0 run actually returned, recorded because the shape recurs.** Production
answered `unresolvable = 0` over `count(*) = 0` — the same empty-set zero, so it released
the block without being evidence of anything. The instance was single-user at the time, and
a self-mention is skipped by design, so the only producer had never fired. Writing one
comment that mentioned somebody else changed that: the pre-flight then ran over a real row
and `parses` came back `1`. That version of the number is worth more than either zero,
because the row was written by the **image already deployed** — which is the one thing the
development rows could not test, since those were written by the build that ships the
regex. If a future release finds this table empty again, the cheap way to earn the same
evidence is to make one row and re-run, rather than to accept a zero the emptiness
guaranteed.

**The general rule this leaves behind.** A migration whose `DELETE` is a backstop for a
condition expected never to occur is untested by construction in every environment where
the condition does not occur. It needs three things before it ships: a pre-flight the
operator can run on their own data, a test that manufactures the condition, and somewhere
for the rows to go — a conditional side table costs a clean install nothing and is the
difference between "the answer expired when you missed the window" and "the answer is
still there". A comment saying "expected to be zero" is none of the three.

And whatever the pre-flight prints, **the instruction attached to a non-zero answer has to
name an action its reader can perform**. This section's first version said "reopen the
design question", which is not something a DC operator can do; it also attributed the
number to the one cause that had already been ruled out, and stopped a deploy that should
have gone ahead.

**Editing a migration in place.** Allowed only while *both* are true: its branch
is unmerged, and the only database that has ever run it is the author's local
one. Then a checksum change costs one local `DROP DATABASE` and nothing else,
and a `V{n+1}` correcting a `V{n}` nobody has run would permanently record a
mistake no operator experienced. Once either condition fails — the branch is
merged, or it has run anywhere shared (CI's throwaway databases do not count,
they are created per run) — the only correct fix is a **new** migration. **Say
in the PR description which you did**, because after the fact the only evidence
is the file's mtime, and the justification expires silently at merge.

## Release notes — what a user or integrator will notice

The tag build cuts the GitHub Release with `generate_release_notes: true`, which lists
merged PRs and new contributors and **nothing about behaviour**. Anything an upgrader has
to be told is written by hand into that Release body (edit it after the build — a later
re-run never overwrites an existing body). That is the place: `docs/self-hosting.md` sends
every upgrader to the Releases page before a minor upgrade, and it is the only page a
Cloud user or an API integrator will look at.

The rule for what belongs there: **every behaviour change that looks like a bug when it is
met without warning** — a status code that moved, a response shape that grew, a value that
is now rejected, a new mode. Additive endpoints do not need a line each; the API reference
already has them.

**A changed default belongs here too, and is the entry most easily missed** — nothing was
added, nothing was rejected, and the diff reads as configuration. See "Releases that change
a resource default" above, which carries this release's lines ready to paste.

Below is that text for the **roles & permissions** release (HD-123, V13–V16), which is also
the worked example of the shape. See "Releases carrying a destructive migration" above for
what this particular release needs operationally.

### The one new capability

- **A project administrator can now delete other people's comments.** Nobody could before,
  at any role — not a project manager, not a workspace owner — because the only rule was
  authorship. The built-in **Project admin** role holds `comment.delete` unrestricted, so
  moderation is possible for the first time. This is the release's one deliberate
  divergence from previous behaviour that *grants* something rather than reorganising what
  already existed. `comment.edit` stays own-only at every role and is not grantable any
  other way: deleting someone's comment is moderation, editing it is impersonation. A
  workspace Owner/Admin holding no project membership row does **not** get this.

### Status codes that moved

- **`403` where `409` used to be, on an archived project.** The permissions a request needs
  are now checked **before** the project's state, so a caller who lacks the permission on an
  archived project gets `403 "Requires permission: …"` instead of `409 "Project is
  archived"`. It shows up on issue deletes, attachment deletes, commenting and ranking. The
  rule is that whether you *may* do a thing must never depend on the state of the thing you
  are asking about. A caller who **has** the permission still gets the `409`.
- **`409` where `204` used to be, removing the last project administrator.**
  `DELETE …/projects/{p}/members/{u}` — and a demotion through the new
  `PATCH …/projects/{p}/members/{u}`, which strands a project just as effectively with no
  row removed — is refused when the target is the only ACTIVE member holding
  `project.member.manage`. It used to succeed and leave the project unmanageable by
  *anyone*, workspace Owner included, because that permission is deliberately not part of
  the workspace-wide curator set: nobody could add a member back. Add another administrator
  first. A project with no explicit administrator at all remains a normal state; only the
  step from one to none is refused.
- **`403` where `404` used to be, on `/api/workspaces/{ws}/projects/{p}/admin/**`.** A
  workspace member who is not a member of *that project* used to get `404` from this
  endpoint family, while its sibling `/workspaces/{ws}/admin/**` answered `403` for the
  identical failure. It is `403` everywhere now. **Tenancy is unchanged and absolute:**
  somebody who is not a member of the *workspace*, or a workspace that does not exist, is
  still `404`. If you were treating a `404` here as "no such project", read it as "not your
  workspace".
- **`200` with a body where a bare `204` used to be, on workspace member removal.**
  `DELETE …/workspaces/{ws}/members/{u}?adoptStrandedProjects=true` answers
  `200 {"adoptedProjects": [ {id, key, name}, … ]}` when the removal took one or more
  projects over on the caller's behalf, and stays `204` when nothing was adopted. **Branch
  on the status, and show the user what they were granted:** the flag is accepted without a
  prior `409`, so a client that wires it on once — or a script that retries on any conflict
  — would otherwise accumulate project roles for its user with nothing on the wire saying
  so. The same call answers `409` with an `errorType` of `STRANDED_PROJECTS` (retry with the
  flag clears it), `ADOPTION_BLOCKED`, `ADOPTION_ROLE_UNREADABLE` or
  `STRANDED_BY_INHERITANCE` (no retry exists — offer none).
- **`422` where `400` used to be, naming a role.** `role` was a closed enum, so an unknown
  or absent value failed deserialization or validation with `400`. Every endpoint that names
  a role now answers **`422`** for an unknown key or id, a correctly-spelled key from the
  *other* scope, the wrong case, and for a body that names the role in neither way or in
  both. `400` still covers malformed JSON and ordinary field validation.

### New behaviour to know about

- **`STRICT` project access exists.** One workspace switch (`projectAccessMode` on
  `PATCH /api/workspaces/{id}`, needs `workspace.edit`) decides exactly one thing: whether
  people who were never added to a project inherit that project's default role. **`OPEN` is
  the default, and behaviour under it is identical to the previous release** — every
  workspace this release upgrades is `OPEN`, so nothing changes until somebody flips it. In
  `STRICT`, only people explicitly added to a project can change anything in it; everyone
  can still **see** every project, so no read is lost, and flipping back restores every
  member's permissions byte for byte (neither direction writes a membership row or touches
  an issue). Two things to publish with it: a workspace Owner is **not** a rescue — their
  workspace-wide grants are `project.edit`, `component.manage`, `version.manage` and
  `sprint.manage`, with no issue or comment permission, so in a `STRICT` project nobody has
  been added to, nobody can file an issue — and `POST …/project-access/preview` counts
  exactly that (`projectsWithNoWriters`) before you commit. Self-hosters get
  `DEFAULT_PROJECT_ACCESS_MODE` for *newly created* workspaces only.
- **Names containing invisible or bidi-reordering characters are now rejected** where they
  were previously accepted: the display name at registration and at `POST /admin/users`, a
  workspace name, a project name and description, and a role name and description.
  Zero-width characters, bidi marks/overrides/isolates, NEL and LINE/PARAGRAPH SEPARATOR,
  the interlinear annotation characters and the supplementary tag block are refused with the
  ordinary validation `400`. Visible homoglyphs are deliberately **not** touched — this is
  not a confusables rule and it rejects no real name. Values already stored are unaffected.
- **Role values are open strings from now on.** `TEAM_LEAD` ("Team lead") is a new built-in
  project role you can assign and will meet in `role` and `myRole`; `COMMENTER` became
  assignable; and a workspace can define roles of its own, whose keys travel in exactly
  those fields. **Do not switch exhaustively on a role value** — display it, and decide with
  `myPermissions`. `role` and `myRole` can now also be `null`, which means "this row's role
  is not nameable", never "this member has no role".
- **`roleId` beside `role`.** Every endpoint that assigns a role accepts `roleId` — the only
  way to name a custom role — and exactly one of `roleId` / `role` must be present. The
  `role` key is deprecated but works unchanged, including its project-side `VIEWER → MEMBER`
  mapping; naming the built-in Viewer by **id** is what makes a genuinely read-only project
  membership expressible. Member listings carry `roleId` beside `role`, because a key is
  unique only within one (workspace, scope) pair: `MEMBER` names the workspace Member role
  *and* the project Contributor role, two different permission sets.

### Say this positively — it is the headline for an operator

- **`myPermissions` is advisory, for rendering only. The API is the enforcement boundary.**
  It tells a client which controls to show; it authorizes nothing. **A client that hides
  nothing is still safe** — the worst it can produce is a button that answers `403`, and
  that `403` names the permission it needed. The same rule covers the `settable` block on
  the default-role pickers and the project-access preview: **counts are advisory, refusals
  are authoritative**, and every ceiling and stranding check is re-derived inside the
  write's own transaction whether or not anyone previewed.

## Tracker bookkeeping

Independent of git, and easy to forget:

- Mark the project **version** released (Releases page). The tracker version is
  the plan/scope record; the git tag is the code record. They are named the
  same except the tag's leading `v`.
- Create the next version so in-flight work has somewhere to go.
- Move the shipped tickets to Done.

## Rolling back

`latest` only ever moves forward, so a bad release is rolled back by moving
forward, not by re-tagging an old commit (the guard above will refuse it):
revert on `main` and tag the revert. For an emergency, pin `app.image` in
`/opt/hamstrack/docker-compose.prod.yml` to the previous `X.Y.Z` and
`docker compose up -d` on the box — but remember to un-pin, or the next deploy
silently does nothing.
