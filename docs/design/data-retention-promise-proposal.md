# Data-retention promise — retiring the Cloud reset and saying so once (HD-195)

**Status:** proposed (spec only — no application code changes)
**Ticket:** HD-195 · release 0.18.0 · High
**Related:** HD-194 (licensing claim + its seal), HD-192 (legal pages, BLOCKED), HD-232 (`failed_email` retention), HD-241 (seal blind to assembled JSX), HD-187 (backups), ADR-0012 (write-only backup bucket)

---

## 1. Problem & goal

`README.md` tells the world that data on the Cloud instance "may periodically be reset". The
product tells nobody: the SPA contains no occurrence of the word *beta* and no statement about
what happens to a user's work. A person who arrives from an announcement post, signs up and
spends an afternoon filing issues has been given no data policy at all — and the one policy that
exists lives in a file that is not part of the product. Meanwhile three surfaces already say the
opposite: `PrivacyPage` §5 ("We keep your data for as long as your account exists"), the landing
page's Cloud card ("Automatic backups & upgrades"), and the headline above it ("The task tracker
you can actually trust").

The owner has settled the fork: **the reset is retired.** Backups exist (daily `pg_dump` to a
write-only bucket, 30-day lifecycle, plus daily EBS snapshots), and a restore was drilled on
2026-08-26 in ~4 minutes. So the product keeps user work, the README line is deleted, and no
in-app beta indicator is built.

Success is: one durability statement, written once, carried verbatim by every surface that raises
the subject; the reset claim gone from every published surface; the *API*-stability claim (a
separate, still-true claim currently fused with the reset claim in `docs/api-cloud.md:13`)
surviving intact; the wipe *mechanism* preserved as an operator tool with the trap in it labelled;
and a test in the CI suite that fails when the retired claim returns.

---

## 2. Scope

**In scope**

- Deleting the reset claim from every published surface, and replacing it with one canonical
  durability paragraph (§5.1) used verbatim everywhere.
- Separating the API-stability claim from the data-durability claim in the two API references and
  in `openapi.yaml`.
- Adding the canonical paragraph to the landing page (the arriving-from-the-post surface).
- Re-labelling the wipe how-to in `docs/project-state.md` as an operator tool, and fixing the
  data-destroying trap it currently contains (§7.2).
- A dated historical marker on `docs/design/admin-console-proposal.md` §4.4.
- One JUnit category test that seals the retired claim and the canonical paragraph (§9).
- One ADR (§12).

**Out of scope / non-goals**

- **No in-app beta indicator**, dismissible or otherwise (owner decision). No shell banner, no
  badge, no `beta` flag in `/api/meta`, no config store field.
- **No change to `RegisterPage.tsx`** — argued in §4, not merely asserted.
- **No change to `TermsPage.tsx` / `PrivacyPage.tsx` / `CookiesPage.tsx`.** HD-192 owns those and
  is blocked on inputs HD-195 does not have (§8).
- No retention *periods* stated anywhere new (that is HD-192 + HD-232 territory).
- No SLA, no uptime commitment, no point-in-time recovery claim.
- No deletion of the wipe capability — a retired policy is not a deleted tool.
- No backend, schema, endpoint, DTO or permission change of any kind.
- The announcement post, the GitHub repo description and the GitHub Release bodies live outside
  this repository; they are named in the propagation checklist (§9.4) and are the owner's to edit.

---

## 3. Actors & permissions

Nobody. This ticket adds no endpoint, no authorization decision, no tenant-scoped resource, and no
runtime behaviour. Every artefact it touches is text: repository documentation, static SPA copy
compiled into the bundle, and one test.

The only "actor" worth naming is the **next contributor who reads
`docs/project-state.md` and treats the wipe block as sanctioned** — §7.2 exists for them.

Reviewer routing: `api-docs-sync` applies (the `openapi.yaml` + both `docs/api-*.md` trio moves
together, even though no endpoint changes); `tenancy-reviewer`, `migration-reviewer` and
`security-officer` are `n/a` by construction — there is no query, no migration and no auth surface
in the diff.

---

## 4. The register-screen criterion: moot as a disclosure, and adding copy would be a new defect

The ticket's second acceptance criterion — *"The register screen states it before submission, not
after"* — was written to force disclosure of an **adverse** term before consent. Retiring the reset
removes the adverse term. Three things follow, and they point the same way.

**(a) There is nothing left to disclose.** The criterion is a pre-consent disclosure obligation,
and disclosure obligations attach to terms that work against the person consenting. "We do not
reset your data" is not one. The criterion is therefore **satisfied by removal rather than by
addition** — and that reasoning must be written down (here, and in the ADR), because the next
reader of the ticket will otherwise "fix" an unmet criterion by adding copy.

