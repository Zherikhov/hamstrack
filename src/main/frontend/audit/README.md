# Contrast audit harness (HD-175)

`npm run audit:contrast` renders the product in a real browser, measures every visible
text element against the background it is actually painted on, and fails the run if
anything misses its WCAG 1.4.3 threshold.

It exists because the measurement that produced HD-175's numbers was run against
production by hand and lived nowhere. A number nobody else can reproduce is not a gate,
and the ticket's own acceptance criterion asks for a re-run.

## What it needs

1. **A backend.** Postgres on 15432 plus the app on 8080:

   ```
   DB_URL=jdbc:postgresql://localhost:15432/hamstrack_audit DB_USERNAME=hamstrack \
   DB_PASSWORD=hamstrack JWT_SECRET=dev-only-jwt-secret-hamstrack-0123456789abcdef \
   ./mvnw spring-boot:run -Dfrontend.skip=true \
     -Dspring-boot.run.arguments=--spring.docker.compose.enabled=false
   ```

   A **seeded** instance. The demo seed (`app.demo.seed-on-first-login`, on by default)
   gives one workspace, one project and 20 issues on the account's first login, which is
   what the element-count floors below are calibrated against.

2. **A frontend.** `npm run dev` (port 5173, proxies `/api` to 8080) — the default target,
   per the spec's OQ5. `AUDIT_BASE_URL=http://localhost:8080` runs the same audit against
   the packaged JAR, which is what a CI run would use if HD-242 ever wires one.

   **Both of those are loopback, and a non-loopback target is refused.** See
   "Which box" below: the ticket's reference numbers were measured against production, so
   this document would otherwise be teaching a production run with nothing standing in the way.

3. **An account on the local audit database.** `AUDIT_EMAIL` / `AUDIT_PASSWORD`; the
   harness logs in through the real login form so it measures the same authenticated render
   a user sees.

   Register it on the instance from step 1 — the throwaway `hamstrack_audit` database on
   localhost — and **never on a shared, staging or production instance**. It is disposable:
   it exists to be typed into a browser by a script, its password lives in a shell, and it
   should be deleted when the run it was made for is done. The harness signs it out at the
   end of every run — including a run whose login *appeared* to fail, because the server
   may have minted the row anyway and logout is harmless without a cookie. That deletes
   the run's refresh token if the cookie reached the backend, and never the account;
   `/api/auth/logout` answers 204 either way, so the printed line is the strongest signal
   the API offers and not a proof.

4. **Chrome.** `puppeteer-core` with `channel: 'chrome'` — the *system-signed* Chrome.
   Never the `puppeteer` package: Smart App Control on the maintainer's machine blocks the
   downloaded Chromium, so a harness that ships its own browser cannot be run by the person
   who most needs to run it.

## Running it

```
AUDIT_EMAIL=audit@hamstrack.local AUDIT_PASSWORD='<the disposable one from step 3>' \
  npm run audit:contrast
```

A real password is deliberately not written here. Any literal in this file satisfies the
registration policy on **every** instance, and the natural failure is somebody registering
the documented address with the documented password on shared staging.

Environment knobs: `AUDIT_BASE_URL`, `AUDIT_CONFIRM_REMOTE` (see "Which box"),
`AUDIT_EMAIL`, `AUDIT_PASSWORD`, `AUDIT_HEADFUL=1`, `AUDIT_CHROME` (explicit executable
path), `AUDIT_REPORT` (output path).

Output: a human summary on stdout and `audit/contrast-report.json`. `audit/*.json` is
gitignored, and the report carries its own `_warning` key — it holds DOM excerpts from a
logged-in session (issue titles, member names, element markup), and an ignore rule protects
the repository but protects no ticket, chat thread or email.

**When the run has to be reported, paste the stdout table.** It is the same combination rows
with the content left out — colours, sizes, ratios, pages and one selector each — which is
why it prints what it prints. The report's own `combinations` array is *not* the safe half:
every row there carries `samples[].html` and `samples[].text`, which is the source
attribution the whole report exists for. Delete the file once the run has been acted on.

Exit code is non-zero on **any** 1.4.3 failure, **any** `indeterminate` element, or **any**
page that came back below its element-count floor.

## Which box

`AUDIT_BASE_URL` is the knob that answers *which box*, and it is the only one that does —
so it is the one that is checked, in the shape `ops/loadtest` already uses for
`LOAD_TARGET` / `LOAD_CONFIRM`. A loopback host runs unremarked. Anything else refuses
unless `AUDIT_CONFIRM_REMOTE` is set to **today's UTC date**:

```
AUDIT_CONFIRM_REMOTE=$(date -u +%Y-%m-%d) AUDIT_BASE_URL=https://… npm run audit:contrast
```

**A loopback hostname is not a loopback box.** The check reads the host, not where it
resolves, so `http://localhost:5199` on the near end of an `ssh -L` or a `kubectl
port-forward` passes silently and types the credentials at whatever is on the far end —
`LOAD_TARGET` in `ops/loadtest` has the same property, and there is no cheap hostname-level
fix for either. This guard is here for the accidental production run, not the tunnelled one:
if you opened the tunnel, you are the one who has to know.

Every run **states its target before it logs in**, so a run's output says which box produced
it. A remote run also types the credentials into that host, writes that host's DOM into the
report, and mints a refresh token on it — `jwt.refresh-token-expiration` is `P30D`, and
signing out is the only thing that deletes the row, which is why the harness now signs out in
a `finally`. Being able to point this at a box is not permission to point it there.

## What it measures

Eight pages at 1280x900: Home, My work, Board, Backlog, Issue detail and Search signed in
(the six the original production measurement sampled), plus the landing page and /terms
signed out, in their own browser context. The two public ones are here because the same
tokens paint them, and because they carry the only **gradient** backgrounds in the product
— the case a naive harness skips and then reports a clean button that is not clean.

## What it does not do

It is not wired into any automated path. CI runs exactly one command, `./mvnw -B verify`,
and nothing in it invokes npm test or this script (HD-242). This is a tool a person runs,
and `src/palette.contrast.test.ts` is the browserless half that a person runs more often.

Three things it structurally cannot see, so a clean run is not a proof of legibility:
**pseudo-element text** (a `::after` `content` string is not an element — the landing
page's safety-state arrow was one, at 1.86:1), **a state nothing renders at rest** (an
overdue date, a form error, a closed menu), and **a page nobody added to the list**.
