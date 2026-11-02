# Forgeable log records — text a row supplied may not decide where a record's fields begin and end

**Ticket:** HD-341 (2 story points, `deferred-from-gate`, filed from the HD-319 tenancy gate on 2026-09-29).
**Related:** HD-275 (the shadowed-key epic that built both affected records), HD-319 (the gate that
surfaced this), HD-340 (last edit to `ShadowedFieldStartupScan`), HD-306 (the calibration for what a
spec measures and what it leaves to the builder).
**Backlog search performed for this spec (2026-11-01):** `hd_dump.mjs` was not reachable from this
agent's session; the orchestrator's own search is recorded verbatim in the ticket ("unscoped /
workspace_id / field_defs / log forging / delimiter / oneLine / startup scan, scanned across all
issues by title. No match. Related: HD-275, HD-319, HD-123"). Nothing in this spec proposes a new
ticket, so no second search is load-bearing: every finding below is either in scope or recorded in
§15 as a question for the owner.

Every claim in this document is labelled **MEASURED** (a command was run today, 2026-11-01, against
this tree at `8be6ebf`, and its output is quoted), **READ** (`file:line`), or **INFERRED** (a
hypothesis, with the probe that settles it and what changes either way — §14).

---

## 1. Problem & goal

`ShadowedFieldStartupScan` writes one WARN per shadowed custom field, once per boot, and that WARN is
the only signal an operator gets for the shadowed-key defect. The record uses `'` as the delimiter
around two values a **row** supplies — the field's display name and its key:

```
shadowed-field-def: custom field '{}' (key '{}', id {}, scope {}) is shadowed by the built-in search field '{}'. …
```
— READ, `src/main/java/com/hamstrack/search/ShadowedFieldStartupScan.java:133-140`.

A display name that closes that quote and continues in the template's own grammar produces one
syntactically plausible record naming a workspace the row does not belong to. MEASURED today through
the real production encoder (probe P-1, §2): with the name

```
x' (key 'labels', id 0192-aaa, scope workspace DEADBEEF) is shadowed by the built-in search field 'label
```

the shipped `logstash` console format emits

```json
{"@timestamp":…,"message":"shadowed-field-def: custom field 'x' (key 'labels', id 0192-aaa, scope workspace DEADBEEF) is shadowed by the built-in search field 'label' (key 'labels') is shadowed by 'label'", …}
```

Nothing leaks — the uuid is the author's own to type. What breaks is **attribution**: the operator's
remedy is a `PATCH` against a field id at a scope, and a reader keyed on `custom field '(…)'` can be
made to name the wrong tenant and the wrong id. No validation ever reaches the row: nothing rewrites
an existing `field_defs` row, and the class exists precisely because such rows arrive by a migration,
a seeder, direct SQL, or a release older than the DTO's bound (READ, class javadoc lines 19-27), so
the forged record repeats **on every boot, for ever**.

**And the doored route is open too — measured after the first draft said otherwise.** The DTO's
`name` bound is `DisplayText.SINGLE_LINE`, which refuses line terminators and invisible or
reordering characters and **permits `'`, `"`, `(`, `)` and `=`** (MEASURED 2026-11-02 against the
compiled pattern; a 77-character forged name re-points the first `scope workspace <uuid>` a reader
extracts). So on any instance upgraded past 0.18.0 that already carries a shadowed row, a holder of
`WORKSPACE_TAXONOMY_MANAGE` can forge this record by an ordinary supported
`PATCH …/fields/{id}` on the display name, at any scope, in their own workspace — no SQL, no
migration, no seeder, no operator. They cannot *create* the row (`createField` answers 409 on a
registry-claimed key), which is why an upgraded instance is the precondition. Only the
*line-terminator* variant was ever a console-door question; the *delimiter* variant was reachable
through the live door throughout. This matters past accuracy: a reader who believes the door is shut
concludes the sink normalisation is belt-and-braces, and is the next person to remove it.

**Goal.** A value Hamstrack did not mint may not occupy a position in a log record from which it can
imitate the record's own structure — stated over the class of records that promise anything to a
machine, applied to every member of that class, sealed by a test, and written down where an extractor's
author reads it.

**Non-goals.** No new metric. No change to what is reported or when. No change to `/search/schema`, to
the rename remedy, to the reserved-key guard, or to any HTTP status. No change under `observability/`.
No new ticket (§15 carries what is out of scope as questions, per the run's constraints).

---

## 2. What was measured, and the probes that measured it

All five probes ran from the session scratchpad
(`C:\Users\zheri\AppData\Local\Temp\claude\…\scratchpad`) against the project's own dependency jars —
Boot 4.1.0 (`<logback.version>1.5.34`, MEASURED from
`spring-boot-dependencies-4.1.0.pom`), slf4j 2.0.18. **No file was added to the tree.**

| # | Probe | Measured result |
|---|---|---|
| P-1 | `StructuredLogEncoder(format=logstash)` + `StandardEnvironment`, forged name interpolated into the template | The forgery lands inside the JSON **`message` string**, verbatim and plausible. JSON encoding does not help: a regex extractor over `message` is fooled exactly as a line-oriented one is. |
| P-2 | Same encoder, same value attached with `addKeyValue` instead | Key-value pairs become **top-level JSON members with the key verbatim** (`"field.name":"x' (key 'labels'…"`), and the value is JSON-escaped by the encoder: `"` → `\"`, `\` → `\\`, newline → `\n`. A row-supplied value **cannot** close its own member, create a second one, or survive as a nested object (`a\nb"c" {"message":"forged"}` → `"field.name":"a\nb\"c\" {\"message\":\"forged\"}"`). |
| P-3 | logback `PatternLayout` with Boot's console pattern tail `%m%n`; then `%kvp`; then `%kvp{NONE}` | `%m` alone **drops key-value pairs entirely**. `%kvp` renders `key="value"` with **no escaping of embedded `"`** (`field.quoted="a "double" and a 'single' and a \"`) and **does not escape a newline** — one event rendered as two physical lines. So the safety in P-2 belongs to the **JSON encoder**, never to the key-value mechanism. |
| P-4 | A key-value pair named `message` through the logstash encoder | `IllegalStateException: The name 'message' has already been written` from `JsonValueWriter.writePair` — the **record is lost**. Key names therefore matter: they share one namespace with the schema's own members and with MDC. |
| P-5 | `log.atWarn().addKeyValue(…).log("… {} …", args)` into a `ListAppender` | `getFormattedMessage()` interpolates the args exactly as `log.warn(fmt, args)` does; `getMessage()` keeps the raw template; `getKeyValuePairs()` returns the pairs. The fluent builder is a drop-in. |

Facts read rather than run:

- Both shipped profiles log JSON: `logging.structured.format.console=logstash` in
  `application-cloud.properties:7` **and** `application-dc.properties:7` (READ). The default/`local`
  profile — dev and the test JVM — uses Boot's console pattern, whose tail is `%m%n` with **no
  `%kvp`** (READ, `defaults.xml` in `spring-boot-4.1.0.jar`).
- The shipped Loki pipeline parses each line as JSON and lifts only `level` and `deployment` into
  labels; the whole object is stored (READ, `observability/alloy/config.alloy:39-57`). A key-value
  member is therefore queryable in Loki (`| json | field_id="…"`) **with no ops change**.
- `field_defs.key` is `varchar(50)` and `field_defs.name` is `varchar(100)`, and the table has **no
  CHECK constraint of any kind on their contents** (MEASURED, `psql` against
  `information_schema.columns` / `pg_constraint`, local 15432). `role_permissions.permission` is
  `varchar(64)`, likewise unconstrained (MEASURED, same session).
- The scan's query is `… where f.archivedAt is null and lower(f.key) in :keys` (READ,
  `FieldDefRepository:68`) and `FieldRegistry.claimedKeys()` returns the registry's own lowercased
  key set (READ, `FieldRegistry:326`). So for **every** row the scan reports, `lower(row.key)` *is* a
  member of a Hamstrack-minted set — which §5 uses to keep the HQL example in the message without
  keeping a row-supplied value in it.
- `Scoped.scopeLabel()` returns `PROJECT` / `WORKSPACE` / `GLOBAL` (READ,
  `src/main/java/com/hamstrack/issue/entity/Scoped.java:25-29`) — minted, and carries no id. It
  stays what the API responses return; the fix round added `Scoped.scopeRef()` beside it for the
  id-bearing form both published records now use (§6 R2/R3).

### 2.1 Corrections to the ticket (all MEASURED or READ today)

1. **Line numbers** — `oneLine()` is at 163-172, the WARN template at 133-143, `scan()` package-private
   since HD-340. The defect is unchanged. (READ.)
2. **The impact premise is not in the document the ticket cites.** `docs/self-hosting-upgrades.md`
   §"Shadowed custom field keys from 0.18.0" (lines 1235-1302) shows the WARN verbatim at 1249,
   explains it, gives a DBA query and the rename remedy, and says **nothing about alerting** (READ).
   The sentence exists at `docs/design/shadowed-field-key-proposal.md:618` ("Cloud alerting keys on
   the `shadowed-field-def:` log prefix in Loki") — a design document — and, newly found, in a **test
   javadoc**: `src/test/java/com/hamstrack/admin/ShadowedFieldKeyRenameTest:629-630` asserts in prose
   that "`docs/self-hosting-upgrades.md` … tells operators to key their alerting on that prefix"
   (READ). That is almost certainly where the ticket's premise came from. Acceptance criterion 4 is
   therefore re-phrased from *fix the advice* to **write the contract** (§8), and the stale test
   javadoc is inside this change.
3. **No deployed alert keys on this prefix.** `grep -rn "shadowed" observability/ ops/` returns
   nothing (MEASURED). The extractor in the ticket's harm story is a **plan**, not a shipped
   mechanism. What is true today: the record is the only signal for the defect, it is documented
   verbatim for operators, the release checklist tells release authors to quote it
   (`docs/release-checklist.md:271,295` — READ), and a forged copy of it appears on every boot for
   ever. What would change if the planned alerting ships: a forged record would route a real finding
   to the wrong tenant, and the remedy the record prescribes would be run against the wrong field.
   The fix is worth making before, not after, that rule exists — but the severity argument must not be
   written as if the rule were live.
4. **The category is not inherited from the ticket.** §4 derives the rule, derives the population from
   the code, and names every exclusion with its principle. The ticket's own reason for excluding the
   permission-key record ("keys are `[a-z0-9_]` at every minting door") is **measurably insufficient** —
   the same reason it was insufficient for `field_defs.key`: there is no CHECK constraint, and a row
   can arrive without passing a door. The ticket reached the right member list through an argument
   that does not hold; a different argument, which does, is in §4.
5. **A second stale sentence, same shape, inside the category.** `ShadowedFieldKeyRenameTest:637`
   says "The key needed nothing — it has been `[a-z0-9_]` since V1". That is true of the *doors* and
   false of the *column* (MEASURED: no CHECK on `field_defs.key`), and the record this spec changes
   prints that column. Correcting it is part of this change.

---

## 3. Scope / non-goals

**In scope:** the two records that carry a documented machine-readable prefix and interpolate
row-supplied text (§4); one shared normaliser; the extractor contract in `docs/observability.md`; the
operator-facing example in `docs/self-hosting-upgrades.md:1248-1254`; the seal; the four existing
assertions and two javadoc sentences the format change invalidates (§13.4).

**Out of scope, deliberately:** every record that promises a machine nothing (§4.3), including the
permission-key ERROR and the mail-domain family — both recorded in §15 with a recommended default,
not filed. The `k=v` audit family is in the category and already compliant; it is listed, not touched.

---

## 4. The rule, and the category it defines

### 4.1 The rule, phrased as a property of a class of records

> **A log record that promises anything to a machine is built only from values Hamstrack minted.
> A value it did not mint is carried in a key-value pair, never interpolated into the message.**

Three definitions make that mechanically decidable, and each is a property rather than a list:

- **Minted** — a value whose bytes this codebase produced: a `UUID`, an enum constant, a boolean, a
  count, a timestamp, a string literal, a name taken from `FieldRegistry`, or a column whose SQL type
  cannot hold a delimiter (`uuid`, `boolean`, numeric, `timestamptz`). **Foreign** — everything else:
  a `varchar`/`text`/`jsonb` column read back from a row, a request/header/config value, or a library
  exception's message.
- **Promises something to a machine** — the record's prefix is **listed in the contract document**
  (§8). A prefix is the only machine-readable convention this tree has ever published; being *listed*
  is what makes the promise a deliberate act rather than a side effect of a template's wording. The
  **candidate** population — every template with a literal `:` before its first `{}` placeholder — is
  derived from source, so a record cannot acquire a prefix without the build asking for a verdict
  (§13.3).
- **Bounded** — a charset restriction that holds on **every** write path, not only at a door. A DTO
  `@Pattern` is not a bound on a column; a CHECK constraint is. (MEASURED: neither affected column has
  one.)

The rule's complement is the other half of the contract and is just as load-bearing: **a record whose
prefix is not listed in §8 promises nothing — its `message` is prose, it may contain text a row
supplied, and no extractor may key on it.** That sentence is what keeps the enforced population small
without making the smallness a coincidence; a template that merely happens to start with a word and a
colon is not a promise.

Why the quote is not the unit: escaping `'` would answer this record and leave the next convention
(`(`, `k=`, "the rest of the line") open. Why a *published promise* is the unit rather than "any
attributed record": a promise is the thing a reader relies on, it is written down, and — unlike harm —
it is visible in the source. §4.3 records what that boundary excludes and §13.3 seals the boundary
itself, so a record that joins the tier later cannot join it quietly.

### 4.2 Members, derived from the code

Candidate population as this spec was written (MEASURED then,
`grep -rhoE 'log\.(trace|debug|info|warn|error)\("[^"{]*:' src/main/java | …| uniq -c`): **17 records
under 13 distinct prefixes.** That census is a snapshot and is not maintained here — the live one is
derived by `SourceCallSites.logSites` and pinned entry by entry by `ForgeableLogRecordTest`'s ledger,
which is what a reader should consult; the shell grep above was also wrong by two, having missed
`DataSeeder`'s records whose colon arrives in a later concatenated literal. Of the snapshot, two
prefixes are the published ones — `shadowed-field-def:` and `field-key-rename:` — and the remaining
11 prefixes are prose (`mail send
allowed:`, `mail_send_events retention:`, `failed_email retention:`, `SSE:`, `Expensive-read share in
force:`, `Statement bound lifted for this transaction:`, `Tomcat upload timeout enabled:`,
`MaxHeapSize is not a byte count:`, `Bean Validation escaped to the advice:`, `After-commit effect
failed and will not be retried:`, `mail send allowed (anonymous):`). The members below are the two
published families plus the one already-structured record, which is listed because a member that is
already compliant is what stops the rule from being mistaken for a bug list.

| # | Record | Foreign values | Verdict |
|---|---|---|---|
| M1 | `ShadowedFieldStartupScan:133` — `shadowed-field-def: custom field '…'` (per row) | `name` (`varchar(100)`, unbounded), `key` (`varchar(50)`, unbounded) | **change** |
| M2 | `AdminFieldService:179` — `field-key-rename: field … renamed from '…' to '…'` | `oldKey` (the column, unbounded — the forged tail can re-point `to '…' at scope … by user …`, and 49 characters is ample), `newKey` (door-bounded, carried the same way so the record has one rule and not two) | **change** |
| M3 | `ShadowedFieldStartupScan:124` — `shadowed-field-def: scan could not run`, with the throwable | none — the throwable renders into the `stack_trace` member, not into `message` (READ, `LogstashStructuredLogFormatter.jsonMembers`) | compliant, no edit |
| M4 | `ShadowedFieldStartupScan:128` — the clean-instance DEBUG | none (literal) | compliant, no edit |
| M5 | `ShadowedFieldStartupScan:142` — the summary WARN | none (`int`) | compliant, no edit |
| M6 | `CspReportSink:356` — the CSP violation record (no prefix; listed because it is the **precedent**) | every browser-supplied value is already an `addKeyValue`, truncated and path-patterned | compliant — the shape M1/M2 are being moved to |
| M7 | `ShadowedFieldStartupScan#reportWithoutABuiltInName` — the per-row `shadowed-field-def:` record for a row whose claimed key this JVM cannot fold, added during implementation when M1's `?` placeholder turned out to be a published member | `name`, `key` (both carried as members); `field.builtin` is the one member it cannot supply, and the message says so in words | **new record** — defensive, not an observed case (see the note below) |

M7 did not exist when the table was first written: M1's `builtIn == null` arm printed `?` into the
message, which publishes a placeholder as a contract member, so the arm became a record of its own.
It is **defensive**. Both ways in were probed (MEASURED 2026-11-02): `FieldRegistry.find` cannot miss
a key drawn from `claimedKeys()`, and on `en_US.utf8` the only characters above U+00FF whose
Postgres `lower()` is a single ASCII letter are U+0130 and U+212A, which Java's `equalsIgnoreCase`
folds the same way — so no real row reaches it, its test arm mocks the repository, and the clause in
`docs/observability.md` says "defensive, not observed" rather than letting a reader alert on it. The
branch stays because a collation is a deployment property: an ICU or `C` collation is free to fold
differently, and the alternative at that moment is the placeholder again.

The `key=value` audit family — `RoleService` (×5), `ProjectService` (×3), `WorkspaceMemberService`
(×3), `WorkspaceService` (×2), `ProjectAccessService` (×1) — carries no prefix and is therefore
outside the tier; independently, every value it interpolates is minted (uuid, enum, boolean, count,
`Permission.key()`), with the single exception noted in §15 Q2. It is listed here so the next reader
does not have to re-derive it, and it is not edited.

### 4.3 Exclusions, each with its principle

- **`PermissionConverter:66`** — `role_permissions holds an unknown permission key '{}'`. Same *shape*
  as M1 (a `varchar(64)` column, unbounded — MEASURED — inside a quote delimiter). Excluded because
  the record carries **no published prefix** — it is not even a candidate, having no `:` before its
  placeholder (MEASURED) — so nothing is promised to a machine and no second value follows the foreign
  one that a forged tail could re-point. Note what the exclusion does **not** rest on: not on the
  write door's 422, which does not reach a row. The exclusion is guarded, not asserted — the only way
  into the tier from inside this tree is to acquire a prefix, and §13.3's candidate scan then demands
  a verdict for it (as it would for the role id this record's own javadoc already claims it carries —
  READ, `PermissionConverter:41-42` — which the code does not emit). §15 Q1.
- **`GlobalExceptionHandler` (×8), `EmailUniqueness:138/141`, `VersionService:890`,
  `WorkspaceService:1101/1104`, `DemoDataService:307`** — unlisted prefix or none, i.e. `prose`; the
  interpolated text is a library or driver message, or a Postgres constraint *name*, which is our own
  schema rather than a tenant's data.
- **`CspReportSink:232`, `DatabaseTimeoutConsistency:588/671`, `ExpensiveReadShare:131/149`,
  `StartupMemoryLogger:219/430`, `ScheduledJobHeartbeat:231`** — `prose`; the values are this
  process's own configuration or JVM state, printed to the operator who set them.

---

## 5. The remedy — chosen

> **The foreign values move out of the message into SLF4J key-value pairs; the message is rebuilt from
> minted values only.**

**The decisive reason:** it is the only one of the three candidates whose escaping is performed by a
mechanism we neither own nor have to document — the JSON encoder, MEASURED (P-2) to escape `"`, `\`
and newlines in a member value, and MEASURED (P-4) to be the single namespace those members live in.
The other two make Hamstrack the author of an escaping convention that every extractor must then
implement correctly.

Against **escape the delimiter**: it invents a private mini-format, and the escape character then needs
escaping (the one case P-2 shows the encoder already handles: `\` → `\\`). It must be applied to both
foreign values on this record and to every future one. And it leaves the extractor doing exactly what
the ticket's harm story assumes it does — splitting prose on a quote — which is the thing that broke.

Against **put the row-supplied value last**: this record has **two** foreign values, so "last" cannot
hold for both; the message's tail is the remedy sentence an operator acts on (`PATCH …/fields/{id}`),
which would have to move or go; and a positional promise about prose is the most fragile contract
shape this tree has. It also fails on its own terms — an extractor keyed on `custom field '(.*?)'`
still matches a forged fragment wherever it sits.

**The cost, stated.** Under the Boot default console pattern — dev and the test JVM — key-value pairs
are **dropped** (MEASURED, P-3), so the display name and key are invisible there. That is acceptable
and no `%kvp` is added to the pattern: `%kvp` quotes with `"` and escapes **nothing** (MEASURED, P-3),
so adding it would re-introduce a forgeable convention in the one place that currently has none. Both
shipped profiles are JSON (READ), so no deployment loses a value. What the *message* keeps is
everything an operator needs to act — the field id (the `PATCH` target), the scope, the built-in name,
and the remedy — all minted.

`oneLine()` **stays**, and now applies to the values as they are attached, for a measured reason: the
JSON encoder escapes a newline (P-2) but `%kvp` does not (P-3), so on a console an operator may
configure, a newline in a key-value pair still splits the physical record. It moves to
`com.hamstrack.common.observability.LogValues.oneLine(String)` with its javadoc, because it now has
two callers and duplicated logic is a defect; `ShadowedFieldStartupScan`'s own paragraph about *why*
the DTO bound is not the whole guard stays with the method.

---

## 6. Behaviour & rules

**R1 — key naming.** Every key is dotted and namespaced (`field.*`, `actor.*`) and MUST NOT equal any
member the schema already writes — `@timestamp`, `@version`, `message`, `logger_name`, `thread_name`,
`level`, `level_value`, `tags`, `stack_trace` (READ,
`LogstashStructuredLogFormatter.jsonMembers`) — nor the static adds `application` / `deployment`
(READ, `application-{dc,cloud}.properties:8-9`), nor an MDC key. MEASURED consequence of a collision
(P-4): the encoder throws and **the record is lost**.

**R2 — M1, the per-row WARN.** Values attached: `field.id` (uuid), `field.scope` (minted by
`Scoped.scopeRef()`, shared with R3 so one member name has one grammar), `field.key` (`oneLine`),
`field.name` (`oneLine`), `field.builtin` (the registry's canonical name). Message, interpolating
**minted values only** and keeping the single `shadowed-field-def:` prefix:

```
shadowed-field-def: a live custom field definition is shadowed by the built-in search field <builtin>
 — field id <uuid>, scope <scope>; its key and display name are in this record's field.key and
 field.name. HQL `<claimed key> = …` answers from the built-in field, not from this one, and it is not
 offered in /search/schema. A taxonomy admin at that scope can rename its key (PATCH …/fields/<uuid>);
 see docs/self-hosting-upgrades.md#shadowed-custom-field-keys-from-0180.
```

The HQL example keeps a concrete key — and keeps it **minted** — via `claimedKeyOf(f)`: the element of
`registry.claimedKeys()` that `equalsIgnoreCase` the row's key, i.e. a string from our own registry,
returned by value and never derived by lowercasing the row (Postgres `lower()` and Java
`toLowerCase` are not guaranteed to agree on every code point, and the point is that the printed
bytes are ours). Cost is a scan of ~30 keys per reported row at boot.

**That lookup is also the only one** — revised in the HD-341 fix round, after the tenancy gate, and
the sentence it replaces ("the query guarantees such an element exists for every reported row") was
measurably wrong. Three relations decided this one record and they disagree. MEASURED 2026-11-01 on
a key spelled `SPRINT` with U+0130 for its `I`: Postgres `lower()` answers `sprint`, so the query
returns the row; Java's `toLowerCase(ROOT)` of that key is seven characters and is **not** `sprint`,
so `FieldRegistry.find` — which lowercases its argument — missed; `equalsIgnoreCase` folds the pair
to true, because `Character.toLowerCase(U+0130)` is `'i'`. The record therefore read *"shadowed by
the built-in search field `?`"* on every boot of such an instance, and this change is what would
have published that `?` as a contract member while removing the row's own key from the message.

The built-in descriptor is now resolved **through** the claimed key, so `?` is unreachable. The one
case left — the claimed-key lookup finding nothing at all — is reported by a **second record** under
the same prefix, carrying four of the five members and saying in words that no built-in name can be
resolved for this row. It is a report and not a drop, which is why it needs no counter; it has its
own ledger entry, its own clause in the published table, and its own arm
(`ShadowedFieldStartupScanTest#aRowWhoseKeyThisJvmCannotFoldIsReportedWithoutABuiltInName`).

**R3 — M2, the rename INFO.** Values attached: `field.id`, `field.scope` (`scopeRef()` — minted,
id-bearing), `field.key.old` (`oneLine`), `field.key.new` (`oneLine`), `actor.id` (uuid or absent).
`scopeRef()` and not `scopeLabel()`: one published member name may not carry two grammars, and until
the fix round this record said `WORKSPACE` where the startup scan said `workspace <uuid>` — so a
rule routing a finding to a tenant got a uuid from one prefix and a label from the other, and this
record named no tenant at all. Message:

```
field-key-rename: field <uuid> renamed at scope <scope> by user <uuid> — the old and new keys are in
 this record's field.key.old and field.key.new.
```

**R4 — one rule, one helper.** Both call sites use the fluent builder
(`log.atWarn()/atInfo().addKeyValue(…).log(fmt, args)`), MEASURED (P-5) to interpolate args into
`message` and expose the pairs on the event exactly like the 2-arg form.

**R5 — nothing else changes.** Same level, same prefix, same one-record-per-row cadence, same
`catch`-everything degradation (M3), same quiet clean instance (M4), same summary (M5).

---

## 7. Edge cases & failure modes

| Case | Behaviour |
|---|---|
| `name` or `key` is `null` | `oneLine(null)` returns `null`; `addKeyValue(k, null)` writes a JSON `null` member. Both columns are `NOT NULL` (MEASURED), so this is a defence, not a path. |
| Name carries a newline / CR / NEL / U+2028 | `oneLine` flattens it to a space before attachment (`\R`); the encoder escapes LF, CR and NEL in JSON but **emits U+2028/U+2029 raw** (MEASURED 2026-11-02 on the encoded codepoints), and escapes none of them on a `%kvp` console (P-3). One terminator becomes one space, so a **CRLF** pair collapses as a single unit and the value is one character shorter — the obvious "length unchanged" is false for the commonest Windows value this will see. Nothing hidden. |
| Name carries `"`, `\`, `{`, `}` or a whole JSON object | Escaped by the encoder (MEASURED, P-2). No member can be created, closed or nested. |
| Name is 100 chars of quotes; key is 50 chars of them | No bound is needed: the member is one JSON string whatever it holds. |
| Two shadowed rows in one tenant, one of them forged | One record each, each with its own `field.id`; the forgery cannot merge or hide the other (the existing "exactly one line names this collision" assertion is kept). |
| A reader on a plain console | Sees the message: id, scope, built-in name, remedy. Does not see the key or name (MEASURED, P-3) and is told so by the message text and by §8. |
| A key-value key collides with a schema member, **or one member name is attached twice** | The record is lost (MEASURED, P-4 and §13.7) and the next record on the same thread is corrupted with it. Sealed by arm A5 *and* by running the production encoder over every published record (§13.7) — the duplicate is invisible to every map- or set-shaped reader, so the list alone could not see it. |
| The scan query throws | Unchanged: one WARN, boot continues (M3). |
| An operator overrides `logging.structured.format.console` to empty | Their console is prose with no promises, per §8's complement; nothing in the product keys on it. |

---

## 8. The contract — what an extractor may rely on, and what holds it

**Where it lives:** `docs/observability.md` § *Part A — app instrumentation (both modes) → Logging*
(READ, lines 125-153 — the one place that already states the JSON schema, is addressed to both modes,
and is read by whoever writes a Loki rule). **Not** `docs/self-hosting-upgrades.md`: that file is
per-release upgrade notes whose `###` headings must name a release (READ,
`UpgradeNotesCoverageTest:46`), and a standing contract is not an upgrade step. The design-document
sentence at `shadowed-field-key-proposal.md:618` stays as history and is not the contract.

The section gains, in ~8 lines:

1. Key-value pairs are emitted as **top-level JSON members** alongside MDC (MEASURED, P-2) — the
   existing paragraph lists MDC and omits them, which is now incomplete.
2. **A record whose first token is a published prefix** (`shadowed-field-def:`, `field-key-rename:`)
   **may be keyed on by its prefix and by its `field.*` members. Its `message` is prose for a human
   and carries no tenant data; never parse it.** Every value a row or a request supplied is in a
   member, verbatim.
3. **Every other record promises nothing**: `message` is prose, may contain text a row supplied, and
   no rule may key on it.
4. The per-prefix member list, which is also what the seal pins.

**What makes it true** rather than a stale paragraph: `ForgeableLogRecordTest` (§13) asserts the
members for both prefixes against the running code, asserts the documented example in
`docs/self-hosting-upgrades.md` names the same members, and fails on any **new** prefixed record that
the contract does not list.

---

## 9. Data model impact

None. No migration, no entity change, no column, no `FieldRegistry` name. The two columns involved
were measured (`field_defs.name varchar(100)`, `field_defs.key varchar(50)`, no CHECK) and are
deliberately left as they are: a CHECK constraint would not be retroactive and the fix must hold for
rows that already exist. `migration-reviewer` is not armed by this diff.

---

## 10. API surface

None. No endpoint, no DTO, no status code, no `openapi.yaml` change; `api-docs-sync` is not armed.
`GET …/search/schema`'s `shadowedFields` is untouched.

---

## 11. Frontend impact

None. No SPA file changes; `DESIGN.md` is not involved; `browser-qa` is not armed.

---

## 12. DC / Cloud implications

Identical in both modes, and that is measured rather than argued: both profiles set
`logging.structured.format.console=logstash` (READ), so both emit the same JSON object with the same
members. **No new property, no env var, no profile gate, no toggle** — a per-mode switch here would
mean one mode's operator gets a weaker record for the same defect, which is the argument
`shadowed-field-key-proposal.md:605-614` already settled for this feature. The Alloy pipeline needs no
change (READ, §2). `dc-cloud-guard` is armed only if the builder adds a property; it should not.

---

## 13. Acceptance criteria — over the category

Seal class: **`src/test/java/com/hamstrack/common/observability/ForgeableLogRecordTest.java`** (new;
`@SpringBootTest`, logback `ListAppender`, plus Boot's own encoder — MEASURED to start outside Spring
with a `StandardEnvironment`, P-1/P-2).

### 13.1 AC-1 — no prefixed record interpolates text a row supplied
For **every** member with verdict *change*, a row is planted through the repository (the way this
population really arises) whose foreign value is a complete forged record embedding a **different,
real workspace's uuid**. For each: exactly one record names the field id; the record's
`getFormattedMessage()` **does not contain that other workspace's uuid** and does not contain the
planted text; the record's key-value pairs carry the planted text **verbatim** (flattened, never
hidden). Shape: two behavioural arms, A1 (M1, via `scan()`) and A3 (M2, via the admin rename door).

### 13.2 AC-2 — the structural guarantee, asserted through the production encoder
The same event, encoded with `StructuredLogEncoder(format=logstash)`, parses as **exactly one** JSON
object; `message` does not contain the other workspace's uuid; `field.name` equals the planted value
byte-for-byte; parsing does not throw (P-4's loss mode). Arm A2. This is the arm that makes §8's
promise a measurement rather than a sentence. Since §13.7 the encode itself is
`LogRecords.oneJsonObject` and runs over **every** published record this suite captures rather than
over M1 alone, which is where the security round found the hole.

### 13.3 AC-3 — the population cannot grow in silence
A source scan over `src/main/java/**/*.java` collects every log template carrying a literal `:` before
its first `{}` placeholder, with a floor that refuses a
broken regex rather than reading it as an empty population. The floor is not restated here, because a
count restated in prose goes stale one entry before the list does and this document has already been
wrong about this one: the live bound and the measurement behind it are
`ForgeableLogRecordTest.LITERAL_TEMPLATE_FLOOR` and its javadoc. Every candidate must appear in the test's ledger with a verdict
— `machine-readable` (its prefix is listed in `docs/observability.md`) or `prose` (it promises
nothing) — and every `machine-readable` entry must (a) name its key-value members, which are compared
against the keys the code actually emitted in A1/A3, and (b) have a template containing **no quoted
placeholder** (`'{}'`, `"{}"`). The failure message is the propagation checklist: give the record a
verdict; if it is machine-readable, move its foreign values into members and list them in
`docs/observability.md`. Arm A4. (A hand-list with a floor is the weaker shape; this is a derivation
of the *candidates* with a ledger for the *verdicts*, because no source scan can tell minted from
foreign — the judgement is recorded, never inferred. Keeping the candidate net wider than the tier is
deliberate: it is what puts §15 Q2's mail family in front of a reader instead of outside the scan.)

### 13.4 AC-4 — key hygiene, and the doc moves with the format
A5: the keys both members emit are disjoint from the schema's members and the static adds; the failure
message states the measured consequence (the record is lost, not merely renamed). A5 is **not** the
whole of key hygiene — it cannot see a member name attached *twice*, which costs the record exactly
the same and which no set- or map-shaped reader can see; §13.7 is what closes that and says which
four of the eleven reserved names A5 remains the only guard for. A6 **parses the
published table** under `docs/observability.md` § *What an extractor may rely on* and compares it as
a set, in both directions, against the prefixes of the ledger's machine-readable entries — resolved
through the same source walk A4 uses — plus their members per prefix, plus that no `prose` prefix
appears in it. (Checking the document for two hardcoded prefix literals, which is what the first
draft of this seal did, is circular in the direction that matters: the rule's own definition of
"promises something to a machine" **is** "listed in that table", so a third row added there would
join the published contract with no ledger entry and no member pinning, and a record demoted out of
the tier with its row left behind would leave the document promising what the code no longer
guarantees. Both green.) A6 also asserts the fenced example in
`docs/self-hosting-upgrades.md` §"Shadowed custom field keys from 0.18.0" contains every
member key the scan emits and **no** `custom field '` text — the fenced example at
`docs/self-hosting-upgrades.md:1248-1254` is a verbatim copy of the record and must move with it
(compared on member names, not bytes: the block carries ids and a timestamp). While rewriting it, note
that the example is currently plain console text while **both** shipped profiles emit JSON (READ,
§2) — so the replacement shows the JSON object, abridged, with the `field.*` members visible. An
operator comparing the doc to their own stdout has never been able to match this block.

### 13.5 Negative control, and what must stay green
- **Planted:** the defect, which means restoring the interpolating template in
  `ShadowedFieldStartupScan` *and* planting the forged display name of §1 with a second real
  workspace's uuid inside it. The forged row **alone**, against the fixed code, is not a control —
  it leaves A1 and A2 green, which is the change working.
- **Red, MEASURED rather than predicted** (2026-11-01, `Tests run: 7, Failures: 4`): A1, A2, **A4
  and A6**. A4 reds because its ledger key is the owning method plus the template, and this defect
  cannot be planted without changing the template. A6 reds because it resolves each published prefix
  *through that ledger*, so a stale ledger makes the table unverifiable rather than verified. The
  first draft of this section predicted both green and was wrong about both.
- **Green while it is red:** A5 and **both M2 arms** (`aRenameRecordCarries…`,
  `aRenameIsRefused…`). So what the control demonstrates is *member* independence — an M1 defect
  reds every arm that reads M1 and no arm that reads M2 — rather than "two of five", which is the
  weaker claim the first draft tried to make.
- **Must stay green through the change:** `ShadowedFieldStartupScanTest`
  `springStillRegistersThePackagePrivateReadyListener`, `v3sArchivedSeedPlaceholdersProduceNoWarning`,
  `anArchivedCollisionIsNotShadowed`, `aScanWhoseQueryThrowsWarnsAndDoesNotPropagate`;
  `ShadowedFieldKeyRenameTest.aDisplayNameCarryingALineTerminatorIsRefusedAtBothDoors` (the door bound
  is not weakened by this change).
- **Must be amended by this change, named so they are not discovered late** — a format change that
  left them alone would be a red build, which is correct:
  - `ShadowedFieldStartupScanTest.aLiveCollisionIsNamedWithItsIdKeyScopeAndTheBuiltInThatTookIt` —
    `contains("Team components")` and `contains("components")` move to key-value assertions;
    `contains("'component'")` becomes `contains("component")` (the built-in name is minted and stays in
    the message, its quotes do not).
  - `ShadowedFieldStartupScanTest.aLineTerminatorInARowsNameCannotForgeALogLine` — the one-record
    assertion stays (`oneLine` stays, for the `%kvp` reason); `contains("Team labels")` moves to the
    key-value pair; `contains("custom field 'nothing'")` **inverts** to `doesNotContain`, which is the
    whole point.
  - `ShadowedFieldKeyRenameTest:629-630` javadoc — delete the false claim that the upgrade notes tell
    operators to alert on the prefix; point at §8's contract instead. `:637` — "the key needed
    nothing — it has been `[a-z0-9_]` since V1" is true of the doors and false of the column.
  - `PermissionConverter:41-42` javadoc claims the ERROR carries "the key and the row's role"; the code
    logs only the key. Weaken the sentence (one word) — it is prose about a record this spec touches,
    and leaving it would be the same defect class as item 2 of §2.1.

### 13.6 Category block for the Stop hook
`rule`: "a production log record carrying a published machine-readable prefix is built only from
minted values; foreign values are key-value members". `members`: the members of §4.2, verdicts
included — named by pointer and not by range, because a range goes stale one entry before the table
does and this one did, on M7.
`sealedBy`: `ForgeableLogRecordTest` plus the seals named in `run.json` — a list in a spec goes
stale the way a count does, and the hook's copy is the one the build reads.

### 13.7 Security round (2026-11-02) — the seal was blind to the *other* loss mode it names

The forgery is closed and the remedy is right; what was not sealed is §7's own row "a key-value key
collides with a schema member → the record is lost". Three measurements, all 2026-11-02 against
logback 1.5.34 / Boot 4.1.0:

- **A duplicated member name on one record costs exactly what a schema collision costs it** —
  `IllegalStateException: The name 'field.name' has already been written`, record lost. Every
  reader in the seal was map- or set-shaped (`pairs()` returns a `LinkedHashMap`,
  A4 compared `Set.copyOf`), and both shapes answer *identically* for a clean record and for one
  the encoder refuses. Planted against the shared derivation on a temp tree: the parser reported
  `[field.id, field.key.old, field.key.old]` faithfully and the comparison returned `true`.
- **The loss is worse than one record and the damage is thread-scoped, not per-encoder.** The
  buffer is not reset on a throw; a *brand-new* `StructuredLogEncoder` on the same thread still
  emitted the failed record's fragment in front of the next one, that line does not parse as JSON,
  the corruption is exactly one record deep, and another thread was unaffected. In production the
  appender encodes on the logging thread, so the casualty is the next record from *any* logger
  there. The throw is inside logback, so **no counter is possible** — a build is the only witness
  available, which is what made the gap expensive.
- **Only one of the three published records had ever met the production encoder.** A2 encoded M1;
  M2 and M7 were pinned on member *names* by the collapsing readers above.

Fix, all of it in the seals: `LogRecords` (new, `common.testsupport`) owns one encode — on a fresh
thread per record, so no arm inherits another's corrupted buffer — and
`LogRecords.oneJsonObject` now runs over **every** published record this suite captures: A2 (M1),
A3 (M2), A7, and in `ShadowedFieldStartupScanTest` inside the *capture helper* and the two
mock-driven arms, so M3, M5 and M7 are covered and a fixture added later cannot skip it. A4 asks
for duplicates before comparing sets; A5 asks it of each declared list separately (`field.id`
belongs to more than one, so the concatenation cannot be the unit). `LogRecordsTest` is the shared
derivation's own witness, with floors in **both** directions — six shapes it must accept and six it
must refuse — because one shared check that has stopped looking is green everywhere at once, where
two hand-written ones would red each other.

**What this does *not* demote.** `RESERVED_MEMBERS` stays a live guard, measured over all eleven
names: seven throw unconditionally; `tags` and `stack_trace` throw only on a record carrying a
marker or a throwable, so an arm whose fixture has neither reports them clean; and `application`
and `deployment` never throw under the bare `StandardEnvironment` the encoder is given, because
they are written only when `logging.structured.json.add.*` is configured. For four of the eleven
the list is the only guard in the tree — so "the encode subsumes it" would have been false.

---

## 14. Observability contract (§13 of the pipeline spec)

| Failure mode | Witness in production | Drill that proves the witness fires |
|---|---|---|
| A row's text forges a plausible record and mis-attributes a finding | **None is possible at runtime** — a writer cannot know a value was crafted, and a forged record is by construction indistinguishable from a real one. The witness is therefore a **build-time** one: A1/A2 fail if any foreign value can reach `message`. Stated plainly because the alternative is a counter that counts nothing. | The negative control of §13.5, whose measured verdict is recorded there. A pointer, not a restated list: the first draft of this row named the arms itself, predicted A4 green, and was refuted by the run — and a restated verdict goes stale one arm before the section does. |
| The report stops running (scan silently absent) | Unchanged and already witnessed: `ShadowedFieldStartupScanTest.springStillRegistersThePackagePrivateReadyListener` (READ, HD-340). | Existing. |
| The scan cannot query | The `scan could not run` WARN (M3). | `aScanWhoseQueryThrowsWarnsAndDoesNotPropagate`. |
| A record is lost because a key collided with a schema member | logback's own appender error stream, and the member's absence. Sealed by A5. | P-4 (measured today); A5 re-measures it at build time. |

**No counter and no gauge, with reasons.** This change introduces **no drop path**: `oneLine` replaces
and never removes, the key-value pairs carry the full value, and nothing is skipped — so there is
nothing to count. The class javadoc's existing argument against a Micrometer gauge (a boot-set gauge
goes stale the moment an admin renames out of the collision — READ, lines 61-63) is untouched and is
not contradicted here; this spec adds only that a *forgery* counter is unimplementable in principle.
**No ops change and no ops gate:** nothing under `observability/`, `ops/`, `.github/workflows/`,
`Dockerfile`, `Caddyfile`, `pom.xml` or `docker-compose*` is touched, and the Loki pipeline needs no
edit because it already stores the whole JSON object (READ, §2). If the planned Cloud rule ships
later, its read-back belongs in `ops/CHANGELOG-console.md` at that time, not here.

---

## 15. Probes the builder runs first, and open questions

### 15.1 INFERRED premises — each with its probe and its consequence

| # | Premise (INFERRED) | Probe | If it refutes |
|---|---|---|---|
| I-1 | The five probes of §2 reproduce inside the suite's own JVM (Boot's `StructuredLogEncoder` starts with a `StandardEnvironment`, no Spring context needed). | A2's first run; print the encoded line once. | A2 degrades to asserting the key-value pairs on the event plus a unit assertion that `message` holds no foreign text; §8's promise then rests on P-2 alone and the contract says so. |
| I-2 | `ListAppender` + `ILoggingEvent.getKeyValuePairs()` is usable from the existing test helpers (the file already uses `ListAppender`, READ, `ShadowedFieldStartupScanTest:325-342`). | Compile A1 against the existing `attach()/detach()` helpers. | Read the pairs through a custom `AppenderBase` instead; no spec change. |
| I-3 | `AdminFieldService.updateField` can be driven to a rename whose **old** key contains `'` by planting the row through `FieldDefRepository` first (the DTO bounds only the new key). | A3's fixture. | If a door refuses the *rename* of such a row, the plant moves to the archived/unarchived path or A3 asserts the service's own log through a direct service call; the member's verdict does not change. |
| I-4 | No other test or doc asserts the two record formats beyond the five sites in §13.4/§13.5. | `grep -rn "shadowed-field-def\|field-key-rename\|custom field '" src/test src/main/frontend docs` before editing. | Amend what it finds, in the same change — a format claim left behind is the defect class this ticket is about. |

### 15.2 Open questions for the owner (recommended defaults; nothing filed, per the run's constraints)

- **Q1 — `PermissionConverter:66`.** Same shape, no published prefix, measured-unbounded column
  (`varchar(64)`, no CHECK), and a javadoc that already claims an attribution the code does not emit.
  **Recommended default: leave it in this run** (it is outside the tier by §4.1 and the exclusion is
  guarded by A4), and treat "give that record a prefix, a role id and key-value members" as one
  deliberate edit later — at which point A4 turns red by itself and the change is forced rather than
  remembered.
- **Q2 — the mail-domain family.** `recipientDomain={}` / `a {} address` (≈13 records in
  `WorkspaceService:756`, `RecipientMailThrottle:667/670/696/700`, `MailService:269/278/306/346`,
  `UndeliverableMail:193/227/238/265`) interpolates `MailAddresses.domainOf(...)`, which is a raw
  substring after the last `@` (READ, `MailAddresses:65-74`) of a value bounded in **length only**
  (READ, `requireStorableAddress`) and reachable from a row. No published prefix, so outside the tier
  — but unlike the audit family it is not minted either, and I will not record it as clean.
  **Recommended default: leave unchanged in this run**, and note that §8's complement ("no rule may
  key on an unprefixed record") is what currently makes it harmless.
- **Q3 — an ADR?** The decision has an external consumer (an operator's alert rule), which is the
  hard-to-reverse part. **Recommended default: no ADR.** The contract belongs where its consumers read
  it (`docs/observability.md`, §8) and is held by a test; a second copy in `docs/adr/` would be a
  paragraph with nothing holding it — the exact failure mode §2.1 item 2 is about. Revisit if the
  Cloud alerting rule ships, which is when the format becomes somebody else's dependency.

---

## 16. Highest-risk assumption

That **a published prefix is the right boundary for "promises something to a machine"**. It is the only
machine-readable convention the tree has ever published, and it is greppable, which is what makes the population derivable
instead of remembered. But an operator can key a Loki rule on any substring they like, and if one ever
does so against a `prose` record, §4.1's complement becomes false by someone else's action rather than
by ours. Two things keep that from being a silent failure:
the contract says it in the document those rules are written from (§8), and A4 re-derives the
candidate population from source on every build, so a record that acquires a prefix — the only way to
*join* the promise from inside this tree — cannot do it quietly. What A4 cannot see is a rule written
outside the repository; that residual is stated here rather than in the acceptance criteria.
