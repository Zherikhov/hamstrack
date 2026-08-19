-- ---------------------------------------------------------------------------
-- HD-136 — an EIGHTH built-in role: "Team lead" (PROJECT scope).
--
-- Contributor's set, plus `project.member.manage`. It exists for one caller:
-- `ProjectAdminGuard.adoptAll`. When removing a workspace member would leave a
-- project with no holder of `project.member.manage`, the admin may repeat the
-- DELETE with `?adoptStrandedProjects=true` and take that project's MEMBERSHIP
-- over, so the offboarding can complete. Something must be granted at that
-- moment, and the first cut granted the built-in Project admin — all 20 project
-- permissions, `issue.delete` and unrestricted `attachment.delete` included, to
-- satisfy an invariant about exactly one of them. That was a real escalation:
-- stranding a project destroyed its manageability and gave the actor NOTHING,
-- while adoption gives them durable authority over somebody else's project.
--
-- WHY NOT `{project.member.manage}` AND NOTHING ELSE, which is the narrower
-- role the review asked for. Because a role narrower than the project's §5.2
-- FALLBACK is not a smaller grant, it is a one-way demotion:
--
--   1. In an OPEN workspace every member already inherits Contributor in every
--      project. Writing a membership-only row REPLACES that inherited role, so
--      the adopter silently LOSES issue.create/edit/transition/assign/rank and
--      comment/attachment rights in the project they just rescued.
--   2. They cannot undo it. Deleting their own row is `project.member.manage`
--      (they have it) but `ProjectService.removeMember` also applies the grant
--      ceiling to the role the removal would LEAVE them on — the inherited
--      Contributor — which a membership-only holder does not cover. 403, for
--      ever. Exactly the "no endpoint can repair this" shape HD-136 exists to
--      delete.
--   3. And they could staff the project with nobody: `addMember` refuses to
--      grant a role the actor does not cover, so a membership-only adopter can
--      only ever appoint another membership-only member. In a STRICT workspace,
--      where there is no inherited role at all, that leaves the project
--      permanently unstaffable.
--
-- Contributor is precisely what a workspace member already has in an OPEN-mode
-- project, so measured against the baseline that actually applies there, the
-- delta of an adoption IS exactly `project.member.manage` — which is what the
-- review asked for. What it deliberately still excludes is every permission the
-- escalation argument named: `issue.delete`, unrestricted `attachment.delete`,
-- `project.archive`, `project.edit`, `project.taxonomy.manage`,
-- `sprint.manage`, `version.manage`, `component.manage`.
--
-- WHAT AN ADOPTION ACTUALLY ADDS depends on the fallback that applies, and must
-- be stated as a delta against it rather than as a universal (an earlier draft
-- of this comment said flatly "adoption gives control of a project's roster,
-- never of its work", which is true in only one of the two configurations):
--
--   * OPEN workspace, default fallback (the shipped configuration): the adopter
--     already inherits Contributor in that project, so the delta IS exactly
--     `project.member.manage` — the roster, and nothing about the work.
--   * STRICT workspace, or an OPEN one whose fallback is narrower: there is no
--     inherited role, so the same grant is an ABSOLUTE one — Contributor's
--     twelve included, i.e. issue.create/edit/transition/assign/rank and the
--     comment/attachment rights. That is authority over the project's work as
--     well as its roster, and it should be read as the real widening it is.
--
-- What caps both readings is the exclusion list above: nothing irreversible and
-- nothing over the project itself. `ProjectAdminGuard`'s class javadoc carries
-- the same two bullets; they are one argument and must move together.
--
-- (Residual, recorded rather than hidden: an adopted project cannot regain a
-- full Project admin through the API, because §11.2 lets nobody hand out a role
-- wider than their own and the departing member was the last holder of that
-- width. That is the same gap `project.archive` sitting outside
-- `Permission.projectCuration()` already creates, and it is a seed/policy call.)
--
-- This is also §7.2's own "team lead" recipe — duplicate Contributor, add
-- member management — which until now every workspace had to build by hand in
-- the S4 role editor that has not shipped. Seeding it as a built-in is what
-- makes the adoption path work in a workspace that has never opened that
-- editor, in DC and Cloud alike, with no bootstrap step: `roles_builtin_ck`
-- makes `workspace_id IS NULL` the only shape offered everywhere.
--
-- The id is a fixed literal for the reason V13 gives at length (product
-- constants, zero-query resolution, human-readable in psql), continues V13's
-- PROJECT block (…011–…014) at …015, and is mirrored in
-- `workspace/entity/BuiltInRoles.java#PROJECT_TEAM_LEAD`.
-- `RoleIdsMatchMigrationTest` pins the two together, including this role's exact
-- grant set, and its "exactly N built-ins" assertion moves 7 → 8 here: an eighth
-- built-in IS a product decision, and this is it.
--
-- POSITION 4 (last within PROJECT), deliberately: display order only, and
-- appending avoids an UPDATE that renumbers the four rows V13 seeded.
-- ---------------------------------------------------------------------------

INSERT INTO roles (id, workspace_id, scope, key, name, description, built_in, position) VALUES
    ('00000000-0000-7000-8000-000000000015', NULL, 'PROJECT', 'TEAM_LEAD', 'Team lead',
     'Everything a Contributor can do, plus managing this project''s members and their roles. '
     || 'Granted when an administrator adopts a project that would otherwise be left with nobody '
     || 'able to manage its membership.', TRUE, 4);

-- Contributor's twelve, verbatim (V13's "load-bearing row"), plus the one
-- permission the last-administrator invariant is about. Keep the two halves in
-- step with V13: a Contributor grant added there and not here would make an
-- adoption narrow the adopter's inherited role again — see trap 1 above.
INSERT INTO role_permissions (role_id, permission, own_only)
SELECT '00000000-0000-7000-8000-000000000015', p.permission, p.own_only
  FROM (VALUES
        ('project.member.manage', FALSE),
        ('issue.create',          FALSE),
        ('issue.edit',            FALSE),
        ('issue.transition',      FALSE),
        ('issue.assign',          FALSE),
        ('issue.assignable',      FALSE),
        ('issue.rank',            FALSE),
        ('comment.create',        FALSE),
        ('comment.edit',          TRUE),
        ('comment.delete',        TRUE),
        ('attachment.create',     FALSE),
        ('attachment.delete',     TRUE),
        ('sprint.assign',         FALSE)
  ) AS p(permission, own_only);
