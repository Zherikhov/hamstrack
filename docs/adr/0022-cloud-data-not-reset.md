# ADR-0022: User data in Cloud is not reset; wipe is an operator tool, not a migration

Record date: 2026-09-02
Status: Accepted
Source: `docs/design/data-retention-promise-proposal.md` (HD-195); backups and
the restore measurement — `docs/ops-prod-hardening.md` §6.1, §6.5, §6.6 (HD-187);
the wipe procedure — `docs/project-state.md` ("Wiping an instance's user data (operator tool — not a policy)")

## Context

`README.md` has carried a line from the very beginning: data on the Cloud instance "may be reset
periodically while the product is in test mode". The application, meanwhile, said this **nowhere** —
not on the landing page, not on the registration screen, not in the shell after login; the word
"beta" does not appear in the SPA at all. At the same time three surfaces claimed the opposite:
`PrivacyPage` §5 ("we keep your data as long as the account exists"), the HOSTED CLOUD card on the
landing page ("Automatic backups & upgrades") and the heading above it ("The task tracker you can
actually trust").

What turned this into a fork rather than a text edit:

- **Backups now exist.** A daily `pg_dump -Fc` into a write-only bucket (ADR-0012), a 30-day
  lifecycle, plus daily EBS snapshots. Restoring was verified in the 2026-08-26 drill, the
  measured time is about 4 minutes (the drill log entry records a partial pass of the procedure).
  After that, "data may be reset" is a **chosen policy**, not a constraint one simply has to
  endure.
- So there are exactly two coherent positions: cancel the reset — or keep it and say so in the
  product **before** an account is created. Shipping both is precisely the defect.
- The wipe procedure is documented as "add a migration that erases user data". When that was
  written there was a single instance — production with no users. Now the image
  `ghcr.io/zherikhov/hamstrack` is published and there is a self-hosting guide, Flyway applies the
  chain on every start, `ddl-auto=validate`. That is, this instruction has become a direction whose
  execution destroys the data of **every** self-hosted installation on the next upgrade.
- The terminology was muddled too: one sentence in `docs/api-cloud.md` joined two independent
  claims — "the API is not versioned" and "data may be reset". Cancelling the second must not
  quietly cancel the first.

## Decision

**The reset policy is cancelled for Hamstrack Cloud.** User data is kept;
the line in `README.md` is removed.

There is one wording, verbatim on every surface that raises the topic at all (README,
`docs/api-cloud.md`, the landing page, the announcement):

> Hamstrack Cloud does not reset user data. Your workspaces, projects and issues stay until they
> are deleted. The database is backed up daily and restoring from a backup has been tested; that
> is an operational practice, not a guaranteed service level.

Three properties of the wording are mandatory, not stylistic:

- the first sentence **denies a policy** rather than guaranteeing an outcome;
- the third sentence is **a caveat that travels together with the claim**: without it the marketing
  promise contradicts Terms §5 ("as is", no SLA) and §7 (disclaimer of liability for data loss),
  i.e. it reproduces exactly the same defect, only in the other direction;
- no dates, no numbers, no retention periods: a retention period is a statement about the retention
  of personal data, it belongs to HD-192 and requires legal inputs that HD-195 does not have.

**There will be no "beta" indicator in the application** (the owner's decision), and the word "beta"
is removed from every published surface: the claim about the API is rewritten as a property of the
API rather than of a phase — "the API is not versioned; breaking changes are announced in the
release notes".

**The registration screen does not change.** The "say it before the form is submitted" criterion was
an obligation to disclose an **adverse** condition; the condition no longer exists, so the criterion
is closed by deletion, not by adding text. A promise placed next to the "I agree to the Terms"
checkbox, where those Terms disclaim that promise, is the same contradiction defect, at the worst
possible moment.

**The ability to wipe data is kept, its status changes.** The procedure block stays (it holds
non-obvious knowledge: the deletion order — `issues` first, because `issues.workspace_id` has no
cascade; the re-arming of demo seeding; orphaned attachments), but it is renamed into an operator
tool and gets a hard rule: **never as a versioned Flyway migration** — only an out-of-band SQL
script against the instance that really has to be wiped.

## Consequences

+ No surface any longer states a data policy that another one contradicts.
+ The promise is worded so that it does not conflict with the Terms: it describes a practice and
  denies a policy, but guarantees nothing.
+ The claim about the API's instability survived separately from the cancelled claim about data.
+ The "wipe as a migration" trap is defused before the first self-hosted installation would have
  upgraded to a release carrying such a migration.
− **The "wipe and re-seed" emergency exit is closed for Cloud.** Any data-model change must now
  migrate the data; a reset becomes a breach of the published promise and requires a separate
  decision and announcement, not a routine migration.
− Walking back a promise costs more than never having made it: if an upcoming release holds an event
  that requires wiping production, it must be done **before** the wording is published.
− The RPO stays at up to 24 hours, there is no PITR, there is a single region — this is not hidden,
  but neither is it brought onto the registration screen: it is already covered by Terms §5/§7 and
  is not a policy applied to the user.
− The guarantee is the test category `PublishedClaimsTest` in the JVM suite (not vitest: `npm test`
  runs neither in CI nor in `mvnw verify`). It catches **the return of a known wording** and a
  divergence of the canonical paragraph, but not a reworded claim and not text assembled from
  adjacent JSX children (HD-241) — the limits are recorded in the test's javadoc.

## Alternatives

- **Keep the reset and disclose it before registration** (the ticket's second coherent position) —
  rejected: backups exist and restoring is verified, so the reset has stopped being a constraint;
  it would ask the user to accept a cost the operator is no longer obliged to impose — and to do so
  on the screen where he simultaneously agrees to Terms that disclaim liability for data loss.
- **Quietly stop resetting and write nothing** — rejected: the line in the README would go on
  contradicting three product surfaces, i.e. the original defect would remain in full.
- **Add the promise to the Terms** — rejected: inside a contract it turns into a guarantee that
  §5 ("as is", no SLA) in the same document disclaims — a self-contradiction within one text. The
  Terms remain a **boundary** for the copy, not its source. If a stronger promise is ever needed —
  the contract changes first, then the copy, never the other way round.
- **Delete the wipe procedure together with the policy** — rejected: it loses correct and
  non-obvious operational knowledge that under pressure would be reconstructed wrongly (deleting
  `workspaces` before `issues` gives an FK error).
- **Make the wording configurable (a property/profile, an in-app banner)** — rejected: that is
  exactly the banner the owner rejected, only more expensive; the claim is about the operator's
  instance, not about the product's capabilities, so there is nothing to switch between `dc` and
  `cloud`.
