# ADR-0035: Security headers are set by the application, not by the edge

Record date: 2026-09-05
Status: Accepted
Source: HD-264 (`docs/design/content-security-policy-proposal.md` — §5 "Where the policy lives" and §15);
HD-199 / ADR-0013 (`ops/deploy/apply-config.sh`, `ops/deploy/synced-paths.txt` — the rule "the Caddyfile
is never synced"); HD-191 and HD-262 (`request_body max_size` at the edge and the `edge-body-limit` scope in
`ops/drift/hamstrack-config-drift.sh` — the precedent of the opposite choice); HD-233
(`com.hamstrack.common.exception.DatabaseBusyRefusal` — the product's only response written outside the
Spring Security filter chain); ADR-0006 (one codebase, two modes)

## Context

Not a single Content-Security-Policy is set in the stack: the `Caddyfile` sets no headers at all
(it holds only `request_body max_size` and `reverse_proxy app:8080`), and `SecurityConfig` has no
`.headers(...)` block, so only Spring Security's defaults apply — the six headers listed
literally in `DatabaseBusyRefusal.SECURITY_HEADERS`, plus conditional HSTS. CSP is not among them.

The question "where to set the header" is not rhetorical here, because this tree already contains **both**
answers, and one of them is deliberate. `request_body max_size` sits at the edge: only the proxy can
refuse a body **before** Tomcat reads it into a temp file, whereas the application can do so, in
principle, only afterwards. But the `Caddyfile` is one of the two paths the deploy never syncs
(`apply-config.sh` refuses by file name: the production copy carries a hand-added Cloudflare
`trusted_proxies` block that is absent from the repository's copy). So a control at the edge has no way
of reaching the box other than a manual merge — and it is precisely because of that that a fourth drift
scope, `edge-body-limit`, had to be set up: it exists only so that the absence of a manual merge is audible.

Hence the temptation: since the edge was chosen for the body limit and observability was built out for
it, CSP should go there too.

A check shows that the precedent does not carry over. The repository's `Caddyfile` contains no
`file_server`: `index.html` and `/assets/**` are served by Spring's resource handler, that is, **all**
document responses of this product pass through the application's filter chain. Attachments are served
only with `Content-Disposition: attachment` (`IssueController.downloadAttachment`), and there is not a
single presigned link (`grep presign` over `src/main/java` is empty) — bytes that a browser would render
as a document from our origin the product does not serve at all. Behind the edge there remain exactly four
classes of response: its own `413`, `502`/`504`, redirects and refusals of a malformed request.

## Decision

**Security headers are set by the application (`SecurityConfig`), not by the reverse proxy. CSP is
introduced by a report-only header from there; the `Caddyfile` does not change and does not get a new
drift scope.**

The decisive argument is not "it is in the repository and testable" (that is true, but weak:
`edge-body-limit` was set up for exactly that, so that the contents of an unsynced file are observable),
but the coupling of the artefacts:

> **CSP is a claim about the JS bundle, and the bundle travels inside the image.** A new chunk with a web
> worker, a library that has started using `blob:`, moving the fonts to self-hosting — each of those
> changes changes which policy is correct, and each arrives with the image. A policy at the edge
> separates the claim and the subject of the claim into two artefacts with different lifecycles, one of
> which the deploy is forbidden to touch.

The rule for future cases is phrased as a property of a class, not as a list: **a control that the
repository can ship inside the image has no place in the one file the deploy is forbidden to sync;
`edge-body-limit` sits at the edge because its control cannot live anywhere else.**

## Alternatives

1. **CSP in the `Caddyfile`.** Rejected: the policy would be invisible to the repository, would not reach
   the box without a manual merge, would be absent for any self-hoster who puts nginx, Traefik, a cloud
   load balancer or nothing in front of the application, and would require a fifth value in the closed
   `hamstrack_config_drift{scope}` enum — that is, one more series per box forever.
2. **In both places.** Rejected: two sources of one header, where the stricter one quietly wins, and not
   one test sees it.
3. **`<meta http-equiv="Content-Security-Policy">` in `index.html` as a backstop.** Rejected:
   in a policy delivered via meta both `frame-ancestors` and `report-uri` are ignored — the backstop
   would come out imperceptibly weaker than what it duplicates.
4. **Do nothing, rely on point guards.** Rejected by the ticket itself: HD-176 closed a
   specific hole twice (a 422 on the writing door and `fillOf` with a corpus test at every rendering
   site), and both guards are piecemeal and rest on somebody remembering. The `users.avatar_url` column
   is already read into an `<img src>` by five DTOs and has not a single writer in `src/main/java`; on the
   day the writing door appears, the leak shape from HD-176 will reproduce itself in another component.

## Consequences

- **Four classes of response stay without CSP**: the `413` from `request_body max_size`, `502`/`504`, redirects
  and Caddy's refusals of a malformed request. Deliberately accepted — not one of them is an HTML document
  capable of executing a script (Caddy's error bodies are plain text). Listed in spec §5.2 so that
  the next reader does not open this question afresh.
- **A seventh header in the `DatabaseBusyRefusal` refusal — but NOT a seventh entry in
  `SECURITY_HEADERS`.** That `503` is written outside the chain: `HeaderWriterFilter` sets the block in a
  `finally`, and `DatabaseBusyFilter` does a `response.reset()` and strips it. Without restoring it,
  exactly one response of the product would have been left without CSP. The spec assumed "an ordinary
  entry in the map" — during the build it turned out that this cannot be done: the header's value is not a
  compile-time constant (`CSP_REPORT_ONLY_ENABLED` extinguishes it entirely, and the `report-uri` clause is
  present exactly when the sink is enabled). A literal in the map would have sent to Cloud — that is, to
  the only deployment the reports come from — a `503` with a policy differing from the policy of all the
  other responses, and silently: the comparison in the test runs on the test profile's settings. So the
  resolved value is passed into `DatabaseBusyRefusal.write` by that single caller which has it
  (`common.security.ContentSecurityPolicy` — a bean that resolves the string once at startup). The
  safeguard against this does not change: `DatabaseBusyRefusalTest` compares the filter's set of headers
  with "whatever it may be" the advice's set, and not with a list — that is, it was written a release
  earlier than the change, it needed neither extending nor fixing, and it **failed on the very first run
  without the restored header**.
- **There are still four drift scopes.** That is a saving, not an absence of work: the `scope` value is a
  closed enum, every addition costs a series per box forever and a row in the metrics table of
  `docs/observability.md`.
- **The policy contains not a single value that depends on the deployment** — only `'self'`, `'none'`,
  `data:` and two absolute font origins. Neither `SITE_ADDRESS` nor `APP_BASE_URL`. It is precisely this
  property that makes one versioned string correct for Cloud and for every self-hosted installation at once —
  and it is the same property that removes the only strong argument in favour of the edge (the deployment's
  address already lives there).
- **The header costs ≈352 bytes per response.** Over HTTP/2 a repeat of an identical header on a connection
  is compressed into a dynamic-table index, so the real price is once per connection, not per
  request. Recorded because the objection is reasonable and the answer to it is not obvious.
- **The decision extends to future headers** (`Referrer-Policy`, `Permissions-Policy`,
  COOP/COEP): their place is `SecurityConfig`. Each next one, conversely, is obliged to be checked against
  the criterion from the decision — can the control live in the image at all; if not (as with the body limit),
  its place is at the edge, and then it brings a drift scope with it.
