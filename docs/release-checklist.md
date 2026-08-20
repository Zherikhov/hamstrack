# Cutting a release

**Push order no longer matters** (HD-115, 2026-08-17). Tag before `main`, tag
after `main`, merge locally or through the GitHub PR button — production ends up
on the tagged version either way. If you are looking for the old "the tag must
exist before the `main` build starts" rule: it is gone, and so is the failure it
guarded against.

## The sequence

```bash
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

## Releases carrying a destructive migration

Most releases need nothing here. A release whose migrations **drop or rename a
column the previous image still reads** needs two extra things, and the roles
release (**V13–V15**, HD-123) is the first one that does: `V15` drops
`workspace_members.role`, `workspace_invites.role` and `project_members.role`.

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