**(b) The pre-submission surface a user is actually bound by already exists.** When
`termsAcceptanceRequired` is on, `RegisterPage` renders a required checkbox linking `/terms` and
`/privacy`, both opening before submission. Those are the documents that state, contractually, what
happens to the account's data. A marketing sentence next to that checkbox does not improve the
disclosure; it competes with it.

**(c) A promise placed beside the checkbox that disclaims it is the same defect, pointed the other
way.** Terms §5 provides the Service "as is" and "as available" with no SLA; §7 disclaims liability
for loss of data. Putting *"we never reset your data"* immediately above a checkbox that says *"I
agree to the Terms of Service"* manufactures the exact contradiction HD-195 exists to delete — a
surface stating a data policy that another surface contradicts, at the one moment the user is
legally agreeing to the second one.

**One adverse fact does survive the retirement and is deliberately not on the register screen:**
the recovery point objective is up to 24 hours (`docs/ops-prod-hardening.md` §6.1 states plainly
that layer 1 loses up to a day and that PITR is absent), and there is no SLA. That is not a policy
chosen and applied *to* the user — it is the ordinary shape of any service without a continuous
archiver, and it is already covered by Terms §5/§7. It does not belong at signup. It does,
however, bound what marketing copy may claim, which is why the canonical paragraph carries its own
qualifier (§5.1) and why the forbidden-wording list (§5.4) exists.

**Decision: `RegisterPage.tsx` is not modified by HD-195.** AC#2 is recorded as met-by-retirement.

---

## 5. The wording

### 5.1 The canonical durability paragraph (one wording, everywhere)

Three sentences, written as **plain text with no emphasis markup**, carried verbatim by every
surface that raises the subject:

> Hamstrack Cloud does not reset user data. Your workspaces, projects and issues stay until they
> are deleted. The database is backed up daily and restoring from a backup has been tested; that
> is an operational practice, not a guaranteed service level.

Why each clause is shaped the way it is:

- **"does not reset user data"** — the retraction needs a negative, because it has an antecedent
  the reader may already have seen. It is a statement about a *policy*, not a guarantee about an
  *outcome*, which is what keeps it compatible with Terms §7.
- **"until they are deleted"**, passive and deleter-unnamed — a workspace owner can delete a
  workspace holding another member's issues, so "until *you* delete them" is false for the most
  common non-self deletion. This is the category-over-member rule: naming the deleter dates the
  sentence to today's deletion model.
- **"backed up daily"** — true (host `systemd` timer, `hamstrack-backup.timer`, 03:15 UTC). It
  describes cadence, not durability, and states no retention period (§8).
- **"restoring from a backup has been tested"** — past tense, and deliberately so. It requires
  exactly one true historical event (2026-08-26, logged in `ops-prod-hardening.md` §6.6, recorded
  as a **partial** walk of the procedure). "Restores are rehearsed" would assert an ongoing
  practice that one partial drill does not yet support, and would go quietly false the first
  quarter a drill is skipped.
- **"an operational practice, not a guaranteed service level"** — this is the load-bearing clause.
  It is what makes the paragraph agree with Terms §5 and §7 instead of contradicting them. **The
  qualifier travels with the claim**: any surface that copies the first two sentences and drops the
  third has re-created the defect this ticket closes.

No dates, no numbers, no counts, no version references in the paragraph — every one of those would
be a thing that stales one entry before the sentence does.

### 5.2 The API-stability claim (separate, retained, de-fused)

`docs/api-cloud.md:13` currently fuses two unrelated claims in one "Beta notice". They are
independent: the API really is unversioned, and retiring the data policy must not retire that.
Canonical form, for both API references and `openapi.yaml`:

> **API stability:** the API is unversioned — breaking changes are possible and are announced in
> release notes.

Note what was dropped: **"while Hamstrack is in beta"**. Two reasons, and the first is the stronger
one. (i) It is a *conditional* — it implies that when beta ends the API becomes versioned, which is
a commitment nobody made and which would go stale the day the label is dropped. Written as a
property of the API rather than of a phase, the sentence stays true through any maturity change.
(ii) The word had one remaining job on the data side ("while in *test mode*") and that job is now
retired; keeping "beta" only in the API docs would leave the maturity label living in exactly one
place, which is how the *reset* claim came to survive alone in the README.

**Recommendation: remove "beta" from every published surface** (README status line included), and
do not reintroduce it as a maturity label anywhere. This is consistent with the owner's rejection
of an in-app indicator: if the product does not call itself a beta to its users, its documentation
should not be the only place that does. Flagged for confirmation in §11.

### 5.3 The demo-seeding claim (independent, survives, and gets one wording)

The README/`api-cloud` sentence fuses a *third* claim onto the reset: "every account gets a
pre-populated demo project/workspace to explore". It is true, unrelated to durability, and today
inconsistent between the two files (README says *project*, `api-cloud` says *workspace*;
`DemoDataService` seeds a **Demo Workspace containing a Demo Project**, and
`WelcomePage.tsx:55` says "Comes with a demo project to explore"). One wording:

