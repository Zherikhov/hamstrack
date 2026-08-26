# Restoring FK integrity on `issues.type_id` and `issues.status_id`

**Ticket:** HD-13 · **3 points** · fix version **0.17.0** · branch `feat/release-0.17.0`
**Status:** proposed, ready to build
**Touches:** one new migration (`V19`), `AdminCatalogService` (3 lines), `GlobalExceptionHandler`
(one handler), the `V1` baseline comment, `docs/release-checklist.md`, both API references,
`docs/design/cross-tenant-data-exposure-audit.md` §4.4.

---

## 1. Problem & goal

`issues.type_id` and `issues.status_id` are `NOT NULL UUID` columns with **no foreign key**. The
originals were dropped by the pre-squash `V6`'s `DROP TABLE statuses/issue_types CASCADE` and never
recreated; the single-baseline squash carried the gap forward and documented it in a comment at
`V1__init_schema.sql:355`. Every other reference on that table is constrained —
`priority_id`, `assignee_id`, `reporter_id`, `parent_id`, `project_id`, `workspace_id`,
`component_id`, `sprint_id` — so these two are the only columns in the product's hottest table
where a row can point at nothing and the database will not object.

Nothing has stranded an issue yet: the live development database holds **77,619 issues, zero nulls
and zero dangling references on both columns**. That is the application doing its job, not the
schema. The goal is to make it a property of the data rather than a property of the current call
sites, so that a delete path written next year cannot silently produce an issue whose status does
not exist — a row that renders as a blank board column, disappears from every status filter, and
cannot be repaired through the UI because no screen can name a status that is not there.

**Success:** two constraints exist, the migration applies to prod-shaped data in one deploy window,
every admin delete path still works, and the one condition the constraints can now raise has a
refusal a human can act on instead of a stack trace.

---

## 2. Scope

**In scope**

1. `V19__issues_taxonomy_fk.sql` — `issues_type_id_fkey` → `issue_types(id)` and
   `issues_status_id_fkey` → `statuses(id)`, plain form, `NO ACTION`.
2. A pre-flight audit (dangling references, and — advisory — pre-existing cross-tenant references)
   to run against production before the release.
3. Closing the one write path that can point an issue at a catalog row from another tenant:
   the `replaceWithId` lookup in `AdminCatalogService.deleteStatus` / `deleteIssueType` /
   `deletePriority`. **This is an addition to the ticket's scope; see §4.3.**
4. A `DataIntegrityViolationException` handler so a `23503` is a `409`, not a `500`.
   **Also an addition; see §4.4.**
5. Replacing the `V1` comment, adding two rows to the release-checklist inventory, and correcting
   the standing recommendation in the cross-tenant audit that this migration makes dangerous (§4.5).

**Out of scope / non-goals**

- **Enforcing tenancy in the database for these two columns.** It is not possible (§3), and
  pretending otherwise is the failure mode this document exists to prevent.
- **Reclassifying any other `DataIntegrityViolationException`.** `23505` (e.g. the
  `issues_project_id_number_key` collision) keeps today's outcome. Turning unique violations into
  `409`s app-wide is a separate decision with a separate message.
- Adding FKs anywhere else, converting any existing constraint to the two-step form, or touching
  `priority_id`.
- A `DELETE /projects/{id}` or `DELETE /workspaces/{id}` endpoint. None exists; §4.2 explains what
  the day one is added must look like, and stops there.

---

## 3. What the constraint buys, and what it does not

This section is the point of the ticket. Write it into the migration file, not only here.

### 3.1 What it buys

A **stranded** reference becomes impossible. `issues.status_id` and `issues.type_id` can no longer
name a row that does not exist, in any path — including paths written later, including a
`psql` session, including a cascade. A catalog `DELETE` that would strand issues is refused by the
database rather than by a service method somebody remembered to write.

### 3.2 What it does not buy

**Tenancy.** A reader who sees these two constraints next to `issues_component_fk` will assume they
give the same guarantee. They do not:

```
issues_component_fk   FOREIGN KEY (component_id, workspace_id) REFERENCES components(id, workspace_id)
issues_sprint_fk      FOREIGN KEY (sprint_id,    workspace_id) REFERENCES sprints(id,    workspace_id)
issues_status_id_fkey FOREIGN KEY (status_id)                  REFERENCES statuses(id)
issues_type_id_fkey   FOREIGN KEY (type_id)                    REFERENCES issue_types(id)
```

The first two make a cross-tenant reference **impossible in the database**. The last two do not:
workspace A's issue may point at a status that is project-scoped to workspace B's project, and
PostgreSQL will accept it, because the parent row exists. `issues_priority_id_fkey` has been in the
schema since `V1` and proves the point — it has never prevented a cross-tenant priority reference,
and §4.3 shows the API call that produces one today.

### 3.3 Why the composite shape is impossible here — verified, not assumed

Seven formulations were considered. All fail, and the reason is structural.

