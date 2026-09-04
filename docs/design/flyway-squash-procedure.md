# The Flyway chain squash — an executable procedure (HD-188)

**Status:** proposal / design review. **Date:** 2026-09-04. **Author:** systems-analyst.
**Release:** its own, and nothing else in it — see §1.3 (this is a recommendation the ticket does not make).
**Migration:** one file, `V1__init_schema.sql`, mechanically generated. Twenty-six files are deleted. **No
new table, no new column, no new index, no row created, changed or deleted in any tenant's data, on any
installation, ever.** The only table this procedure writes on production is `flyway_schema_history`.
**Related:** HD-176 / `V27` (whose header §"FOLDING INTO HD-188" is written *for* this procedure and whose
central warning §5.2 retires), the 2026-08-07 squash (`docs/project-state.md` §"Schema baseline"),
`docs/release-checklist.md` §"Releases carrying a destructive migration" (which already names the
pre-flight backup label `pre-hd188-squash`), `docs/ops-prod-hardening.md` §6.5 (whose restore-drill
container *is* the scratch database this procedure needs), ADR-0002 (Flyway owns the schema), ADR-0025
(the shape "a restored backup needs a replay step applied to it" — this procedure creates a second
instance of it).
**No new configuration property. No new environment variable. No profile gating.** DC and Cloud are
affected identically and by documentation only (§13).

---

## 0. The recommendation, first

**Do it, mechanically, in its own release, and split it in two halves that are separately checkable.**

1. **The baseline is cut by `pg_dump` from a database the chain built** — never hand-authored. The
   previous baseline was hand-authored and that is why `V27`'s header spends thirty lines warning that
   a squash will silently revert its colour alignment. A generated baseline retires that entire class of
   error, and the proof that it did is one of the four parity proofs rather than a reviewer's attention.
2. **Half A needs no production access at all** — generation, and three of the four proofs. It is
   destructive to nothing but the operator's own local database, and it can be run, thrown away and run
   again as often as you like.
3. **Half B needs production access and is a checklist with expected output at every step.** Exactly one
   step in it is a point of no return, and even that one has a prepared, tested, one-file rollback.
4. **Production is re-labelled, never migrated.** After the cutover its `flyway_schema_history` holds one
   row. Its schema is not touched, its data is not touched, and no DDL runs.
5. **Version numbers are never reused again.** The chain resumes at **`V28`**, not at `V2`. The last
   squash reused `V2..V12` and the cost is visible today: `CLAUDE.md` still says the `projects.issue_seq`
   resync was done "(V9)", and today's `V9` is `V9__components.sql`, which says nothing about `issue_seq`.
   A reader following that pointer finds a file that contradicts the sentence that sent them there.
6. **The retired chain is kept, verbatim, outside the classpath**, in `docs/db/retired-chain/`. It is
   ~3,900 lines of reasoning that exists nowhere else, and — decisively — it is the only performable
   upgrade path left for a self-hoster who is behind (§9.3).

**The highest-risk assumption in this whole procedure, stated once and plainly:** *that production's
schema is exactly what the chain produces.* Nothing in the product enforces that. It is assumed by every
migration ever written, it has never been checked, and after the cutover it becomes unfalsifiable —
production will be *labelled* as being the baseline whether or not it is. Proof 4 (§7.4) is the only
thing that tests it, it can only be run before the cutover, and it reduces the assumption to "identical
at the moment of the dump" rather than eliminating it. **If proof 4 is skipped, the cutover is a
guess.**

---

## 1. Problem, goal, and what is out of scope

### 1.1 Problem

`src/main/resources/db/migration` holds **27 files, 3,923 lines**; `V1__init_schema.sql` alone is 611 of
them (measured by the owner, 2026-09-04 — re-measure at execution time, because a number goes stale one
entry before its list does). Every new installation replays 27 files including four data backfills, two
row-deleting cleanups and one refusal, none of which can find anything to do on an empty database. Every
reader of the schema reconstructs it by reading 27 files in order. Every migration test builds its
fixture by asking Flyway for an intermediate version of a schema that no live database is ever in.

### 1.2 Goal

One `V1__init_schema.sql` that *is* the schema; a production database that keeps every byte of its data
and every column of its schema and merely stops claiming to have run 27 files; and a set of guards that
is no weaker afterwards than it is today (§10, §11) — this last part being the half that a squash
usually gets wrong, because deleting a test deletes its failure and nothing turns red.

### 1.3 Ship it alone, in its own release — and NOT inside 0.18.0

This is the largest correction this document makes to the plan, and it is worth the extra release.

The ticket's reasoning is "0.18.0 is the last release in which a squash is cheap, because after the
announcement production carries other people's work". That is right about *timing* and wrong about
*packaging*, for two reasons that only appear when you ask what an existing self-hoster does:

- **`V21`–`V27` have not shipped in any release yet.** If the squash lands inside 0.18.0, there is no
  released build anywhere that applies `V21`–`V27` to an existing database. A self-hoster on 0.17.x can
  then only recreate their database from nothing, and 0.18.0's own operator documentation — the
  "Coming from before 0.18.0, run one query before you pull" section, the invite-cleanup counts, the
  duplicate-address refusal — describes migrations that will never run for them. The release would ship
  a manual that contradicts itself.
- **If the squash is one release later, every upgrader lands on the perfect precondition.** 0.17.x →
  0.18.0 runs the full chain: every guard fires, every operator note is true, and the database ends at
  `V27` head. 0.18.0 → the squash release is then a *re-label* of a database that is already, exactly,
  the schema the baseline produces. Nobody loses data and nobody needs the retired files.

**Recommendation: cut 0.18.0 as planned; then cut the squash as the next release, containing this change
and no other, and in particular no second migration.** §8.3 shows why a co-shipped `V28` would make the
rollback dishonest. This is Decision **D1** in §15 — the owner may overrule it, and if they do, §9.3 is
the compensating control.

### 1.4 Out of scope / non-goals

- **Splitting the baseline into several files.** Rejected by the ticket, and the rejection stands: a
  multi-file baseline has to be ordered by hand, and hand-ordering is the failure mode the generation
  step exists to delete.
- **Changing the schema.** Not one column moves. If a diff shows a difference between the chain and the
  baseline, the *baseline* is wrong — never the chain, and never "while we are here".
- **Recovering the pre-2026-08-07 chain** (the original `V1..V12`) into `docs/db/retired-chain/`. It
  lives in git history only. Optional, cheap, and not part of this ticket.
- **Any change to how deploys, backups or the pipeline work.** The procedure uses what exists.

---

## 2. Corrections to the ticket, before anything is built on it

Each of these was measured against the tree at `b735390`, not recalled.

| The ticket says | Actually |
|---|---|
| "`V1` plus `V2..V20` — 20 files, 2,675 lines" | **27 files, 3,923 lines**; `V1` is 611. `V21`–`V27` all landed with the 0.18.0 mail, uniqueness, storage, occupancy and palette work. |
| the pending migrations are `V21`/`V22` | they are applied; the next free number is **`V28`**, and §5.3 recommends the chain resume there rather than at `V2`. |
| "the release's other migrations must land first" | **they have.** `V27__taxonomy_palette_alignment.sql` is the last. It was deliberately landed before this ticket so its corrected seed values fold into the baseline instead of arriving as an `UPDATE` after it. §7.2 is the check that this is what the chain now actually produces — and it is a *check*, not an assumption, because the property is only true of a baseline cut from a fully-migrated database. |
| "`CLAUDE.md` quotes comment text from `V1`, `V9` and `V19` by name" | `CLAUDE.md` names **`V9`** (the `issue_seq` resync), **`V12`** (the delivery-capability columns) and **`V18`** (the `sprint_scope_events` composite FK). It does **not** name `V1` or `V19`. The `V1`/`V9` quotations the ticket is thinking of are in **`docs/project-state.md` §"Schema baseline"**, which quotes `V1__init_schema.sql:355` and `V9__components.sql:79` with line numbers. §12 handles all of them, and the reference surface is much larger than three lines: **143 occurrences of a `V<n>__` filename across 63 files**, including production javadoc. |
| "two of the 0.18.0 migrations abort startup when they find data that violates the new uniqueness" | **one does.** `V23__users_email_uniqueness.sql` raises and aborts. `V22__invite_uniqueness.sql` does the opposite on purpose — it **deletes** the offending rows, because a withdrawn invitation is recreatable in two clicks and an account is not. The distinction is the whole of §9, and it changes what is lost: for `V23` a *diagnosis* is lost, for `V22` a *repair* is lost, and those need different sentences in the release note. |

