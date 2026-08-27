# Invitation budget — the invite mailer is an open relay, and the control that closes it is keyed on the victim (HD-190)

**Status:** proposal / design review. **Date:** 2026-08-27. **Author:** systems-analyst.
**Release:** 0.18.0 (public-launch readiness), priority **Urgent** — a launch gate, because the abuse
it permits is aimed at strangers and is sent from our domain.
**Related:** HD-133 (`UNIQUE(workspace_id, email)` on pending invites — filed, not built),
HD-202 (per-address throttle on forgot-password / resend-verification — filed, not built),
HD-199 (`RATE_LIMIT_TRUST_FORWARDED_FOR` — built, unmerged), HD-188 (Flyway chain squash — 0.18.0).
**Touches:** `WorkspaceService.inviteMember`, `common.ratelimit/**`, `common.mail/**`,
`common.observability.ProductMetrics`, a new Flyway migration,
`observability/grafana/provisioning/alerting/rules.yml`, `application.properties`,
`.env.prod.example`, `docs/self-hosting.md`, `docs/api-*.md`, `openapi.yaml`,
`src/main/frontend/src/pages/settings/WorkspacePeoplePage.tsx`.

---

## 0. The premise, corrected before anything is built on it

The ticket's core claim holds and I re-verified every part of it. Three details in it are wrong or
incomplete, and two of the three change the design.

**Confirmed.** `WorkspaceService.inviteMember` (`WorkspaceService.java:170-203`) validates the role,
the grant ceiling and "already a member", then writes a `WorkspaceInvite` and calls
`mailService.sendWorkspaceInviteEmail(req.email(), …)` with the address the caller typed. There is no
budget of any kind on that path. `V1__init_schema.sql:127-137` has no `UNIQUE(workspace_id, email)`,
so HD-133 is indeed unbuilt. `AuthRateLimitFilter` is registered on exactly the six literal
`/api/auth/*` URLs in `RateLimitConfig.java:19-25`; `PrincipalThrottleInterceptor` is registered in
exactly two places, `ReportRateLimitConfig` and `SearchRateLimitConfig`; `InviteController`
(`/api/invites`) carries list/accept/decline only. Nothing throttles the invite mailer.

**Correction 1 — "the application has exactly three rate limiters" undercounts, and the two it omits
are the two whose shapes this ticket needs.** There are five, and `docs/self-hosting.md` already
counts five node-local mechanisms:

| # | Mechanism | Key | Shape this ticket borrows |
|---|---|---|---|
| 1 | `AuthRateLimitFilter` per-IP fixed window | client IP | — (this design is deliberately **not** IP-keyed, §6.5) |
| 2 | **Per-account login backoff** (`RateLimitService.checkLoginAllowed`) | **lowercased submitted email, whether or not the account exists** | **the victim-keyed precedent** — the app already has one limiter keyed on a subject rather than an actor, and it already documents why that is enumeration-safe |
| 3 | Reports budget (`ReportRateLimiter`) | principal | the 429 + `Retry-After` envelope |
| 4 | Search budget (`SearchRateLimiter`) | principal | ditto |
| 5 | **Rank-rebalance cooldown** (`IssueRankService`, `RateLimitKind.RANK_REBALANCE`) | **project** | **the per-resource cooldown precedent** — the app already has a limiter that is not per-principal and not per-IP |

So "invitations are on none of them" is true, but "there is no precedent for what invitations need"
is false. Both missing shapes exist and the design below reuses their reasoning rather than inventing.

**Correction 2 — a per-minute window, "like the other limiters", does not protect the Resend
budget.** The quota named in the ticket is 3000 messages a **month**. Any per-minute ceiling loose
enough to let a team lead paste twenty addresses is 28 800/day, which spends a monthly quota before
lunch. A rate window and a quota are different units; the budget concern needs a **daily** ceiling,
and the harassment concern needs a **cooldown**. This spec therefore uses three windows (hour, day,
cooldown) and no per-minute window at all. §6.1.

**Correction 3 — the new properties must NOT be wired into `docker-compose.prod.yml`.** That file
carries `env_file: .env` (line 25) and its `environment:` block holds only *the deployment's
opinions* — values that must survive an operator setting nothing (`SPRING_PROFILES_ACTIVE`, `DB_URL`,
`RATE_LIMIT_TRUST_FORWARDED_FOR`), sealed by `ProdComposeContractTest`. A literal under
`environment:` **wins over `env_file:` and cannot be overridden from `.env`**, which is the exact
trap HD-199 spent a round removing. These are ordinary operator knobs; their wiring targets are
`application.properties` → `.env.prod.example` → `docs/self-hosting.md` → `docs/api-*.md` /
`openapi.yaml`, and compose is deliberately untouched. §11.

---

## 1. Problem & goal

Any account that can log in can cause Hamstrack to send mail to any address on the internet, without
limit, by typing that address into the invite box of a workspace it created seconds earlier. In Cloud
that account costs one throwaway mailbox, because `PUBLIC_SIGNUP_ENABLED` is `true` in production and
there is no cap on how many workspaces one user may create (there is no `app.workspace.max-*`
property, and no `DELETE /api/workspaces/{id}` either — a created workspace is permanent, which
matters later). The mail is authenticated by our SPF/DKIM and carries our domain, so a stranger's
spam complaints land on the same sending reputation that carries our verification and reset mail. The
first symptom of either failure — reputational or quota — is that new users stop receiving their
verification links, i.e. signup breaks silently, from a cause with no error in it.

**Goal.** No principal can turn Hamstrack into a bulk mailer, and no address can be made to receive
repeated invitations it did not ask for, regardless of how many accounts or workspaces the sender
controls. Refusals are ordinary, retryable `429`s that tell the caller something they can act on, and
the condition is visible to an operator through a metric and an alert rather than through a bounced
domain.

**Success looks like:** the largest number of invitation emails one authenticated stranger can cause
in a day is a configured number an operator chose, and one address cannot be made to receive more
than a handful in a day no matter who asks.

**The distinction this spec is organised around:** the three proposed ceilings are not one control at
three granularities. One of them closes the attack in the ticket, one bounds the spend, and one does
nothing at all against either. §6 says which is which and recommends not building the third.

---

## 2. Scope

### 2.1 In scope

1. **A per-sender volume budget** on invitations — two windows, hourly and daily, keyed on the
   principal, in-memory (§6.1).
2. **A per-recipient-per-sender cooldown** — the same principal may not invite the same address twice
   inside a window, *across every workspace*, and the state that enforces it is **persisted** so it
   cannot be reset by deleting the invite (§6.2, §7).
3. **A global per-recipient daily cap** — N per address per day, counting your own sends one each
   and every other sender once, so one account can neither exceed it nor spend it on somebody
   else's behalf (§6.3).
4. **One shared mechanism** (`RecipientMailThrottle` + `invite_send_events`) designed so HD-202's
   forgot-password / resend-verification throttle is two `require(...)` calls on it and not a second
   half-overlapping limiter (§6.6).
5. **Three `RateLimitKind` constants + two Grafana rules**, plus the observation that the most
   valuable of the rules watches *mail volume*, not throttle hits, because an abuser who stays under
   the ceiling trips nothing (§10).
6. **A structural seal** — `MailThrottleCoverageTest`, which inverts `ThrottleCoverageTest`'s axis
   from *path* to *mailer* and fails when a fourth kind of outbound mail is added (§9.3).
7. Full property wiring, DC/Cloud reasoning, docs (§11).

### 2.2 Out of scope, named so nothing here reads as covering them

- **HD-133's `UNIQUE(workspace_id, email)`.** Complementary and still worth building; it stops the
  duplicate *row* and this stops the duplicate *mail*. §8.6 says how they interact and which one has
  to land first if both ship in 0.18.0.
- **A per-workspace cap on outstanding invites.** In scope for the *ticket*, and this spec
  **recommends against building it** (§6.4). It is the one control here that is theatre against the
  named attack, and its refusal would prescribe an action its reader cannot perform.
- **A pending-invitations list / revoke endpoint.** There is none today — `WorkspacePeoplePage`
  renders a dashed "coming soon" stub because no endpoint lists a workspace's pending invitations.
  Its absence is an *argument* in §6.4, not a thing this ticket fixes.
- **Bounding the number of workspaces one user may create.** A real second lever on the same attack
  and a different ticket; note it exists so nobody reads §6 as claiming the attack is *expensive*, only
  that it is *bounded*.