1. **Composite FK `(status_id, workspace_id) → statuses(id, scope_workspace_id)`.** `statuses` and
   `issue_types` have no `workspace_id` column at all; the nearest thing is `scope_workspace_id`,
   which is **NULL for a global row and NULL for a project-scoped row** (`statuses_scope_ck` forbids
   both scope columns being set, and a global row sets neither). FK matching is equality, and
   `NULL = <a workspace id>` is UNKNOWN, so no parent row matches — the constraint would reject
   **77,545 of 77,619** type references and **77,491 of 77,619** status references on day one.
   MATCH SIMPLE's escape hatch ("if any *referencing* column is NULL the constraint is satisfied")
   does not apply: `issues.workspace_id` is `NOT NULL`.
2. **A different unique key on the parent.** Cannot help, because the obstacle is **cardinality, not
   key shape**: one global catalog row is referenced by issues in every workspace on the instance. A
   composite key that includes a workspace can be satisfied by exactly one workspace per parent row.
   To make it work you would have to materialise one status row per workspace — that is, delete the
   global catalog, which is the design.
3. **`MATCH PARTIAL`** (which would allow a partly-NULL match): **not implemented in PostgreSQL**.
   The documentation says so explicitly; it is not a version question.
4. **A partial / conditional FK** ("only when the parent is scoped"): does not exist.
   `ADD CONSTRAINT … FOREIGN KEY` takes no `WHERE`. Partial *indexes* exist; partial *foreign keys*
   do not.
5. **A generated column on `issues`** carrying the parent's scope: generated columns may only
   reference other columns **of the same row**, and the scope lives in another table.
6. **Two discriminated FKs on `issues`** (a `status_scope` column plus a nullable global/scoped pair):
   works in principle, doubles the column count on the hottest table, changes every read and write
   path, and buys a rule the application already applies. Rejected as disproportionate to a 3-point
   integrity fix.
7. **A `BEFORE INSERT OR UPDATE` trigger on `issues`.** Expressible, and rejected on four counts: a
   per-row PL/pgSQL lookup on the hottest table in the product; invisible to the ORM and to
   `ddl-auto=validate`; it would raise exactly the unhandled-`23503`-as-500 this ticket is fixing;
   and — decisively — it would encode a **weaker** rule than the one already enforced. A status is
   legal for an issue because it is in the project's **bound workflow**, not because its scope
   matches the tenant. A "same tenant" trigger would cost per-row time and catch nothing
   `ProjectConfigService` does not already catch.

**Conclusion: plain single-column FKs, `NO ACTION`.** Consistent with `issues_priority_id_fkey`,
which is the only precedent this table has for a taxonomy reference.

`ON DELETE` clause, for completeness: `CASCADE` would delete issues when a status is deleted, which
is catastrophic and is the opposite of the point; `SET NULL` is impossible against a `NOT NULL`
column; `RESTRICT` differs from the default `NO ACTION` only in that it cannot be deferred, and
`priority_id` uses the default. Use the default and say nothing.

### 3.4 What actually enforces the tenancy half, by name

So that the next reader does not go looking for it in the schema:

- **`ProjectConfigService.requireStatusOffered` / `requireTypeOffered`** (422) — every issue create
  and every issue update goes through them (`IssueService:184-185`, `IssueService:435-438`). A write
  may only name a status in the project's *effective workflow* and a type in its *effective type
  set*.
- **The binding is itself scope-checked** when it is written (`ScopedProjectAdminService.applyBindings`,
  `AdminProjectService.updateBindings`), and the statuses/types a workflow or type set may contain
  are resolved with `findByIdVisibleTo(id, scope.visibleWorkspaceId(), scope.visibleProjectId())`
  (`AdminWorkflowService:160`, `AdminIssueTypeSetService:120`, `AdminPrioritySetService:124`).
- So the chain is **project → binding (scope-checked) → container membership (scope-checked) →
  status/type**, and it is closed — *except* at the one place that writes `issues.status_id` and
  `issues.type_id` without going through it, which is the admin remap. That is §4.3, and closing it
  is what makes the sentence above true rather than nearly true.
- Keeping the *issue* in one tenant is `issues.workspace_id` plus workspace-scoped queries
  everywhere; the taxonomy row is deliberately shareable across tenants, which is what "global
  catalog" means, so no constraint on `issues` alone can express "same tenant".

---

## 4. The delete-with-remap audit

### 4.1 Every path that can delete a `statuses` or `issue_types` row

| # | Endpoint | Actor / authorization | Scope |
|---|---|---|---|
| 1 | `DELETE /api/admin/statuses/{id}` · `/api/admin/issue-types/{id}` | system role `ADMIN` (`hasRole("ADMIN")` on `/api/admin/**` in `SecurityConfig`; no per-method check) | global |
| 2 | `DELETE /api/workspaces/{ws}/admin/statuses/{id}` · `/issue-types/{id}` | workspace member (404 otherwise) + `WORKSPACE_TAXONOMY_MANAGE` (403) | workspace |
| 3 | `DELETE /api/workspaces/{ws}/projects/{p}/admin/statuses/{id}` · `/issue-types/{id}` | `resolveProject` (404 for missing workspace/project or non-member of the **workspace**) + `PROJECT_TAXONOMY_MANAGE` (403) | project |

All three land in `AdminCatalogService.deleteStatus` / `deleteIssueType`. **There is no fourth
path**: `statusRepository.delete` and `issueTypeRepository.delete` are each called exactly once in
`src/main/java` (`AdminCatalogService:138` and `:316`), there is no `@Modifying` bulk delete on
either table, and no native `DELETE`. Every caller passes through `requireStatus`/`requireIssueType`,
which resolve `findByIdAtScope` — so a caller can only delete a row at **their own** scope.