> Every account starts with a demo workspace and a sample project to explore.

Carried by README and `docs/api-cloud.md` only. `WelcomePage` is left alone — it is describing the
workspace the user is about to create, not the account-level seeding claim, and its sentence is
correct in that context.

### 5.4 Wording that may NOT be used (checked against Terms §5/§7)

These would each be a marketing promise the Terms disclaim — the same defect class, pointed the
other way:

- "your data is safe" / "your work is safe" — an outcome guarantee; §7 disclaims liability for
  exactly that outcome.
- "never lose your work", "zero data loss", "no data loss" — contradicted by the documented ≤24 h
  RPO and by the absence of PITR.
- "guaranteed", "we guarantee", "SLA", "uptime" — §5 says explicitly there is no guaranteed service
  level.
- "point-in-time recovery", "continuous backup", "instant restore" — untrue; `ops-prod-hardening.md`
  §6.1 lists PITR under *deliberately absent*.
- "redundant across regions", "geo-redundant" — untrue; single region (`eu-north-1`).
- "your data is never deleted" — false and undesirable; deletion on request is a Privacy Policy
  commitment.

**The rule, phrased as a category so it outlives this list:** published copy may describe what the
operator *does* (a practice), and may deny a policy the product does *not* apply (the reset). It
may not warrant an *outcome* the Terms decline to warrant. If a stronger claim is ever wanted, the
Terms change first and the copy second — never the copy first.

---

## 6. Behaviour & rules (what "done" means, as invariants)

1. **One wording.** The canonical paragraph appears on every surface that states the data policy,
   character-identical after whitespace normalisation. There is no short form and no long form —
   variants are how "one wording" decayed the first time.
2. **Contiguous literal text.** On every surface, including JSX, the paragraph is written as plain
   contiguous text. It may not be assembled from adjacent children (`{' '}` between halves),
   concatenated, templated or built at runtime. This is not style: it is the only reason the guard
   in §9 can see it, and HD-241 records the exact blind spot being avoided.
3. **The qualifier is part of the claim.** No surface carries the first sentence without the third.
4. **Cloud-scoped by construction.** The paragraph names "Hamstrack Cloud" in its first four words,
   so it cannot be misread as a promise about a self-hosted instance no matter where it is pasted.
5. **DC-facing documents do not carry it.** `docs/api-dc.md` and `docs/self-hosting.md` get the
   API-stability sentence and a durability pointer respectively — never the Cloud paragraph (§10).
6. **The API claim and the data claim are two notes, never one.** Nothing may re-fuse them.
7. **The wipe capability survives the policy.** The mechanism stays documented; what changes is its
   status (operator tool, not user-facing policy) and the way it is run (§7.2).
8. **No published surface calls Hamstrack a beta.**

---

## 7. Surface list (execute this; do not re-derive the sweep)

Paths are repo-relative from `C:\Projects\Development\easyTask`.

### 7.1 Published surfaces — the claim itself

| # | Surface | What changes |
|---|---|---|
| 1 | `README.md:22` (status line) | Replace wholesale. New line: `> **Status:** Hamstrack is in active development — breaking changes are called out in the release notes. ` + the canonical paragraph (§5.1) + the demo sentence (§5.3). Drops: "(beta)", "test mode", "may periodically be reset". Keep it a single blockquote. |
| 2 | `docs/api-cloud.md:13` | Split the one "Beta notice" into **two** blockquote notes: `> **API stability:** …` (§5.2) and `> **Your data:** ` + canonical paragraph + demo sentence. |
| 3 | `docs/api-dc.md:13` | API-stability sentence only (§5.2); drop "while Hamstrack is in beta". **Must not** gain the Cloud paragraph. |
| 4 | `src/main/frontend/public/openapi.yaml:21` | In `info.description`, "The API is unversioned while in beta; breaking changes are announced in release notes." → "The API is unversioned; breaking changes are announced in release notes." Nothing else in the spec changes. Re-validate: `npx @apidevtools/swagger-cli validate src/main/frontend/public/openapi.yaml`. |
| 5 | `src/main/frontend/src/pages/LandingPage.tsx` | Add the canonical paragraph as a muted note in the `#deploy` section, immediately **after** the existing `<p className="lp-licence">` (lines 231–238) — the established slot for a policy-meta line under the deploy cards. New class (e.g. `lp-datanote`) in `src/main/frontend/src/index.css` mirroring `.lp-licence`'s scale/colour tokens; no new hex. Plain text, no `<strong>`, no `{' '}` inside the sentence. Nothing else on the page changes — the hero, the Cloud card bullets ("Automatic backups & upgrades") and the final CTA stay as they are. |
| 6 | `src/main/resources/static/openapi.yaml` | **Do not touch.** `src/main/resources/static/` is gitignored and wiped by every Vite build (`emptyOutDir`); the file is a build copy of surface #4 and regenerates from it. It is not a seventh surface, and the guard in §9 must not scan it (it is absent on a clean checkout, so scanning it would make the test's result depend on whether a build has run). |