One more thing the ticket does not mention and which would have bitten at run time: **`ops/loadtest/fixture/lib.sh` pins `LOAD_PINNED_FLYWAY_VERSION="24"` and refuses to generate against any other schema version.** After the squash, `max(version)` is `1` everywhere and the load-test fixture refuses on every environment, permanently, with a message about a schema it was not written for. It is a one-constant edit, and it is in the checklist (§12).

---

## 3. Actors, and the split by who can run what

There are two roles. They are defined by **capability, not by person** — if one person holds both, they
run both halves and the split still tells them which steps are safe to redo and which are not.

| | **Operator A — "this machine"** | **Operator B — "production"** |
|---|---|---|
| Needs | the repo, Docker, the local Postgres on 15432 | AWS SSM session to the EC2 instance, **owner** S3 credentials for the backup bucket, `/opt/hamstrack` |
| Owns | §6 (generate), §7.1–§7.3 (three of the four proofs), §10–§12 (tests, references) | §7.4 (the fourth proof), §8 (cutover + deploy), §8.4 (rollback) |
| Destroys | nothing but their own local `hamstrack` database and throwaway databases they create | nothing until the one step marked **POINT OF NO RETURN**, and that one has a prepared rollback |
| Can redo | everything, any number of times | everything up to the cutover; after it, see §8.4 |

**The one artefact that crosses the boundary is a schema-only dump of production.** `pg_dump
--schema-only` emits DDL and no rows: no addresses, no password hashes, no issue titles. It is therefore
safe to hand to Operator A, to attach to the ticket and to keep in a scratch directory — unlike the full
`daily/` dump, which is production PII in one file, is readable only with owner credentials, and never
leaves the drill container. **That single artefact is what makes proof 4 checkable by the person who
cannot produce it**: B restores and dumps, A diffs.

**Permissions and tenancy:** nothing in this change touches the application's authorization surface.
There is no endpoint, no role, no permission, no workspace scoping decision. The reason it appears in a
spec at all is that it rewrites the one table in the database that decides whether the application starts.

---

## 4. What is true of the result, stated as invariants

These are the sentences the acceptance criteria in §14 are derived from.

1. **A fresh install runs exactly one migration** and ends with a schema byte-identical to the one the
   27-file chain produced.
2. **Production's schema and data are not modified.** `flyway_schema_history` goes from 27 rows to 1;
   every other table has the same rows before and after, with the same values.
3. **No installation is silently broken.** Every failure mode of this change is a refusal to start with
   a message naming a version and a checksum. There is no path in which the application boots and serves
   a wrong schema.
4. **The set of guarded properties does not shrink.** Every assertion in the twelve migration tests
   either survives as a property of the baseline, is already asserted elsewhere, or is deliberately
   retired *with its subject* — and §10 says which, per class, with the evidence that replaces it.
5. **The baseline stays honest without anybody re-deriving it**, because a committed, human-readable
   schema projection is regenerated and diffed by a test on every build (§11).

---

## 5. The artefacts and the naming decisions

### 5.1 One file, generated, `V1__init_schema.sql`

Keep the name. Three reasons, in order of weight: the row already in production says
`script = 'V1__init_schema.sql'` and `description = 'init schema'`, and Flyway validates *both* of those
alongside the checksum — keeping the name means the cutover changes exactly one column instead of three;
a fresh install and the re-labelled production then agree on every column of the row rather than on a
subset; and "the first migration is the schema" is what a newcomer expects.

### 5.2 It is cut by `pg_dump`, not written

`V27`'s header says, correctly of the world as it stands: *"THE TRAP … THE BASELINE IS HAND-AUTHORED …
nothing mechanical will carry V27's outcome forward … a line copied across untouched silently reverts
this alignment for every new install while every already-migrated database keeps it: a divergence with
no error, no log line and no failing test, discovered by somebody comparing two screenshots."*

**This procedure makes that paragraph obsolete, and that is a deliberate objective rather than a side
effect.** A baseline dumped out of a database that has run `V27` carries `#667085`, `#F79009`, `#0EA5A4`
and the three column defaults because they are *what is in the database*, not because a person re-typed
them. The remaining risk moves from "did the author re-derive 40 literals correctly" to "was the
generating database really at head", which is one `SELECT` (§6.2, step 3).

### 5.3 The chain resumes at `V28`

Flyway does not require contiguous versions, and a gap costs nothing at runtime. What a gap buys is that
**a version number identifies one migration for ever**. Today it does not: `docs/project-state.md` has to
carry a whole paragraph headed "number-collision caution" explaining that the old `V6` was taxonomy and
the new `V6` is custom-field search indexes, and `CLAUDE.md`'s `issue_seq` gotcha points at a `V9` that
has meant `components` since August. A second reuse would make three generations of `V2`.

So: after this squash the next migration is `V28__…`, the one after that `V29__…`, and a future squash
takes the next free number for its own baseline or keeps `V1` and resumes above the previous high-water
mark. **Never reuse.** This is ADR-0033.

### 5.4 The retired files are kept, verbatim, at `docs/db/retired-chain/`

Copy all 27 files unchanged into `docs/db/retired-chain/`, with a `README.md` that says in its first
line that nothing there is ever executed by the application, and why they are kept:

- They hold reasoning that exists nowhere else — `V22`'s 111-line header on why the uniqueness predicate
  cannot mention expiry and why folding an address in place hands somebody else's mailbox a standing
  invitation; `V23`'s algebra proving its own gate makes the index-build failure unreachable; `V25`'s
  correction of two stale counts in `V21`. Some of it is distilled into ADRs and `docs/self-hosting.md`;
  most of it is not.
- **They are the upgrade path for anyone who is behind** (§9.3). This is the load-bearing reason.
- They are outside the classpath, so Flyway cannot see them: `docs/` is not a resource root. The
  guarantee is structural, not a convention, and §11's file-set assertion catches a stray copy back.

`git log` is not a substitute. Nobody greps deleted files, and the last squash proved it: its files are
in history and every reference to them in the docs is now either stale or points at a different file.

---

## 6. HALF A — generation. Runs on any machine. Destroys nothing.