**Verdict: all three are FK-safe, and the reason is worth naming precisely.** The guard counts with
`issueRepository.countByStatus` / `countByType` — **unscoped, all tenants** — and the remap is
`remapStatus` / `remapType`, an equally unscoped bulk `UPDATE`, in the same `@Transactional` method,
issued before `delete(...)`. If either the count or the remap were narrowed to the caller's scope,
rows would survive the remap and the `DELETE` would raise `23503`. See §4.5 — there is a standing
recommendation to narrow exactly that count.

Two details a builder should not "tidy":

- `@Modifying(clearAutomatically = true)` on the remaps is correct and load-bearing. It detaches the
  entity, so `statusRepository.delete(s)` merges it back before removing — harmless, one extra
  `SELECT` — and it is what stops a stale `Issue` loaded earlier in the transaction from flushing the
  old `status_id` back over the remap.
- The remap runs **before** the delete, as an immediate bulk `UPDATE` rather than a queued action,
  so the wire order is `UPDATE` → `DELETE` regardless of Hibernate's flush ordering. Do not move it.

### 4.2 Cascades: deleting a workspace or a project

`statuses` and `issue_types` each carry `scope_workspace_id → workspaces(id) ON DELETE CASCADE` and
`scope_project_id → projects(id) ON DELETE CASCADE`. Deleting a workspace or a project therefore
deletes its scoped taxonomy rows, and the live data shows 74 issues on project-scoped types and 128
on project-scoped statuses, so the case is real.

**Nothing in the application deletes a project or a workspace.** There is no `DELETE /projects/{id}`
and no `DELETE /workspaces/{id}`; no `projectRepository.delete*` or `workspaceRepository.delete*`
call exists in `src/main/java`. Both are **archived**, never removed. The only deleters in the tree
are `SprintLifecycleTest:304` (`projectRepository.deleteById`) and an operator running SQL by hand.

**MEASURED (AC-7, 2026-08-21). The prediction below was wrong; this paragraph is the finding.**
The concern was that `statuses`/`issue_types` cascade on project delete and that the inner
cascade `DELETE`'s own immediate `NO ACTION` check might fire before the sibling `issues`
cascade had removed the referencing rows. Executed against a scratch database built by
replaying `V1`..`V19` in order, so the constraint-creation order is the real one:

- **Deleting a project whose OWN issues use its OWN project-scoped status, type and priority
  cascades cleanly** — before and after `V19`. The trigger-order race does not occur. The outer
  `DELETE` queues all of its `AFTER ROW` cascade events up front, and the `NO ACTION` check
  queued by an inner (SPI) taxonomy `DELETE` is appended to the **tail of that same queue**, so
  it runs after the `issues` cascade whatever order the cascade triggers themselves fire in.
  **Trigger-name/OID order is irrelevant to this case**, which is the half of the original
  reasoning that was load-bearing and false.
- **It aborts with `23503` in exactly one shape:** when an issue **outside** the deleted project
  references that project's project-scoped catalog row. Note what that shape is — a cross-scope
  reference to a scoped catalog row, i.e. precisely the data the unscoped `replaceWithId` lookup
  in §4.3 could mint, and which §4.3's fix now makes unreachable through the API.
- **That abort is not new.** With `V19`'s two constraints dropped, the same delete raises the
  same `23503` from `issues_priority_id_fkey`, which has been in the schema since `V1`. `V19`
  adds two more constraint names that can report the condition, not a new condition. `statuses`
  reports it first, its RI trigger having the lower OID.
- **Deleting the referencing issues first makes it succeed**, so the operator rule below holds —
  with a corrected precondition: what must be deleted first is the issues that reference the
  project's scoped taxonomy from *outside* it, not the project's own issues.

Pinned by `V19IssuesTaxonomyFkTest`, in both directions, so a future PostgreSQL upgrade that
changes the queue ordering fails a test rather than a deploy.

**Do not rely on the ordering in either direction.** The rules:

- Any future code that deletes a project or a workspace must delete that project's `issues`
  explicitly first, in the same transaction, before the parent row.
- An operator purging by hand does the same: `DELETE FROM issues WHERE project_id = …;` then the
  project.
- Do **not** make the new constraints `DEFERRABLE INITIALLY DEFERRED` to paper over it. It would
  move violations to commit time with less context, would leave `priority_id` immediate and so fix
  only two thirds of the same delete, and would buy an inconsistency in exchange for half a remedy.
- ~~The behaviour is currently **unmeasured**.~~ **Measured** — see the finding above; the
  observed answer is recorded in `V19`'s header.

### 4.3 The hole the audit found — cross-tenant remap (**scope addition**)

`AdminCatalogService` resolves the replacement with a bare `findById`:

```java
// AdminCatalogService:123, :209, :308
var replacement = statusRepository.findById(replaceWithId)          // unscoped
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Replacement status not found"));
```

