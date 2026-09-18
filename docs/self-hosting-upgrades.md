# Self-hosting — release upgrade notes

One section per behaviour that changed in a released version, for an operator who is moving
an **existing** install forward. A first-time installer needs none of it: a fresh install
gets every one of these behaviours already in place, which is why these notes were moved out
of [the self-hosting guide](self-hosting.md) — they were about half of it, and a reader
installing for the first time had to recognise 1200 lines as not applying to them.

The guide keeps everything that is true whatever version you are on: requirements, the quick
start, the configuration reference, TLS, email, storage, backups and troubleshooting. It
also keeps the mechanics of upgrading itself — which tag to pin, how to apply repository
configuration, and why downgrades are not supported. **Start there**; come here when an
upgrade note applies to the hop you are making.

## Contents

**0.17.0**

- [Notifications are scoped to a workspace from 0.17.0](#notifications-are-scoped-to-a-workspace-from-0170)
- [Statements are bounded from 0.17.0](#statements-are-bounded-from-0170)
- [The heap is bounded from 0.17.0](#the-heap-is-bounded-from-0170)

**0.18.0**

- [Connection acquisition is bounded from 0.18.0](#connection-acquisition-is-bounded-from-0180)
- [PostgreSQL is bounded and tuned from 0.18.0](#postgresql-is-bounded-and-tuned-from-0180)
- [Free text is bounded from 0.18.0](#free-text-is-bounded-from-0180)
- [Attachment storage is capped per workspace from 0.18.0](#attachment-storage-is-capped-per-workspace-from-0180)
- [Expensive reads are bounded by CONCURRENCY from 0.18.0](#expensive-reads-are-bounded-by-concurrency-from-0180)
- [Shadowed custom field keys from 0.18.0](#shadowed-custom-field-keys-from-0180)

**0.18.2**

- [The deploy reads itself back from 0.18.2](#the-deploy-reads-itself-back-from-0182)

**0.18.0 — the address fold, and its fallout**

- [Account addresses become case-insensitive in 0.18.0 (one query, before you pull)](#account-addresses-become-case-insensitive-in-0180-one-query-before-you-pull)
- [Duplicate accounts after an upgrade (locale-dependent email folding)](#duplicate-accounts-after-an-upgrade-locale-dependent-email-folding)

### The deploy reads itself back from 0.18.2

**Nothing here changes what your stack runs, and one thing changes when your upgrade stops.**
From 0.18.2 `ops/deploy/apply-config.sh` ends with a **verify** step: after `up -d` it reads
the running box back and refuses if what is running disagrees with what it just applied. A run
that used to end at `deploy complete` can now end at `VERIFY FAILED` — the files are already
placed and the containers already up, nothing is rolled back, and the refusal names both the
finding and the two things you can do about it. That is the intended trade: a deploy that lied
quietly is worse than one that stops loudly. If you upgrade with `git pull && docker compose
up -d` and never run the applier, none of this reaches you.

**Three things to know before the first run.**

- **A ceiling that resolves to `0` is now a refusal, not a warning** — for **every**
  `*_MEMORY_LIMIT` variable, not one of them. `0` means *unlimited* to Docker, not "use the
  default", so `APP_MEMORY_LIMIT=0`, `POSTGRES_MEMORY_LIMIT=0` and `CADDY_MEMORY_LIMIT=0` have
  each always run that container unbounded — they simply said nothing. Measured on Compose
  v5.1.0 against the deployed set: with none of them set, all ten services carry a `mem_limit`
  in the resolved model; `APP_MEMORY_LIMIT=0` leaves nine, and the two others together leave
  eight. The verify step iterates every service Compose declares, so any of the three refuses.
  Check before you deploy with `docker compose config | grep -B2 mem_limit`; the remedy is a
  size, or removing the override.
- **Narrowing `COMPOSE_FILES` cannot clear a finding.** A check that did not read your box may
  lower confidence and never raise it, so a run that *skips* a check an earlier run read as
  failing republishes that `0` and refuses, saying so. Re-read with the compose set that
  declares the check, or fix what the earlier run found.
- **The summary line says how much was read.** `verify: PASS ran=5/5` means every declared check
  looked at your box; `verify: PARTIAL ran=3/5 skipped=grafana,drift-fresh` means two of them had
  nothing to look at here. Read the first word, not the exit code alone.

**If you provision the bundled Grafana alerting**, this release also adds the rule
**`DeployVerifyFailed`** (critical, 5 m) over a new `hamstrack_deploy_verify_check_ok{check}`
gauge the applier writes into the node-exporter textfile directory. It is quiet on a box that
has never run the verifying applier (`noDataState: OK`). What clears it is a run that READS the
check green: `bash /opt/hamstrack/ops/deploy/apply-config.sh /opt/hamstrack /opt/hamstrack
--verify-only` re-reads the box after a hand fix without redeploying. The metric and every value
it can take are in [`docs/observability.md`](observability.md); the procedure is
[Applying repository configuration](self-hosting.md#applying-repository-configuration) above.

### Notifications are scoped to a workspace from 0.17.0

**Two changes, and one of them wants five minutes *before* you pull the image.**

**What your users will see.** A notification now belongs to the workspace whose comment it
quotes, and an inbox shows only the notifications from workspaces that person is currently
a member of. Remove somebody from a workspace and that workspace's notifications stop
appearing for them — in the bell, in the unread count and in the live stream. The rows are
**hidden, not deleted**: add the person back and their notifications return, with the same
read and unread state they had when they left. Before 0.17.0 they kept a readable inbox of
that workspace's comment text indefinitely, which is why this is worth the upgrade.

**What to check first.** The upgrade gives every existing notification a workspace by
reading it out of the row's `link`, which is where the product has always recorded which
issue a mention points at. A row whose workspace cannot be read back that way is **removed
from your inbox** — it could never be shown again under the new rule, and nothing anywhere
else records which workspace it belonged to, so there is nothing to repair it from. It is
copied aside rather than destroyed (see below), but it does not come back. Every
notification the product has ever written carries a usable link, so the expected answer
below is `0`; run it anyway, because the only instance that can tell you about your data is
yours:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT count(*) AS unresolvable
  FROM notifications n
  LEFT JOIN workspaces w
    ON w.id = substring(n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/')::uuid
 WHERE w.id IS NULL;
SQL
```

`0` means the upgrade deletes nothing — pull the image and carry on.

**Any other number is not a reason to stop.** It is that many notifications the upgrade will
move out of your inbox table, and there are two quite different reasons a row can be in that
count. This tells you which:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT n.link IS NULL
       OR substring(n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/') IS NULL
         AS link_did_not_parse,
       count(*)
  FROM notifications n
  LEFT JOIN workspaces w
    ON w.id = substring(n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/')::uuid
 WHERE w.id IS NULL
 GROUP BY 1;
SQL
```

- **`link_did_not_parse` is `false`** — the link is fine, but the workspace it points at no
  longer exists on your instance. These are leftovers from a workspace deleted outside the
  application, a partial restore, or a dump reloaded without its parent rows; before 0.17.0
  nothing in the database noticed them. They could never be displayed again. **Upgrade** —
  removing them is the point.
- **`link_did_not_parse` is `true`** — some notification on your instance was written in a
  shape this release does not recognise. Upgrading is still safe (see the next paragraph),
  but please post the numbers on the
  [issue tracker](https://github.com/Zherikhov/hamstrack/issues): every notification the
  product is known to write carries a readable link, so yours would be new information.

**The upgrade keeps a copy either way.** If it removes anything at all, it first copies those
rows — in full, content included — into a table called `notifications_unresolvable_v20`, so
you can still look at them afterwards. That table is created **only** when there is something
to put in it, so on a clean upgrade it never appears. Nothing in Hamstrack reads it; it is
there for you. Once you have your answer, drop it:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT * FROM notifications_unresolvable_v20;
DROP TABLE notifications_unresolvable_v20;
SQL
```

If you would rather have the rows as a file before you upgrade, export them first:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' > unresolvable-notifications.csv <<'SQL'
\copy (SELECT n.* FROM notifications n LEFT JOIN workspaces w ON w.id = substring(n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/')::uuid WHERE w.id IS NULL) TO STDOUT WITH CSV HEADER
SQL
```

A backup taken as [Backups](self-hosting.md#backups) describes covers you either way, and is the general
answer for a minor upgrade.

### Statements are bounded from 0.17.0

**Read this if your instance holds a lot of history** — a workspace with hundreds of
thousands of issues, years of activity, or one very large project. On a small or ordinary
install nothing changes and there is nothing to do: the bound is roughly a hundred times a
normal request.

Before 0.17.0, a single database statement could run **for ever**. Nothing shortened it: a
browser that gives up does not stop the query on the server, and the connection pool's own
timeout governs *waiting for* a connection, never one already in use. Ten slow queries were
the whole pool (`DB_POOL_MAX_SIZE`, default 10) and everything else on the instance began
failing to get a connection. From 0.17.0 every statement the application runs is cancelled
after **10 seconds** (`DB_STATEMENT_TIMEOUT_MS`), and the request that asked for it answers
`422` with `errorType: STATEMENT_BUDGET_EXCEEDED`.

**Database migrations are deliberately not bounded.** Flyway runs its own transactions, so
an index build or a table rewrite on a large install still takes as long as it takes.

> **0.17.0 changed a second default, and on a big host the two compound.**
> [The heap is bounded from 0.17.0](#the-heap-is-bounded-from-0170) cuts the JVM heap on any
> host larger than 2 GB — a 4 GB host drops from ~1 GB to 512 MB. Less heap means more garbage
> collection inside the same query, which makes queries *slower*, which pushes borderline ones
> over this bound. **The causal direction is one-way:** the heap change can produce a `422`
> here, but nothing here affects the heap. So if reports started failing after upgrading on a
> host of 4 GB or more, set `APP_MEMORY_LIMIT` **first** and see whether the `422` goes away,
> before raising `DB_STATEMENT_TIMEOUT_MS`. Raising the bound hides a heap problem by letting
> the slower query run longer, on a connection it holds the whole time.

**What changes for you, if anything:**

| Your install | Before | After |
|---|---|---|
| Ordinary size — no request takes more than a second or two | nothing was near the bound | unchanged |
| Large, with one query that took ~5 s | a slow report | still works, ~5 s |
| Large, with a report or search that took 30 s | very slow, and it pinned a connection the whole time | **`422`**, in 10 s |
| Very large, where removing a busy member took 30 s | slow, and it held locks throughout | **`422`**, and the caller has nothing to narrow |

The last row is the one worth knowing about: a report or a search can be made cheaper by
asking for less — a shorter date range, fewer sprints, a narrower filter — but **removing a
workspace member cannot**, because the expensive part is a single update over every issue
that person was assigned. If that is where you meet this, raise the value.

**What to do.** Nothing, unless something that worked yesterday starts answering `422`
today. When it does, the app has already written the reason to the log:

```
Statement budget exceeded on GET /api/workspaces/{workspaceId}/projects/{projectId}/reports/flow
after 10000ms — answering 422 (SQLSTATE 57014). Raise app.persistence.statement-timeout-ms
(DB_STATEMENT_TIMEOUT_MS) if this request is legitimate, or narrow the query.
```

Then pick a value and restart:

| Situation | Set in `.env` | Why |
|---|---|---|
| Default, and nothing is failing | nothing | 10 s is far past any healthy request |
| A report or a report CSV download on a large tenant fails | `DB_STATEMENT_TIMEOUT_MS=30000` | 30 s. **No startup WARN**: it fits inside the stop grace the platform gives the process (`APP_STOP_GRACE_SECONDS`, 30 s by default), which is what the sizing rule compares against since 0.18.0. Raise `DB_POOL_MAX_SIZE` with it anyway: a longer bound means one request holds one connection for longer |
| A member removal or another write fails | `DB_STATEMENT_TIMEOUT_MS=60000` | the caller cannot narrow a write; give it a minute. **Startup logs one sizing WARN and nothing silences it** — above the stop grace, a statement running at this bound cannot finish inside a shutdown, so a deploy kills it while it is still holding its connection. Correct to accept if this was deliberate; `APP_STOP_GRACE_SECONDS` is the knob that makes it finishable. Raise `DB_POOL_MAX_SIZE` with it either way |
| You are diagnosing and want the old behaviour | **not available on purpose** | `0` means "no bound" to PostgreSQL and is refused at startup — that is the state this release exists to remove. Use a large value like `300000` instead, and expect the sizing WARN on every boot: at ten times the stop grace it is certain, and it is the app telling you this is a diagnostic setting rather than a resting one |

```bash
# in .env, next to DB_LOCK_TIMEOUT_MS
DB_STATEMENT_TIMEOUT_MS=30000
```

Then `docker compose up -d`.

**The floor is twice `DB_LOCK_TIMEOUT_MS` (default 3000), so the smallest accepted value is
6000** — and if you go below it the app **refuses to start** and says so. That is friendly
rather than hostile: PostgreSQL counts time spent waiting for a lock as part of the
statement, so a statement bound at or under the lock bound would fire first, and every
"someone else is editing this, try again in a moment" `409` in the product would silently
become a `422` that no retry can fix.

> **Above `APP_STOP_GRACE_SECONDS` (30 s by default) the app logs a sizing WARN at every boot,
> and no setting turns it off.** The rule changed in 0.18.0 and the new anchor is one you can
> act on: a statement allowed to run for longer than the grace the platform gives the process
> *cannot finish inside a shutdown*, so a deploy kills it while it is still holding one of your
> connections. Until 0.18.0 this compared the statement bound against half of the pool's
> acquisition timeout — which warned at anything above 15 000 and certified nothing, because one
> statement is not one connection hold: a transaction of many statements holds its connection for
> all of them, and one planning read holds one for minutes at a statement bound of ten seconds.
> Raising `DB_POOL_MAX_SIZE` is still the right response to a long bound (and the WARN also points
> at `EXPENSIVE_READ_MAX_IN_FLIGHT`, which decides how much of that pool the expensive surface may
> hold); raising `APP_STOP_GRACE_SECONDS` is what makes the statement survivable across a deploy.
> Neither suppresses the line, and the WARN says so itself while firing.
>
> **Raising it is not free, and these numbers are really one setting.** Every second you add is
> a second one request can hold one of your `DB_POOL_MAX_SIZE` connections. What keeps that from
> being an arithmetic exercise is that **occupancy is now bounded directly** rather than inferred
> from a rate:
>
> > Through the expensive-read surface, no user may occupy more than
> > `EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL` of a replica's connections and no set of users
> > more than `EXPENSIVE_READ_MAX_IN_FLIGHT`, so the rest of the API always retains
> > `DB_POOL_MAX_SIZE − EXPENSIVE_READ_MAX_IN_FLIGHT` of them. The per-minute budgets bound
> > throughput; they do not bound occupancy and never did.
>
> That replaces the `requests-per-minute × statement-timeout-seconds ≤ pool-size × 60 × share`
> relation this section used to ask you to solve. The relation was not wrong, it was
> unsatisfiable at the defaults — one user was entitled to 180 expensive requests a minute (120
> search + 60 reports) while one replica has 600 connection-seconds a minute to spend — and a
> rate can never deliver a bound on occupancy anyway, because it spends the same unit whether a
> request takes 8 ms or 8 s. A load probe confirmed the consequence: a single user, breaking no
> rule, saturated an instance and everything else on it failed on connection acquisition.
>
> So, practically: if you raise `DB_STATEMENT_TIMEOUT_MS` near or above 30 s, raise
> `DB_POOL_MAX_SIZE` with it — and if you raise the pool in order to give the expensive surface
> more room, check `EXPENSIVE_READ_MAX_IN_FLIGHT` with it, since that is the number deciding how
> much of the pool that surface can actually reach. Lowering `REPORTS_REQUESTS_PER_MINUTE` /
> `SEARCH_REQUESTS_PER_MINUTE` / `PLANNING_REQUESTS_PER_MINUTE` is no longer the lever for pool
> safety; they bound throughput.
> **Whether the pool alone widens that surface depends on whether you pinned the share**: unset, it
> is derived from the pool at every boot (60 % of it, capped at 6), so raising the pool from 6 to 10
> widens the share from 3 to 6 by itself; pinned, the number is yours and nothing moves it.
> **What the statement bound still does not govern** is a request that assembles its response in
> Java while the transaction is open (a report CSV): it is per *statement*, so occupancy ×
> duration remains unbounded above even though occupancy is not.

### Connection acquisition is bounded from 0.18.0

**Read this if your instance is ever busy.** Until 0.18.0 a request that found every
database connection in use waited **30 seconds** for one — HikariCP's default, which
Hamstrack had never set. From 0.18.0 it waits **3 seconds** (`DB_CONNECTION_TIMEOUT_MS`)
and is then refused with **`503`**, `errorType: DATABASE_BUSY` and `Retry-After: 1`.

**This is a change you will see, and it is the intended one.** A busy or
under-provisioned instance used to degrade into *slowness*; now it degrades into
*errors* — and the request that fails is not the slow one. Whoever asks while something
else is holding the connections is refused. Two things make that a policy rather than a
symptom: a parked request holds a worker thread and a connection of its own for the whole
wait, so waiting propagates the outage, and since 0.18.0 the
[expensive-read occupancy bound](#expensive-reads-are-bounded-by-concurrency-from-0180)
already guarantees the rest of the API a reserve of the pool that reports and searches
can never take.

**Every endpoint answers it, and the two pieces of code that write it write the same
document.** One is an exception handler, which covers a request that reached a handler; the
other is a servlet filter outside the whole chain, which covers a request whose connection was
needed earlier — **that is every authenticated request**, because the access token is resolved
to a user inside the security filter chain, and it is the larger half. Until 0.18.0 that half
answered a bare `500`, which was worse than untidy: the web UI declines to retry a `503` and
deliberately *does* retry a `500`, so a starved instance was asked again by every open tab.

**What still has no status, stated as the property rather than as a list of paths.** A refusal
needs a response it can still change. So a failure on an `ASYNC` or `ERROR` dispatch, one after
the response has already begun (a streamed download, an SSE stream), and one outside a request
altogether — a scheduled job, the shutdown residue write, Flyway at startup — cannot be turned
into one, here or anywhere else in this product. Those last are why
`hikaricp_connections_timeout_total` can still read higher than the app's own counter: it counts
acquisitions that had no caller to refuse.

**If you start seeing `503 DATABASE_BUSY`, the fix is almost always
`DB_POOL_MAX_SIZE`, not this value.** They are refusals about *capacity*: the pool had
nothing to give. Raising `DB_CONNECTION_TIMEOUT_MS` buys waiting instead — the old
behaviour — and it costs a worker thread per waiting request while doing it.

| What you see | Look at | Why |
|---|---|---|
| Occasional `503 DATABASE_BUSY` at peak | `DB_POOL_MAX_SIZE`, then `POSTGRES_MEMORY_LIMIT`/`POSTGRES_WORK_MEM` with it | more connections is the capacity answer; the pool is a factor in the database's memory arithmetic, so the two move together |
| Sustained `503 DATABASE_BUSY`, one tenant or one screen | `EXPENSIVE_READ_MAX_IN_FLIGHT` and the WARN lines, which name the route | something is holding connections for a long time; the occupancy bound is what caps that share |
| You would genuinely rather wait than shed | `DB_CONNECTION_TIMEOUT_MS=6000` (say) | legitimate. Every second added is a second a refused request holds a worker, and see the ceiling below |

**Three values it refuses at startup, one of which looks harmless.** `0` is refused
because HikariCP reads it as `Integer.MAX_VALUE` — about 24.8 days, i.e. *no bound*
rather than *no wait*; anything below `250` is refused (HikariCP's own floor); and a
**blank** line is refused, since `DB_CONNECTION_TIMEOUT_MS=` is an empty value and not an
absent one. Comment the line out to get the default.

**And a fourth, which is about a *name* rather than a value.** Before the web server is
started, the app compares the bounds the pool is actually holding against the one it validated,
and refuses to start if they differ. That happens when something sets the value by a spelling
the check does not read — most plausibly `SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT` (no
dashes), which Boot's relaxed binding accepts and which overrides everything else. Without this
check that configuration starts happily and then reports a bound it is not using, in the log
line below and in the ceiling arithmetic above. **Both** Hikari settings this variable drives
are checked — `connection-timeout` and `validation-timeout` — because the second one is read
back by nothing else at all, so `SPRING_DATASOURCE_HIKARI_VALIDATIONTIMEOUT` would otherwise
pull the two apart with no refusal, no warning and no metric to show for it. Set the value
through `DB_CONNECTION_TIMEOUT_MS` and nothing else.

The refusal arrives **before the connector opens**, deliberately: an instance that has already
begun listening is one a load balancer will route to and a rolling deploy will count as up, so a
misconfiguration would flap rather than stop.

**And one ceiling that comes from somewhere else entirely.** When the app shuts down it
drains the mail queue and then writes whatever is left to the database as one batch — and
that write has to obtain a connection first, inside the stop grace the platform gives the
process. So the boot refuses any combination where
`drain + acquisition + commit + queued rows` exceeds `APP_STOP_GRACE_SECONDS`: at the
default mail settings (drain 15 s, queue 100, grace 30 s) the largest acquisition bound
that boots is **13900 ms**. If you want a longer wait than that, raise
`APP_STOP_GRACE_SECONDS` in the same edit — the refusal names every knob that can move the
arithmetic.

**Read that ceiling from the other end before you upgrade**, because it is the one way this
release can stop an install that changes nothing: the acquisition is a *new term* in a sum
`MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS` and `MAIL_ASYNC_QUEUE_CAPACITY` were already checked
against, so 3000 ms of slack that used to be spare is now spent. At the shipped mail settings
the drain's ceiling is **25 s**; `MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS=28` with a queue of 100
against a 30 s grace boots today (`29 100 ≤ 30 000`) and refuses afterwards
(`32 100 > 30 000`). Raise `APP_STOP_GRACE_SECONDS` — which moves the container's
`stop_grace_period` and this bound together — or lower the drain.

**The log line is the operator's copy of the refusal**, and it carries what the caller's
never does:

```
Could not obtain a database connection within 3000 ms on POST /api/auth/login — answering
503 DATABASE_BUSY with Retry-After 1s. The pool said: HikariPool-1 - Connection is not
available, request timed out after 3005ms (total=10, active=10, idle=0, waiting=3). The
usual remedy is a larger pool (DB_POOL_MAX_SIZE) …
```

`waiting=` and `total=` are how you tell a pool that is *tight* from one that is *gone*.
If you run the [observability stack](self-hosting.md#observability-optional), the same event is
`hamstrack_db_connection_acquisition_failed_total`, tagged with the route, and unlike the
`422` above it also raises the general `HighErrorRate` alert — correctly, since pool
exhaustion is an incident. The `route` tag is the **mapped pattern** for a refusal that
reached a handler and **`unmapped`** for one refused earlier — that is how you tell the two
halves apart on one graph, not a missing label. `hikaricp_connections_timeout_total` counts
every acquisition the pool refused, including ones with no caller to refuse (a scheduled job,
the shutdown write, Flyway), so **expect it to be the larger of the two** and read the
difference as those rather than as a broken metric.

### The heap is bounded from 0.17.0

**Read this if your host has more than 2 GB of RAM.** On a smaller host you *gain*
heap and there is nothing to do. There is no error either way, which is the problem:
the only symptom is that the instance behaves as though it has less memory than it
used to, and nothing connects that to the upgrade.

Before 0.17.0 the image ran `java -jar` with no heap flag and the bundled compose set
no memory limit, so the JVM applied its own default — **~25% of whatever the host
had**. Your heap was a property of the machine, and no setting in Hamstrack named it.
From 0.17.0 the image runs `-XX:MaxRAMPercentage=50` and the bundled
`docker-compose.prod.yml` limits the app container to `1g` (`APP_MEMORY_LIMIT`), so
the heap is **half the container limit — 512 MB by default, on every host**. That is
what makes `REPORTS_MAX_ROWS` and the other byte budgets mean something: they are
costed against a 512 MB heap, which until now was an assumption about your machine
rather than a fact about the deployment.

**Break-even is a 2 GB host**, and it moves in both directions:

| Your setup before | Heap before | Heap after (default `1g`) |
|---|---|---|
| 1 GB host, no container limit | ~256 MB | **512 MB** — you gain |
| your own compose with `mem_limit: 1g` | ~256 MB | **512 MB** — you gain |
| 2 GB host, no container limit | ~512 MB | 512 MB — unchanged |
| 4 GB host, no container limit | ~1 GB | **512 MB** — you lose half |
| 8 GB host, no container limit | ~2 GB | **512 MB** — you lose three quarters |

On the losing rows the instance still works. It garbage-collects more often, large
reports and searches get slower, and a report that used to fit may now report itself
truncated or, at the extreme, fail. It reads as "0.17.0 made it slower".

**"Fail" has a specific spelling now, and it is the other half of this release.**
0.17.0 also cancels any single database statement after 10 seconds
([Statements are bounded from 0.17.0](#statements-are-bounded-from-0170)), so a query the
smaller heap has slowed past that answers **`422` `STATEMENT_BUDGET_EXCEEDED`** rather than
finishing late. Two changed defaults, one symptom, and this one is the cause: on a host of
4 GB or more, fix the heap here first — raising the statement bound instead buys the slower
query more time on a connection it is already holding too long.

**What to do:** on a host of 4 GB or more, set `APP_MEMORY_LIMIT` to **about half the
host** — never above **host RAM minus 2 GB**, less another 1 GB if you run the
[observability stack](self-hosting.md#observability-optional). Whichever of the two is smaller is your
number, and the heap is half of it. On a 2 GB host or smaller, leave the default: the
whole point of that box is the app, and `1g` already gives it what it had.

| Host | Set in `.env` | Heap you get | Also worth setting |
|---|---|---|---|
| ≤ 2 GB | nothing — the default `1g` is right | 512 MB | — |
| 4 GB | `APP_MEMORY_LIMIT=2g` | 1 GB | — |
| 4 GB **with observability** | nothing — the default `1g` is right | 512 MB | — |
| 8 GB | `APP_MEMORY_LIMIT=4g` | 2 GB | `JAVA_TOOL_OPTIONS=-Xmx3g` → 3 GB |
| 8 GB **with observability** | `APP_MEMORY_LIMIT=4g` | 2 GB | `JAVA_TOOL_OPTIONS=-Xmx3g` → 3 GB |
| 16 GB | `APP_MEMORY_LIMIT=8g` | 4 GB | `JAVA_TOOL_OPTIONS=-Xmx7g` → 7 GB |

```bash
# in .env, next to the other settings
APP_MEMORY_LIMIT=2g     # 4 GB host running app + PostgreSQL + Caddy → 1 GB heap
```

Then `docker compose up -d` to recreate the container; a memory limit is not applied to a
running one.

**From `4g` up, also set an explicit heap** — the fourth column above. The 50% split is
headroom sized for a *small* container and does not stay right as the limit grows, because
most of what lives outside the heap — metaspace, the code cache, thread stacks — is roughly
*constant* rather than proportional: at `4g` the default reserves ~1.5 GB nothing will use,
where at `2g` it over-reserves by ~300 MB and is not worth a second setting. Claim the rest
back with

```bash
JAVA_TOOL_OPTIONS=-Xmx3g    # with APP_MEMORY_LIMIT=4g: limit minus ~700 MB
```

> **Only the `-Xmx` form works.** Setting `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`
> — the natural thing to try, since that is the flag named above — changes nothing:
> the image passes its own copy on the command line and that copy wins. The JVM still
> logs `Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75.0` when it starts, and
> that line means the variable was *read*, not that it was *applied*. So the evidence
> you would look for is present and says the wrong thing. `-Xmx` is a different flag
> and does override the percentage.

**Check what you actually got.** From 0.18.0 the application says so itself, once, at
startup — ask it before you ask anything else:

```bash
docker compose logs app | grep "Memory: max heap"
# Memory: max heap 512 MB = 536870912 bytes (MaxHeapSize; derived from -XX:MaxRAMPercentage=50,
# no -Xmx); GC SerialGC; container memory limit 1024 MB; app.reports.max-rows=20000
```

That line is printed by the running JVM about itself, so unlike every other check here
it cannot be reading a different process. It names:

- **the resolved maximum in bytes**, and *which* maximum it is. `MaxHeapSize` is
  HotSpot's own figure — the one `-XX:+PrintFlagsFinal` prints below, so the two can be
  compared digit for digit. If that word instead reads `Runtime.maxMemory`, this JVM
  would not state `MaxHeapSize` and the number is *usable* heap, which some collectors
  report a little below the configured maximum (~18 MB below it at `1g`);
- **whether that maximum came from an explicit `-Xmx` or was derived from a percentage**
  — which is what settles the `JAVA_TOOL_OPTIONS` trap above, since
  `Picked up JAVA_TOOL_OPTIONS: …` says a variable was read and not that it was applied;
- **the garbage collector.** At `APP_MEMORY_LIMIT=1g` the JVM is below its "server-class
  machine" threshold and picks **SerialGC** — single-threaded, stop-the-world — and at
  `2g`, same image and same flags, it picks **G1** (measured 2026-09-01 on
  `eclipse-temurin:21-jre-alpine`, the tag the published image is built from, with 2 CPUs).
  That is the difference between a 50 ms pause and a multi-second one, and the
  likeliest explanation of the 4.99 s pause the 2026-08-31 load run measured on a `1g`
  container. If pauses rather than `OutOfMemoryError` are the symptom, `APP_MEMORY_LIMIT`
  is the dial that moves the collector;
- **the container limit the JVM can see** — `none` means no limit, so the percentage is
  being taken against *host* RAM; `unknown` means there is no cgroup memory file to read;
- **`REPORTS_MAX_ROWS`**, because that budget is costed in bytes against exactly this heap.

The container's limit and the heap from the outside:

```bash
docker stats --no-stream --format '{{.Name}}  {{.MemUsage}}'
docker compose exec app java -XX:MaxRAMPercentage=50.0 -XX:+PrintFlagsFinal -version | grep -w MaxHeapSize
```

The first prints `used / limit` per container. The second prints the heap in bytes
(`536870912` is 512 MB). **Repeat the flag exactly as shown**: `exec` starts a *fresh*
JVM that does not inherit the image's startup arguments, so without it you would be
reading the JVM's default (~25% of the limit, i.e. `268435456` — a plausible-looking
number for a process that is not your application) rather than yours. That mistake has
been made against this deployment in earnest, which is the other reason the startup line
above exists. If you set `JAVA_TOOL_OPTIONS`, that fresh JVM picks it up the same way the
app does, so the number stays honest.

**Do this even if you are sure**, and the blunt form of the check is

```bash
docker inspect "$(docker compose ps -q app)" --format '{{.HostConfig.Memory}}'   # 0 means NO LIMIT
```

Resolve the container instead of naming it: it is called `<compose-project>-app-1`, after
the directory your compose file sits in, so a hard-coded name works only on the install it
was written on.

Run it because **a value in `.env` is not a limit until a container reports one**. The
hosted Hamstrack instance ran for six weeks with `APP_MEMORY_LIMIT=1g` sitting in its
`.env` and read by nothing — its copy of the compose file predated the `mem_limit` line —
so the JVM took its 50% against *host* RAM and ran a heap ceiling larger than the memory
the machine could ever hand it, which the kernel would have ended with an OOM kill (exit
`137`, no stack trace) long before any heap alert noticed. You can reach the same state by
editing `.env` and not recreating the container, or by running an older copy of the compose
file. The command above is the only thing that answers it; a file cannot.

**Running your own compose file rather than the bundled one?** Then nothing has
capped your container and the percentage is taken against host RAM — which at 50% is
*more* heap than before, and closer to the host's ceiling than is safe. Add a
`mem_limit:` to the app service;
[`deploy/dc/docker-compose.yml`](../deploy/dc/docker-compose.yml) shows one.

### PostgreSQL is bounded and tuned from 0.18.0

**Read this if you use the bundled `docker-compose.prod.yml` and have never edited the
`POSTGRES_*` lines in `.env`.** Two defaults change for you, and only one of them can hurt.

Until 0.18.0 the `postgres` service carried **no `mem_limit`** and ran at the image's own
memory settings. From 0.18.0 the bundled file caps the container and passes three dials
explicitly:

| Setting | Before (image default) | From 0.18.0 | `.env` variable |
|---|---|---|---|
| container ceiling | none | `512m` | `POSTGRES_MEMORY_LIMIT` |
| `effective_cache_size` | `4GB` | `512MB` | `POSTGRES_EFFECTIVE_CACHE_SIZE` |
| `shared_buffers` | `128MB` | `128MB` — unchanged | `POSTGRES_SHARED_BUFFERS` |
| `work_mem` | `4MB` | `4MB` — unchanged | `POSTGRES_WORK_MEM` |

**The defaults are sized for a 1–2 GB host, and `effective_cache_size` is the one that can
hurt you without failing.** It is not an allocation — it is what the planner *believes* is cached, so
nothing ever refuses it and nothing ever runs out because of it. On a small box the image's
`4GB` was a claim that more data was cached than the machine had RAM, and correcting it is
the fix this change exists for. On an **8 GB or 32 GB host with a multi-gigabyte page
cache, `512MB` is an under-claim**: the planner stops believing in cache it really has and
shifts towards sequential scans on large tables. There is no error and nothing fails — the
only symptom is that things get slower, which is exactly the shape of the 0.17.0 heap cut
above.

The rows are disjoint — read the one your host falls in, not the first one that could match:

| Your host | Do this |
|---|---|
| under 1 GB (a small VPS) | **lower all three**: `POSTGRES_SHARED_BUFFERS=64MB`, `POSTGRES_EFFECTIVE_CACHE_SIZE=192MB` (that is `64MB` + what `free -m` really shows as cache — check yours rather than copying `192MB`), `POSTGRES_WORK_MEM=2MB`. Bring `POSTGRES_MEMORY_LIMIT` down with them if you like, but never under what the server is then configured to use |
| 1–2 GB | nothing — the new defaults are the fix, and this is the host they were measured on |
| 4 GB, app + database on one box | `POSTGRES_EFFECTIVE_CACHE_SIZE=1GB` |
| 8 GB, app + database on one box | `POSTGRES_EFFECTIVE_CACHE_SIZE=2GB`, and `POSTGRES_SHARED_BUFFERS=256MB` with `POSTGRES_MEMORY_LIMIT=1g` if the database is the busy part |
| dedicated database host | `shared_buffers` ~25% of RAM, `effective_cache_size` ~75%, and `POSTGRES_MEMORY_LIMIT` above the sum |

**Raising `POSTGRES_WORK_MEM` on any of the roomy rows wants `POSTGRES_SHM_SIZE` raised with
it.** Parallel workers put their share of a sort in `/dev/shm`, which Docker sizes at 64 MB
for every container; exhausting it fails with `could not resize shared memory segment … No
space left on device`, which names neither dial. The default is Docker's own 64 MB, so it
only becomes a setting once `work_mem` grows.

Then `docker compose up -d`. A value PostgreSQL cannot parse makes the **server** refuse to
start while `docker compose up -d` still exits `0`, so change one dial at a time and check
`docker compose ps`.

**The container ceiling is the other half, and it is containment rather than protection.**
`512m` is ~2× the peak RSS measured on this project's own production box (~240 MB) at these
settings. What it buys is that a runaway database dies and restarts inside its own cgroup
instead of the kernel picking a victim across the whole host — which, before this release,
could as easily have been the application, the only bounded process in the file. What it
does **not** buy is a host that cannot run out of memory: ceilings are maxima, not
reservations, and the bundled defaults still declare more of them than a 2 GB box has RAM.

**Raise `POSTGRES_MEMORY_LIMIT` whenever you raise any dial that is a term in what it has to
contain — `POSTGRES_SHARED_BUFFERS`, `POSTGRES_WORK_MEM` or `DB_POOL_MAX_SIZE`.** The first
is obvious; the last is the one that catches people. `work_mem` is charged per sort or hash
node **per backend**, so both it and the pool size are factors in the same worst case:
`4MB × ~4 nodes × ~12 backends` (a pool of 10, plus the `postgres-exporter` and a `psql`
session) ≈ **190 MB** at the defaults, and `DB_POOL_MAX_SIZE=50` makes it `4MB × 4 × 52` ≈
**830 MB** — well under a stock `max_connections` of 100, and well over a `512m` ceiling,
where the failure is an OOM-killed backend or postmaster rather than a refused connection.
Doubling `POSTGRES_WORK_MEM` doubles the same figure without touching the pool at all.

**Running your own compose file?** None of this reaches you: both the ceiling and the dials
live in `docker-compose.prod.yml`, so your database keeps the image's `4GB`
`effective_cache_size` and no container limit.
[`deploy/dc/docker-compose.yml`](../deploy/dc/docker-compose.yml) shows the form to copy,
spelled with the same variables so `.env` can still drive it.

### Account addresses become case-insensitive in 0.18.0 (one query, before you pull)

**Most instances have nothing to do here, and one query proves it in ten seconds.** Run
it *before* upgrading and you can never meet the block described below.

**What changes.** `users.email` gains a second uniqueness rule — `UNIQUE (lower(email))` —
so `Ivan@x.com` and `ivan@x.com` can no longer both be accounts. Until now that was true
only *by convention*: every place in Hamstrack that creates an account lower-cases the
address first, and nothing but that habit enforced it. From 0.18.0 PostgreSQL enforces it,
which is what makes it survive an LDAP/SSO import, a bulk load or a support script that
forgets.

**What does not change.** Nothing about how you log in. Sign-in still matches your address
exactly, deliberately — a login that folded could resolve one typed address to either of
two rows, and that is a door, not a convenience. Existing addresses are not rewritten,
re-cased or merged by the upgrade.

**Run this before you pull:**

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT id, email, status, created_at
  FROM users
 WHERE email <> lower(email)
 ORDER BY created_at;
SQL
```

**Empty is the expected result, and it is the only one that needs no action.** Every
account Hamstrack itself creates is lower-cased at signup, so rows appear here only if some
*other* writer touched the table — an import, a support script, a dump edited by hand. If
the query is empty, upgrade normally and skip the rest of this section.

**If it returns rows, the upgrade will refuse to start.** It refuses *atomically*: nothing
is applied, no index is created, and no account row is changed or deleted. **Flyway records
nothing either** — on PostgreSQL the schema-history row is written inside the same
transaction and rolls back with it — so there is no failed migration to `repair` and no
half-state to clean up: fix the data and start the container again. The message names both
counts and repeats the queries it needs you to run. Ask which rows collide as well:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT lower(email)                          AS folded,
       count(*)                              AS copies,
       array_agg(id    ORDER BY created_at)  AS ids,
       array_agg(email ORDER BY created_at)  AS addresses
  FROM users
 GROUP BY 1
HAVING count(*) > 1;
SQL
```

**Fix them in this order. The order is load-bearing, not a preference.**

1. **Resolve every collision first.** Only you can decide which of two accounts survives,
   and the answer is *re-address or disable* — never delete: `issues.reporter_id`,
   `comments.author_id`, `invited_by` and the `created_by` columns all reference `users`
   with no `ON DELETE`, so a delete either fails on a foreign key or would destroy that
   person's history. The block under
   [Duplicate accounts after an upgrade](#duplicate-accounts-after-an-upgrade-locale-dependent-email-folding)
   retires one row of a pair and hands its address to the survivor. **Its mechanics apply
   here; one line of its reasoning does not** — it says the survivor must take *the
   duplicate's* address because that is the spelling the current build just wrote, which is
   true after the locale-folding incident it was written for and false here. Nothing was
   just written: both spellings are old, and the duplicate's may be the wrong one. Decide
   which of the two addresses the pair should end up on, then run the block with that one.
2. **Then fold whatever is left:**
   `UPDATE users SET email = lower(email) WHERE email <> lower(email);`

Doing 2 before 1 is not a shortcut. At that moment the new index does not exist yet, so a
blind fold across a colliding pair **succeeds** in producing two identical addresses and is
only then refused by the older byte-exact constraint — an error that reads like a different
bug entirely. Note also that **step 1's own output is mixed-case in two places** — the
tombstone it leaves (`Ivan@x.com.retired-<id>`) *and* the address it hands the survivor,
which is the one that matters, because that is a live account rather than a disabled row.
Step 2 is what clears both. Do not spot-check the tombstone and call it done: **re-run the
first query** afterwards and expect nothing back.

**Why the upgrade will not do step 2 for you**, given that it is one statement it could
obviously run. `Bob@x.com` and `bob@x.com` are two different mailboxes on any RFC-compliant
mail server, so folding an address in place changes **which mailbox can reset that
account's password**. That is your decision about your people, and a migration may not make
it silently.

**And why it refuses over a single row that collides with nothing.** That row is already
broken: its owner cannot log in (sign-in lower-cases what they type before looking it up)
and cannot receive a reset mail (so does that flow). From 0.18.0 it additionally *occupies*
the lower-cased address, so registering the correct spelling would be refused with "Email
is already registered" — for an address nobody holds, in a way no one would ever connect
back to this row. The upgrade is the one moment anybody looks.

**A non-ASCII address is a different question and blocks nothing.** The upgrade prints a
notice if it finds one, because it *may* be a legitimate internationalised address or *may*
be the locale-folding bug described next — and no query can tell those apart, because the
stored value is a perfectly legal lower-case address either way. The notice is a pointer to
the next section, not a verdict on your data.

#### If the database's collation provider ever changes

This applies to both address indexes at once — `users_email_lower_uk` and
`workspace_invites_pending_email_uk` — and it is one procedure, not two. After a C-library
or ICU upgrade under a running cluster, PostgreSQL says:

```
WARNING: index "users_email_lower_uk" depends on collation "default" version "2.28",
         but the current version is "2.36"
DETAIL:  The index may be corrupted due to changes in sort order.
HINT:    REINDEX to avoid the risk of corruption.
```

```sql
REINDEX INDEX users_email_lower_uk;
REINDEX INDEX workspace_invites_pending_email_uk;
ALTER DATABASE hamstrack REFRESH COLLATION VERSION;
```

What actually breaks is narrower than the warning sounds, and the precise version is worth
having because the vague one causes panic:

- **Equality does not change.** Under a deterministic collation — every collation this
  schema uses — equality is byte equality, so a provider change can never make two stored
  addresses newly equal or newly distinct. No existing account's uniqueness lapses. (That
  is about the *stored values*. What `lower()` maps them to is a separate question, and
  the third bullet is where it is answered.)
- **Sort order can change**, and a btree finds its duplicate candidates by order, so a
  stale index could fail to notice a *new* duplicate until it is rebuilt. That is what the
  `REINDEX` is for.
- **`lower()` itself can change** — it reads `LC_CTYPE` — and this is the part that would
  matter and does not: the characters whose folding varies between providers are
  *uppercase* ones, and Hamstrack has already lower-cased every address it stores. On the
  values in your table, `lower()` is the identity function under every provider in
  practical use. **And if that ever stopped being true** — a provider that knows a case
  mapping the app's JVM does not — the failure is the loud one in the next bullet rather than a silent
  one: every check the application makes goes through the *same* `lower()` the index does,
  so a disagreement can only produce a refusal you can see, never a duplicate account.
- **`REINDEX` is the detector, and it fails loudly.** If a provider change ever did fold
  two stored addresses together — a change in `lower()`'s *image*, which is a different
  question from the collation *equality* of the first bullet — the rebuild fails with
  `could not create unique index … Key (lower(email))=(…) already exists` — and run in
  `psql` you see the `DETAIL` naming the value. (The application's connection pool
  suppresses that detail so third-party addresses stay out of its log; your session is not
  the application's.)
- The bundled compose file pins `postgres:16-alpine`, so the C library changes only when
  *you* move that tag. This is an upgrade-time event with a known moment, not drift — which
  is why it lives here and not in a monitor.

### Duplicate accounts after an upgrade (locale-dependent email folding)

**Most instances can skip this.** It applies only if your Hamstrack container or host
ever ran with a Turkish, Azeri or Lithuanian locale (`LANG=tr_TR.UTF-8`, `az_AZ…`,
`lt_LT…`), and only to addresses containing an uppercase `I`. If `LANG` was never set
— the default for the published image and the sample compose — nothing here applies.

Before 0.16.0 the app lower-cased email addresses using the **JVM default locale**,
which on Linux comes from `LANG`/`LC_ALL`. Those three locales fold `I` to a dotless
`ı` (U+0131) rather than `i`, so an address entered as `IT-Admin@corp.com` was stored
as `ıt-admin@corp.com`. From 0.16.0 the fold is locale-independent and the same
address stores as `it-admin@corp.com` — meaning any row written under the old
behaviour is one this version can no longer find.

Two consequences, and the second is why this section exists:

- **That account can no longer log in.** The address its owner types no longer
  resolves to their row.
- **`SEED_ADMIN_EMAIL` mints a *second* administrator.** The seeder looks its
  configured address up and, on a miss, creates the account — so the first boot after
  upgrading leaves you with a second ACTIVE system administrator holding
  `SEED_ADMIN_PASSWORD`, while the original stays active and orphaned. Nothing logs
  it: the seeder deliberately never prints the address.

**This cannot recur on the published image.** 0.16.0 pins the JVM locale in the image
itself (`-Duser.language=en -Duser.country=US`), identically for every deployment.
That pin reaches the container and nothing else.

If you run the JAR directly you need those flags on your own command line — but be
clear about what that path is before you take it: **there is no published JAR asset
and no documented bare-JAR install.** Releases ship the container image, and
`docker compose` is the documented way to run Hamstrack. Building from source and
launching the JAR yourself is reachable, and this paragraph exists for that case; it
is not a second supported deployment model. If that is you:

```bash
java -Duser.language=en -Duser.country=US -jar target/hamstrack-<version>.jar
```

or `JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"` in a systemd unit.

The pin is `en`/`US` rather than a neutral root locale. For case folding the two are
equivalent; `en-US` additionally fixes the default number and date formatting used by
any code that formats without naming a locale. If your operators read the UI in
another language that is unaffected — this sets a server-side default, not the
interface language.

**Exactly two characters can differ**, and it is worth knowing which, because the
folding tables are full of near-misses that are *not* involved here. An uppercase `I`
folds to `ı` (U+0131) under these locales and to plain `i` everywhere else; a dotted
capital `İ` (U+0130) folds to plain `i` under these locales and to `i` followed by a
combining dot above (U+0307) everywhere else. Those are the only two. Long s (`ſ`,
U+017F) and the Kelvin sign (`K`, U+212A) look like they belong on this list and do
not — both fold identically under every locale, so they can never be the difference
between an old row and a new one.

**Check before upgrading.** While the old rows are still the only rows:

```sql
SELECT id, email FROM users WHERE email ~ '[^\x00-\x7F]';
```

A hit here is a flag, not a verdict: internationalised addresses are perfectly legal
and Hamstrack accepts them. What you are looking for is an otherwise-ASCII address
containing `ı`. Note that this query cannot see the `İ` case, whose old spelling is
pure ASCII — the pair query below catches both, so treat this one as an early warning
rather than a clearance.

**Check after upgrading.** Once the new build has booted, the duplicate exists and
the query above returns only *one* row of each pair. Ask for the pairs instead:

```sql
SELECT translate(email, U&'\0131\0307', 'i')     AS folded_form,
       count(*)                                  AS copies,
       array_agg(id    ORDER BY created_at)      AS ids,
       array_agg(email ORDER BY created_at)      AS addresses
  FROM users
 GROUP BY 1
HAVING count(*) > 1;
```

`translate` here maps `ı` to `i` and **drops** the combining dot: its third argument
is shorter than its second, and PostgreSQL removes any character with no counterpart.
That collapses both spellings of a pair onto one key.

**An empty result means no duplicate pairs.** `users.email` is `UNIQUE`, so two rows
land in one group only by differing in exactly the characters that fold — so in
practice there is nothing to sift here. The one way to get a group you should *not*
act on is if somebody deliberately registered a genuinely different address that
happens to differ only by a dotless `ı`; check the two addresses look like the same
person before merging them. A non-empty result lists each pair with its ids and both
spellings, **oldest first**.

**It does not clear the lone-stale-row case.** If the old account existed but nothing
has since re-created it — nobody re-registered, and it was not the seed admin — there
is no pair, no group, and nothing above finds it. The symptom is a single person
unable to log in. There is no duplicate to retire here, so fix the row directly — set
it to what the current build folds their typed address to. For a dotless `ı` row that
is `UPDATE users SET email = translate(email, U&'\0131', 'i') WHERE id = '<their id>';`
and here `translate` *is* correct, because that case's lookup key and group key
coincide.

The `İ` variant of the lone-row case behaves differently again, and better than it
looks. Two things are genuinely unavailable: you **cannot detect it proactively** —
its stored spelling is ordinary ASCII, indistinguishable from a correct row, so no
query finds it and it surfaces only as a login complaint — and you **cannot restore
the dotted-capital spelling**, because the address that spelling now folds to carries
an invisible combining dot. Neither matters, because you do not need either.

That stored spelling being plain ASCII is exactly what rescues it: an ordinary ASCII
`I` folds to a plain `i` under the current build too, so **the row is already
reachable — by typing an ordinary `I` instead of `İ`**. Confirm it with the address
they *meant*, spelled with ordinary ASCII capitals — which doubles as the way to find
the row, since no query detects this case:

```sql
-- Type the address in lower case yourself. Do NOT wrap it in lower(): that folds
-- under the DATABASE's collation, and on a tr_TR cluster it reproduces this very bug
-- from the SQL side, returning nothing and sending you looking for a row that is there.
SELECT id, email, display_name FROM users WHERE email = 'it-admin@corp.com';
```

If that returns their row, the spelling in the `email` column is their working
address: pure ASCII, nothing invisible, and this was a **read** — no write, no retire,
no lost history. Give it to them verbatim and they log in with it from now on.

**Do not send them to "forgot password" first.** That flow folds the address exactly
the way login does, so the dotted spelling misses the same row — and because the
endpoint deliberately reports success for unknown addresses to prevent enumeration, it
tells them a mail is on the way when none was sent. On DC, where SMTP is optional,
it is weaker still. Nor should you delete the row and re-create the account:
`issues.reporter_id`, `comments.author_id`, `invited_by` and the `created_by` columns
are all `NOT NULL REFERENCES users(id)` with no `ON DELETE`, and a person with a stale
row is by definition someone who has been using the instance — so the delete fails on
a foreign key, and would destroy their history if it did not.

**Fixing a pair.** Decide which row to keep first: it is the one with **history**
(memberships, issues, comments — normally the older, listed first above), *not* the
one the seeder has just minted. Move any work off the duplicate before you retire it;
for a freshly created seed admin there will not be any.

The survivor must end up holding **the duplicate's exact address** — that is by
definition the spelling the current build produces, because the duplicate is the row
the current build just wrote. Copy it across in SQL rather than retyping it: one of
these spellings carries a combining dot (U+0307) that is **invisible in a terminal**,
so a retyped address can look identical and still not match.

> **Arriving here from the 0.18.0 upgrade instead?** Then that reasoning does not
> hold, because nothing was just written: both spellings are old, and the duplicate's
> may be the *wrong* one of the two. The mechanics of the block are unchanged — decide
> which address the pair should end up on first, and use it wherever the block says
> "the duplicate's address".

**Run this block in one interactive session**, in the order printed:

```bash
docker compose exec -it postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"'
```

then paste it there. The stash in statement 0 is a **temp table, which lives only for
the connection that created it** — so running these as separate one-shot
`psql -c "…"` invocations, one command per shell line, drops it between statements:
statement 1 still retires the duplicate and tombstones
its address, and statement 2 then fails with `relation "keep" does not exist`. That is
the stop-you-halfway state the comment in statement 1 warns about, reached through a
different door.

```sql
-- 0. Stash the duplicate's address before step 1 overwrites it. Doing this in SQL is
--    what removes the transcription risk -- never retype the address by hand.
CREATE TEMP TABLE keep AS
SELECT email FROM users WHERE id = '<duplicate id>';

-- 1. Retire the duplicate: disable it AND free its address, so the survivor can take
--    it. Order matters -- correcting the survivor first, while the duplicate still
--    holds the spelling it is moving to, violates the UNIQUE constraint on
--    users.email and stops you half way. left(email, 200) keeps the tombstone inside
--    VARCHAR(255); appending the id keeps it unique.
UPDATE users
   SET status = 'DISABLED',
       email  = left(email, 200) || '.retired-' || id
 WHERE id = '<duplicate id>';

-- 2. Hand the stashed address to the survivor.
--    Do NOT re-derive it with translate(): translate() produces the GROUP KEY, which
--    is not the lookup key. For a dotted capital I the address the build looks up
--    carries the combining dot that the group key deliberately drops -- and in that
--    case the survivor is already plain ASCII, so a translate() here would change
--    nothing, report "UPDATE 1", and leave the account locked out with the only
--    matching row already retired.
UPDATE users
   SET email = (SELECT email FROM keep)
 WHERE id = '<survivor id>';

DROP TABLE keep;
```

**Then verify by logging in as that account.** This is the one step whose failure is
silent — every statement above reports success whether or not the address it left
behind is the one the application will look up — so a clean run is not evidence that
access is restored. A login is.

If the pair was your seed administrator, **reset that account's password** afterwards:
`SEED_ADMIN_PASSWORD` was set on a live administrator account that nobody asked to create.
Change it on the *account* (sign in and change it, or Admin console → Users) — editing the
variable alone changes nothing, because seeding skips a user that already exists.
Both rows in that pair are usually administrators, which is why "keep the one with
history" is the rule rather than "keep the active one".

### Free text is bounded from 0.18.0

**Read this if your instance holds long descriptions or comments** — a pasted stack trace, a
migrated wiki page, a specification somebody kept in an issue. On an ordinary install nothing
changes and there is nothing to do: the bound is roughly four pages of text.

Before 0.18.0 the free-text fields on the API were unbounded; the only ceiling anywhere was the
database column. From 0.18.0 each one is bounded, and an over-long value answers `400` naming the
field that was too long:

| Field | Where you meet it | Bound |
|---|---|---|
| Issue description | issue create and update | 10 000 characters |
| Comment body | issue comments | 10 000 characters |
| Project description | project create and update, project settings | 10 000 characters |
| Workflow description | Admin console → Workflows | 10 000 characters |
| A custom field of type *text area* | issue fields | 10 000 characters |
| A field definition's `config` | Admin console → Fields | 20 000 characters serialized (`422`) |

**The bounds apply to rows you already have, and that is the part worth reading.** They are checked
on the way in, so a record whose stored text is already longer than the bound refuses **every**
save until it is shortened — including a save that changes something else entirely, because the
editor submits the whole record. In practice: a 15 000-character description means that issue's
status, assignee and due date cannot be changed through the UI either, and the message names
`description` even though nobody touched it.

**Nothing is lost and nothing is rewritten.** There is no migration behind this. No row is
truncated, no text is deleted, no column is narrowed. Every existing value stays exactly as it is
and is read back in full — on the board, in the issue view, through the API, in a database dump.
The only thing that changed is that a *write* carrying an over-long value is refused.

**It is self-healing.** Shorten the text once, below the bound, and that record saves normally from
then on. If the content is worth keeping — a log, a dump, a long specification — attach it as a
file and leave a line in the description instead.

**Size it before you upgrade.** Nothing here is destructive, so this is for planning rather than
safety:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT 'issues' AS t, count(*) FROM issues WHERE length(description) > 10000
UNION ALL SELECT 'issue_comments', count(*) FROM issue_comments WHERE length(body) > 10000
UNION ALL SELECT 'projects', count(*) FROM projects WHERE length(description) > 10000
UNION ALL SELECT 'workflows', count(*) FROM workflows WHERE length(description) > 10000;
SQL
```

**Treat that number as a floor, not as an answer.** PostgreSQL's `length()` counts characters; the
server counts UTF-16 code units, and anything outside the Basic Multilingual Plane — emoji, the
rarer CJK ranges — costs two units and one character. An emoji-heavy 8 000-character description is
around 14 000 units: it will be refused, and the query above counts it as fine. Ordinary Latin,
Cyrillic or Greek prose is one unit per character, so for most instances the count is exact. If you
want a result where **zero really means zero**, run the same query with `octet_length(...) > 10000`
in place of `length(...) > 10000` — a UTF-16 unit is never more than a UTF-8 byte, so nothing can
hide under it. That one errs the other way (Cyrillic costs 2 bytes per character, CJK 3), so a
non-zero answer from it is a list to re-check with the first query, not a list of problems.

Text-area custom field values and field-definition `config` are deliberately not in the query:
they are stored inside JSONB documents rather than in a prose column. Both behave the same way —
refused on save, unchanged in storage, fixed by shortening once.

### Attachment storage is capped per workspace from 0.18.0

Before 0.18.0 nothing bounded how much attachment storage one workspace could occupy:
`ATTACHMENT_MAX_FILE_SIZE` refused one large file and nothing refused the ten-thousandth
small one. From 0.18.0 there is a per-workspace ceiling and **it arrives switched on**
(`STORAGE_QUOTA_ENABLED` defaults to `true`), so it applies at the container restart your
upgrade performs, to an install whose `.env` names neither variable.

**The break-even is one number.** A workspace holding **less** than the ceiling sees no
change of any kind — no refusal, nothing slower, nothing hidden. A workspace already holding
**more** has every new upload in it answered `409 STORAGE_QUOTA_EXCEEDED` from the moment the
deploy completes, with no warning to anyone and no entry in any error rate, because a `409` is
a clean refusal rather than a fault. Existing files stay readable and downloadable and
nothing is deleted, archived or expired; only new uploads are refused.

The ceiling is **100 GB self-hosted, 10 GB on the `cloud` profile**, and *which one you get
follows `SPRING_PROFILES_ACTIVE`*. `.env.prod.example` ships `cloud`, so an install that
never changed that line is on the **10 GB** ceiling while this page quotes 100 GB — the most
likely way to be surprised by this change is to be on the wrong profile rather than to be a
large install.

**Am I affected? One query, before you pull.** It works on any 0.17.x instance (the counter
table does not exist yet, so it counts the rows directly):

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' <<'SQL'
SELECT i.workspace_id,
       pg_size_pretty(SUM(a.size_bytes)) AS attachments
  FROM issue_attachments a
  JOIN issues i ON i.id = a.issue_id
 GROUP BY i.workspace_id
 ORDER BY SUM(a.size_bytes) DESC
 LIMIT 20;
SQL
```

Zero rows, or a largest workspace comfortably under your ceiling, means this change is
invisible to you. Otherwise pick one of two values and put it in `.env` **before** the pull:

| Situation | Line to add |
|---|---|
| Largest workspace is over the ceiling and you want the cap anyway | `STORAGE_QUOTA_WORKSPACE_BYTES=` a value above it (e.g. `500GB`) |
| You are self-hosting and `.env` still says `SPRING_PROFILES_ACTIVE=cloud` | `SPRING_PROFILES_ACTIVE=dc` — and read [the deployment-model note](self-hosting.md#configuration): the profile also decides public signup and where attachments are stored |
| You do not want a ceiling at all | `STORAGE_QUOTA_ENABLED=false` |

**Whatever you type there is checked at boot, and a value that cannot work stops the container
rather than the first upload.** `STORAGE_QUOTA_WORKSPACE_BYTES` must be at least
`ATTACHMENT_MAX_FILE_SIZE` — a ceiling smaller than one permitted file admits nothing at all —
and the check runs **even when `STORAGE_QUOTA_ENABLED=false`**, because a number that is only
validated while a switch is on is a number that is wrong the moment somebody turns the switch on.
A **blank** `STORAGE_QUOTA_WORKSPACE_BYTES=` stops the boot too rather than restoring the default:
remove the line to get the default back. The startup message names both numbers and the variable
to change.

`STORAGE_QUOTA_ENABLED=false` stops the refusals and keeps the bookkeeping: usage is still
counted and still shown on **Workspace settings → Storage**, which is the figure you need in
order to choose a number later. Once you know the distribution, lower the ceiling deliberately
as its own change — the procedure is
[Turning the storage quota on where there is already content](self-hosting.md#turning-the-storage-quota-on-where-there-is-already-content).

After the upgrade the same question is one primary-key read per workspace:

```sql
SELECT workspace_id, bytes_used, attachment_count, updated_at
  FROM workspace_storage_usage
 ORDER BY bytes_used DESC
 LIMIT 20;
```

### Expensive reads are bounded by CONCURRENCY from 0.18.0

Before 0.18.0 two per-minute budgets bounded how *often* one user could ask for a report or a
search, and **nothing bounded how many they could have running**. That is not a small gap: a
rate spends the same unit whether a request takes 8 ms or 8 s, so its protection evaporates
exactly as an instance slows down. At the shipped defaults one user was entitled to 180
expensive requests a minute while one replica has 600 connection-seconds a minute to spend —
and a load probe confirmed the consequence, which is worse than a slow report: **one user,
breaking no rule, saturated the instance, and everything else on it failed on connection
acquisition after 30 s**, including endpoints with nothing to do with reports.

From 0.18.0 there is an occupancy bound, and **it arrives switched on**
(`EXPENSIVE_READ_LIMIT_ENABLED` defaults to `true`), so it applies at the container restart
your upgrade performs, to an install whose `.env` names none of these variables:

> Through the expensive-read surface — every read that holds a connection while it works, today
> `…/reports/**`, `…/search/**`, `…/filters/**`, `…/storage/projects` and the **planning** reads
> under `…/projects/*/backlog/**` — no user may occupy more than
> `EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL` (3) of a replica's connections and no set of
> users more than `EXPENSIVE_READ_MAX_IN_FLIGHT` (6), so the rest of the API always retains
> `DB_POOL_MAX_SIZE − EXPENSIVE_READ_MAX_IN_FLIGHT` of them.

**Those two numbers are derived from your pool while you leave them unset, and that is what makes
this upgrade safe on a small box.** 3 and 6 are what the derivation produces against the default
`DB_POOL_MAX_SIZE` of 10; on a pool of 6 it produces 3 and 3, on a pool of 4, 2 and 2 — 60 % of the
pool, capped at the shipped 6, with the per-user ceiling clamped to fit. **The share is never
derived larger than 6**, so a big pool keeps the documented numbers and the only installs whose
behaviour the derivation changes are the ones that would otherwise have refused to start. Set
either variable and the number is yours exactly, checked against the pool as described below. The
boot log names the numbers in force and says whether they were derived.

**What you may see that you did not see before: a `429` where yesterday there was a slow
`200`.** A request over the share waits up to `EXPENSIVE_READ_ACQUIRE_WAIT_MS` (1 s) for a slot
and is then refused with `Retry-After: 1` and one of two `errorType`s — `TOO_MANY_IN_FLIGHT`
(the caller's own requests are occupying their share) or `EXPENSIVE_SURFACE_BUSY` (the
instance's share is full). Nothing is computed and nothing is wrong with the request; the
identical retry a moment later succeeds. That is the trade, stated plainly: under sustained
overload some legitimate reports and searches are refused **in milliseconds** instead of
everything on the instance failing **after 30 s**.

**The planning reads join this bound in the same release, and there the refusal is newer still.**
`GET …/projects/{projectId}/backlog` and its per-section refreshes under `…/backlog/**` had **no
budget of any kind** before 0.18.0 — the largest single response this product produces was
unbudgeted, which was not a decision anybody made. From 0.18.0 they carry two: a per-principal
`PLANNING_REQUESTS_PER_MINUTE` (240 a minute, in memory per app node, under `RATE_LIMIT_ENABLED`)
and a share of the same occupancy bound as reports and search, with **no dial of its own**. Two
consequences to know before somebody meets them. **The Backlog page can now answer `429` where it
previously always answered `200`** — with `Retry-After`, never a narrowed section, a smaller cap or
a truncated view, and the identical retry succeeds. And because the share is *one* share, **a team
grooming a backlog can be the reason a colleague's report is refused, and the reverse.** That is
the intended trade rather than a defect to chase: the alternative was that colleague waiting out a
30 s connection timeout behind a planning read legitimately holding its connection for minutes.
The one thing a client owes here is not to answer a refusal by asking for something bigger — a
refused section refresh must not be retried as the whole view, which assembles every open section
in one transaction.

**Who is likely to notice.** A small box under real load, and anyone driving the reports, search or
planning API with more requests in flight at once than their per-user ceiling. The web UI's widest
parallel burst on this surface is the search results page's three mount queries — so **while the
per-user ceiling is 3, i.e. on a pool of 5 or more**, the acquire wait absorbs those rather than
refusing them. On a smaller pool the derived ceiling is 2 (pool 4) or 1 (pools 1–3) and that page
can meet it: the mount queries then serialise inside the one-second wait, and only past that does
one of them answer `429` and retry.

**If the numbers are wrong for your instance**, they are three `.env` lines — but they are not
independent of your pool, and the app checks the relation rather than trusting it:

- An explicit `EXPENSIVE_READ_MAX_IN_FLIGHT` must be **strictly less than `DB_POOL_MAX_SIZE`**, or
  the app **refuses to start**. At or above it the surface could hold every connection and the
  reservation this feature exists to make would not exist. **A derived share satisfies this by
  construction** — refusing to boot is the right answer to a number you typed and the wrong one to
  a number nobody chose.
- An explicit `EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL` must be
  **≤ `EXPENSIVE_READ_MAX_IN_FLIGHT`** — above it the per-user ceiling can never fire and callers
  would get the wrong refusal for their situation. **What happens then depends on who chose the
  other number.** If you pinned *both*, the app refuses to start naming both: you stated a relation
  and the relation cannot work. If you pinned only this one and let the share be derived from your
  pool — a pool of 4 derives 2, so the `3` this file shows you is already above it — the app
  **narrows your number to the derived share and logs one WARN** naming both numbers and the pool.
  A bound that exists so one surface cannot take an instance down must not take the instance down
  over a pair only half of which anybody chose.
- **An explicit share above 60 % of the pool** logs one sizing WARN naming the connections left. It
  is legitimate on a large pool and nothing silences it. A derived share is taken at exactly that
  fraction and never warns about itself.
- **Neither number bounds how long one request may hold its slot**, and there is no variable for
  that. A slot is taken before the request body is read and given back after the response is
  written, so a client that trickles bytes would otherwise hold one for the price of a socket.
  **The layer that makes that hold finite is the watchdog**: it force-releases a slot held past
  `DB_STATEMENT_TIMEOUT_MS` + 60 s and counts it in
  `hamstrack_expensive_read_permit_force_released_total`. Two further layers raise the price of the
  attempt rather than ending it, and it is worth knowing which does which. Inside the application
  the gap between two reads of a request body is pinned at 20 seconds — Tomcat's default is *not*
  "no timeout"; it lets the body inherit the connector's connection timeout (60 s, and whatever you
  set `server.tomcat.connection-timeout` to), so this tightens that gap and makes it independent of
  a dial meant for idle keep-alive connections. It ships in the app, so it applies behind any proxy,
  including your own. At the edge, a `read_body` timeout in the bundled `Caddyfile` is an absolute
  deadline on reading a whole request. **That last one does not arrive with an upgrade** — like `.env`, the
  `Caddyfile` is never replaced by [config apply](self-hosting.md#applying-repository-configuration), so if you
  run the bundled edge and your copy predates 0.18.0, copy the `timeouts` block from the repository
  by hand and reload Caddy.

So the order for giving reports more room is **raise `DB_POOL_MAX_SIZE` first, then the share**
— and if you raise the pool, re-read `POSTGRES_MEMORY_LIMIT` and `POSTGRES_WORK_MEM` with it,
since the pool is the `backends` term in that arithmetic.

**Turning it off is one variable and it is not `RATE_LIMIT_ENABLED`.**
`EXPENSIVE_READ_LIMIT_ENABLED=false` removes the bound; `RATE_LIMIT_ENABLED=false` does **not**,
deliberately — removing a bound on your connection pool should not require disabling
brute-force protection on your login page. Turning it off restores exactly the behaviour above,
so if you do it, watch `hamstrack_expensive_read_in_flight` and Hikari's `pending`.

**Is the share ever full?** `hamstrack_expensive_read_in_flight` is the gauge, per replica —
alert with `max()`, never `sum()`. The rule `ExpensiveReadSurfaceSaturated` fires on a sustained
rate of `EXPENSIVE_SURFACE_BUSY` refusals, which means the instance is under-provisioned for its
traffic rather than that anything is broken.

### Shadowed custom field keys from 0.18.0

**What you will see.** From 0.18.0 your instance names, once per boot, every custom field
definition whose key a built-in search name has taken:

```
WARN  shadowed-field-def: custom field 'Team labels' (key 'labels', id 0192…, scope workspace 0192…)
      is shadowed by the built-in search field 'label'. HQL `labels = …` answers from the built-in
      field, not from this one, and it is not offered in /search/schema. A taxonomy admin at that
      scope can rename its key (PATCH …/fields/0192…); see
      docs/self-hosting-upgrades.md#shadowed-custom-field-keys-from-0180.
```

plus one summary line. **A healthy instance prints nothing here** — the three archived
placeholders Hamstrack seeds itself (`labels`, `sprint`, `components`) are deliberately not
counted. If you see no such line, there is nothing to do.

**What it means.** Hamstrack's search language reserves its field names: a registered name
outranks any workspace's custom field of the same key, in every workspace, permanently. When a
release registers a name that one of your tenants already used as a custom field key, that field
keeps working everywhere in the product — it still renders on issues, still sits in field sets,
still comes back from the project-config endpoint — but it becomes **unreachable from search**,
and `key = "…"` starts answering from the built-in field instead. Nothing errors. That silence is
the reason this WARN exists.

**Finding them yourself**, at any time:

```sql
SELECT id, key, name, scope_workspace_id, scope_project_id
  FROM field_defs
 WHERE archived_at IS NULL
   AND lower(key) IN ('label','labels','component','components','sprint','sprints',
                      'status','type','priority','project','assignee','reporter','parent',
                      'text','created','updated','due','closed','closedat',
                      'fixversion','affectsversion','storypoints','points');
```

(The WARN is authoritative; this list is the same one the application derives from its own
registry, written out for a DBA who wants to run the query between restarts.)

**The remedy: rename the key.** From 0.18.0 a field's key can be changed *exactly while* a
built-in name shadows it. Nothing else moves — values, field-set placements and history all
reference the field's UUID, not its key — and the field becomes searchable again under the new
name straight away.

- A **workspace**-scoped field (`scope_workspace_id` set): a workspace admin with
  *Manage taxonomy* renames it in **Settings → Fields**.
- A **project**-scoped field (`scope_project_id` set): a project admin, in that project's
  field settings.
- A **global** field (both null): only an instance administrator, in **System administration →
  Fields** — and the key changes for **every workspace on the instance at once**, so treat it as
  a breaking change and announce it first.

After the rename, anyone whose **saved filter** used the old key must edit it to the new one.
Hamstrack never rewrites the text of a stored query, so those filters keep running and keep
answering from the built-in field until their owner updates them.

**If you would rather not rename**, nothing breaks: the field goes on working outside search
indefinitely. The WARN repeats on every boot so the choice stays visible.