Everything in §6 and §7.1–§7.3 can be thrown away and redone. Work in a scratch directory
(`.\squash\`, gitignored or outside the repo).

### 6.0 Two traps that ruin this half silently on Windows

- **PowerShell `>` writes UTF-16.** `docker exec … pg_dump … > file.sql` produces a UTF-16LE file with a
  BOM. As a baseline it changes the Flyway checksum, and as a diff input it makes every comparison
  garbage. **Always redirect inside the container and copy the file out:**
  `docker exec hamstrack-postgres sh -c "pg_dump … > /tmp/x.sql"` then `docker cp`.
- **`DB_URL` is ignored when you run the app locally.** `spring-boot-docker-compose` is on the
  classpath and overrides the datasource, so a run you believe is pointed at `chain_a` quietly migrates
  the compose database. Every boot below passes `--spring.docker.compose.enabled=false`. If you skip it,
  every proof in §7 compares a database with itself.

### 6.1 Prepare three databases

```powershell
docker start hamstrack-postgres
foreach ($db in "chain_a","chain_b","baseline") {
  docker exec hamstrack-postgres psql -U hamstrack -d postgres -c "DROP DATABASE IF EXISTS $db"
  docker exec hamstrack-postgres psql -U hamstrack -d postgres -c "CREATE DATABASE $db"
}
```

`chain_a` and `chain_b` are two independent runs of the existing 27-file chain. Two, not one, and §7.2
says why: the difference between them is the mechanical definition of "a value this schema does not
generate deterministically".

### 6.2 Run the chain into `chain_a` and `chain_b`

For each of the two, from the repo root, on the **unmodified** tree (the chain must still be all 27
files at this point):

```powershell
$env:DB_URL="jdbc:postgresql://localhost:15432/chain_a"   # then chain_b
$env:DB_USERNAME="hamstrack"; $env:DB_PASSWORD="hamstrack"
$env:JWT_SECRET="dev-only-jwt-secret-hamstrack-0123456789abcdef"
$env:SEED_ADMIN_EMAIL=""     # do NOT seed an admin — the generating database must hold seeds and nothing else
.\mvnw.cmd spring-boot:run --% -Dfrontend.skip=true -Dspring-boot.run.arguments="--spring.docker.compose.enabled=false --server.port=8091"
```

Stop it (Ctrl+C) as soon as `Started HamstrackApplication` appears.

**Expected in the log:** `Successfully applied 27 migrations to schema "public"`, then
`Current version of schema "public": 27`, then an `EntityManagerFactory` that initialises with no
schema-validation error.

**Then prove the generating database is at head and holds only seeds**, because everything downstream
inherits this:

```sql
SELECT count(*) AS files, max(version::int) AS head, bool_and(success) AS ok FROM flyway_schema_history;
-- expect: 27 | 27 | t
SELECT count(*) FROM users;       -- expect 0
SELECT count(*) FROM workspaces;  -- expect 0
SELECT name, color FROM statuses  WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL ORDER BY position;
-- expect exactly: To Do #667085 | In Progress #F79009 | Done #0EA5A4   ← V27 folded in
```

That last query is the ticket's "verify that is actually what the chain now produces". If it prints
`#6B7280` / `#3B82F6` / `#10B981`, `V27` did not run and everything after this point is wrong.

### 6.3 Cut the baseline

Two dumps out of `chain_a`, then one assembly.

```powershell
# (a) the schema — no owners, no privileges, no history table
docker exec hamstrack-postgres sh -c "pg_dump -U hamstrack -d chain_a --schema-only --no-owner --no-privileges -T flyway_schema_history > /tmp/schema.sql"

# (b) the seed rows, as column-named INSERTs, from the SAME database, restricted to the catalog tables
docker exec hamstrack-postgres sh -c "pg_dump -U hamstrack -d chain_a --data-only --column-inserts --no-owner \
  -t statuses -t priorities -t issue_types \
  -t workflows -t workflow_statuses \
  -t priority_sets -t priority_set_items \
  -t issue_type_sets -t issue_type_set_items \
  -t field_defs -t field_sets -t field_set_items \
  -t roles -t role_permissions > /tmp/seeds.sql"

docker cp hamstrack-postgres:/tmp/schema.sql .\squash\schema.sql
docker cp hamstrack-postgres:/tmp/seeds.sql  .\squash\seeds.sql
```

Those fourteen tables are the complete seed surface: they are the tables any migration in the chain ever
`INSERT`s into, minus `workspace_storage_usage` (whose only insert is a backfill over existing
workspaces and is empty on a fresh database). **Verify that list rather than trusting it** —
`Grep "INSERT INTO"` over `db/migration` and confirm the table set matches; if a future run of this
procedure finds a fifteenth, the seed dump must grow.

Then assemble `V1__init_schema.sql` as: a header comment (§6.4) + the schema section + a
`-- ==== seed data ====` divider + the seed section. And **post-process, in this order**:

1. **Strip `public.` qualification and the `SELECT pg_catalog.set_config('search_path', '', false);`
   line.** This is not cosmetic. Every migration in this repository is unqualified on purpose: it is
   what lets the migration tests point a whole Flyway run at a throwaway *schema* instead of needing
   `CREATE DATABASE`. A `public.`-qualified baseline silently writes into `public` from a run that
   believes it is isolated — which is the dangerous direction, because it does not fail, it pollutes.
   §7.3 has the positive check for this.
2. Delete `pg_dump`'s `SET` preamble (`statement_timeout`, `lock_timeout`, `idle_in_transaction…`,
   `client_encoding`, `standard_conforming_strings`, `xmloption`, `client_min_messages`,
   `row_security`). Flyway sets its own session; leaving them in means the baseline's checksum changes
   whenever the `pg_dump` version changes its preamble.
3. Leave everything else exactly as dumped, including `CREATE FUNCTION set_updated_at()`, all five
   `trg_*_updated_at` triggers, every `CHECK`, every partial index and its `WHERE` clause, and the
   `COMMENT`s. Do not reorder, do not reformat, do not "tidy". The next reader's ability to trust this
   file rests on it being machine output.

### 6.4 The header the baseline carries

Short, and it must not repeat the retired chain's content. Six lines: what this file is; that it is
generated by §6.3 of this document and regenerated the same way; the date and the chain version it was
cut from (`27`); that `docs/db/retired-chain/` holds the files it replaced; that the next migration is
`V28`; and that **editing this file by hand changes production's required checksum and will stop every
installation from starting** — with the pointer to §11, the test that notices.

### 6.5 Swap the chain out

Delete `V2`–`V27` from `src/main/resources/db/migration`, copy all 27 originals into
`docs/db/retired-chain/`, and put the new `V1__init_schema.sql` in place. Recreate the local development
database (`DROP DATABASE hamstrack; CREATE DATABASE hamstrack;`) — the old chain is applied there and
Flyway will refuse to start against it. **This is the only destructive act in Half A and it destroys
only local demo data.**

---

## 7. The four proofs

Three of them are Half A's. The fourth is the one that matters most and needs Half B for one step.

### 7.1 Proof 1 — the chain and the baseline produce the same schema

```powershell
# migrate `baseline` with the NEW tree (one file), exactly as in §6.2 but DB_URL=…/baseline
# expect: "Successfully applied 1 migration", "Current version of schema public: 1"

docker exec hamstrack-postgres sh -c "pg_dump -U hamstrack -d chain_a  --schema-only --no-owner --no-privileges -T flyway_schema_history > /tmp/a.sql"
docker exec hamstrack-postgres sh -c "pg_dump -U hamstrack -d baseline --schema-only --no-owner --no-privileges -T flyway_schema_history > /tmp/b.sql"
docker cp hamstrack-postgres:/tmp/a.sql .\squash\chain.schema.sql
docker cp hamstrack-postgres:/tmp/b.sql .\squash\baseline.schema.sql
git diff --no-index .\squash\chain.schema.sql .\squash\baseline.schema.sql
```

