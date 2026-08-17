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