### 7.2 The wipe mechanism — kept, re-labelled, and one real trap fixed

`docs/project-state.md:115` documents how to perform a test-mode reset. **Keep it.** Deleting it
throws away operational knowledge that is correct, non-obvious and expensive to re-derive: the
delete order (`issues` first, because `issues.workspace_id` has no cascade — get this wrong and you
get an FK error), the demo re-arm (`UPDATE users SET demo_seeded_at = NULL`), the orphaned-blob
consequence, and the Flyway "never rewrite an applied migration" rule. Policy retired ≠ capability
deleted, and a local or self-hosted instance may legitimately want a wipe.

But whoever reads that section next will treat it as sanctioned, so it must be re-labelled — and
while re-labelling it, **fix the trap in it**, which the sweep for this ticket found:

> The block instructs the operator to *"add a migration that wipes user data"*. When it was written,
> the only installation in existence was a production instance with no users to preserve. That is no
> longer true: Hamstrack publishes `ghcr.io/zherikhov/hamstrack` and a self-hosting guide, Flyway
> runs on every startup, and `ddl-auto=validate` means the chain is applied unconditionally. **A wipe
> committed as a versioned migration destroys the data of every self-hosted installation on its next
> upgrade** — silently, as part of a routine `docker compose pull && up -d`. This is the single most
> valuable finding in this ticket's sweep, and it is a DC/Cloud rule, not a documentation nicety
> (§10).

Rewrite outline for that section:

- Retitle: **"Wiping an instance's user data (operator tool — not a policy)"**.
- Open with the status: Hamstrack Cloud does not do this any more (link ADR-0022); this block exists
  for a local, demo or self-hosted instance whose owner wants a clean slate.
- **Never as a versioned Flyway migration.** Run it as an out-of-band SQL script against the one
  instance being wiped (`psql -f`), by the operator of that instance. State the reason in one
  sentence, as a property of the release chain rather than as an anecdote: anything in the
  migration chain runs on every installation that upgrades.
- Keep the SQL block, the ordering rationale, the demo re-arm and the orphaned-attachments note
  verbatim.
- Note that the historical `V5__demo_data_reset.sql` was folded away by the schema squash (already
  there) — and that its existence is *why* the "add a migration" phrasing was once correct.
- `docs/project-state.md:113` (demo seeding) contains "after a data reset, users with a live session
  never hit `/login`" as the rationale for calling the seeder from `/refresh`. That rationale is
  still valid; change "a data reset" → "a wipe" so the phrase does not read as a scheduled policy.
  Low priority, same commit.

### 7.3 Historical design doc — in scope, minimally

`docs/design/admin-console-proposal.md:99` reads *"Prod is in test mode with an approved reset
precedent and automatic demo reseeding on next login."* **In scope**, and the reason is not that it
is historical — it is that it is written in the **present tense**, so a reader today reads a
standing statement of policy, and a design doc is exactly the kind of document a future spec cites
as precedent ("there is an approved reset precedent"). Rewriting the paragraph would be worse: the
document is the record of how the taxonomy migration was actually performed, and a record edited to
match today is no longer a record.

Change: add one dated marker line at the top of §4.4 and nothing else, e.g.

> *(Historical — written 2026-07-16, when the production instance had no users to preserve. The
> reset policy this section assumes was retired by HD-195; see ADR-0022. Kept as the record of how
> the migration was done, not as current policy.)*

Line 126 ("Existing … endpoints are retired (breaking, acceptable in beta)") is a statement about
*API* breakage at the time of writing, not about data, and is covered by the same marker. Leave it.

### 7.4 DC-facing durability pointer

`docs/self-hosting.md` — in the **Backups** section intro, one sentence so a self-hoster cannot
carry the Cloud statement across:

> Durability on a self-hosted instance is whatever your backups make it — nothing in the product
> provides it for you, and the statement about Hamstrack Cloud is about the operator's instance,
> not yours.

Recommended, cheap, and it closes the only way the new wording could become a false promise to a
DC reader.

### 7.5 Surfaces checked and deliberately NOT changed

Recorded so the next sweep does not redo them:

- `src/main/frontend/src/pages/RegisterPage.tsx` — §4.
- `src/main/frontend/src/pages/legal/{Terms,Privacy,Cookies}Page.tsx` — §8. Verified consistent
  with the new position as they stand: Privacy §5 ("we keep your data for as long as your account
  exists") already agrees with the canonical paragraph; Terms §5/§7 are the bound the paragraph's
  third clause respects.
- `src/main/frontend/index.html` — no beta claim, no data claim (verified).
- `src/main/frontend/src/components/AboutModal.tsx`, `Footer.tsx` — licence copy only, no data or
  maturity claim (verified).
- `src/main/frontend/src/pages/welcome/WelcomePage.tsx:55` — §5.3.
- `pom.xml <description>` — no beta claim (verified).
- `.claude/agents/*.md`, `.claude/skills/**` — swept for the copy-**generator** failure HD-194 hit
  (an agent prompt that would rewrite the retired claim into every future spec by construction).
  No agent or skill prompt mentions a reset, test mode, or a data policy. Nothing to fix — but
  re-read any prompt added later the same way.
- `.github/workflows/*.yml`, `docs/release-checklist.md` — the only "beta" occurrences are
  pre-release *tag* names (`0.13.0-beta`), which are a versioning convention, not a product claim.
- `docs/ops-prod-hardening.md`, `docs/design/production-backups-proposal.md`,
  `docs/observability.md` — operator documents; they describe the backup machinery and contain no
  user-facing data policy.

---

## 8. Coupling to HD-192 (legal pages) and HD-232 — what may ship, and what may not

HD-192 is blocked on the owner for legal entity, jurisdiction, a monitored legal/privacy mailbox,
a retention period in days, and sub-processor confirmation. HD-195 must not spend any of those
inputs in advance. The line is sharp and it is about **which kind of claim** is being made:

**Ships now, needs nothing from HD-192:**

- *Deleting* the reset claim. Removing a statement never requires legal input.
- The canonical paragraph, in full. Every clause is either a denial of a policy (sentence 1), a
  description of current behaviour (sentence 2 — and it merely restates Privacy §5, which already
  says the data lives as long as the account), or a description of an operational practice with an
  explicit non-warranty (sentence 3). None of them is a personal-data **retention representation**.
- The API-stability rewording, on all three API surfaces.

**Must NOT ship in HD-195 — this would be writing HD-192's answer:**

- Any *period*. "Backups are kept 30 days", "logs are kept N days", "we delete within N days" — the
  moment a number about how long a copy of personal data persists lands in the product, it is a
  retention representation, and it interacts with erasure obligations HD-192 has the lawyer for.
  **The specific reconciliations this raises are recorded on HD-192, not here**, and HD-195 must
  not attempt them — not even as a helpful sentence. A design document in a published repository is
  not the place to characterise a gap in a live policy; the ticket is.
- Any edit to `PrivacyPage.tsx`, including "improvements" to §5.
- Anything about HD-232's subject matter. That is a retention disclosure by definition, and it
  belongs to HD-192, where it is now recorded.

**Hand-off note for HD-192**: the two reconciliations HD-195 deliberately leaves open are written
up on that ticket — following this section’s own rule that they belong there and not in the
product, which, for a tracked and published `docs/design/` file, includes this one. The canonical
paragraph leaves room for whatever the legal answer turns out to be: it states cadence, never
duration.

---

## 9. The returning-claim guard

### 9.1 Should there be one? Yes — but a narrow one, and not where HD-194 put its own

The claim being retired here is the same *shape* as HD-194's: a sentence that was true once,
survives a decision that made it false, and comes back by copy-paste from git history or from an
older doc. That is exactly what a seal is for. But two findings change the design.

**Finding 1 — vitest never runs in CI.** `.github/workflows/build.yml` runs exactly one command
(`./mvnw -B verify`); `pom.xml`'s `frontend-maven-plugin` executions are `npm ci` and `npm run
build` (`tsc -b && vite build`) — **no `npm test`**. Nothing in the repository, CI, or
`docs/release-checklist.md` invokes `vitest`. So `src/main/frontend/src/licensing.test.ts` — and
any sibling written for HD-195 — runs only when a human types `npm test` in
`src/main/frontend`. A guard that CI never executes is a guard nobody has seen fail *by
construction*. (This is a real gap in HD-194's seal too, and is worth its own ticket; see §11.)

**Finding 2 — this claim's surfaces are mostly outside `src/`.** The reset claim lives in
`README.md` and `docs/*.md`; the SPA contains none of it today. A vitest seal scoped like
`licensing.test.ts` would guard a set in which the claim has never appeared, while being blind to
the three files where it actually lives.

**Recommendation: one plain JUnit test**, no Spring context, in the Maven suite — therefore in CI.
Precedent exists for reading repository text from a test with a CWD-relative path:
`AuthMailDoorsTest` walks `Path.of("src", "main", "java")`, and `MailThrottleCoverageTest` reads
`rules.yml`. Java reads `.tsx` and `.md` as text equally well, so **one** test covers both halves of
the surface set — which is itself the point: a guard split across two runners, one of which does not
run, is how half a set silently stops being covered.

Suggested location: `src/test/java/com/hamstrack/common/docs/PublishedClaimsTest.java`.

### 9.2 What it must assert

Scanned set — a *category*, not an enumeration, expressed as: the repository's **published product
surfaces**, i.e. `README.md`, `docs/api-cloud.md`, `docs/api-dc.md`, `docs/self-hosting.md`,
`src/main/frontend/index.html`, `src/main/frontend/public/openapi.yaml`, and every
`src/main/frontend/src/**/*.{ts,tsx,css}` whose filename does not declare it a test
(`*.test.ts`, `*.test.tsx`). Explicitly **not** scanned: `src/main/resources/static/**` (build
output, gitignored — §7.1 #6), `docs/project-state.md`, `docs/ops-prod-hardening.md` and
`docs/design/**` (internal records and operator tools, which must remain free to *discuss* a wipe),
`.github/**` and `docs/release-checklist.md` (tag names).

1. **Tripwire.** The scan resolves a non-trivial number of files and every named non-glob surface
   above exists. A glob that matches nothing passes every other assertion in the file.
2. **Negative — the retired claim does not return.** No scanned surface contains, case-insensitively:
   `test[- ]mode`; `may (periodically )?be reset`; `(data|workspaces?|projects?|issues?) (may|might|can|will) be (reset|wiped|erased|cleared)`; `periodically (be )?(reset|wiped)`; or the bare word `beta`.
3. **Positive — the canonical paragraph is present and identical.** After normalisation (collapse
   all whitespace runs to one space; strip `{' '}`, `**`, `*`, `_`, `<strong>`/`</strong>`,
   `&amp;`→`&`), `README.md`, `docs/api-cloud.md` and `LandingPage.tsx` each **contain the canonical
   paragraph in full**. The paragraph is written **once** in the test, as a single constant, and
   compared against each surface — never re-typed per surface, for the reason `licensing.test.ts`
   gives about its own two-copy drift.
4. **Positive — no fragment without the whole.** If a scanned surface contains the fragment
   `does not reset` (or `backed up daily`) but **not** the full normalised paragraph, fail with
   *"the canonical paragraph was reworded, truncated or assembled"*. This is the HD-241 mitigation:
   for this one sentence, assembly and truncation become a detectable state rather than a blind
   spot, because the exact target text is known.
5. **Positive — the API claim survived the data claim's retirement.** `docs/api-cloud.md`,
   `docs/api-dc.md` and `openapi.yaml` each still say the API is unversioned. This is the
   assertion that makes the *separation* of the two fused claims permanent, and it is the one a
   future "tidy-up" of the beta notice would trip.
6. **Negative — DC surfaces stay DC.** `docs/api-dc.md` and `docs/self-hosting.md` do **not**
   contain the canonical paragraph. A Cloud promise pasted into a self-hosted reference is a false
   promise to every self-hoster, and it is the single most likely copy-paste error in this diff.

### 9.3 What it structurally cannot see — state it in the javadoc, do not imply completeness

- **A reworded proposition.** "Instances are refreshed periodically", "workspaces older than N days
  are cleared", "for evaluation only, don't rely on it" — none matches the regexes. The negative
  half is a tripwire for the *known phrasing returning* (which is how the licensing claim survived
  five surfaces: copy-paste, not invention), never a proof that no surface contradicts.
- **Assembled or runtime-built copy in general.** Assertion 4 closes it for the canonical paragraph
  only, because that is the one string whose exact target text is known. A *different* claim
  assembled from adjacent JSX children remains invisible — the HD-241 shape, unclosed, and
  deliberately named rather than papered over.
- **New files of a kind not in the scanned category** — a new `docs/*.md`, a new email template, a
  new static page. The globs cover `src/main/frontend/src/**` by construction, but the markdown
  surfaces are named individually because "every `.md` in the repo" would sweep in the records and
  runbooks that must stay free to discuss a wipe.
- **Everything outside the repository** — the announcement post, the GitHub repo description and
  topics, GitHub Release bodies, the Swagger UI rendering (derived from #4), support replies, any
  future translated copy.
- **An omission.** If a surface simply says nothing, only assertion 3's three named files notice.
  The product-wide version of "no surface tells the user anything" — the defect HD-195 opened with
  — is not mechanically detectable.

### 9.4 Propagation checklist (the failure message)

The failure message is the checklist, in the shape `licensing.test.ts` uses. It must state the
decision, the canonical paragraph, the forbidden-wording rule, and then the surfaces the test
cannot see: the announcement post, the GitHub repository description/topics, GitHub Release bodies,
`docs/project-state.md`'s operator tool, `docs/design/admin-console-proposal.md`'s historical
marker, and any prompt under `.claude/` that generates copy. Write it as properties, not counts —
no leading "the 6 surfaces below".

### 9.5 The guard must be seen to fail

A guard nobody has watched go red is a belief. Before this ticket is closed, plant each of these,
run the test, observe the failure name the right file and line, then remove it:

1. The old README sentence, restored verbatim at `README.md:22`.
2. `"Data may be reset while we are in beta."` as a string in any non-test `.tsx` under
   `src/main/frontend/src/`.
3. The canonical paragraph on `LandingPage.tsx` with its third clause deleted (must trip assertion
   4, not merely 3).
4. The canonical paragraph pasted into `docs/api-dc.md` (must trip assertion 6).
5. `docs/api-cloud.md`'s API-stability sentence deleted while the data note stays (must trip
   assertion 5).

---

## 10. DC / Cloud implications

- **No profile gating, no property, no env var, no wiring.** This ticket introduces no runtime
  toggle. The durability statement is a fact about the *operator's* Cloud instance, not a product
  capability, so there is nothing for `dc`/`cloud` to switch — and inventing a
  `app.instance.data-policy` string to render it in-app would be building the banner the owner
  rejected, with extra steps.
- **The single-codebase rule bites in the documentation direction here**, which is the unusual part:
  `README.md` serves both audiences, so the durability paragraph is Cloud-scoped in its own first
  four words; `docs/api-dc.md` and `docs/self-hosting.md` never receive it (guard assertion 6);
  `docs/self-hosting.md` gets the DC-equivalent pointer instead (§7.4).
- **The wipe-as-migration trap is a genuine DC/Cloud defect, not a doc nit** (§7.2). The Flyway
  chain is the one artefact that executes identically on the operator's instance and on every
  self-hosted install; an operational action that is safe on one and destructive on all the others
  therefore may not live in it. Worth restating as a general rule for `backend-builder`: *anything
  placed in the migration chain runs on every installation that upgrades — an instance-specific
  action is a script, not a migration.*
- `dc-cloud-guard` should still run this diff (it touches `docs/self-hosting.md` and the DC API
  reference), and will find no properties, compose files or `.env.prod.example` entries to check.

---

## 11. Acceptance criteria

Data & policy consistency:

1. `rg -i "test.?mode|may periodically be reset"` returns hits **only** in
   `docs/project-state.md` (re-labelled operator tool) and `docs/design/admin-console-proposal.md`
   (dated historical marker) — and in neither case as a statement of current policy.
2. `rg -i "\bbeta\b"` returns no hits in `README.md`, `docs/api-cloud.md`, `docs/api-dc.md`,
   `src/main/frontend/public/openapi.yaml`, `src/main/frontend/index.html`, or any non-test file
   under `src/main/frontend/src/`.
3. The canonical paragraph (§5.1) appears, verbatim and complete, in `README.md`,
   `docs/api-cloud.md` and `src/main/frontend/src/pages/LandingPage.tsx`, and in no DC-facing file.
4. `docs/api-cloud.md` and `docs/api-dc.md` each carry the API-stability sentence as its **own**
   note, not fused with a data claim; `openapi.yaml`'s `info.description` says the API is
   unversioned without saying "beta".
5. No surface uses any wording from the forbidden list (§5.4).
6. `src/main/frontend/src/pages/RegisterPage.tsx` is byte-identical to `main` (AC#2 is met by
   retirement — §4).
7. `TermsPage.tsx`, `PrivacyPage.tsx`, `CookiesPage.tsx` are byte-identical to `main`; no retention
   period, and no statement about backup windows, appears in any legal page (§8).

Mechanism & records:

8. `docs/project-state.md`'s wipe section is retitled, states that Cloud no longer does this, links
   ADR-0022, and instructs that a wipe is run as an out-of-band script — with an explicit statement
   that a wipe committed as a Flyway migration destroys data on every self-hosted installation that
   upgrades.
9. `docs/design/admin-console-proposal.md` §4.4 carries a dated historical marker; the paragraph
   itself is unchanged.
10. `docs/adr/0022-*.md` exists with `Status: Proposed`, and `docs/adr/README.md` has its row.

Guard:

11. `PublishedClaimsTest` exists, runs inside `./mvnw -B verify` (no Spring context, no DB), and is
    green on the finished branch.
12. Each of the five plants in §9.5 has been run and observed red, and the failure message named the
    offending file and line; the tree is clean afterwards.
13. The test's javadoc states its blind spots (§9.3) and its failure message carries the propagation
    checklist (§9.4), written without a leading count.

Build & docs hygiene:

14. `npx @apidevtools/swagger-cli validate src/main/frontend/public/openapi.yaml` passes.
15. `src/main/resources/static/openapi.yaml` is untouched in the diff (`git status` clean for that
    path — it is gitignored).
16. `npm run build` (i.e. `tsc -b && vite build`) passes; the new CSS class uses existing tokens and
    no hardcoded hex (`DESIGN.md`).
17. `npm test` in `src/main/frontend` is still green (the HD-194 seal must not be disturbed by the
    landing-page edit).

---

## 12. Highest-risk assumption

**That "Hamstrack Cloud does not reset user data" is a promise the project can keep for the
lifetime of the instance it is made about.** Publishing it converts every future data-model change
into a data-migration obligation for a single-operator service with one production instance, no
staging environment, an RPO of up to 24 hours and no SLA. Today's escape hatch — "wipe and reseed,
it's beta" — is exactly what is being given up, and the schema squash (HD-188) is a live example of
the class of work that historically leaned on it.

The mitigation is in the wording, not in a caveat: the paragraph denies a *policy* ("does not
reset") and describes a *practice* ("backed up daily"), and warrants no outcome. A future
emergency wipe would still be a broken promise — but a broken promise about a policy is
recoverable by announcing a policy change, whereas a broken warranty is not. The ADR exists so
that the next contributor who reaches for a wipe migration finds a decision to overturn rather than
a habit to follow.

Second-order risk, worth the owner's attention: **retracting a durability promise costs far more
trust than never making one.** If there is any foreseeable event in the next two releases that
would require wiping production, it should be done *before* this copy ships, not after.

---

## 13. Open questions

1. **Drop "beta" everywhere, or keep it as a maturity label in the API docs?**
   *Recommendation: drop it everywhere* (§5.2) — the conditional it creates ("unversioned *while* in
   beta") is a commitment nobody made, and leaving the label in exactly one place is the shape that
   let the reset claim survive alone in the README. Reversible by one sentence if the owner
   disagrees.
2. **Landing-page placement.** *Recommendation: a muted note directly under the existing licence
   note in the `#deploy` section* — an established slot where a plain policy sentence reads
   naturally, rather than inside the Cloud card where it would compete with feature bullets and
   raise a question the visitor did not have. Owner may prefer it inside the card.
3. **The announcement post is outside the repository and outside the guard.** *Recommendation: the
   post carries the canonical paragraph verbatim*, and the owner pastes it rather than paraphrasing
   — paraphrase is how a fifth variant is born. Same for the GitHub repo description and any
   Release body that raises the subject.
4. **Restore-drill cadence.** The claim "restoring from a backup has been tested" is past tense and
   stays true even if the quarterly cadence in `ops-prod-hardening.md` §6.5 lapses. That is a
   deliberate wording choice (§5.1) — confirm the owner is comfortable with a sentence that does not
   decay, rather than one ("restores are rehearsed quarterly") that would silently become false.
   Note the 2026-08-26 drill was logged as a **partial** walk of the procedure.
5. **`npm test` is not in CI — separate ticket?** Found while specifying the guard (§9.1): no
   workflow, Maven execution, or checklist runs `vitest`, so the HD-194 seal and every existing
   `*.test.tsx` execute only when a human runs them locally. HD-195 works around it by putting its
   own guard in the JVM suite. *Recommendation: file a follow-up* to wire `npm test` into
   `./mvnw -B verify` (or a second CI step), because the workaround does not help the ~dozen SPA
   tests already in the tree.
6. **HD-192 hand-off.** Confirm the owner accepts that the backup-window ↔ erasure reconciliation
   (and HD-232's 90/7 asymmetry) stays entirely with HD-192 and does not get a "helpful" sentence
   here (§8).

---

## 14. Architectural decisions (ADR-worthy)

One. Retiring the reset is not routine feature mechanics: it is a public, hard-to-reverse product
commitment that changes what future engineering is allowed to do (no wipe-and-reseed escape hatch;
every data-model change becomes a migration obligation), and it establishes a rule a future
contributor will otherwise trip over ("why can't I just add a reset migration like `V5` did?").

**ADR-0022 — Cloud user data is not reset; a wipe is an operator tool, never a migration.**

- **Chosen:** withdraw the scheduled/ad-hoc reset policy for Hamstrack Cloud; publish one durability
  paragraph that denies the policy and describes the backup practice without warranting an outcome;
  keep the wipe procedure documented as an out-of-band operator script; forbid a wipe in the Flyway
  chain.
- **Rejected — keep the reset and disclose it pre-signup** (the ticket's other coherent position):
  with backups in place and a drilled restore, the reset is a policy chosen rather than a limitation
  suffered; disclosing it would ask users to accept a cost the operator no longer has to impose, on
  the exact screen where they are consenting to Terms that already disclaim data loss.
- **Rejected — say nothing and quietly stop resetting:** leaves the README claim contradicting three
  product surfaces, which is the defect the ticket exists to close.
- **Rejected — delete the wipe how-to along with the policy:** loses correct, non-obvious
  operational knowledge (delete ordering, demo re-arm, orphaned blobs) that would be re-derived
  wrongly under time pressure.
- **Trade-off accepted:** the wipe-and-reseed escape hatch is gone for Cloud; schema work must
  preserve data or be scheduled around a documented, announced exception.

Draft written to `docs/adr/0022-cloud-data-not-reset.md` with `Status: Proposed`; the orchestrator
flips it to `Accepted` once the change ships.