**Pass: empty output.** Not "no interesting differences" — *empty*. Both sides were produced by the same
`pg_dump` binary against the same server, so any difference is a real difference. The two systematic
non-differences to expect and not to be surprised by: dropped columns (`V15`'s three `role` columns)
leave a physical gap in `chain_a` that the dump does not emit, and constraint/index names are explicit
in both because the baseline carries the names it was dumped with.

**Evidence to record:** the command's exit status and the two file names, in the ticket.

### 7.2 Proof 2 — the seed rows are the same rows

Two comparisons, and the second exists because the first cannot see one class of error.

**(a) Strict, against the database the seeds were cut from.** For each of the fourteen tables, on
`chain_a` and on `baseline`:

```sql
SELECT 'statuses' AS t, count(*) AS rows,
       md5(string_agg(x::text, '|' ORDER BY x::text)) AS content
  FROM statuses x;
```

**Pass: identical `rows` and `content` for all fourteen.** This proves the extraction and re-insertion is
lossless — a dropped row, a mangled JSONB `config`, a `NULL` that became `''`, a `false` that became
`NULL` all show up here and nowhere else.

**(b) Loose, against an independently generated chain.** Compare `chain_a` with `chain_b` the same way
first. **Any table whose `content` differs between two fresh runs of the same chain contains a
non-deterministic value** — in practice the rows seeded with `gen_random_uuid()` (`workflow_statuses`,
`priority_set_items`, `field_set_items`, `issue_type_set_items`, the "Engineering fields" `field_sets`
row, the `V3` system fields). Derive that set mechanically from this diff instead of reading 27 files
for it; then repeat the `chain_b` ↔ `baseline` comparison with those id columns projected out
(compare the natural keys and every other column). **Pass: equal.**

**One consequence to record, because it is a real behaviour change and it is invisible in a diff:** the
baseline freezes those previously-random ids as literals, so from now on every fresh installation shares
them. That is an improvement (a catalog row can be named in a support conversation) and it is harmless
(nothing joins across installations), but it means **production's ids for those rows differ from a fresh
install's for ever** — which is fine, because production is re-labelled and never re-seeded, and is
exactly why proof 2 is run between two fresh databases and never against production.

### 7.3 Proof 3 — it boots, validates, and the suite passes

1. `.\mvnw.cmd test` with the local `hamstrack` database recreated (§6.5) and empty.
   **Expected: green** — including `HamstrackApplicationTests#contextLoads`, which is Flyway migrate →
   Hibernate `validate` → context up on a database that has only ever seen the baseline.
2. **The migration tests will not be green** until §10 is done. Every one of the twelve calls
   `Flyway.configure().target("<n>")`, and a target version that no longer resolves fails loudly rather
   than silently passing. That loudness is a feature: the squash cannot quietly delete a guard, because
   the build goes red until somebody decides what each guard becomes.
3. **The throwaway-schema check, which is the positive form of §6.3 step 1.** Run one of the surviving
   schema tests (they point Flyway at a schema like `v26_migration_test`) and assert two things
   afterwards: the throwaway schema holds the full table set, **and** `public` gained no object. A
   `public.`-qualified baseline passes the first and fails the second, which is why the second one has
   to be written down.
4. **A behaviour that currently only the dying tests assert:** the `updated_at` trigger and the
   `SET LOCAL hamstrack.skip_updated_at = 'on'` suppression. Confirmed covered independently by
   `IssueRankTest` and `AuditDateSearchTest`, both of which exercise the GUC against live rows. No new
   test needed — but confirm this by running those two, not by re-reading this sentence.

### 7.4 Proof 4 — production's schema really is the chain's schema

**This is the decisive one and it is the only one that can fail for a reason none of the others can see.**
It is split so that the half needing production access is three commands.

**B does this (needs the owner's S3 credentials; the drill container already exists — `docs/ops-prod-hardening.md` §6.5 was written anticipating this step and says so):**

```powershell
# 1. restore the newest daily dump into the throwaway PostgreSQL on 127.0.0.1:15433 —
#    the full §6.5 recipe, unchanged, including --exit-on-error and step 4b.
# 2. dump ITS schema only. No rows leave the container; this file contains no personal data.
docker exec hamstrack-restore-drill sh -c "pg_dump -U hamstrack -d hamstrack --schema-only --no-owner --no-privileges -T flyway_schema_history > /tmp/prod.sql"
docker cp hamstrack-restore-drill:/tmp/prod.sql .\squash\prod.schema.sql
# 3. record what the restored history says, and hand both to A.
docker exec hamstrack-restore-drill psql -U hamstrack -d hamstrack -c "SELECT count(*) files, max(version::int) head, bool_and(success) ok FROM flyway_schema_history"
```

**Expected from step 3: `27 | 27 | t`.** Anything else stops the procedure — a `success = false` row, or
a head below 27, means production is not where this baseline was cut from.

**A does this:**

```powershell
git diff --no-index .\squash\prod.schema.sql .\squash\baseline.schema.sql
```

**Pass: empty.** A non-empty diff is *the finding this whole proof exists for*: it means somebody applied
DDL to production outside the chain, and it must be resolved — by understanding the difference, deciding
whether the baseline or production is right, and either fixing the baseline or writing a `V28` that
brings production back — **before** the cutover. It must never be "tidied" by editing the baseline to
match production silently, because that bakes an undocumented divergence into every future installation.

Two honest limits, so nobody reads more into a pass than it contains:

- It proves the schemas were identical **at the moment of the dump**. Nothing prevents DDL between the
  dump and the cutover; nothing does DDL outside migrations today, and §8 stops the application before
  the cutover, which is as close as this gets to closing the gap.
- It compares **schema only**. Drift in production's *seed rows* — a hand-edited status colour — is
  invisible to it, and that is correct: production keeps its rows either way. The squash changes what
  new installations get, not what production has.

---

## 8. HALF B — the production checklist

Read the whole section before starting. Steps 1–5 destroy nothing and can be abandoned at any point.
Step 7 is the point of no return.

### 8.1 Preconditions (all four, checked, not assumed)

| # | Check | Where | Expected |
|---|---|---|---|
| P1 | Proofs 1, 2, 3 recorded green | the ticket | three explicit "pass" comments naming the artefacts |
| P2 | Proof 4 recorded green | the ticket | empty diff, and `27 \| 27 \| t` from the restored copy |
| P3 | The release contains this change and no migration | the diff | `db/migration` holds exactly `V1__init_schema.sql` |
| P4 | The baseline's checksum is known and was produced by the artefact that will run | §8.3 | one integer, recorded in the ticket |

### 8.2 Backups — two of them, and they are different objects

```bash
# 1. The permanent one. `manual/` has no lifecycle expiry and the instance can neither delete nor
#    overwrite it, so unlike the nightly daily/ copy it is still there in a month.
sudo BACKUP_S3_PREFIX=manual BACKUP_LABEL=pre-hd188-squash /usr/local/bin/hamstrack-backup
sudo journalctl -u hamstrack-backup -n 30 --no-pager
```
**Expected:** a completed run, and the object visible in `s3://<bucket>/manual/` with a `pre-hd188-squash`
label in its name. `docs/release-checklist.md` already prescribes exactly this command for "any migration
that rewrites `flyway_schema_history`" — this is the migration it was written for.

```bash
# 2. The rollback payload. Tiny, specific, and the only artefact that undoes step 7.
cd /opt/hamstrack
docker exec hamstrack-postgres-1 sh -c \
  "pg_dump -U hamstrack -d hamstrack --data-only --column-inserts -t flyway_schema_history > /tmp/history-rows.sql"
docker cp hamstrack-postgres-1:/tmp/history-rows.sql /opt/hamstrack/history-rows-pre-squash.sql
wc -l /opt/hamstrack/history-rows-pre-squash.sql
grep -c '^INSERT INTO' /opt/hamstrack/history-rows-pre-squash.sql
```
**Expected: 27 `INSERT` lines.** Take this **after** the application is stopped (§8.4 step 1) so nothing
can write between the dump and the cutover. Keep a copy off the box — paste it into the ticket; it is
27 lines and it is the difference between a two-minute rollback and a restore.

Also confirm today's EBS snapshot exists (`describe-snapshots`, §6.4 of the ops doc). Three
independent copies before touching anything is not paranoia here; it is the whole reason this can be
done at all.

### 8.3 Get the checksum from the artefact that will actually run

**This is the value that decides whether production starts.** It must be the checksum *the deployed
image's Flyway computes for the file inside that image* — not a number typed from a developer's machine
and hoped to match.

**Recommended path (seconds of downtime): build the image without deploying, using a pre-release tag.**
`docs/release-checklist.md` records that a tag containing `-` publishes its semver images and never
touches `latest` and never deploys.

```bash
# A pushes v0.19.0-rc1 on the release commit; CI publishes ghcr.io/zherikhov/hamstrack:0.19.0-rc1.
# B, on the box:
docker pull ghcr.io/zherikhov/hamstrack:0.19.0-rc1
docker exec hamstrack-postgres-1 psql -U hamstrack -d postgres -c "CREATE DATABASE squash_scratch"
docker run --rm --network <the compose network> \
  -e SPRING_PROFILES_ACTIVE=cloud \
  -e DB_URL=jdbc:postgresql://postgres:5432/squash_scratch \
  -e DB_USERNAME=hamstrack -e DB_PASSWORD=<from .env> \
  -e JWT_SECRET=<any 32+ byte throwaway> -e SEED_ADMIN_EMAIL= \
  ghcr.io/zherikhov/hamstrack:0.19.0-rc1
# stop it once "Started HamstrackApplication" appears, then:
docker exec hamstrack-postgres-1 psql -U hamstrack -d squash_scratch \
  -c "SELECT installed_rank, version, description, type, script, checksum, success FROM flyway_schema_history"
```
**Expected: exactly one row** — `1 | 1 | init schema | SQL | V1__init_schema.sql | <checksum> | t`.
Record all six values in the ticket. `description`, `type` and `script` must equal what production's row
already holds; only `checksum` is new. If `description` or `script` differs, the baseline was renamed and
§5.1's assumption is broken — stop and re-read.

Then drop the scratch database.

**Simpler path, if the extra tag is not wanted:** push the real tag, let the automatic deploy fail
(the new image will refuse to start against the un-relabelled history — see §8.6 for exactly what that
looks like), read the checksum from the crash log, do the cutover, re-run the deploy. It costs ~10
minutes of downtime instead of seconds, and it costs a red pipeline that looks like an incident. The
recommended path is worth the extra tag.

### 8.4 The cutover

```bash
# 1. Stop the application. This is deliberate downtime and it is what makes the window zero-risk:
#    nothing writes flyway_schema_history, and no restart can meet a half-changed state.
cd /opt/hamstrack && sudo docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml stop app

# 2. Take the rollback payload NOW (§8.2 item 2), with the app stopped.

# 3. Look at what is there, before changing it. On ONE line each, at a psql prompt
#    (docker exec, not docker compose exec — prod's compose file is not named compose.yaml).
docker exec -it hamstrack-postgres-1 psql -U hamstrack hamstrack
```
```sql
SELECT installed_rank, version, description, type, script, checksum, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;
```
**Expected: 27 rows, versions `1`…`27`, `success = t` on all of them.** If there is a 28th row with a
`NULL` version and `type = 'SCHEMA'`, that is Flyway's schema-creation marker; it is informational, the
statement below removes it too, and that is fine — but **note in the ticket that it was there**, because
the row counts in the acceptance criteria have to match what was actually seen.

```sql
-- 4. POINT OF NO RETURN. One transaction. Paste it as one line at a time and read each result.
BEGIN;
DELETE FROM flyway_schema_history WHERE version IS DISTINCT FROM '1';
UPDATE flyway_schema_history SET checksum = <THE NUMBER FROM §8.3>, script = 'V1__init_schema.sql', description = 'init schema', type = 'SQL', success = true WHERE version = '1';
SELECT installed_rank, version, description, type, script, checksum, success, installed_on FROM flyway_schema_history;
-- read it. One row. checksum equals §8.3. installed_on is still the ORIGINAL date — do not fake it,
-- the schema really was installed then.
COMMIT;
```
**`DELETE` must report `DELETE 26`** (or 27 with the schema marker). **`UPDATE` must report
`UPDATE 1`.** If either number is different, `ROLLBACK;` and stop.

Why hand-written SQL and not `flyway repair`: repair realigns checksums and marks missing migrations as
deleted rather than removing them, so it leaves 26 tombstone rows where the acceptance criterion asks for
one; it needs the Flyway CLI on the box; and it computes the checksum itself, which is precisely the
value this procedure wants to verify independently rather than trust.

### 8.5 Deploy

```bash
# recommended path: adopt the rc image, which is byte-identical to what the real tag will publish
sudo sed -i 's/^APP_IMAGE_TAG=.*/APP_IMAGE_TAG=0.19.0-rc1/' /opt/hamstrack/.env   # or append
cd /opt/hamstrack && sudo docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml up -d
```
Then push the real tag; the automatic deploy replaces it with the identical tree, and the pin is removed
(`--adopt-pin` semantics and the `DeployImagePinned` alert are covered in `docs/release-checklist.md`
§"Rolling back" — read it before pinning, because a forgotten pin turns every later deploy red on
purpose).

### 8.6 Verify — five checks, and each names its evidence

| # | Check | Command | Expected |
|---|---|---|---|
| V1 | Flyway validated one migration | `docker compose logs app \| grep -E "validated\|Current version\|up to date"` | `Successfully validated 1 migration`, `Current version of schema "public": 1`, `Schema public is up to date. No migration necessary.` |
| V2 | Hibernate validated the schema | same log | `EntityManagerFactory` initialised, no `SchemaManagementException`, `Started HamstrackApplication` |
| V3 | One history row | `SELECT count(*), version, checksum FROM flyway_schema_history GROUP BY 2,3` | `1`, `1`, the §8.3 checksum |
| V4 | No data moved | `SELECT count(*) FROM users; … workspaces; … projects; … issues;` | **identical to the numbers taken in §8.2**, allowing for traffic after restart |
| V5 | The site answers as itself | `curl -s https://hamstrack.com/api/meta` | the expected version string |

**Take V4's "before" numbers while the app is stopped**, from SQL rather than from Grafana — a gauge is
scraped at intervals and the acceptance criterion asks for equality, not for approximate equality.

**What a failure looks like, so it is recognised in one second rather than diagnosed:** a wrong checksum
produces `Migration checksum mismatch for migration version 1`, the container exits, and the fix is one
`UPDATE` with the right number followed by `up -d`. A forgotten cutover produces
`Validate failed: Detected applied migration not resolved locally: 2` (and 3, and 4…). Neither of them
changes any data; both are boot-time refusals, which is the property §4.3 promises.

---

## 9. What happens to the migrations that refuse (or repair) on bad data

### 9.1 The ticket's premise, corrected

Only **`V23`** refuses. It counts rows where `email <> lower(email)`, and if there are any it raises,
Flyway rolls the whole file back, and — because PostgreSQL writes the schema-history row inside that same
transaction — leaves nothing to repair. **`V22` does the opposite deliberately**: it *deletes* unaccepted
mixed-case invitations and then all but the newest per folded address, on the stated rule that a
migration may repair what its own application can recreate and must refuse what it cannot.

### 9.2 Folded into a baseline, both are correct to drop — and the reasoning is not "a fresh install has no data"

That reasoning is *nearly* right and it is worth stating precisely, because the precise version also
tells you what is lost.

- **The guarantee is not lost. It never was in the migration.** The invariant is
  `users_email_lower_uk UNIQUE (lower(email))` and `workspace_invites_pending_email_uk UNIQUE
  (workspace_id, lower(email)) WHERE accepted_at IS NULL`. Both are in the dumped baseline, by
  construction, and proof 1 is what says so. A fresh installation therefore cannot acquire the bad data
  at all: the *first* row wins the folded key and the second is refused with the 409 the application
  already translates.
- **`V23`'s pre-flight was never the enforcement. It was the *diagnosis*** — the thing that turns a bare
  `23505` naming an index into a message that names the row count, the collision groups, the two remedies
  and the order they must run in. Its own header says why that matters: since `V22` the datasource runs
  `logServerErrorDetail=false`, so PostgreSQL's `DETAIL` never reaches the log and a failed index build
  names the index and not the rows.
- **`V22`'s cleanup was the *repair*.** Dropping it drops the repair, not the rule.

### 9.3 What IS lost, and for whom — this is the part that changes the release note

Nothing is lost for a fresh installation. The loss lands on **an operator who restores existing data into
a baseline schema**, and it lands in exactly the place where the diagnosis used to be:

- Restoring a pre-0.18.0 `users` table into the baseline fails on `users_email_lower_uk` with a bare
  unique-violation naming the index, no `DETAIL`, and no remedy. `V23`'s message would have told them
  what to do; a `pg_restore` will not.
- The same for `workspace_invites` against the partial index — except that here the migration would have
  *fixed it for them*, so the operator now has to run `V22`'s two `DELETE` statements by hand, in order,
  and the order is load-bearing (`V22`'s header: step 1 below step 2 destroys a live invitation in favour
  of an unredeemable one).
- The same shape applies to every other data-shaping migration in the retired chain —
  `V20`'s `notifications.workspace_id` backfill, `V18`'s `started_at`, `V15`'s role translation,
  `V11`'s story-point promotion. A restore into the baseline does not run any of them.

**Two things follow, and they are both concrete:**

1. **`docs/db/retired-chain/` is not sentimental.** It is the remedy: "apply these seven files with
   `psql`, in order, then re-label" is a performable instruction, and it is the only one that keeps
   `V23`'s message reachable for somebody who needs it. A refusal — or a release note — may only
   prescribe an action its reader can perform.
2. **The recommended shipping order (§1.3) removes the need for that instruction almost entirely.** An
   operator who upgrades to 0.18.0 first has *already* run `V22` and `V23` under the old build; their
   database is clean by the time the squash release exists, and their path is a pure re-label with no
   data work at all. That is the strongest argument for not folding this into 0.18.0.

### 9.4 The release-note wording this implies

The ticket asks for "a self-hoster must recreate their database, and this is the last time we will ask".
Half of that is not true and the other half can be made a better promise:

> **This release replaces the 27 database migrations with a single baseline.** A **new** installation is
> unaffected. An **existing** installation cannot upgrade by pulling: the application will refuse to
> start, because its history names 26 migrations that no longer exist. Two paths, and the first is the
> one to take:
>
> 1. **Upgrade to 0.18.0 first** (which applies the full chain), then move to this release and run the
>    four statements in [Re-labelling an existing database](self-hosting.md#…). Nothing is deleted, no
>    data is touched, and the whole operation is a rewrite of one bookkeeping table. This is exactly what
>    we did to our own hosted instance, with the same statements.
> 2. **Or recreate the database** from empty and start again. Faster, and it costs you everything in it.
>
> **This is the last release that will make you choose.** From now on a squash is a documented
> re-label: the procedure above is permanent, and any future baseline will ship with the same four
> statements.

Whether path 1 is published at all is Decision **D2** (§15). Recommendation: publish it, gated on one
precondition query, because we will have executed it ourselves before we ask anyone else to.

---

## 10. The twelve migration tests, decided one by one

**They will all fail the moment the chain is deleted**, loudly, at the first `Flyway…target("<n>")` call
— a target version that does not resolve is an error, not a silent no-op. That is the property that
makes this section possible: a squash *cannot* quietly delete these guards, but it can very easily
delete them noisily and call it cleanup. It must not.

Three verdicts are used. **KEEP** means the subject is the schema, so the test survives with its
scaffolding re-pointed at head (`Flyway.configure()…` with **no** `.target(…)`, and the "sanity: the
column is absent" pre-assertions deleted). **CONVERT** means part of it is a property of the baseline and
the rest died with the migration. **RETIRE** means its subject no longer exists — and every RETIRE names
the evidence that takes its place.

| Class | Verdict | What happens |
|---|---|---|
| `V11StoryPointsMigrationTest` | **RETIRE** | Its subject is the promotion of `story_points` custom-field values into the native column and the deletion of exactly the rows it copied. No installation can be in the pre-state again. **Replaced by:** the baseline seed assertion that the global `story_points` and `sprint` placeholders exist **and are archived** (if they were live, `FieldResolver` would resolve those keys again — that is the live consequence the test was incidentally protecting), plus the `issues.story_points` `CHECK` in §11's projection. Before deleting, confirm a behavioural test exercises the 0–999/scale-2 bound; if none does, that one assertion moves to `IssueService`'s tests rather than vanishing. |
| `V12DeliveryCapabilitiesMigrationTest` | **CONVERT** | The §7 upgrade policy ("an upgrade never takes away a capability") is gone with the upgrade. **Case 4 survives and is load-bearing:** the column defaults for `releases_enabled` / `estimation_enabled` are `FALSE` — the lean new-project policy that `CLAUDE.md` calls out and that a "tidy-up" would invert. Becomes `DeliveryCapabilityDefaultsTest`, reading `information_schema.columns.column_default`. |
| `V15RoleBackfillMigrationTest` | **RETIRE** | Rules 1–6 are all about translating legacy `role` strings that no schema has held since `V15`. Rule 7 — the seven built-in templates and their exact permission sets — is **already asserted at head** by `RoleIdsMatchMigrationTest` (`everyBuiltInConstantNamesTheRowV13Seeded`, `thereAreExactlyEightBuiltInRolesAndNothingElseIsGlobal`, `everyBuiltInGrantsExactlyTheSetSectionSevenDeclares`) and `BuiltInRoleSeedParityTest`. **Evidence replaced by:** those, plus §11's seed projection. Rename `RoleIdsMatchMigrationTest` → `BuiltInRoleSeedTest` and de-number its prose (it currently says "the row V13 seeded"). |
| `V18ReportsFoundationsMigrationTest` | **RETIRE** | An approximate, name-keyed `started_at` backfill with five deliberate blind spots. Nothing recreates the pre-state. **Replaced by:** §11's projection for the column, the `sprint_scope_events` composite FK and its `ON DELETE SET NULL` (the `CLAUDE.md` gotcha), and — importantly — the *report* tests that assert `missingStartCount` behaviour on NULL starts, which are the live half of what this file protected. Confirm those exist before deleting. |
| `V19IssuesTaxonomyFkTest` | **KEEP** (4/4) | Every one of its four cases is an assertion about the schema as it stands — the constraints exist, are validated and are `NO ACTION`; a stranded reference is refused; a project delete cascades its own scoped taxonomy; a project delete aborts only on a foreign reference. Re-point to head, rename `IssuesTaxonomyFkTest`. |
| `V20NotificationsWorkspaceScopeTest` | **CONVERT** (1/2) | `theBackfillAttributesEachRowToTheWorkspaceItsLinkNames` retires with the backfill. `theForeignKeyCascadesFromWorkspacesAndTakesNothingElse` is a live schema property — **keep**, re-point. Rename `NotificationsWorkspaceScopeTest`. |
| `V22InviteUniquenessMigrationTest` | **CONVERT** (partial) | The cleanup half retires (§9). The index half — "the index refuses the rest", i.e. one standing offer per folded address per workspace, and an accepted row keeps no slot — is a live invariant and must stay, alongside the existing `DUPLICATE_INVITE` 409 test. Rename `InviteUniquenessIndexTest`. |
| `V23EmailUniquenessMigrationTest` | **CONVERT** (1/4) | `aCleanDatabaseTakesTheIndexInSilenceAndKeepsBothJobs` survives: **both** `users_email_key` and `users_email_lower_uk` exist, and the reason there are two is in ADR-0016. The three refusal cases retire with the pre-flight — and they are the ones §9.3 says leave a documentation debt rather than a code one. Rename `EmailUniquenessIndexTest`. |
| `V25MailAnonymousIndexTest` | **KEEP** | Asserts the partial index the concentration gauge reads through, and that the gauge's query uses it. Both are properties of head. Re-point, rename `MailAnonymousIndexTest`. |
| `V26StorageQuotaMigrationTest` | **KEEP** (3/4) | The backfill case retires. The other three — the composite cascade, the index and the trigger on both mutable columns; a deleted project taking its bytes with it; a size-only update moving the counter by the delta — are the ADR-0026 invariants and are the only tests that prove a DB-maintained counter follows `ON DELETE CASCADE`. Re-point, rename `StorageQuotaSchemaTest`. |
| `V27TaxonomyPaletteMigrationTest` | **CONVERT** (1/5) | `everyGlobalSeedAndAllThreeDefaultsLandOnTheDeclaredPalette` becomes a **baseline** assertion and is the direct answer to the ticket's "verify that is actually what the chain now produces" — the same statement is proof and guard. The four "must not touch a tenant's row" cases retire with the `UPDATE`s they guard. Rename `TaxonomyPaletteBaselineTest`. |
| *(twelfth)* | — | The ticket says "a dozen"; there are **eleven** classes in `com.hamstrack.migration`. `RoleIdsMatchMigrationTest` lives in `com.hamstrack.workspace`, already runs at head, and is the twelfth by name — it needs prose de-numbering only. |

**Two structural changes that go with the table.** The package `com.hamstrack.migration` should be
renamed **`com.hamstrack.schema`** — a package called `migration` that contains no migration test is a
lie, and the surviving tests are schema tests. And every surviving class loses its `V<n>` prefix: a class
named after a file that no longer exists is the same stale-member reference §12 is about.

**Net effect, stated so a reviewer can check it rather than trust it:** 11 classes and 23 test methods
go in; 8 classes and 13 methods come out; 10 methods retire with their subject; and every retirement
above names either an existing test or a §11 projection entry that carries the property forward. **A
reviewer's job on this ticket is to disagree with that table, line by line.**

---

## 11. What keeps the baseline honest afterwards

Today the chain is self-verifying by construction: run it and you get the schema. Afterwards the schema
is a 4,000-line file nobody re-derives, and the question "what catches a hand-edit to it" has to be
answered with something other than hope.

### 11.1 `ddl-auto=validate` is not enough, and the reason is measured

`CLAUDE.md` and `V24`'s header both record it, verified against hibernate-core 7.4.1 and 7.2.12:
**Hibernate's validator compares JDBC type codes and not column lengths.** `hasMatchingLength` exists and
is reached only from `StandardTableMigrator`, i.e. from `ddl-auto=update`. So `VARCHAR(50)` in the
database against `@Column(length = 100)` on the entity **boots perfectly clean and fails at INSERT** —
which is exactly the defect `V24` shipped to fix.

The full list of what `validate` does **not** see, and every item on it is something the baseline could
lose to a hand-edit: column **lengths** and numeric precision/scale; **defaults**; **CHECK** constraints;
**UNIQUE** constraints and every **index**, including a partial index's `WHERE` predicate; **foreign keys**
and their `ON DELETE` actions; **triggers** and functions; **seed rows**; and any extra table or column
(validate ignores what no entity maps).

### 11.2 The answer: a committed schema projection, diffed by a test

Add one test — `SchemaBaselineProjectionTest` — that queries `information_schema` and `pg_catalog` for a
canonical, deterministically ordered projection of the migrated database and compares it to a checked-in
file, `src/test/resources/db/schema-projection.txt`:

- every column: table, name, ordinal, type, `character_maximum_length`, numeric precision/scale,
  `is_nullable`, `column_default`;
- every constraint: name, type, and for CHECKs the normalised clause;
- every index: `pg_indexes.indexdef` verbatim (this carries partial predicates and expression indexes,
  which is where `users_email_lower_uk` and `workspace_invites_pending_email_uk` live);
- every foreign key with its update/delete action;
- every trigger with its function;
- for each of the fourteen catalog tables: the row count and an ordered content hash.

**Store the projection, not a hash of it.** A hash tells a reviewer that something changed; the
projection makes `git diff` *be* the schema diff, so the reviewer sees `character_maximum_length: 50 →
100` and decides. A failing run prints the diff and the one command that regenerates the file, so the
mechanism is a snapshot test and the deliberate act is a reviewed change to a committed file.

Two further guards, both one-liners:

- **The file set.** Assert that `src/main/resources/db/migration` contains exactly the expected names —
  today `V1__init_schema.sql` alone. It catches a retired file copied back onto the classpath, and it
  makes adding `V28` a deliberate edit.
- **The entity/column width parity assertion** already precedented by `IssueHistoryFieldWidthTest` is
  subsumed by the projection above: the widths are in it, so a drift between `@Column(length = n)` and the
  column shows up as a projection diff on the next build rather than as a 500 at INSERT.

### 11.3 What still is not caught, said out loud

A hand-edit to the baseline that changes nothing observable in the projection — a comment, whitespace,
reordering two `CREATE INDEX` statements — is not caught by §11.2 and does not need to be. It *is* caught
by Flyway: the checksum changes, and every already-installed database refuses to start. **That refusal is
the reason §6.4's header must say so in plain words**: the file is not editable, it is regenerable, and
the two are different verbs.

---

## 12. Collateral: every reference to a file that will not exist

`Grep "V\d+__"` finds **143 occurrences across 63 files**, and that grep misses the bare forms (`(V9)`,
`V12`, `since V20`). The rule for fixing them is the one `CLAUDE.md` already states about stale claims:
**a claim phrased about a category outlives a claim phrased about a member.** So the fix is almost never
"renumber"; it is "name the guarantee".

| Where | What to do |
|---|---|
| `CLAUDE.md` — `issue_seq` gotcha "(V9)", capabilities "(V12)", `sprint_scope_events` "(V18)" | **De-number.** "repaired once by a migration that resynced `issue_seq` to `MAX(number)`; the baseline carries the corrected column" — the lesson is the `updatable = false` rule, not the file. Note that today's "(V9)" already points at `V9__components.sql`, which is about components: the last squash's number reuse made that reference *wrong*, not merely stale, which is §5.3's evidence. |
| `docs/project-state.md` §"Schema baseline" | **Rewrite whole** (the ticket asks for this). It must state: the chain squashed on 2026-09-xx from 27 files; the baseline is generated, not hand-authored, and how; production was re-labelled and not migrated; the chain resumes at `V28` and numbers are never reused; the retired files are at `docs/db/retired-chain/`. Its current quotations of `V1:355` and `V9:79` become historical prose with no line numbers. Its "number-collision caution" paragraph gets a third generation to explain — or, better, is replaced by §5.3's rule. |
| `docs/release-checklist.md` (10 filename references) | The `V19`/`V20`/`V15` operator paragraphs describe releases that shipped; keep them as history but mark the files as retired and point at `docs/db/retired-chain/`. The "Constraints on a populated table" inventory table is a **live** reference list for a future rolling deploy — keep every row, re-point the `migration` column at the retired path. |
| `docs/self-hosting.md` | Add the re-label section (§9.4, Decision D2). The existing 0.18.0 upgrade paragraphs stay true *if* §1.3 is followed, and become misleading if it is not. |
| Production javadoc — `EmailUniqueness`, `User`, `SprintService`, `VersionService`, `LabelService`, `ComponentService`, `MailSendEvent`, `GlobalExceptionHandler`, `PermissionConverter`, `RolePermissionCache`, `AdminCatalogService`, `DefaultRoleCeilingException` | De-number in place. Each names a migration to explain *why a rule exists*; the rule is what the next reader needs. Where the reasoning is long and worth keeping, point at the ADR or at `docs/db/retired-chain/<file>`. |
| Tests — `CatalogDeleteGuardsStayUnscopedTest`, `AdminCatalogDeleteWithRemapTest`, `WorkspaceMemberManagementTest`, `ReferencedRowConflictContractTest`, `RoleIdsMatchMigrationTest`, `IssueHistoryFieldWidthTest`, `AuditDateSearchTest` | Prose only. |
| `ops/loadtest/fixture/lib.sh` — `LOAD_PINNED_FLYWAY_VERSION="24"` | **Functional, not prose.** Set it to the post-squash head (`1`), and update the comment: the pin still names the exact schema the generator was written for, and it will move again when `V28` lands. Without this the load-test fixture refuses on every environment for ever. |
| `ops/loadtest/capture/fingerprint.sh`, `ops/loadtest/RESULTS-2026-08-31.md` | The first captures `max(version)` — no change needed, its recorded value simply becomes `1`. The second is a dated record; leave it. |
| `.claude/agents/migration-reviewer.md`, `docs/hql-search-maintainers-guide.md`, ~20 files in `docs/design/` | Design proposals are dated records of decisions — **do not rewrite them**. Add one line to `docs/db/retired-chain/README.md` saying that a `V<n>` named anywhere in `docs/design/` before 2026-09 refers to a file in that directory. That is one sentence instead of a hundred edits, and it is the durable form. |
| `docs/ops-prod-hardening.md` §6.5 + §6.6 | **Add the consequence nobody will think of later:** every backup taken *before* the cutover restores into a database whose history has 27 rows, so restoring one under a squashed image requires re-applying the cutover to the restored copy. The `daily/` copies age out in 30 days; the `manual/pre-hd188-squash` copy **never does**, so this note is permanent. It is the same shape as ADR-0025 ("a restored backup needs a replay step"), and the four statements belong next to the drill recipe. |

---

## 13. DC / Cloud

**No behavioural difference, no profile gate, no new variable.** Both modes run the same Flyway on the
same schema; the squash changes the number of files, not what any of them do. `dc-cloud-guard` has
nothing to wire: no property, no compose change, no `.env.prod.example` row, no README row.

The only asymmetry is in *documentation*, and it is real:

- **Cloud** is one instance we control. Its upgrade is §8, executed by us, once.
- **DC** is an unknown number of instances we do not control. Their upgrade is §9.4, and it is the only
  half of this change that can hurt somebody who did not choose it. That is why §1.3 (ship after 0.18.0)
  and D2 (publish the re-label) are the two decisions that matter most in this document, and why both are
  about self-hosters rather than about us.

---

## 14. Acceptance criteria, each with the artefact that produces it

**Generation**

1. `src/main/resources/db/migration` contains exactly one file, `V1__init_schema.sql`. — *evidence:* the
   file-set assertion in §11.2, and `git status`.
2. That file is machine-generated per §6.3, is unqualified (no `public.`), and carries the §6.4 header.
   — *evidence:* `Grep "public\."` over the file returns nothing; the header is present.
3. All 27 retired files exist verbatim at `docs/db/retired-chain/` with a `README.md`. — *evidence:*
   `git diff --no-index` between each retired copy and its pre-squash original is empty.

**The four proofs**

4. **Proof 1:** `git diff --no-index chain.schema.sql baseline.schema.sql` is empty. — *evidence:* the two
   files and the command's exit code, recorded on the ticket.
5. **Proof 2a:** all fourteen catalog tables have equal row counts and equal ordered content hashes
   between `chain_a` and `baseline`. — *evidence:* the query output, both sides, pasted.
6. **Proof 2b:** `chain_a` ↔ `chain_b` differences are confined to the enumerated non-deterministic id
   columns, and `chain_b` ↔ `baseline` is equal with those projected out. — *evidence:* the enumerated
   list, derived from the diff rather than from reading migrations.
7. **Proof 2c (the `V27` check the ticket asks for):** the three global statuses read `#667085` /
   `#F79009` / `#0EA5A4`, the four priorities and four issue types read their `DESIGN.md` hues, and all
   three `color` column defaults read `#667085` — **in the baseline**, not only in the chain. — *evidence:*
   `TaxonomyPaletteBaselineTest`, which is the converted `V27` case and therefore is both the proof and
   the permanent guard.
8. **Proof 3:** a fresh database migrates with `Successfully applied 1 migration`, Hibernate `validate`
   passes, `contextLoads` passes, and `mvnw test` is green. — *evidence:* the build log.
9. **Proof 3b:** a Flyway run pointed at a throwaway schema leaves `public` with no new object. —
   *evidence:* the assertion added in §7.3 step 3.
10. **Proof 4:** `git diff --no-index prod.schema.sql baseline.schema.sql` is empty, and the restored
    production copy reported `27 | 27 | t`. — *evidence:* the schema-only dump (attachable to the ticket —
    it contains no personal data) and the diff exit code.

**Tests**

11. Every verdict in §10's table is executed, and the resulting classes live in `com.hamstrack.schema`
    with no `V<n>` in any class name. — *evidence:* the diff, reviewed against the table.
12. No test asserts against a `Flyway…target(<n>)` any more. — *evidence:* `Grep "\.target\("` over
    `src/test` returns nothing.
13. `SchemaBaselineProjectionTest` exists, its committed projection covers columns (with lengths),
    constraints, indexes (with predicates), foreign keys (with actions), triggers and the fourteen seed
    tables, and it fails with a readable diff when the baseline is edited. — *evidence:* a deliberate
    one-character edit to the baseline turns it red, and the failure message names the changed line.

**Production**

14. The `manual/pre-hd188-squash` backup object exists in S3 and the 27-row `history-rows-pre-squash.sql`
    is on the box **and** on the ticket. — *evidence:* `aws s3 ls`, and `grep -c '^INSERT INTO'` = 27.
15. After the cutover, `flyway_schema_history` holds exactly one row: version `1`, script
    `V1__init_schema.sql`, description `init schema`, type `SQL`, `success = true`, checksum equal to the
    value read in §8.3, and `installed_on` unchanged from its original value. — *evidence:* the `SELECT`
    from §8.4 step 4, pasted before and after.
16. Production boots: `Successfully validated 1 migration`, `Schema public is up to date`, Hibernate
    `validate` clean, `Started HamstrackApplication`. — *evidence:* `docker compose logs app`.
17. `users`, `workspaces`, `projects`, `issues` row counts are identical before and after, taken by SQL
    with the application stopped. — *evidence:* both `SELECT`s pasted on the ticket.
18. `curl https://hamstrack.com/api/meta` answers the expected version. — *evidence:* the output.

**Documentation**

19. `docs/project-state.md` §"Schema baseline" is rewritten per §12. — *evidence:* the diff.
20. Every functional reference in §12's table is fixed, and in particular
    `LOAD_PINNED_FLYWAY_VERSION` no longer says `24`. — *evidence:* the load-test fixture runs its
    version guard successfully against a post-squash database.
21. The release body carries the §9.4 text, and `docs/self-hosting.md` carries whichever upgrade path
    D2 settles on. — *evidence:* the rendered Release page and the rendered manual, with every anchor
    clicked (`docs/release-checklist.md` §"Releases that change how a stored value is derived" step 2.1
    exists because an anchor was once swallowed by lazy continuation in a callout).
22. Both ADRs (§16) exist as `Proposed` with a row each in `docs/adr/README.md`. — *evidence:* the files.

---

## 15. Open questions — owner decisions, with a recommendation each

> **Owner decisions taken 2026-09-04. D1 and D5 are answered; D2, D3, D4 and D6 are still open.**
>
> **D1 — the squash is NOT in 0.18.0. It gets its own 0.19.0, carrying nothing else.** Accepted as
> recommended, and the reason is the one that changes who the procedure serves: `V21`–`V27` have never
> shipped, so a self-hoster on 0.17.x has no build that applies them. Cutting 0.18.0 as an ordinary
> chain lands *every* upgrader on exactly `V1..V27` — at which point the re-label recipe in §8 works
> for them as well as for us, instead of the release note reading "recreate your database". It also
> keeps the rollback claim honest: a release carrying only the squash can be undone by restoring one
> metadata table, which stops being true the moment six other tickets ride along.
>
> **D5 — the simple path.** The checksum is read from a local clean database and the history is
> re-labelled in one transaction, accepting a few minutes of red pipeline rather than buying a
> pre-release tag. Chosen deliberately over the rc-tag path: the authoritative-checksum argument is
> real, but proof 4 already establishes that the baseline reproduces production, and a procedure that
> needs a tag, a build and a window is a procedure more likely to be executed under time pressure
> with a step skipped.
>
> Consequence for this document: HALF B is not executed in the 0.18.0 cycle. HALF A stays runnable
> now, and its output is what 0.19.0 opens with.

**D1 — Which release carries the squash?** *Recommendation: not 0.18.0. Cut 0.18.0 as planned, then cut
the squash as the next release with no other migration and ideally no other change.* It gives every
existing self-hoster a clean two-step path, keeps 0.18.0's operator manual truthful, and makes the
rollback story in §8.4 honest (a co-shipped `V28` would be applied after the cutover, so restoring the
history dump would erase the record of a migration whose DDL is still applied). Cost: one extra release.

**D2 — Do we publish the re-label recipe to self-hosters, or only "recreate your database"?**
*Recommendation: publish it, as the primary path, gated on one precondition query* —
`SELECT count(*), max(version::int), bool_and(success) FROM flyway_schema_history` must return
`27 | 27 | t`, which is a cheap, decisive gate, and a database that is not at head fails afterwards at
*boot*, loudly, under `ddl-auto=validate` rather than silently. We will have executed the same four
statements on our own production before asking anyone else to. The counter-argument is a support
commitment on somebody else's database, and it is the owner's to weigh.

**D3 — Does "this is the last time we will ask" get published?** *Recommendation: publish the narrower,
keepable version:* "the last time we will ask you to **recreate**; from now on a squash is a documented
re-label". The unqualified promise cannot be kept without promising never to squash again.

**D4 — Do the retired files stay in the repository for ever, or for one release?** *Recommendation: for
ever.* ~3,900 lines of prose in `docs/` costs nothing at build time, and D2's remedy depends on them.
The owner may prefer to delete them after two releases, once nobody plausibly upgrades from 0.17.x.

**D5 — The pre-release-tag path in §8.3 (seconds of downtime, one extra tag) versus the simple path
(~10 minutes of downtime and a deliberately red pipeline).** *Recommendation: the rc path*, because it
also makes the checksum come from the exact artefact that will run rather than from a developer's
machine. If the owner prefers fewer moving parts, the simple path is correct and its failure mode is
documented in §8.6.

**D6 — Freezing the previously-random seed ids as literals** (§7.2). *Recommendation: accept it* — it is
an inherent consequence of a generated baseline, it is a small improvement, and the alternative
(post-processing the dump to restore `gen_random_uuid()` calls) reintroduces hand editing into the one
step whose whole value is that it is mechanical. Flagged because it is a real, permanent behaviour change
that no diff will show.

---

## 16. Architectural decisions

Two, and both are the kind a future contributor will ask "why?" about while looking straight at the
evidence — one at a production database whose history no chain in git can reproduce, the other at a
migration chain that jumps from 1 to 28.

1. **The baseline is cut mechanically from a live schema, and production is re-labelled rather than
   migrated.** Chosen over: hand-authoring the baseline (rejected — it is what `V27`'s header spends
   thirty lines warning about, and the warning was earned); dropping and recreating production (rejected
   — it was the 2026-08-07 answer and it expired the day production carried a real backlog); `flyway
   repair` (rejected — leaves 26 tombstone rows, needs the CLI on the box, and computes the very number
   we want to verify independently). Trade-off: production's schema history stops being reproducible from
   the repository, so the assumption "production is what the chain produced" becomes unfalsifiable after
   the cutover and has to be *proved before it*, once, by proof 4. → **ADR-0032**.
2. **Flyway version numbers are never reused; after a squash the chain resumes above the previous
   high-water mark.** Chosen over: resuming at `V2` as the 2026-08-07 squash did (rejected — it is why
   `CLAUDE.md`'s `issue_seq` pointer resolves to a file about components, and why
   `docs/project-state.md` carries a paragraph disambiguating two generations of `V2..V12`); numbering
   the baseline itself with the next free number instead of `V1` (rejected — it would make the cutover
   rewrite three columns instead of one and would leave "the baseline" as a number a reader has to look
   up). Trade-off: the version sequence has a visible gap that has to be explained once, in the baseline
   header, and `V1` remains the one number that has meant three different files. → **ADR-0033**.