- **Making signup cost anything** (proof-of-work, domain allow-lists, mailbox-provider filtering).
  The deep fix for "an authenticated stranger" is to make the account cost something. Out of scope
  and named in §14.
- **Moving `MailService.send*` out of the calling transaction.** Mail is dispatched `@Async` from
  inside `@Transactional inviteMember`, so a rolled-back invite can still have sent its mail. This is
  pre-existing, it makes the throttle undercount by at most the number of rolled-back invites, and it
  is a follow-up (§8.7).
- **A shared (Redis) store for any limiter.** Every existing budget is per-node; §7.3 says exactly
  what degrades on N replicas and what does not.

---

## 3. Actors & permissions

| Actor | Can they trigger it | Tenant scoping |
|---|---|---|
| Workspace member holding `workspace.member.manage` | Yes — the only caller of `POST /api/workspaces/{id}/invites` | Resolved by `workspaceAccess.requireMember`; the grant ceiling and the OWNER-not-grantable rule are unchanged |
| Workspace member without that permission | No — 403 `MissingPermissionException`, unchanged and **checked before any ceiling** | — |
| Non-member / unknown workspace | No — **404**, unchanged and **checked before any ceiling** (§4.2 — this is why these cannot be interceptors) | — |
| System `ADMIN` | No special path. The ceilings are not admin-bypassable, and there is deliberately no per-workspace exemption (§14 Q3) | — |
| Operator | Sets the numbers; cannot exempt an individual | Instance-wide |

Nothing in this ticket adds a permission, a role, or an admin surface. The 29-constant
`common.security.Permission` enum is untouched.

---

## 4. Behaviour — the happy path and where the check goes

### 4.1 Happy path

`POST /api/workspaces/{id}/invites` behaves exactly as today: `201` with
`{"message":"Invite sent to …"}`, one `workspace_invites` row, one async invite email. One new row is
written to `invite_send_events` in the same transaction, and `hamstrack.invites.sent` is incremented
as it already is.

### 4.2 Where the check goes, and why it is not an interceptor

The order inside `inviteMember` becomes:

1. `workspaceAccess.requireMember` → **404** for a non-member or unknown workspace.
2. `memberService.requireMemberAdmin` → **403**.
3. `roleCatalog.requireAssignable` → **422**; OWNER guard → 422; grant ceiling → 403.
4. "already a member" → **409**.
5. **`inviteThrottle.require(actor, email)` → 429** ← new, here and nowhere earlier.
6. Write `WorkspaceInvite`, write `InviteSendEvent`, `metrics.inviteSent()`, send mail.

**These ceilings cannot be a `PrincipalThrottleInterceptor`, and the reason is not only that the
recipient address is in the request body** (an interceptor has no argument resolvers — its own
javadoc says so). The load-bearing reason is tenancy. `PerPrincipalMinuteBudget` is spent *before*
the controller resolves anything, and its javadoc argues that this is safe precisely because the key
is the caller: "the 429 is identical for a real workspace, a nonexistent one and somebody else's". A
**recipient-keyed** refusal does not have that property. Spent in an interceptor it would answer, to
a caller who is not a member of the workspace in the path, a question about invitation traffic
elsewhere in the instance — a `429` where the tenancy contract requires `404`. So the check runs
after step 1, inside the service, and the structural guarantee that a future mail path does not
forget it is provided by a different seal on a different axis (§9.3) rather than by pretending this
is a path binding.

This is a deliberate, documented departure from `ThrottleCoverageTest`'s failure message ("do not add
a check inside the service, which is the line the next endpoint forgets"). That message is right
about path-shaped, principal-keyed budgets and wrong about victim-keyed ones; §9.3 says what changes
in the test so the sentence does not become a lie that the next reader has to re-derive.

### 4.3 Counting semantics

- An event row is written **per attempt that reaches step 6**, not per delivered message. Delivery is
  async and best-effort (invite mail does not retry or dead-letter, by design in
  `MailService.isCritical`); an abuse control that only counted successes would be defeated by
  addresses that bounce, which is exactly what a spam run consists of.
- Refusals at steps 1–4 cost nothing. A caller cannot exhaust their own budget by probing invalid
  roles, and — more importantly — cannot exhaust *a victim's* recipient cap with requests that never
  send mail.
- The **invitation** is identified by the address folded with `Locale.ROOT` **once, at the boundary**
  (`inviteMember` already does this) and compared with `=` thereafter — the HD-120 rule, in force
  because on the *accept* path an extra match lets the wrong person redeem somebody else's
  invitation.
- The **ceilings** need the opposite and get it from a separate value. They count
  `MailAddresses.throttleKey(...)`, stored as `mail_send_events.recipient_key`: one key per
  destination *inbox*, with `+tag` stripped, quoted local parts unquoted, the domain normalised to
  its ASCII/punycode form, and Gmail's dots and `googlemail.com` alias folded.
  Keyed on the lower-cased address alone every ceiling here is decorative — `victim+1@`,
  `victim+2@`, `"victim"@` and `v.i.c.t.i.m@googlemail.com` are distinct strings that reach one
  human, so §5's shape B is re-spelled at the cost of one keystroke and both counts read zero. **For
  a ceiling, over-folding is the fail-safe direction** (an extra match raises a count and refuses
  sooner) and under-folding is the hole. That is the reverse of the redemption path, and the
  direction of harm is what decides — neither argument may be transplanted onto the other. Mail is
  sent to the submitted address, never to the key.
- **Over-folding is fail-safe but not free, and its cost is not the same for both ceilings.** For the
  per-(sender, recipient) cooldown an over-fold costs an honest sender a wait they did not earn —
  their own inconvenience, and it expires. For the **global** daily cap it spends a slot belonging to
  *a different, innocent person* who merely shares a folded key: that ceiling is sender-invariant, so
  the over-fold denies somebody else an invitation for a reason nobody involved can see. That is the
  bound, and it is why every folding rule is a published, provider-documented fact about delivery or
  a standard normalisation — never a heuristic, and never a configurable delimiter.

---

## 5. The attack, priced

The attacker is an authenticated, verified account. Four shapes, and what stops each:

| # | Shape | Cost to attacker | Stopped by |
|---|---|---|---|
| A | One workspace, 3000 distinct victims | one mailbox | **Per-sender daily budget** (§6.1). Per-workspace cap would also stop it, redundantly. |
| B | **One victim, many workspaces the abuser creates** — the ticket's shape | one mailbox; workspace creation is free and unbounded | **Per-recipient-per-sender cooldown** (§6.2). Per-workspace cap: **nothing** (each workspace holds one invite). HD-133 uniqueness: **nothing** (different workspaces). |
| C | One victim, many *accounts* | one mailbox **per account** — the real friction, and the only friction | **Global per-recipient daily cap** (§6.3). Everything sender-keyed multiplies by the number of accounts. |
| D | Many victims, many accounts, each account staying under every ceiling | N mailboxes | **Nothing here.** Bounded at N × the daily budget. This is the residual, it is real, and §10.2's volume alert is the control — not a ceiling. |

Shape D is why §10 insists the most valuable alert watches mail volume rather than throttle hits: an
attacker who reads this document will stay under the ceilings, trip no `RateLimitedException`, and be
invisible to any rule built on `hamstrack_ratelimit_hit_total`.

---

## 6. The ceilings — which one closes the attack, and which is theatre

### 6.1 A. Per-sender volume budget — the quota control

`app.invites.max-per-sender-per-hour` (default **20**) and `app.invites.max-per-sender-per-day`
(default **100**), keyed on the principal's user id, fixed windows.

Two windows because the two losses in the ticket have two units. The hourly window bounds a burst;
the daily window is the one that protects a monthly quota, and it is the window a per-minute limiter
cannot express (Correction 2). 100/day against a 3000/month quota means a single abusive account
consumes at most ~1/30 of the month per day — a condition an operator has a month to notice, rather
than an afternoon.

**20/hour and 100/day are sized to be invisible to honest use and to be the first thing an operator
raises.** The largest real invite burst the product supports is a single-address form typed by one
admin; 100 addresses in a day through that form is already an unusual day. A DC admin onboarding 300
people on install day *will* hit this, and the answer is one env var raised for the day — which is
why the numbers are properties with a documented range and not constants (§11), and why the refusal
does not pretend the ceiling is a law of nature.

In-memory, per node, swept like every other budget. It is a bound on abuse, not an invariant.
Concurrent requests can overshoot it slightly and that is accepted, exactly as `PerPrincipalMinuteBudget`
accepts it today.

**Verdict: build. Necessary, and the only control that addresses the quota loss.**

