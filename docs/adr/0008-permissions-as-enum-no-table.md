# ADR-0008: Permissions as an enum catalog, with no `permissions` table

Record date: 2026-08-22 (retrospective; the decision was made as part of epic HD-123)
Status: Accepted
Source: `CLAUDE.md` → the "Gotchas" section → "Authorization is one resolution and
one primitive (HD-123, complete)"

## Context
Permission/role models often keep the catalog of permissions in a DB table (`permissions`) and
resolve authorization with queries to `workspace_members` / `project_members` on
every check. That multiplies queries and turns adding a permission into a migration.

## Decision
The catalog of 29 permissions is the Java enum `common.security.Permission`. **There is no
`permissions` table.** `WorkspaceAccessService.resolveProject` verifies membership in the
workspace, finds the project inside it and resolves **once per request** an immutable
`PermissionSet` onto `WorkspaceContext` / `ProjectContext`. A call site
authorizes with one line: `ctx.permissions().require(Permission.SPRINT_MANAGE)`
(403 `MissingPermissionException`).

The cost is fixed: 2 queries for workspace scope, 4 for project scope, 0 for
the check itself (sealed by `PermissionResolutionQueryCountTest`). Adding a permission is
an enum constant + a `require(...)` + a seed row, **not** a migration. Roles are
rows: the built-in ones are shared (`workspace_id IS NULL`), the custom ones belong to a
workspace. An authorization decision **never** re-queries
`workspace_members` / `project_members` — the answer already lies on the context.

## Consequences
+ A permission check is constant cost and one line of code at the call site.
+ A new permission requires no DB migration.
+ The catalog of permissions is visible in the code (an enum) rather than smeared across table rows.
− Changing the set of permissions is a code release, not a row in the DB.
− The model carries hard invariants (a permission's scope = its role's scope → 422 otherwise; the ceiling
  is checked at both ends of a change, and so on) — details in `CLAUDE.md`.

## Alternatives
- A `permissions` table + a membership query on every check — rejected: extra
  queries per check, and adding a permission requires a migration.
