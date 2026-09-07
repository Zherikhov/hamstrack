# ADR-0024: A workspace outlives its owner's account; an erasure never leaves a tenant without an administrator

Record date: 2026-09-02
Status: Proposed
Source: `docs/design/account-deletion-proposal.md` (HD-193) §5.2, §5.3;
the invariants — `WorkspaceMemberService.lockOwners`/`requireNotLastOwner` (HD-132),
`ProjectAdminGuard` (HD-136, HD-127, HD-130), `V16__team_lead_role.sql`

## Context

An account erasure affects more than the person. If the **only Owner** of a workspace leaves,
the workspace is left with no holder of the rights it is administered by — a state that this
codebase has already declared a failure and defends with four tickets in a row:

- removing the last Owner is refused with `409 LastWorkspaceOwnerException`, and the decision
  is made over a **locked** set of owners (`FOR UPDATE`, `ORDER BY id`),
  unconditionally and before the target row is read;
- removing a member who is a project's last administrator is refused with
  `409 StrandedProjectsException`; nine doors lead to that state, three of them
  locked, six advisory, because an aggregate cannot take `FOR UPDATE`;
- `project.member.manage` is deliberately not part of `Permission.projectCuration()`, so a
  project with no holder of that permission **cannot be repaired through the API by anyone at
  all**, including the workspace owner; recovery is a manual `UPDATE`.

The key fact of the fork: **all these guards live in the application, while the erasure procedure is
SQL, where nothing refuses anything.** Plus a second trap of the same family: `status = 'DISABLED'`
takes the subject out from under the `ACTIVE` filter that all the guards' queries are built on, and
a project can be orphaned **with not a single error and no 409**. This is written down outright in
the `ProjectAdminGuard` javadoc as "silent orphaning" (the DISABLED approach direction).

**It matters which step writes that value first, and it is not the anonymisation.** `DISABLED` is
set by the **freeze** (phase 3 of the runbook) — the same step that kills sessions and deletes
refresh tokens; the anonymisation proper (phase 8) merely repeats the same value and overwrites the
address, the name, the avatar and the hash. So everything that is settled "before visibility to the
guards is lost" is settled **before the freeze**, not before the anonymisation. A draft of this
document tied the gate to the anonymisation — that is, it let past it the very step that does
exactly the harm the gate exists to prevent.

## Decision

**The identity is always erased; only the fate of the workspace differs.** The rule
is applied separately to each of the subject's workspaces, before the membership rows are deleted:

**Two different predicates operate here, and one must never be substituted for the other.** The
question "may the workspace be destroyed?" is settled by the **broadest** sign of life — "the
account is not erased" (`email <> 'deleted+' || id::text || '@deleted.invalid'`, the
self-referential form from ADR-0023, not a domain match that a live account could satisfy): a
suspended person, a `PENDING` one and an interrupted
erasure all count as present, and any of them keeps the workspace alive. The question "who may be
appointed?" is settled by the **narrowest** — `status = 'ACTIVE'`: the appointee must be able to log
in. Neither of them equals "not `DISABLED`": that value is written by an administrator suspending
a live person (ADR-0023).

1. **The subject is not the only `ACTIVE` Owner** — nothing special; first the project orphanings
   are resolved, then the membership rows are deleted.
2. **The subject is the only `ACTIVE` Owner, and there is at least one unerased member who is
   `ACTIVE`** — **ownership is transferred, the workspace lives.** The operator asks the subject to
   name a successor; if he refuses or does not answer, the longest-serving `ACTIVE` member
   (`joined_at ASC`) holding `workspace.member.manage` is appointed, and if there is no such member —
   simply the longest-serving of the `ACTIVE` ones. **An email goes to all remaining members** saying
   that ownership has moved and to whom, and the transfer itself is recorded in the erasure ledger:
   a restored dump does not contain it (ADR-0025).