Every sibling service resolves a catalog child with `findByIdVisibleTo(id,
scope.visibleWorkspaceId(), scope.visibleProjectId())`. These three do not. A workspace- or
project-scoped admin can therefore pass **another tenant's** project-scoped row as `replaceWithId`
and have `remapStatus`/`remapType`/`remapPriority` repoint their issues at it. The new FK accepts
that happily — it is precisely the guarantee §3.2 says these constraints do not give.

Reachability differs per catalog, and the difference matters:

- **Issue types — fully reachable.** Nothing checks the replacement at all.
- **Priorities — fully reachable.** Nothing checks the replacement, and `issues_priority_id_fkey`
  has been in place the whole time. This is the empirical proof of §3.2.
- **Statuses — reachable, but only on one branch.** The "replacement must be in every workflow that
  contains the deleted status" loop rejects a foreign row *when the deleted status is in at least
  one workflow*. A status that issues still sit in after it was removed from the workflow is in zero
  workflows — a state the codebase explicitly permits, since `ProjectConfigService` restricts only
  new writes — so the loop body never runs and the foreign replacement goes straight through.

**Fix (three lines):** use `findByIdVisibleTo(replaceWithId, scope.visibleWorkspaceId(),
scope.visibleProjectId())` in all three methods, keeping the existing `404 "Replacement … not
found"` on a miss. **404, not 422 or 403** — the caller must not learn that the id exists somewhere
else; a non-visible row is indistinguishable from a non-existent one, which is the project's
standing rule.