### 6.2 B. Per-recipient-per-sender cooldown — the control that closes the ticket

`app.invites.recipient-cooldown-minutes` (default **60**): one principal may not invite the same
address twice within the window, **across every workspace in the instance**.

This is the only one of the four that answers the attack the ticket describes. Shape B in §5 is one
abuser pressing "invite" at one victim from a succession of workspaces; a per-workspace bound
inspects the wrong dimension, and HD-133's uniqueness constraint inspects the same wrong dimension
with a `UNIQUE`. Only a key that ignores the workspace sees the pattern.

**Its state must be persisted, and the reason is not durability — it is that the state is currently
resettable by both parties.** If the cooldown were derived from `workspace_invites`, three existing
code paths would clear it:

- `declineInvite` **deletes** the row (`WorkspaceService.java:260`). So a victim who declines an
  unwanted invitation would *unlock the attacker's next send* — the product would punish the exact
  action it asks the victim to take.
- `WorkspaceInviteRepository.deleteUnacceptedByWorkspaceAndEmail` deletes on member removal (HD-132),
  reachable by the attacker in their own workspace.
- `workspace_invites.workspace_id` is `ON DELETE CASCADE`. There is no `DELETE /api/workspaces/{id}`
  today, so this is not currently reachable — but a throttle whose correctness depends on the
  continued absence of an endpoint is a throttle that breaks in a future ticket, silently.

Hence a separate append-only table (§7). This is the significant, hard-to-reverse decision in this
spec and it carries an ADR.

Note what the key deliberately is **not**: it is not `(recipient)` alone. Keying it globally on the
recipient would make an ordinary refusal disclose, to an admin in workspace A, that somebody
elsewhere invited this address recently — a small but real cross-tenant disclosure, paid on every
honest re-type. Keyed on `(sender, recipient)` the refusal only ever tells the caller about the
caller's own past action, and it still closes shape B completely, because shape B is one sender. The
global recipient key is used for a *rarer and much higher* ceiling instead (§6.3), where the same
disclosure is paid ~never.

**Verdict: build. This is the ticket.**

### 6.3 C. Global per-recipient daily cap — defence in depth against multi-account harassment

`app.invites.max-per-recipient-per-day` (default **5**): one address receives at most five
invitations a day from senders other than you, and you may send at most five to it yourself.

Everything sender-keyed multiplies by the number of accounts an attacker holds, and an account costs
one mailbox on a catch-all domain. Shape C in §5 is five throwaway accounts inviting one victim once
each per cooldown window — under every sender-keyed ceiling, and still a doorbell. A recipient-keyed
ceiling is invariant under attacker identity, which is the property that makes it worth its cost.

**What it counts, and why not raw sends.** Your own sends count one each; every *other* sender counts
once, however many times they sent. Counting raw sends — the obvious reading, and what shipped first
— turned the control into a weapon: one account, five legal sends spaced past the cooldown
(t=0, 61m, 122m, 183m, 244m), and for the rest of the rolling day **no workspace on the instance can
invite that person**. The innocent admin who tries gets §8.1's deliberately terse refusal, which
gives them nothing to act on and no way to know somebody is doing it on purpose; neither §10 rule
sees five sends. That is a denial of onboarding aimed at a named human, for ~5 requests a day,
indefinitely — and this spec priced the *accidental* false positive on a third party below while
never pricing the deliberate one. Counting only *distinct* senders overcorrects the other way: one
account would then occupy one slot and be free to ring the doorbell all day, bounded by nothing but
its own cooldown (24/day at the default). Counting both halves keeps this section's arithmetic
exactly where it was — shape C still trips at the sixth account, and the first five still cost
nothing — while capping any one account at five sends into one inbox *and* at one of the five slots
as far as everybody else is concerned. The price of denying somebody their invitations goes back to
one fresh mailbox per slot, which is where this section always wanted it.

**What that hybrid costs, priced rather than asserted.** The escape hatch it leaves is superlinear in
the cap. N colluding accounts each send until `own + (N-1)` reaches the cap, so one inbox receives
`N × (cap - N + 1)` messages a day, maximised at `N = (cap+1)/2` and worth `floor((cap+1)² / 4)`:

| cap | worst-case messages into one inbox per day |
|---|---|
| 5 (default) | **9** (raw counting gave a hard 5) |
| 10 | **30** |

1.8× at the default is a good trade for deleting the denial-of-onboarding weapon above. But the bound
is **quadratic**, so doubling the cap triples the harassment ceiling — anyone raising
`INVITE_MAX_PER_RECIPIENT_PER_DAY` is buying more than the number suggests, and the arithmetic is
repeated on the property itself so they see it while turning the knob.

**Per kind of mail, not per address.** Counts are taken within one `email_type`, so after HD-202 one
address can receive this cap in invitations *plus* the reset cap *plus* the verification cap in a
day. Deliberate: a shared bucket would let a stranger's invitations suppress the victim's own
password reset, which is the one message they actually asked for.

Five is chosen so it fires essentially never for a real person. A human genuinely invited to five
distinct Hamstrack workspaces within one day does not meaningfully exist; if they did, the refusal
(§8.1) tells them to try tomorrow and nothing is lost but an hour.

**The accepted trade-off, stated plainly:** at the sixth invitation, an innocent admin learns that
this address has received invitations from workspaces they cannot see. That is a cross-tenant
disclosure. It is bounded to one bit, it is only reachable by a caller who already holds
`workspace.member.manage` and already knows the address, it costs nothing on the first five, and it
buys a harassment control that cannot be bought any other way. The refusal's wording is chosen to
minimise it: it says *"Invitations to this address are paused. Try again in X."* and deliberately
does **not** say "…because other workspaces invited them", "…they already have an invitation", or
anything else that turns one bit into two. §8.1.

**Verdict: build.**

### 6.4 D. Per-workspace cap on outstanding invites — recommend NOT building

The ticket asks for one. I recommend against it, and this is the section the owner should overrule if
they disagree, because it is the one place this spec narrows the ticket's scope.

Three reasons, in increasing order of weight:

1. **It does nothing against the named attack.** Shape B puts one invitation in each of many
   workspaces. A cap of 200 per workspace is not approached.
2. **It is redundant against the attack it *does* address.** Shape A (one workspace, thousands of
   victims) is already bounded at 100/day by §6.1, far below any per-workspace cap anyone would
   choose. The cap would be a second, looser bound on a dimension already bounded — and it would bind
   first on the only population that legitimately generates volume, a genuinely large customer.
3. **Its refusal would prescribe an action its reader cannot perform.** A `409 "too many pending
   invitations — revoke some"` is unactionable: there is **no endpoint that lists a workspace's
   pending invitations and none that revokes one**. `WorkspacePeoplePage` renders a dashed
   "coming soon" box saying exactly that. This project has shipped an unperformable refusal three
   times (CLAUDE.md, HD-123); shipping a fourth, knowingly, in the ticket that is a launch gate,
   would be the worst version of the mistake. If the cap is wanted, the list-and-revoke endpoints are
   its prerequisite, not its follow-up.

**If it is built anyway**, the design is: `app.invites.max-outstanding-per-workspace` (500), counting
`workspace_invites` rows with `accepted_at IS NULL AND expires_at > now()`, refused with **409**, not
429 — it is a stock cap and a business rule, the shape of `app.roles.max-custom-per-workspace`
(409 `ROLE_LIMIT_REACHED`), not a rate limit. Consequently it must **not** sit under
`app.rate-limit.enabled`: that switch turns off brute-force protection, and an operator turning off
brute-force protection has not asked to remove an unrelated stock cap. This contradicts the ticket's
"under the same master switch" sentence, deliberately.

**Verdict: do not build in HD-190. File as a follow-up behind pending-invite list + revoke.**

### 6.5 Nothing here is IP-keyed — and the HD-199 interaction

No ceiling in this design uses the client IP. An IP key is defeated by a phone tether, and the
principal is both cheaper to key on and more meaningful: the mail is sent on behalf of an account,
not an address.

**HD-199's direction of effect.** It flips `RATE_LIMIT_TRUST_FORWARDED_FOR` to `true` in production,
which changes the auth filter's key from *one constant* (Caddy's address, one shared budget for the
whole internet) to *per client*. It touches nothing in this spec directly, and its indirect effect
runs in **both** directions and is worth stating honestly:

- **Against us:** the prerequisite for every attack here is registering accounts, and registration is
  on the per-IP auth budget. While that budget was one shared bucket, an attacker registering
  accounts was contending with every legitimate visitor; afterwards they get their own 15/minute and
  no longer contend. So HD-199 marginally *raises* an attacker's sustainable registration rate.