2a. **There are unerased members, but none of them is `ACTIVE`** — this branch is **blocked**: the
   workspace is not deleted and ownership is not transferred automatically until a human resolves
   the situation (lift the suspension and go to item 2, or obtain consent for deletion and go to
   item 3). Deleting the membership row here orphans the workspace; leaving it once the account has
   stopped being `ACTIVE` makes the workspace silently unmanageable, which is worse.
   **The decision is taken before the freeze, not before the anonymisation.** The usual outcome is
   that the freeze is performed anyway: the subject asked for his access to be closed, and turning
   someone else's management of a workspace into a veto over his request is wrong. But then it is
   done openly: from that moment the workspace has no administrator able to log in, an email goes to
   the remaining members, the escalation is recorded **with a review date**, and it is revisited on
   that date. The membership row stays, the anonymisation is not performed — not as a protection
   (there is none any more), but because the operator still needs the address and the name while the
   request is open.
3. **All other members are erased, or there are none** — the workspace is deleted together with all
   its content and attachment objects. This is not a rare case but the ordinary one: `DemoDataService`
   creates a "Demo Workspace" for every account at first authentication.
4. **The project level, in every surviving workspace** — for each project that would be left
   without an `ACTIVE` holder of `project.member.manage`, the operator assigns the built-in role
   **Team lead** (V16), the same one that `ProjectAdminGuard.adoptAll` hands out.

**The procedure works together with the guards, not around them:** their checks are reproduced in
the pre-flight report as read-only queries, and the step does not begin until they pass.
**The order is itself the safety property:** ownership first, **then the freeze**, then the
deletion of memberships, and only then the anonymisation. The gate sits at the freeze, because that
is what first makes the subject invisible to the guards.

`PENDING` members count as present — but not because of a separate rule about `PENDING`,
rather because the destruction predicate reads "the account is not erased". The same predicate keeps
the workspace for a suspended colleague, and that is no small thing: the condition "the status is not
`DISABLED`", which this draft started from, would have deleted all the work of a suspended person,
justifying it with the phrase "there is nobody here besides the subject", which is false at exactly
the moment it is written.

## Consequences

+ No erasure can leave a workspace without an Owner or a project without a holder of
  `project.member.manage` — a state that is repaired only by hand in the database.
+ An erasure is not blocked by the subject's inaction: an erasure request has a legal deadline, and
  the sole owner of a solo workspace has nobody to hand it to.
+ A solo workspace is not left hanging forever: it consisted of the erased person's data,
  and keeping it would be keeping personal data with no purpose and no reader.
+ Team lead is exactly what gets assigned, not Project admin: V16 and the `ADOPTION_ROLE_KEY`
  javadoc have already argued that the role carries nothing destructive (`issue.delete`,
  unconditional `attachment.delete`, archiving, settings, taxonomy). Repeating a wider grant
  here would silently reverse that decision.
− **The product sometimes decides for itself who owns the team's data.** That is a real cost; it is
  mitigated by asking the subject first and notifying everyone afterwards.
− Rule number 3 is destructive and irreversible: the workspace goes together with its content and
  its objects. That is why it is disclosed in the policy as an adverse fact rather than
  implied.
− While there is no endpoint, all of this is runbook discipline, not code. The order of the steps is
  held by a check of the pre-flight report, not by a type or a lock.

## Alternatives

- **Refuse the erasure until the subject names a successor** — rejected: it hands
  the legal deadline to a person who has just asked to stop dealing with us, and it is
  impossible for the sole owner of a solo workspace.
- **Delete every workspace the subject owns** — rejected: it destroys the work of other
  members because one person left.
- **Leave the workspace with no owner** — rejected: exactly the state the family of guards was
  built to prevent; irreversible through the API.
- **Freeze (or anonymise) first, then deal with ownership** — rejected, and this is
  the main trap: `DISABLED` takes the subject out from under the `ACTIVE` filter in all the guards'
  queries, so the orphaning goes through silently. The "gate at the anonymisation" variant is
  rejected separately — it looks like the same protection, but it lets past it the freeze, which
  already writes `DISABLED`.
- **Give the operator a role in the API instead of an out-of-band procedure** — rejected: it would
  produce a role able to destroy any account in the installation, under the same session model as
  everything else. Until self-service exists (1.0.0) the action stays out of band.