The SPA never produces this call (`AdminStatusesPage:90` builds the replacement list from the
scope's own visible catalog, and its siblings do the same), which is exactly why it must be fixed at
the API. A client that hides nothing is still safe; the server is the enforcement boundary.

This belongs in HD-13 because the ticket asks for the delete-with-remap paths to be audited, and it
is what the audit found. It is also the only half of the tenancy story that is fixable at all here.

### 4.4 What the caller sees when the FK fires (**scope addition**)

**Today: a 500.** `23503` surfaces as Hibernate's `ConstraintViolationException` at flush/commit,
translated to Spring's `DataIntegrityViolationException` — by the repository proxy for a bulk
operation, or by `HibernateJpaDialect` inside `JpaTransactionManager.doCommit` for a commit-time
flush. `GlobalExceptionHandler` declares **no** handler for it, and neither does Boot's
`ResponseEntityExceptionHandler`, so it falls through to the container `/error` and answers `500`
with a body that names nothing an administrator can act on.

That is the third instance of one defect in one release: a database-level condition with a correct,
documented outcome escaping as a crash because nothing bound it (`handleOptimisticLock`,
`handlePessimisticLock` and `handleQueryTimeout` are the other two, and their javadoc says so).
Shipping the constraint without the handler ships the fourth.

**Required:** a `DataIntegrityViolationException` handler in `GlobalExceptionHandler`, branched on
SQLSTATE.

- Read the state as `ex.getMostSpecificCause() instanceof SQLException se ? se.getSQLState() : null`.
  **Never string-match the message.**
- `23503` → **409**, `errorType: "REFERENCE_CONSTRAINT_VIOLATION"`, direction-neutral detail:
  *"This change conflicts with a related record, so it was not applied. Something else may have
  changed at the same time — reload and try again."*

  **Corrected during review, and the correction is the interesting part.** This bullet originally
  prescribed remap-or-archive. That is right for only one of the two directions `23503` arrives
  in — a refused *parent delete* — and the reachable direction is the other one: a refused *child
  write*, where the referenced row does not exist, nothing is being deleted, and neither remedy is
  something the caller could do. The production case is a TOCTOU on an ordinary issue write, so an
  ordinary member creating an issue would have been told to "delete it with a replacement". The
  two directions are not separable from SQLSTATE alone and the house rule forbids parsing
  PostgreSQL's message, so the refusal must not assume one. Remap/archive stay in the three
  pre-checks, which know the direction, the entry and the count.
- **Anything else keeps today's outcome**: log at `ERROR` with the full throwable, answer `500` as a
  `ProblemDetail`. Do not fold `23505` into a `409` in this ticket (§2).
- **Never put the constraint name, the SQL or the parameters on the wire.** They go in the log line
  with the request method and mapped pattern, in the shape `handlePessimisticLock` uses.
- Per the `GlobalExceptionHandler` class note: `ResponseEntityExceptionHandler` declares nothing from
  `org.springframework.dao`, so the only behaviour that changes is `500` → `409`.

**This is a backstop, not the message.** The authoritative refusal stays the pre-check `409` in
`AdminCatalogService`, which names the count and the remedy. The handler exists so that the path
nobody predicted degrades to a sentence instead of a stack trace — the same doctrine as
`handleDateTime`.

### 4.5 Archive is the intended alternative, and one standing recommendation must be corrected

`archived_at` on `statuses` / `issue_types` is the supported "stop offering this" mechanism: it
keeps the row, so history renders and no reference is disturbed. It is what the delete dialog offers
("Archive instead", `admin/common.tsx:252` and `:292`), and it is what the 409 message already
points at. Deletion is for a row nothing uses. Nothing in this ticket changes that, and the UI needs
no change: the remap picker exists, is populated from the scope's visible catalog, and renders the
server's `detail` on failure.

**The correction.** `docs/design/cross-tenant-data-exposure-audit.md` §4.4 carries an open
recommendation to scope the numbers in these guard messages, naming `issueRepository.countByStatus`
specifically. After this migration, narrowing that **query** strands issues and turns the delete
into a `23503`. The count must stay unscoped. If the number is judged a leak, **drop the number from
the message and keep the query.** Add that sentence to §4.4 of the audit as part of this ticket —
the recommendation is live, and someone will act on it.

The same distinction applies to the UI heuristic and needs no code change:
`DeleteDialog`'s `needsRemap = usage.issues > 0` reads the **scoped** count while the server guards
on the **unscoped** one. They coincide only because a scoped catalog row can only be referenced by
issues inside its own scope — which is true once §4.3 is fixed and while bindings stay
scope-checked. If they ever diverge the dialog shows a plain "Delete", the server answers 409, and
the dialog renders it: degraded, still correct.

---

## 5. Actors & permissions

Unchanged by this ticket. No new endpoint, no new permission, no new role.

- The migration is applied by Flyway on application startup; no human actor.
- The catalog deletes keep the authorization in §4.1's table.
- The new `409` is visible to whoever could already reach those deletes; it discloses nothing
  (no ids, no names, no counts, no constraint name).
- Tenancy shape is unchanged: non-existence and non-membership of a workspace are both `404`;
  `403` only for a proven member missing the permission.

---

## 6. Data model impact

### 6.1 The migration

`src/main/resources/db/migration/V19__issues_taxonomy_fk.sql` — `V18__reports_foundations.sql` is
the highest applied version.

```sql
-- ---------------------------------------------------------------------------
-- HD-13 — restore FK integrity on issues.type_id / issues.status_id
-- ---------------------------------------------------------------------------
-- The V1 baseline shipped these two columns with NO foreign key: the originals
-- were dropped by the pre-squash V6's `DROP TABLE statuses/issue_types CASCADE`
-- and never recreated. Every other reference on `issues` is constrained,
-- including priority_id, which is the direct precedent for the shape used here.
--
-- WHAT THIS BUYS: a *stranded* reference becomes impossible — status_id/type_id
-- can no longer name a row that does not exist, in any path, including ones
-- written later and including raw SQL.
--
-- WHAT IT DOES NOT BUY: tenancy. issues_component_fk and issues_sprint_fk are
-- composite — (id, workspace_id) → (id, workspace_id) — which makes a
-- cross-tenant reference impossible in the database. These two CANNOT be that
-- shape and the reason is structural, not a shortcut: `statuses`/`issue_types`
-- have no workspace_id, a GLOBAL row's scope_workspace_id is NULL (so a
-- composite key would reject 99.8% of real references), and one global row is
-- referenced by issues in every workspace at once — a key naming a workspace
-- can be satisfied by exactly one of them. PostgreSQL has no MATCH PARTIAL and
-- no conditional FK. Do not read these three constraints as equivalent.
-- The tenancy half is enforced by ProjectConfigService.requireStatusOffered /
-- requireTypeOffered plus the scope-checked binding chain behind them; see
-- docs/design/issues-taxonomy-fk-proposal.md §3.
--
-- NO `ON DELETE`: NO ACTION, like issues_priority_id_fkey. CASCADE would delete
-- issues when a status is deleted; SET NULL is impossible against NOT NULL.
--
-- PLAIN `ADD CONSTRAINT`, deliberately NOT `NOT VALID` + `VALIDATE CONSTRAINT`.
-- Deploys stop the old container before the new one starts (HD-93, 2026-08-21:
-- rolling deploys are not planned), so migrations run with no concurrent writer
-- and the SHARE ROW EXCLUSIVE lock falls in a window where nothing is serving.
-- If that ever changes, docs/release-checklist.md → "Constraints on a populated
-- table" is the inventory these two belong to, and the rule becomes two-step.
--
-- The referencing columns are already indexed (idx_issues_type / idx_issues_status,
-- V1), which is what keeps a future catalog DELETE's RI check off a seq scan.

ALTER TABLE issues
    ADD CONSTRAINT issues_type_id_fkey FOREIGN KEY (type_id) REFERENCES issue_types(id);

ALTER TABLE issues
    ADD CONSTRAINT issues_status_id_fkey FOREIGN KEY (status_id) REFERENCES statuses(id);
```

Constraint names match what PostgreSQL would have generated in `V1` and match
`issues_priority_id_fkey`'s convention.

Migration-rule compliance: no `CREATE TYPE … AS ENUM`, no `CHAR(n)`, no new column, no new table, no
data rewrite, no `SET LOCAL` requirement, nothing that cannot run inside Flyway's transaction.

**The ticket's third scope bullet is wrong and must not be followed.** It says *"Update the baseline
comment noting the FKs now exist"* — meaning the `NO foreign key` note at `V1__init_schema.sql:355`.
**Do not edit `V1`.** Flyway checksums the **whole file**, comments included, so a text-only edit to
an applied migration breaks startup on every existing database. The correction goes in `V19`'s header
(above), where the next reader of the schema will meet it, and in `docs/project-state.md`.

### 6.2 Locks and cost

| statement | lock on `issues` | lock on parent | work |
|---|---|---|---|
| `ADD CONSTRAINT issues_type_id_fkey` | `SHARE ROW EXCLUSIVE` | `SHARE ROW EXCLUSIVE` on `issue_types` | one validation scan of 77,619 rows |
| `ADD CONSTRAINT issues_status_id_fkey` | `SHARE ROW EXCLUSIVE` | `SHARE ROW EXCLUSIVE` on `statuses` | one validation scan of 77,619 rows |

`SHARE ROW EXCLUSIVE` (PostgreSQL ≥ 9.5 for `ADD FOREIGN KEY`; prod runs `postgres:16-alpine`)
blocks every write to both tables and allows plain `SELECT`. Both statements share one Flyway
transaction, so both locks are held until the migration commits.

The validation is `RI_Initial_Check`, one `LEFT JOIN` per constraint —
`SELECT fk.status_id FROM ONLY issues fk LEFT JOIN ONLY statuses pk ON pk.id = fk.status_id
WHERE pk.id IS NULL AND fk.status_id IS NOT NULL` — a sequential scan of `issues` hashed against a
catalog of a few dozen rows. No row locks in that plan. The release checklist's measured figure is
**64 ms of FK validation on a 1M-row table**; at 77.6k rows expect **tens of milliseconds per
constraint**, so the whole migration is well under a second. Each statement also creates two RI
triggers and takes a brief catalog lock.

**Entry in the release-checklist inventory.** `docs/release-checklist.md` → *"Constraints on a
populated table, and why they are free right now"* → *"The inventory, so it is not re-derived"*
gains two rows. That inventory going stale is the failure the section exists to prevent, and this
migration is the first thing to test it since it was written:

| migration | constraint | on | lock |
|---|---|---|---|
| `V19__issues_taxonomy_fk.sql` | `issues_type_id_fkey` | `issues` | `SHARE ROW EXCLUSIVE` |
| `V19__issues_taxonomy_fk.sql` | `issues_status_id_fkey` | `issues` | `SHARE ROW EXCLUSIVE` |

### 6.3 Pre-flight, to run against production before the release

**Blocking — both must return 0.** If either does not, the migration will abort and the deploy will
fail with Flyway unable to apply `V19`; repair the rows first (remap them to a live catalog row).

```sql
SELECT count(*) AS dangling_types
  FROM issues i LEFT JOIN issue_types t ON t.id = i.type_id
 WHERE t.id IS NULL;

SELECT count(*) AS dangling_statuses
  FROM issues i LEFT JOIN statuses s ON s.id = i.status_id
 WHERE s.id IS NULL;
```

**Advisory — cross-tenant / cross-scope references.** The new constraints do **not** catch these
(§3.2), so this is the only occasion anyone will look. It returns nothing when clean, which is a
real all-clear rather than something to match by eye.

```sql
SELECT 'status' AS kind, c.id AS catalog_row, c.name,
       i.workspace_id, i.project_id, count(*) AS issues
  FROM issues i JOIN statuses c ON c.id = i.status_id
 WHERE (c.scope_workspace_id IS NOT NULL AND c.scope_workspace_id <> i.workspace_id)
    OR (c.scope_project_id   IS NOT NULL AND c.scope_project_id   <> i.project_id)
 GROUP BY 1,2,3,4,5
UNION ALL
SELECT 'type', c.id, c.name, i.workspace_id, i.project_id, count(*)
  FROM issues i JOIN issue_types c ON c.id = i.type_id
 WHERE (c.scope_workspace_id IS NOT NULL AND c.scope_workspace_id <> i.workspace_id)
    OR (c.scope_project_id   IS NOT NULL AND c.scope_project_id   <> i.project_id)
 GROUP BY 1,2,3,4,5
UNION ALL
SELECT 'priority', c.id, c.name, i.workspace_id, i.project_id, count(*)
  FROM issues i JOIN priorities c ON c.id = i.priority_id
 WHERE (c.scope_workspace_id IS NOT NULL AND c.scope_workspace_id <> i.workspace_id)
    OR (c.scope_project_id   IS NOT NULL AND c.scope_project_id   <> i.project_id)
 GROUP BY 1,2,3,4,5;
```

Priorities are included because the same hole exists there (§4.3) and the query costs nothing extra.

### 6.4 Entity-mapping impact — **none required**

`Issue.type` and `Issue.status` are already correct and need no change:

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "type_id", nullable = false)
private IssueType type;
```

- **`ddl-auto=validate` keeps passing, and would have passed either way.** Hibernate's
  `SchemaValidator` checks tables, columns, column types and sequences. **It does not validate
  foreign keys, indexes or check constraints.** Adding or removing an FK is invisible to it. So
  validation is not the thing this migration risks — it is not even a signal that the migration
  worked, and AC-1 checks the constraint directly instead.
- **No `@ForeignKey` annotation.** `@JoinColumn(foreignKey = …)` only affects schema *generation*,
  which is off.
- **`insertable` / `updatable` must stay default (`true`).** The `updatable = false` rule from
  `projects.issue_seq` and `issues.sprint_id`'s neighbour applies to columns written by native SQL
  behind the ORM's back; these two are written by JPA and by the bulk remap, and the remap is a JPQL
  `update` that does not depend on the field being updatable at flush. Marking either
  non-updatable would silently break every status transition.
- **No `cascade` attribute, and none may be added.** `CascadeType.REMOVE` or `ALL` on either
  `@ManyToOne` would try to delete the *catalog row* when an issue is deleted — the exact inversion
  of what the constraint protects, and it would now fail loudly on the first shared status instead of
  quietly stranding everyone else's issues. Worth a one-line comment on the fields.
- `optional = false` is consistent with `NOT NULL` and is unaffected.

---

## 7. API surface

No new endpoints. No changed request or response shapes. Two behavioural changes to document:

1. **`500` → `409` on the catalog `DELETE`s when the database refuses the delete.** New
   `errorType: "REFERENCE_CONSTRAINT_VIOLATION"` on the ProblemDetail. Affects
   `DELETE /api/admin/{catalog}/{id}`, `DELETE /api/workspaces/{ws}/admin/{catalog}/{id}` and
   `DELETE /api/workspaces/{ws}/projects/{p}/admin/{catalog}/{id}` — and, as a backstop, any future
   endpoint that can raise `23503`.
2. **`404` where the call previously succeeded**, on `?replaceWithId=` naming a catalog row the
   caller cannot see (§4.3). The existing `404 "Replacement … not found"` branch simply covers more
   cases now.

`api-docs-sync` must update `src/main/frontend/public/openapi.yaml`, `docs/api-dc.md` and
`docs/api-cloud.md`. The rows to touch are the `DELETE .../{catalog}/{id}?replaceWithId=` lines
(`api-dc.md:1345` and `:1391`, `api-cloud.md:1321` and `:1367`), which already document the
in-use `409` and need the `errorType` and the replacement-visibility rule added.

**Release notes.** One line, because a client that retried on `5xx` will now correctly not retry:

> **Deleting a status, issue type or priority that is still referenced answers `409`, not `500`.**
> The database now enforces that no issue can point at a deleted status or issue type. The refusal
> carries `errorType: "REFERENCE_CONSTRAINT_VIOLATION"`; delete with a replacement so affected issues are
> moved, or archive the entry instead. `?replaceWithId=` must also name an entry visible in your own
> workspace or project — one that is not answers `404`.

---

## 8. Frontend impact

**None required.** The delete dialogs already offer a remap picker and an "Archive instead" button
(`src/main/frontend/src/pages/admin/common.tsx`), already draw the replacement list from the scope's
visible catalog, and already render the server's `detail` on failure through `request()`. The new
`409` renders as a sentence in the existing error slot.

Two notes, neither a change: the `needsRemap` heuristic's relationship to the server guard is in
§4.5; and no page is `DESIGN.md`-affected because no element is added or restyled.

---

## 9. DC / Cloud implications

- **No new toggle, no new env var, no profile gating, no property.** The schema is one Flyway
  history shared by both modes; a constraint that is correct in Cloud is correct in DC.
- Nothing here is cloud-only: no storage, email, auth or billing surface is touched.
- The `dc-cloud-guard` checklist (properties → compose → `.env.prod.example` → README) is **n/a** —
  say so explicitly in the gate file rather than leaving it unrun.
- The only mode-shaped item is operational: the §6.3 pre-flight runs once against the Cloud
  production database before the release and its result is recorded. A DC operator inherits the same
  migration and needs nothing — if their data is clean it applies silently, and if it is not,
  Flyway fails the startup loudly, which is the correct outcome for a self-hosted instance with
  corrupt rows.
- `docs/self-hosting.md` needs nothing: no resource default moved, and no stored value's derivation
  changed.

---

## 10. Acceptance criteria

1. **AC-1 — the constraints exist and are validated.** After a fresh `mvnw test` run,
   `SELECT conname, convalidated FROM pg_constraint WHERE conrelid = 'issues'::regclass AND conname
   IN ('issues_type_id_fkey','issues_status_id_fkey')` returns two rows, both `convalidated = true`,
   both `confdeltype = 'a'` (NO ACTION). Pinned by a test, not by a manual query.
2. **AC-2 — the migration applies to prod-shaped data.** `V19` applies cleanly to a restored copy of
   the production database in a single transaction, in under a second. Verified with
   `--spring.docker.compose.enabled=false` so `DB_URL` is honoured (the compose plugin otherwise
   silently overrides the datasource).
3. **AC-3 — a stranded reference is now impossible.** A direct
   `UPDATE issues SET status_id = gen_random_uuid() WHERE id = …` fails with `23503`; a direct
   `DELETE FROM statuses WHERE id = <one in use>` fails with `23503`.
4. **AC-4 — delete-with-remap still works, at all three scopes.** For statuses and for issue types:
   delete an in-use own-scope row with `?replaceWithId=` → `204`, every affected issue now carries
   the replacement, no issue is stranded, and the row is gone. Run at global, workspace and project
   scope.
5. **AC-5 — delete-in-use without a replacement is still the pre-check `409`,** with the count and
   the "remap or archive" remedy — i.e. the service refuses before the database has to.
6. **AC-6 — the FK backstop is a `409`, never a `500`.** Drive a real `23503` to an HTTP client
   (e.g. by deleting a catalog row through a path that bypasses the pre-check, or by a test that
   provokes the constraint directly) and assert `409`, `errorType: "REFERENCE_CONSTRAINT_VIOLATION"`, a
   non-blank `detail`, and that the body contains neither the constraint name nor any SQL.
   Assert a non-`23503` `DataIntegrityViolationException` still answers `500`.
7. **AC-7 — the project-delete cascade behaviour is measured and recorded, not predicted.** A test
   creates a project with a project-scoped status *and* a project-scoped issue type *and* a
   project-scoped priority, puts issues on all three, then deletes the project row. Whatever happens
   — clean cascade or `23503` — the test pins it and the observed answer replaces the prediction in
   `V19`'s comment and in §4.2 of this document. If it aborts, the same test asserts that deleting
   the project's issues first makes it succeed.
8. **AC-8 — `SprintLifecycleTest` still passes** (it deletes a project by id at line 304) and, if
   AC-7 shows an abort, gains a comment saying why its fixture is unaffected.
9. **AC-9 — the cross-tenant replacement is refused.** For statuses, issue types **and** priorities:
   a workspace-scoped admin calling `?replaceWithId=` with a row scoped to another workspace's
   project gets `404`, and no issue is remapped. Include the status variant where the deleted status
   belongs to **zero** workflows, which is the branch that is reachable today.
10. **AC-10 — the guard counts are still unscoped.** A test that fails if `deleteStatus` /
    `deleteIssueType` / `deletePriority` stop using `countByStatus`/`countByType`/`countByPriority`
    and `remapStatus`/`remapType`/`remapPriority`, with a failure message that says *why*: a scoped
    count leaves rows behind and the delete then raises `23503`.
11. **AC-11 — `ddl-auto=validate` passes**, and the migration-reviewer confirms entity⇄schema parity.
    (Note that validate would pass with or without these FKs; AC-1 is the real check.)
12. **AC-12 — docs.** `docs/release-checklist.md` inventory has the two new rows;
    `docs/design/cross-tenant-data-exposure-audit.md` §4.4 carries the "keep the query, drop the
    number" correction; `docs/project-state.md` records that the `V1` comment is superseded;
    `openapi.yaml` + both `docs/api-*.md` carry the `409`/`errorType` and the replacement-visibility
    rule; `swagger-cli` validates.
13. **AC-13 — pre-flight run and recorded.** Both blocking queries returned 0 against production and
    the advisory query's output is pasted into the ticket before the tag is pushed.

---

## 11. Highest-risk assumption, stated plainly

**That `AdminCatalogService`'s two methods are the only way a `statuses` or `issue_types` row is ever
deleted.** It is true today by grep, and it is a claim about a *category* — the kind that goes stale
without containing any word a search for the new path would match. The day someone adds a workspace
purge, a demo-data teardown, a bulk archive-cleanup job, or a `DELETE /projects/{id}`, a delete that
used to strand issues silently becomes a `23503` — and if it is reached outside the admin service, no
pre-check will have run.

Three mitigations, in order of how much they actually buy: the §4.4 backstop handler (any such path
degrades to a `409` with a remedy rather than a crash), AC-7 (the cascade behaviour is *known*), and
this paragraph.

**Second-highest — resolved, and half of it was wrong.** The project-delete cascade ordering in
§4.2 was reasoned from PostgreSQL's trigger-firing rules and had not been executed. AC-7 executed
it. The prediction that trigger order decides the outcome is **falsified**: the ordinary shape
cascades cleanly because the `NO ACTION` check is queued behind the `issues` cascade rather than
ahead of it. The prediction that the abort is already reachable via `priorities` is **confirmed**,
but only in the narrower shape §4.2 now describes. Writing it as a falsifiable prediction rather
than a claim is what made the difference: the same paragraph asserted as fact would have shipped
into `V19`'s header and been believed.

---

## 12. Open questions

1. **Deferrable constraints?** Recommended answer: **no** — `NOT DEFERRABLE`, matching
   `issues_priority_id_fkey`. `INITIALLY DEFERRED` would make a future project purge work regardless
   of trigger order, but leaves `priority_id` immediate so the purge still fails, and moves genuine
   violations to commit time with less context. Revisit only if a project-delete feature is actually
   built, and then fix all three together.
2. **Should the `409` backstop carry the affected count?** Recommended answer: **no.** By the time
   the constraint fires, the transaction is aborting and no further query can run in it; a second
   transaction to count would be a new query on a failed request. The remedy ("delete with a
   replacement, or archive") is performable without the number, and the pre-check path — which is
   where a caller normally lands — already gives it.
3. **Extend the `409` backstop to `23505`?** Recommended answer: **not in this ticket.** It would
   change the outcome of `issues_project_id_number_key` and several admin unique constraints at once,
   each of which wants a different sentence. File a follow-up.
4. **Should the priorities half of §4.3 ship here?** Recommended answer: **yes** — it is the same
   three-line change in the same method family, the same test, and leaving one of three catalogs
   with the hole open is how an audit finding survives its own fix. It is why the ticket's 3 points
   are honest rather than generous.
5. **Does `docs/project-state.md`'s schema-squash section need the `V1` comment marked superseded?**
   Recommended answer: **yes**, one sentence — the comment stays in `V1` (checksums), so the
   correction has to live somewhere a reader will meet.