- **For us:** before HD-199 the per-IP budget was simultaneously trivially exhausted by innocent
  traffic and useless for attribution — sixteen login attempts a minute locked out the world. It was
  not a control an attacker had to work around, because it was not a control.

Net: HD-199 makes the per-IP layer real without making it a barrier for this attacker, which is
another reason the ceilings here are principal- and recipient-keyed. Nothing in this spec should be
re-keyed on IP after HD-199 merges.

### 6.6 HD-202 is the same mechanism, and HD-190 should own it

HD-202 adds a per-address throttle to forgot-password and resend-verification, keyed on the lowercased
submitted email, IP-independent. That is character-for-character the key in §6.2/§6.3. Invite mail,
reset mail and verification mail are one problem — *an authenticated or anonymous caller chooses a
stranger's address and we send to it* — and they should be one mechanism.

**Recommendation: HD-190 builds the mechanism; HD-202 is reduced to wiring.** HD-190 has the strictest
requirements (durable state, cross-workspace key, a survivor of row deletion), so the mechanism
designed for it is a superset of what HD-202 needs. After this ticket, HD-202 is:

- two extra `EmailType` rows in the throttle's configuration map,
- one `recipientThrottle.require(...)` call in `AuthService.forgotPassword` and one in
  `AuthService.resendVerification`,
- two properties, and moving `VERIFICATION`/`PASSWORD_RESET` out of `MailThrottleCoverageTest.EXEMPT`
  (§9.3) — which is what makes the merge *verifiable* rather than intended.

Two things make the merge safe rather than convenient:

- **Enumeration.** `forgotPassword` and `resendVerification` are uniform-response by design
  (`AuthService.java:168-188`: "Silently no-op for unknown or already-verified emails — no
  enumeration"). A throttle keyed on the **submitted** address, counted whether or not an account
  exists, preserves that exactly: an unknown address and a known one hit the same ceiling after the
  same number of submissions. This is not a new argument — it is the one already written on
  `RateLimitService.checkLoginAllowed`, whose javadoc says the key is "the submitted email whether or
  not the account exists, so the limiter itself cannot be used to probe which emails are registered."
  A `429` there discloses only "this address was submitted recently", which the submitter knows
  because they submitted it.
- **Separate budgets, one table.** The counts are per `EmailType`; a reset flood must not consume an
  invite allowance or vice versa. One table, one query shape, N configured ceilings.

If the owner prefers to keep HD-202 separate, the only thing this ticket must still do is build the
mechanism *generically* (an `EmailType` column on the events table, not an invite-only table) so
HD-202 does not have to reopen the migration. That is already the design in §7.

---

## 7. Data model impact

### 7.1 New table — `invite_send_events`

One new Flyway migration. **Next free version is `V21`** (`V1`–`V20` exist); if HD-133 lands in the
same release it takes the other number and neither file is renumbered. HD-188 (Flyway squash) must
fold this table into the squashed baseline — it is new state, not a derived index.

```
V21__mail_send_events.sql

CREATE TABLE mail_send_events (
    id              UUID         PRIMARY KEY,        -- app-generated UUID v7
    email_type      VARCHAR(40)  NOT NULL,           -- ProductMetrics.EmailType name, validated app-side
    recipient_email VARCHAR(320) NOT NULL,           -- exactly as submitted, lower-cased; echoed by refusals
    recipient_key   VARCHAR(320) NOT NULL,           -- MailAddresses.throttleKey(); the ONLY thing counted
    sender_user_id  UUID,                            -- NULL for anonymous senders (HD-202's two flows)
    workspace_id    UUID,                            -- forensic breadcrumb only; see below
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mail_send_events_recipient ON mail_send_events (recipient_key, created_at DESC);
CREATE INDEX idx_mail_send_events_sender    ON mail_send_events (sender_user_id, created_at DESC);
CREATE INDEX idx_mail_send_events_created   ON mail_send_events (created_at);
```

Named `mail_send_events`, not `invite_send_events`, because §6.6 makes it the home of three mail
types and renaming a table later is a migration nobody will want to write.

**Deliberate properties, each with its reason:**

- **No foreign keys at all.** Not on `sender_user_id`, not on `workspace_id`. The whole point of the
  table is to hold state that *outlives the rows it describes* — §6.2 lists three existing paths that
  delete a `workspace_invites` row and one hypothetical that would cascade the workspace. An FK with
  `ON DELETE CASCADE` re-creates exactly the hole the table exists to close; an FK with `RESTRICT`
  would make deleting a workspace or a user fail on throttle bookkeeping, which is worse. Precedent:
  `failed_email` (V7) is the same shape — an install-level operational log with a recipient address
  and no FKs.
- **Not workspace-scoped, and never read through a tenant-facing surface.** This is the shape that
  causes this project's top bug class, so the invariant is stated as a rule and tested (§9.2): the
  table has **no repository method that returns rows**, only aggregate counts consumed by the
  throttle; no entity is exposed in a DTO; no endpoint reads it. `workspace_id` is written and never
  queried — it exists so an operator answering "who did this?" after §10's alert has the answer in
  the database. If the builder finds themselves adding a `findBy…` that returns entities, that is the
  bug.
- **`VARCHAR(320)`** on the recipient to match `failed_email.recipient` and to accommodate HD-202's
  submitted-address key (which is *not* bounded by `workspace_invites.email`'s 255 — an arbitrary
  string can be submitted to forgot-password). The invite path's own 255 bound
  (`InviteMemberRequest`, `EmailLengthBoundTest`) is unchanged and still applies before this row is
  written.
- **`VARCHAR`, never `CHAR(n)` or a PG ENUM**; UUID v7 generated by the application;
  `created_at` with `DEFAULT NOW()` as the raw-SQL safety net while JPA sets it via `@CreatedDate`.
  All four are the standing rules.

### 7.2 Entity

`common.mail.MailSendEvent extends CreatedOnlyEntity` (`id` + `createdAt` only — the row is never
updated). Every column `@Column(updatable = false)`. No associations; `senderUserId` and `workspaceId`
are plain `UUID` fields, not `@ManyToOne` — an association would reintroduce the FK semantics the
schema deliberately refuses, and would tempt a `JOIN FETCH` that turns an aggregate count into a row
read.

### 7.3 What this costs, and what degrades on N replicas

Per invitation, **one additional `SELECT` and one additional `INSERT`**. The `SELECT` is a single
statement with conditional aggregation over the two indexed keys:

```sql
SELECT count(*) FILTER (WHERE sender_user_id = :actor AND created_at > :hourAgo)                          AS sender_hour,
       count(*) FILTER (WHERE sender_user_id = :actor AND created_at > :dayAgo)                           AS sender_day,
       count(*) FILTER (WHERE recipient_email = :email AND sender_user_id = :actor AND created_at > :cd)  AS same_pair,
       count(*) FILTER (WHERE recipient_email = :email AND created_at > :dayAgo)                          AS recipient_day
  FROM mail_send_events
 WHERE email_type = :type AND created_at > :dayAgo
   AND (sender_user_id = :actor OR recipient_email = :email)
```

A bitmap-OR of two index scans over at most a day of rows. Invitation is a low-frequency write; this
is not a surface that needs a cache.

**Concurrency.** The counts are read then written, so two simultaneous requests can both pass.
Resolution, differentiated by what each ceiling is for:

- **Exact for the recipient-keyed ceilings (B and C).** Before the `SELECT`, take
  `pg_advisory_xact_lock(hashtext(:recipient_email))`, preceded by
  `LockTimeout.applyToCurrentTransaction()` — bound, then lock, the standing rule. This serialises
  everything aimed at one address, which is where exactness matters, and contends with nothing else.
  `hashtext` is 32-bit, so two unrelated addresses will occasionally serialise; that is a lock, not a
  decision, and it is harmless.
- **Advisory for the sender-keyed budget (A).** A burst can overshoot by a few. This is the failure
  mode every existing budget in the app already accepts (`PerPrincipalMinuteBudget`: "a bound on
  abuse, not an invariant"), and buying exactness would mean a second lock on the sender for no
  change in outcome.

**On N replicas:** the persisted ceilings (B, C) are **cluster-wide and exact**, because the state is
in Postgres — the first thing in this application that does not degrade on scale-out. The in-memory
sender budget (A) degrades the usual way, N × the ceiling. Production is one box today; this sentence
exists so the next person does not discover the asymmetry, and so `docs/self-hosting.md`'s
"Nth mechanism is node-local" prose can be written correctly the first time (§11).

### 7.4 Retention

`app.invites.event-retention-days` (default **7**, minimum **2**), swept by a `@Scheduled` delete on
`created_at < now() - retention`. Strictly longer than the longest ceiling window — which is the
**larger** of the configurable cooldown and the fixed 24 h daily-cap window, asserted at startup, and
which is why the minimum is 2 days rather than 1. Longer still on purpose: after §10's
alert fires, this table is the only place that can answer *who* and *which addresses*, and the
metrics deliberately cannot (bounded cardinality is non-negotiable). Seven days of low-volume rows is
nothing; it is also short enough that the table is not a durable store of who-emailed-whom.

---

## 8. Edge cases & failure modes

### 8.1 The refusals — what each one says, and who can act on it

The standing rule is that a refusal may only prescribe an action its reader can perform. Each ceiling
has a different reader.

| Ceiling | Status | `detail` | Why this is performable |
|---|---|---|---|
| A — sender hourly/daily | `429` + `Retry-After` | **"Invitation limit reached — you can send up to N invitations an hour and M a day. Try again in X. Invitations you have already sent are unaffected."** | The reader is an admin mid-onboarding. The only thing they can do is wait and continue, so the message says exactly that and names the wait. It does **not** say "ask an administrator to raise the limit": on Cloud the reader has no administrator to ask, and on DC the reader may not be the operator. The second sentence is the one that matters emotionally — it stops them re-sending the invitations that already went out. |
| B — same address, same sender | `429` + `Retry-After` | **"You already invited name@company.com recently. That invitation is still valid for 7 days — ask them to check their inbox, including spam. You can send another in X minutes."** | Fully performable and actually useful: the reader knows the person and can tell them to look. It names the address (the caller's own past action) and never the workspace — the earlier invitation may have come from one the caller can no longer see. |
| C — global per recipient | `429` + `Retry-After` | **"Invitations to this address are paused. Try again in X."** | Terse on purpose. "Wait" is performable, and every richer remedy ("ask them to accept one they already have") converts the one bit of cross-tenant disclosure §6.3 accepts into a description of another tenant's activity. Where the prescription rule and the disclosure rule pull against each other, disclosure wins — and here they barely pull, because waiting *is* the remedy. |

All three are `RateLimitedException(message, retryAfterSeconds)` — the existing constructor built for
exactly this ("used where the generic wording would leave the user guessing what is throttled"), so
the 429 body and `Retry-After` header shape are unchanged and the SPA's existing
`ApiResponseError.retryAfter` already carries it.

`Retry-After` for the daily ceilings is seconds until the window rolls, which can be hours. That is
honest and the SPA renders it as a duration, not as a countdown timer.

For ceiling **C** the value is derived from *another tenant's* send instant, so it is coarsened
before it leaves the process — and **what is rounded is the deadline, not the remaining duration**.
Rounding the duration (`ceil((deadline - now) / 900) * 900`) anchors the quantum to `now`, so the
emitted number steps down by 900 as time passes and the instant of the step *is* the hidden deadline:
about ten probes by bisection, or the minimum over a day of probes, recovers it to within seconds.
Rounding the deadline instead makes `now + Retry-After` identical at every probe. A visible
consequence, stated because it looks like a regression: the emitted number then decreases one per
second and is *not* a multiple of 900 — a value that is a multiple of 900 is the fingerprint of the
defeatable variant, so any test asserting `retryAfter % 900 == 0` would seal the defect rather than
the fix. Ceiling **B**'s wait is left exact: that instant is the caller's own.

This raises the price of the disclosure; it does not bound it to one bit. A caller who keeps probing
until C's refusal *lifts* still learns the deadline at their polling resolution, and no shape of
`Retry-After` closes that against a retryable sliding window. The one-bit claim belongs to the
refusal's **wording** (§8.1 row C).

### 8.2 Enumeration

The constraint is: nothing new may let a caller learn whether an address has an account.

- The ceilings are keyed on the **submitted, folded address**, counted identically whether or not a
  `users` row exists. No branch in the throttle reads `users`.
- Ordering: the throttle runs **after** the "already a member" check (step 4/5 in §4.2), so a member
  and a stranger who is throttled produce different statuses — but "is this address a member of *this*
  workspace" is already knowable to this caller from the member list they can read. No new bit.
- Timing: the throttle's work is one indexed `SELECT` whose plan does not depend on whether the
  address exists anywhere. There is no early return keyed on account existence.
- Metrics: `hamstrack.ratelimit.hit{kind}` carries no address and no id, per the standing cardinality
  rule. The counter suggested during design — labelling sends by whether the recipient already had an
  account — is **rejected**: it is a poor discriminator for the alert (real onboarding invites mostly
  new addresses too) and it puts an existence signal into a store for no benefit.

### 8.3 Last-of-kind, empty, archived, soft-deleted

None apply — this ticket adds no user-visible entity and removes nothing. Two adjacent cases do:

- **A victim declining an invitation** deletes the `workspace_invites` row and leaves the
  `mail_send_events` row. The cooldown is unaffected. §6.2 — this is the case the separate table
  exists for.
- **A member being removed** deletes their unaccepted invites (HD-132) and leaves the events. A
  re-invite after removal is therefore subject to the cooldown; if that is a real workflow (remove,
  realise the mistake, re-invite) the cooldown will bite, and the refusal in §8.1 row B tells them
  the earlier invitation is still valid — which after `deleteUnacceptedByWorkspaceAndEmail` is **no
  longer true**. Fix in the builder: when the cooldown refuses, the message's middle sentence is
  emitted only if an unaccepted, unexpired `workspace_invites` row for that pair still exists; a
  claim about a row is checked against the row. This is the one place in this spec where a refusal
  can go stale, and it is a `count(*)` to avoid it.

### 8.4 Idempotency, double-submit, races

- A double-click today writes two rows and sends two emails. After this ticket the second is refused
  by the cooldown (`same_pair`, cooldown window ≥ 1 minute), which incidentally makes the endpoint
  near-idempotent for the case that actually happens. HD-133's `UNIQUE` makes it exactly idempotent
  for the same-workspace case; the two are complementary and neither depends on the other.
- Concurrency: §7.3.
- If the transaction rolls back after the mail was dispatched (§2.2), the event row rolls back with
  it and the throttle undercounts by one. Bounded, rare, and the alternative (write the event outside
  the transaction) trades an undercount for an overcount that would refuse an honest caller for a
  send that never happened. Undercount is the right side to err on for a control whose ceilings are
  set well above honest use.

### 8.5 The master switch

`app.rate-limit.enabled=false` turns off A, B and C — they are rate limits, and the switch is the
only way to turn any of them off, since none of the individual properties accepts an "unlimited"
value. Its scope is **every limiter that *has* an off switch**, which is not the same as "every
limiter": the backlog-rebalance cooldown in `IssueRankService` is deliberately outside it. That
phrasing matters wherever this switch is described — the wider claim is false and has had to be
corrected in five files. The switch now covers a fourth family, so the numbered list above the
property gets a fourth entry (§11). The retention sweep keeps running when limiting is off; rows are cheap and the
alternative is an unbounded table on any instance that toggles the switch.

Per §6.4, if the per-workspace stock cap is ever built it does **not** join this switch.

### 8.6 Interaction with HD-133

Complementary but **not order-free** — HD-133 constrains a table, this constrains a mailer, and
which of them lands first changes what the second one has to do.

If HD-133 lands first, the duplicate case becomes a `409` before the cooldown is consulted, and the
cooldown's remaining job is cross-workspace, which is where its value was anyway. Nothing extra is
required.

**This landed first, so HD-133 now carries a constraint it did not have when this section was
written.** The throttle records its `mail_send_events` row inside `inviteMember`'s transaction,
*before* the `workspace_invites` insert. A `UNIQUE(workspace_id, email)` violation on that insert
rolls the transaction back and unwrites the recorded event — so a caller could observe the ceilings'
refusals while never spending them, by aiming repeatedly at an address they have already invited.
**The duplicate check must therefore run ABOVE `inviteThrottle.require`, not below it, and must be an
explicit pre-check rather than a caught constraint violation.** The same applies to any other
refusal HD-133 adds on this path. The note lives in three places on purpose: the comment block at
the call site in `WorkspaceService.inviteMember` (which HD-133 must edit anyway), the header of
`V21__mail_send_events.sql`, and here.

Neither ticket should be delayed for the other; both should cite the other so a reader of either
knows it is not the whole answer.

### 8.7 Follow-ups this ticket deliberately does not do

- Dispatch invite mail on `afterCommit` rather than inside the transaction (§2.2).
- Pending-invite list + revoke endpoints, which unblock §6.4.
- A cap on workspaces per user.

---

## 9. API surface & tests

### 9.1 API

**No new endpoints, no changed request or response shapes.** One endpoint gains a status code:

```
POST /api/workspaces/{workspaceId}/invites
  201 — unchanged: {"message": "Invite sent to <email>"}
  400 — validation (unchanged)
  403 — missing workspace.member.manage, or outside the grant ceiling (unchanged)
  404 — workspace not visible: non-member and non-existent alike (unchanged)
  409 — the address already belongs to a member of this workspace (unchanged)
  422 — unknown/unassignable role; OWNER is not grantable via invite (unchanged)
  429 — NEW. Retry-After: <seconds>. problem+json `detail` per §8.1.
        Spent AFTER the workspace is resolved, so unlike the search and report
        budgets a 429 here is only ever seen by a proven member.
```

That last line is a real difference from every other 429 in this API and belongs verbatim in the
docs: for reports and search, "429 precedes 404, so a 429 says nothing about whether the caller can
see the resource". Here the opposite holds. `api-docs-sync` must not copy the existing sentence.

Wiring targets: `src/main/frontend/public/openapi.yaml` (the `429` response on this operation),
`docs/api-cloud.md` and `docs/api-dc.md` (env-var table, the "surfaces with throttles of their own"
section, this endpoint's 429 note).

### 9.2 Tenancy tests

- `POST …/invites` on a workspace the caller is not a member of returns **404**, not 429, **even when
  the caller is over every ceiling** — the check order in §4.2 asserted directly.
- The same, for a workspace id that does not exist.
- A repository test asserting `MailSendEventRepository` exposes **no method returning entities or
  projections of `recipient_email`** — the aggregate-count-only invariant of §7.1, checked by
  reflection over the interface so a future `findByRecipientEmail` fails at the moment it is added.

### 9.3 The throttle seals — what happens to `ThrottleCoverageTest`

`ThrottleCoverageTest.theThrottledPathSetIsSealed()` asserts the **path** set is exactly four
patterns and carries the propagation checklist in its failure message. This ticket registers **no new
path pattern**, so that test stays green and is not weakened — correctly, because the invite ceilings
are not a path binding and could not be (§4.2).

But "the throttled path set is sealed" is a claim about one axis, and a reader who trips it must not
conclude it is the only axis. Two changes:

1. **`ThrottleCoverageTest` gains one paragraph** in `PROPAGATION_CHECKLIST` naming the sibling axis
   and why the invite throttle is not on this one — that a recipient-keyed refusal cannot be spent in
   an interceptor without answering a cross-tenant question, and that the seal for it lives in
   `MailThrottleCoverageTest`. A pointer, not a new assertion; the detection lives in the new test.
   This is the "deliberate edit, never an omission" the standing rule requires.
2. **New `MailThrottleCoverageTest`, sealing the mail axis.** The right generalisation of *"a throttle
   is earned by the work a handler does"* is that the work here is *sending mail to an address the
   caller chose*, so the seal is on the mailer:
   - every public `send*` method on `MailService` maps to a `ProductMetrics.EmailType` constant
     (reflection over `MailService`, so a fourth mailer method with no type fails);
   - every `EmailType` constant is either **recipient-throttled** or in an `EXEMPT` set with a
     written reason (the `ThrottleCoverageTest.EXEMPT` pattern, including its "anything added here
     needs a reason that survives the same question" framing);
   - after HD-190, `EXEMPT = { VERIFICATION, PASSWORD_RESET }` with the reason *"throttled only by the
     per-IP auth budget today; HD-202 moves them onto this mechanism"* — so the test **documents the
     open gap** instead of implying it is closed, and HD-202's completion is literally the deletion of
     those two entries.

   A fourth kind of outbound mail then fails one test that names what to do, at the commit that adds
   it. That is the same mechanism `ThrottleCoverageTest` is, on the axis this ticket actually moves.

### 9.4 Behavioural tests (the ticket's acceptance criteria, made checkable)

Listed in §15.

---

## 10. Observability

### 10.1 Metrics

Three new `ProductMetrics.RateLimitKind` constants — separate, because they mean different things to
whoever reads the alert:

| Constant | Tag | Signal |
|---|---|---|
| `INVITE_SENDER_VOLUME` | `invite_sender_volume` | somebody is sending a lot — bulk / quota shape |
| `INVITE_RECIPIENT_COOLDOWN` | `invite_recipient_cooldown` | somebody is pressing invite at one address — harassment shape |
| `INVITE_RECIPIENT_DAILY` | `invite_recipient_daily` | one address is being hit from several accounts — coordinated harassment, the sharpest of the three |

Emitted through the existing `metrics.rateLimitHit(kind)`; no new meter name, no new labels, and — per
the standing rule and `PerPrincipalMinuteBudget`'s reasoning — **the refusal is a metric, not a log
line**, so a client that keeps retrying cannot be a log-flooding vector.

`hamstrack.invites.sent` / `.accepted` and `hamstrack.email.sent{type,outcome}` already exist and need
no change; §10.2 leans on them.

**One new INFO log line, at a successful send:** actor user id, workspace id, and the recipient's
**domain only** — never the local part. It is the bridge between an alert that legally cannot carry
identifying labels and an operator who needs to know who. One abuser inviting four hundred addresses
across four hundred domains and one customer onboarding four hundred colleagues at one domain are
indistinguishable in Prometheus and trivially distinguishable in this line. Successful sends only, so
it is bounded by the ceilings this ticket introduces.

### 10.2 Alerts — and the one that catches the attacker who read this document

Three rules for `observability/grafana/provisioning/alerting/rules.yml`, in the file's existing
`refId A` (instant PromQL) → `refId B` (threshold) shape, `noDataState: OK`, `execErrState: OK`.
None is `critical`: none of these is "the product is down", and paging on abuse trains an operator to
mute. Ranked by value, which is the reverse of the order they would naturally be written in.

**Rule 1 (most valuable) — `MailDailyVolumeHigh`.** The direct measurement of the loss the ticket
describes, and the only rule that is true regardless of which ceiling was or was not tripped.

```
uid: hamstrack-mail-daily-volume-high    title: MailDailyVolumeHigh
for: 30m    severity: warning
expr: 'sum(increase(hamstrack_email_sent_total{outcome="success"}[24h]))'
threshold: gt 500
summary: "More than 500 emails sent in 24h — at a 3000/month quota that consumes the month in six days"
```

500/day is a sixth of the monthly quota. A real onboarding week can approach it, which is why it is a
warning whose annotation says to look at the INFO lines from §10.1 rather than to act.

**Rule 2 — `InviteVolumeUnaccepted`. The discriminator, and the rule that sees an abuser who stays
under every ceiling.** Real invitations get accepted; spam does not. Nothing else in the metric
surface separates the two.

```
uid: hamstrack-invite-volume-unaccepted    title: InviteVolumeUnaccepted
for: 30m    severity: warning
expr: '(sum(increase(hamstrack_invites_accepted_total[6h])) / clamp_min(sum(increase(hamstrack_invites_sent_total[6h])), 1))
       and on() (sum(increase(hamstrack_invites_sent_total[6h])) > 200)'
threshold: lt 0.1
summary: "Over 200 invitations in 6h with under 10% accepted — a spam run looks like this; a real onboarding accepts"
```

The `and on()` guard is what keeps it quiet: below 200 invitations in six hours the expression
returns no data and `noDataState: OK` holds. **The known false positive, stated so it is not
discovered:** a genuine 300-person onboarding started at 17:00 on a Friday will show a low acceptance
ratio for hours. The six-hour window and the `for: 30m` absorb the ordinary version of that; the
Friday-evening version will fire, and the annotation must say so and point at the domain-only log
line, which answers it in one query. A rule with a named, explained false positive survives; a rule
whose false positive is a surprise gets muted within a week, which is precisely the failure the
ticket warns about.

**Rule 3 (least valuable, still worth having) — `InviteThrottleTripping`.** Catches the loud abuser,
and only the loud one.

```
uid: hamstrack-invite-throttle-tripping    title: InviteThrottleTripping
for: 5m    severity: warning
expr: 'sum(increase(hamstrack_ratelimit_hit_total{kind=~"invite_.*"}[15m]))'
threshold: gt 20
summary: "Invitation ceilings refused more than 20 requests in 15m — check the kind label: recipient_* is harassment-shaped, sender_volume is bulk-shaped"
```

It is listed last on purpose. A rule built on refusals can only see attackers who *hit* the ceiling;
shape D in §5 — the attacker who reads the numbers and stays beneath them — never appears in it.
Anyone who builds only this rule has built a monitor for the incompetent.

`docs/observability.md` gains the three rules and the metric rows.

---

## 11. DC/Cloud implications & wiring

### 11.1 The defaults are identical in `dc` and `cloud`, and must never become profile-gated

The DC abuse profile genuinely differs: a self-hoster runs their own SMTP, nobody else's reputation is
at stake, and `PUBLIC_SIGNUP_ENABLED` defaults to **`false`** there — accounts are admin-created, so
"an authenticated stranger" is not a thing on a default DC install. That is a real argument for
looser DC defaults, and I recommend against it, for three reasons:

1. **The risk tracks the wrong variable.** A DC install with public signup switched on has exactly
   the Cloud abuse profile. A profile-keyed default would protect `cloud` and leave the actually
   exposed DC install — the one whose operator opened signup — on the loose numbers. If a default
   ever needs to key on something, it is `PUBLIC_SIGNUP_ENABLED`, not the profile.
2. **The production code path must be the one everyone exercises.** Divergent security defaults mean
   the configuration running in Cloud is the one least tested by self-hosters and by CI.
3. **The escape hatch already exists and is better.** A DC operator onboarding 300 people raises one
   env var for a day. That is a number, not a code path — the standing rule for
   `SearchProperties`/`RolesProperties`: *"If it were ever limited per deployment it would be by
   lowering a number, never by a second code path."*

So: one code path, one set of defaults, no `application-dc.properties` / `application-cloud.properties`
override, no `@Profile` anywhere in this feature. If the deployment ever needs a different posture, the
technique is the one `RATE_LIMIT_TRUST_FORWARDED_FOR` uses — an opinion in the deployment's compose
file with `${VAR:-default}` so `.env` can still override — and §0 Correction 3 explains why these
particular properties do not qualify.

Nothing here assumes a cloud-only facility: no Resend API, no SES, no provider-specific quota call.
The ceilings are counted against our own table and are true for a self-hoster pointing at MailHog.

### 11.2 New properties

| Property | Env var | Default | Range | Meaning |
|---|---|---|---|---|
| `app.invites.max-per-sender-per-hour` | `INVITE_MAX_PER_SENDER_PER_HOUR` | `20` | 1–1000 | Invitations one principal may send per hour |
| `app.invites.max-per-sender-per-day` | `INVITE_MAX_PER_SENDER_PER_DAY` | `100` | 1–10000 | Per day. This is the quota control |
| `app.invites.recipient-cooldown-minutes` | `INVITE_RECIPIENT_COOLDOWN_MINUTES` | `60` | 1–1440 | Same sender → same address, across all workspaces |
| `app.invites.max-per-recipient-per-day` | `INVITE_MAX_PER_RECIPIENT_PER_DAY` | `5` | 1–1000 | One address per day: own sends one each, other senders one each |
| `app.invites.event-retention-days` | `INVITE_EVENT_RETENTION_DAYS` | `7` | **2**–90 | How long `mail_send_events` rows are kept. Cross-checked at startup against the **wider of the two ceiling windows** — the configurable cooldown *and* the fixed 24 h window of `max-per-recipient-per-day` — because a swept row is a row either ceiling silently stops counting. The floor is 2 rather than 1 for that second window: one day would be *exactly* 24 h against a 24 h window, and a replica whose sweep clock runs a second ahead then deletes a row another replica's daily count still needs |

`@Validated @ConfigurationProperties(prefix = "app.invites")` on an `InviteProperties` record, `@Min`/`@Max`
per the `SearchProperties`/`RolesProperties` pattern: an out-of-range value **fails startup** rather
than being clamped behind the operator's back. **There is no "unlimited" value** — `0` is out of range
and aborts the boot; the off switch is `RATE_LIMIT_ENABLED`, and an operator who wants no practical
ceiling writes the top of the range.

### 11.3 Wiring chain (`dc-cloud-guard`'s checklist)

1. **`src/main/resources/application.properties`** — the five lines, each with its comment block, **and
   a fourth entry in the numbered list above `app.rate-limit.enabled`** ("4. the invitation ceilings
   (`app.invites.*`) — per-sender volume, per-recipient cooldown, per-recipient daily cap"). That list
   currently promises "all three families"; leaving it at three would make the master switch's own
   documentation false.
2. **`.env.prod.example`** — a new `── Invitations ──` block, in the house style: what it bounds, why
   the number is what it is, "leave the line COMMENTED OUT to keep the default — an empty value aborts
   the boot rather than restoring it".
3. **`docker-compose.prod.yml`** — **deliberately unchanged**, §0 Correction 3. `dc-cloud-guard`
   should read this line rather than flag the omission.
4. **`docs/self-hosting.md`** — rows in the operator table; the `RATE_LIMIT_ENABLED` row updated to
   name four families; and the "Nth mechanism is node-local" prose extended with the asymmetry from
   §7.3: the sender budget is node-local like the rest, and the recipient ceilings are **not** — they
   are the first cluster-wide limiter in the product, because their state is in Postgres.
5. **`docs/api-dc.md` + `docs/api-cloud.md`** — env-var table, the "surfaces with throttles of their
   own" section, and the 429 on this endpoint **with its own note** (§9.1: here 429 follows the
   tenancy check, the opposite of reports/search).
6. **`src/main/frontend/public/openapi.yaml`** — the `429` response with `Retry-After`. Not the copy
   under `src/main/resources/static`, which the Vite build overwrites.
7. **`docs/project-state.md`** — a "Config:" line in the invitations/rate-limiting section.
8. **`src/main/frontend/src/api.ts`** — the 429 comment on `apiInviteWorkspaceMember` (§12).

---

## 12. Frontend impact

Small and entirely in the existing invite affordance; no new page, no new store, no `DESIGN.md`
decision.

- **`src/main/frontend/src/pages/settings/WorkspacePeoplePage.tsx` → `InviteRow`.** It already renders
  `error` from the thrown `ApiResponseError`, so the §8.1 `detail` appears with no change. Two changes
  are needed:
  - **Do not clear the email field on a 429.** `handleSubmit` clears `email` only on success today, so
    this already holds — pin it with a note so a refactor does not lose it, because clearing the field
    on a retryable refusal makes the admin retype the address they were told to retry.
  - **Render the wait as a duration, not a raw number.** `ApiResponseError.retryAfter` carries the
    seconds; the message already names the wait in prose, so the component only needs to not
    contradict it. A live countdown is not worth building.
- **`src/main/frontend/src/api.ts`** — the standing comment convention on `apiInviteWorkspaceMember`:
  429 is a retryable throttle, not a fault; nothing was sent; `retryAfter` carries the wait; and — the
  sentence unique to this caller — it is spent **after** the workspace is resolved, so unlike the
  search and report callers a 429 here does imply the caller is a member.
- **The dashed "no endpoint lists pending invitations" stub stays.** It is out of scope and it is the
  evidence in §6.4.

Nothing here is capability-gated and nothing is config-driven rendering; a ceiling is not a
capability, and there is no affordance to hide.

---

## 13. Architectural decisions (ADR)

One, and only one. The rest of this spec is feature mechanics.

**Decision.** Recipient-keyed mail throttling state is **persisted in its own append-only table**
(`mail_send_events`), shared by every outbound mail path, rather than held in memory like the
application's five existing limiters or derived from the business row it describes.

- **Chosen:** a dedicated, FK-free, non-tenant-scoped table, written on every send, read only as
  aggregate counts, swept on a retention window.
- **Rejected — in-memory, like every other limiter.** A per-node map is divided by the replica count
  and reset by every deploy. For a *volume* budget that is acceptable and this spec keeps it (ceiling
  A). For a control against harassment it is not: a cooldown a deploy resets is a cooldown an
  attacker waits out, and the value of a victim-keyed control is precisely that it does not depend on
  the attacker's circumstances.
- **Rejected — derive it from `workspace_invites`.** Free, uses an existing index, and **resettable by
  three existing code paths**, one of them the victim's own "decline" button. A throttle the victim
  can clear by doing the thing the product asks them to do is worse than none, because it reads as
  protection.
- **Trade-off accepted:** the first DB-backed limiter in the product (one extra `SELECT` and `INSERT`
  on a low-frequency write); a non-workspace-scoped table holding recipient addresses, which is the
  shape of this project's top bug class and is therefore constrained by an invariant and a test
  (§7.1, §9.2); and a new table that HD-188's Flyway squash must fold in.

Drafted as `docs/adr/0015-recipient-keyed-mail-throttle-persisted.md`, `Status: Proposed`.

Explicitly **not** ADRs: the choice of hour/day windows over per-minute (a number and a unit),
the three-vs-four ceilings question (§6.4, a scope call), the merge with HD-202 (§6.6, ticket
routing), and the seal inversion (§9.3, a testing convention that follows from the ADR above).

---

## 14. Open questions — with the recommended default

**Q1. Build the per-workspace outstanding cap anyway?**
*Recommendation: no.* §6.4 — it is inert against the named attack, redundant against the other, and
its refusal cannot be acted on until pending-invite list + revoke exist. File it behind those.
**RESOLVED 2026-08-27 — owner confirmed: do not build it.** The narrowing stands. File the cap
together with pending-invite list + revoke, so that its refusal becomes performable before it ships.

**Q2. Does HD-190 own the shared mechanism, or does HD-202?**
*Recommendation: HD-190 owns it*, HD-202 becomes two `require(...)` calls plus config (§6.6). HD-190
is the launch gate and has the strictest requirements, so the mechanism designed for it is a superset.
If the owner would rather not grow HD-190, the minimum is that the table is built generically
(`email_type` column, `mail_send_events` name) so HD-202 never reopens the migration.

**RESOLVED 2026-08-27 — owner confirmed: HD-190 owns the mechanism, HD-202 stays open** and is
finished later by wiring its two call sites. The `email_type` column ships now either way.

**Q3. Should any principal be exempt — a system `ADMIN`, or a per-workspace allowance?**
*Recommendation: no exemption of any kind.* A ceiling with a bypass is a ceiling whose most valuable
target is the bypass, and a per-workspace allowance is a licence tier wearing a security costume —
the `RolesProperties` line ("a sprawl guard, never a licence check") is the precedent. If a real
customer needs more, the operator raises the number for the instance.

**Q4. Is 5/day the right global per-recipient cap?**
*Recommendation: yes, and it is the number most likely to need adjusting after launch.* It is the only
ceiling whose false positive lands on an innocent third party (§6.3). If it proves noisy, raise it to
10 rather than removing it — the harassment control degrades gracefully with the number and
disappears entirely without it.

**Q5. Is the invite mail's `@Async`-inside-`@Transactional` dispatch fixed here?**
*Recommendation: no, follow-up (§8.7).* It costs an undercount of at most one per rolled-back invite,
and moving mail dispatch to `afterCommit` touches all three mail paths — a change that deserves its
own tenancy and test pass rather than riding a launch gate.

**Q6. Does `MailDailyVolumeHigh`'s threshold of 500 belong in a property?**
*Recommendation: no.* Grafana rules are provisioned YAML synced from the repository; a threshold that
is a literal with the arithmetic written above it ("a sixth of a 3000/month quota") is readable, and
an env-driven Grafana threshold is a mechanism this stack does not have.

---

## 15. Acceptance criteria

The ticket's own criteria, made checkable, plus what §6 and §9 added.

**The ceilings**

1. A principal at `INVITE_MAX_PER_SENDER_PER_HOUR` gets `429` with `Retry-After` on the next invite;
   the response `detail` names both the hourly and daily allowances and the wait.
2. The same principal, at the daily ceiling, is refused after the hourly window rolls — proving the
   two windows are independent and that the daily one is not merely a multiple of the hourly.
3. A principal who invites `victim@x.test` in workspace **A** is refused when inviting the same
   address in workspace **B** inside the cooldown. *This is the ticket's attack and this is its test.*
4. Deleting the invite does not lift the cooldown: invite → the invitee **declines** (which deletes
   the `workspace_invites` row) → the same principal is still refused. Likewise after the invitee is
   removed as a member (`deleteUnacceptedByWorkspaceAndEmail`).
5. Five **different** principals inviting one address on one day succeed; the sixth is refused with
   the terse §8.1 row-C message, which contains no reference to another workspace, another sender or
   an existing invitation.
6. `app.rate-limit.enabled=false` turns all three off: every case above succeeds.
7. Each refusal increments `hamstrack.ratelimit.hit` with the right `kind`, and increments **nothing
   else** — no `hamstrack.invites.sent`, no `hamstrack.email.sent`.
8. A refused invite writes **no** `workspace_invites` row, **no** `mail_send_events` row, and sends no
   mail.

**Tenancy and enumeration**

9. A non-member over every ceiling gets **404**, not 429, on `POST …/invites`. Same for an unknown
   workspace id.
10. The ceilings behave identically for an address that has a `users` row and one that does not: same
    counts, same statuses, same message shape. No path in the throttle reads `users`.
11. `MailSendEventRepository` exposes no method returning entities or recipient addresses — asserted
    by reflection over the interface, so a future `findByRecipientEmail` fails at the commit that adds
    it.
12. The ceilings count the **inbox key**, not the spelling: `victim+1@x`, `victim+2@x`, `"victim"@x`
    and `victim@x` share a bucket; on `gmail.com`/`googlemail.com` so do the dotted variants; and an
    internationalised domain shares a bucket with its `xn--` punycode spelling. An
    address differing by a Unicode confusable (dotless i U+0131, long s U+017F, Kelvin sign U+212A)
    is still a **different** key — folding those is guessing, and the invitation itself is matched
    on the exact address (HD-120) because on the *redemption* path an extra match lets the wrong
    person accept. The two rules point in opposite directions on purpose; the assertion is that the
    key is what both the count and the insert use, and that mail goes to the submitted address.

**Structure and seals**

13. `MailThrottleCoverageTest` exists and fails when a fourth `EmailType` constant is added without
    being throttled or exempted; `EXEMPT` contains `VERIFICATION` and `PASSWORD_RESET` with the
    HD-202 reason written out.
14. `ThrottleCoverageTest.theThrottledPathSetIsSealed()` **still passes unchanged** (no new path
    pattern), and its `PROPAGATION_CHECKLIST` names the mail axis and the sibling test.
15. `MailThrottleCoverageTest` also asserts every public `send*` method on `MailService` maps to an
    `EmailType`.

**Config, schema, ops**

16. Each of the five properties fails startup when out of range or empty, and `0` is out of range on
    every one of them.
17. `V21` applies to an empty database and to a copy of production; `ddl-auto=validate` passes; the
    entity has no `@ManyToOne` and every column is `updatable = false`.
18. The retention sweep deletes rows older than `INVITE_EVENT_RETENTION_DAYS` and runs whether or not
    `RATE_LIMIT_ENABLED` is true.
19. `application.properties`' numbered list above `app.rate-limit.enabled` names four families;
    `docs/self-hosting.md` names four and states the node-local asymmetry from §7.3;
    `docker-compose.prod.yml` is untouched and `ProdComposeContractTest` still passes.
20. `openapi.yaml` validates with swagger-cli and declares the `429` with `Retry-After` on this
    operation; both `docs/api-*.md` carry the note that here 429 follows the tenancy check.
21. The three Grafana rules load (`docker compose -f docker-compose.observability.yml config` +
    Grafana provisioning starts clean) and each annotation names what to do, including
    `InviteVolumeUnaccepted`'s known false positive.

**Behaviour a human checks**

22. In the People tab, an over-ceiling invite shows the refusal text, keeps the typed address in the
    field, and the admin can retry after the stated wait and succeed.

---

## 16. The highest-risk assumption

**That 100 invitations per sender per day is above every honest use and below every abusive one — and
that the gap between them exists at all.**

It is the assumption every number in §11.2 rests on, and it is the one with the least evidence behind
it: the product has no invitation telemetry beyond a bare counter, so "no honest team sends more than
a hundred a day" is a belief about a population of roughly one customer. If it is wrong in the
generous direction the ceiling is decorative; if it is wrong in the strict direction, the first thing
a large new customer meets is a refusal — during the release whose entire purpose is to be seen by
strangers.

Two things bound the damage and neither removes it. The number is an env var, so being wrong costs a
restart rather than a release. And §10's alerts are built on volume and acceptance ratio rather than
on refusals, so they keep working when the ceiling is set wrong in either direction — which is the
real reason Rule 1 and Rule 2 are ranked above Rule 3.

The mitigation to actually take: **before launch, read `hamstrack_invites_sent_total` for the current
production instance over a fortnight and check the daily maximum against 100.** If nothing has ever
come close, ship the number. If something has, the ceiling is wrong and this section is why.
