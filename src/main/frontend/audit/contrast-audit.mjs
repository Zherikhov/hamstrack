#!/usr/bin/env node
/**
 * **The contrast audit (HD-175) — `npm run audit:contrast`.**
 *
 * Renders the product in a real browser, measures every visible text element
 * against the background it is *actually* painted on, and exits non-zero if
 * anything misses its WCAG 1.4.3 threshold, if any element could not be measured,
 * or if any page came back thinner than its declared element-count floor.
 *
 * ## Why it exists at all
 *
 * The measurement that produced HD-175's numbers was taken by hand against
 * production and lived nowhere. A number nobody else can reproduce is not a gate,
 * and the ticket asks to be "verified by re-running the audit rather than by
 * inspection" — which was unsatisfiable until this file existed. It is task one of
 * that ticket for a second reason too: the ticket lists five failing *combinations*
 * and says there are 37 more. A fix scoped from the five would miss the tail by
 * construction, so the tail has to be enumerated before the work is sized, and only
 * a run can enumerate it.
 *
 * ## Three decisions that are easy to get wrong
 *
 * 1. **`puppeteer-core` with `channel: 'chrome'`, never the `puppeteer` package.**
 *    Smart App Control on the maintainer's machine blocks a downloaded Chromium;
 *    the system-signed Chrome runs headless fine. A harness that ships its own
 *    browser cannot be run by the person who most needs to run it.
 * 2. **The arithmetic is imported from `../src/colour.ts`, not reimplemented.**
 *    HD-175 and HD-176 share the WCAG maths and differ only in where the hex comes
 *    from; two copies drift, and the copy in a harness is the one with no tests.
 *    (Node >= 22.18 strips the types on import; this file is run by no bundler.)
 * 3. **Element-count floors.** An empty backlog renders no rows, produces no
 *    failures and passes. Without a floor per page, a seeding regression reads as a
 *    contrast fix — the same tripwire shape `colour.test.ts` uses on its token map
 *    and `RequestFieldLengthBoundTest` uses on the backend.
 *
 * ## What it is not
 *
 * Not wired into anything automated: CI runs exactly one command, `./mvnw -B
 * verify`, whose frontend executions are `npm ci` and `npm run build` (HD-242). So
 * this protects a person who runs it, not a merge. `src/palette.contrast.test.ts`
 * is the browserless half and proves a different, smaller thing — that no declared
 * token value is illegal on a surface it is declared for. Neither replaces the
 * other.
 *
 * ## What it cannot see, stated so a clean run is not over-read
 *
 * - **Pseudo-element content.** A `::before` / `::after` with a `content` string is
 *   not an element and is not in `querySelectorAll`. The landing page's safety-state
 *   arrow was one, at 1.86:1, and only a grep found it.
 * - **A state nothing renders at rest** — an overdue date on a project with no
 *   overdue issues, an error line on a form nobody failed, a menu that is closed.
 *   Seven such sites were fixed in HD-175 from the source, not from a report.
 * - **A page nobody listed.** The route list below is what gets measured.
 *
 * Each of those is a reason the static test exists, and a reason a green run here
 * means "nothing rendered at 1280x900 on these routes was illegal", not "the
 * product is legible".
 *
 * See `audit/README.md` for what has to be running first.
 */

import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

import { compositeOver, contrastRatio, parseColour } from '../src/colour.ts'
import { collectInPage } from './collect.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))

// ── Configuration ─────────────────────────────────────────────────────────────

const BASE_URL = (process.env.AUDIT_BASE_URL || 'http://localhost:5173').replace(/\/$/, '')
const EMAIL = process.env.AUDIT_EMAIL || ''
const PASSWORD = process.env.AUDIT_PASSWORD || ''
const HEADFUL = process.env.AUDIT_HEADFUL === '1'
const REPORT_PATH = process.env.AUDIT_REPORT || resolve(HERE, 'contrast-report.json')
const CONFIRM_REMOTE = process.env.AUDIT_CONFIRM_REMOTE || ''

/**
 * **The loopback hosts.** `[::1]` is how `URL` spells an IPv6 literal in
 * `hostname`, and `127.0.0.0/8` is matched as a whole hostname rather than as a
 * prefix, so `127.0.0.1.example.com` is not loopback.
 *
 * **This reads the hostname, not where it resolves**, and there is no cheap check
 * that would: `http://localhost:5199` forwarded by `ssh -L` or `kubectl
 * port-forward` is loopback by every test here and production at the other end, so
 * it runs unremarked and types the credentials there. `LOAD_TARGET` in
 * `ops/loadtest` has exactly the same property. A tunnel is a deliberate act by the
 * person at the keyboard; this guard stops the accident, and the README says so
 * rather than letting a reader hear "loopback" as "safe".
 */
const LOOPBACK = /^(?:localhost|127(?:\.\d{1,3}){3}|\[::1\])$/i

/**
 * **`AUDIT_BASE_URL` is the guard that answers *which box*, and this is what
 * earns it one.**
 *
 * `ops/loadtest/README.md` records the same shape and the same lesson:
 * `LOAD_TARGET` answers *which box* while every other knob answers "may I", and
 * before it existed "nothing could tell a scratch database from production". This
 * harness had the identical hole one layer up — `AUDIT_BASE_URL` *chose* the
 * instance and nothing read it back — while the documents around it actively
 * taught the swap: `audit/README.md` offers another host as a normal
 * alternative, and the spec's section 7 records that the ticket's reference
 * numbers were measured against **production, while logged in**. A reader
 * reproducing them has been told the production run is the reference one.
 *
 * One `AUDIT_BASE_URL=https://<prod>` invocation costs four separate things, none
 * of which anything here refused or logged:
 *
 * - production credentials in an environment variable and in shell history;
 * - a report filled with real customer DOM — `outerHTML` prefixes and text
 *   samples from Board, Backlog, Issue detail and Search — written into the
 *   working tree;
 * - **a refresh token nothing revokes.** Every run mints one at
 *   `jwt.refresh-token-expiration=P30D`, the temporary browser profile takes the
 *   cookie away with it, and the row outlives the run by a month. Ten
 *   reproduction runs are ten live credentials nobody can see. {@link
 *   revokeSession} is the counterpart this file was missing, and it does not
 *   replace this guard: a run that never reaches its `finally` — a typo'd host,
 *   a crash — has already minted one.
 * - a mistyped host is handed the email and the password by `page.type`, with no
 *   origin check anywhere in between.
 *
 * So a non-loopback target is refused unless the operator names today's UTC date,
 * in the spelling `require_confirmation` already uses in
 * `ops/loadtest/fixture/lib.sh`: the value is **compared**, not merely required
 * to be non-empty, so an `AUDIT_CONFIRM_REMOTE=yes` left in a shell from last
 * week does not arm this. Being *able* to point the harness at a box is not
 * permission to point it there.
 */
function requireTargetIsPermitted() {
  let url
  try {
    url = new URL(BASE_URL)
  } catch {
    console.error("refusing to audit: AUDIT_BASE_URL='" + BASE_URL + "' is not a URL.")
    process.exit(2)
  }
  if (LOOPBACK.test(url.hostname)) return 'loopback'

  const today = new Date().toISOString().slice(0, 10)
  if (!CONFIRM_REMOTE) {
    console.error(
      'refusing to audit ' + BASE_URL + ': AUDIT_CONFIRM_REMOTE is unset.\n' +
      "  '" + url.hostname + "' is not loopback, so this run would type AUDIT_EMAIL and\n" +
      '  AUDIT_PASSWORD into it, mint a 30-day refresh token on it, and write its DOM —\n' +
      '  issue titles, member names — into ' + REPORT_PATH + '.\n' +
      "  Set AUDIT_CONFIRM_REMOTE to today's date in UTC, e.g. AUDIT_CONFIRM_REMOTE=" + today + '.\n' +
      '  This is the guard that makes being ABLE to point the harness at a box different\n' +
      '  from being PERMITTED to (ops/loadtest/README.md, "which box").',
    )
    process.exit(2)
  }
  if (CONFIRM_REMOTE !== today) {
    console.error(
      'refusing to audit ' + BASE_URL + ": AUDIT_CONFIRM_REMOTE='" + CONFIRM_REMOTE +
      "' is not today (" + today + ').\n' +
      '  A confirmation that outlives its window is not a confirmation.',
    )
    process.exit(2)
  }
  return 'REMOTE — acknowledged for ' + today
}

/** Matches the viewport the original production measurement used, so the two numbers compare. */
const VIEWPORT = { width: 1280, height: 900 }

/** WCAG 1.4.3 thresholds. Not tuneable — see ADR-0027 on an operator-lowerable floor. */
const AA_NORMAL = 4.5
const AA_LARGE = 3
/** WCAG 1.4.11, reported in a separate non-failing section (spec section 6.8). */
const NON_TEXT = 3

/**
 * **Element-count floors, per page.**
 *
 * Calibrated against a **demo-seeded** instance — one workspace, one project, 20
 * issues, 2 sprints, which is what `app.demo.seed-on-first-login` produces on an
 * account's first login. Observed on 2026-09-04 at 1280x900: home 97, my-work 56,
 * board 103, backlog 184, issue 72, search 35. Each floor sits about 30% under its
 * observation, so a content edit does not trip it and an empty page cannot pass.
 *
 * **These are not the ticket's numbers.** The production measurement that produced
 * HD-175 sampled a workspace with far more issues (its backlog alone rendered ~375
 * failures); a demo seed cannot reproduce that and a floor copied from it would
 * fail every local run. What the floor guarantees is a property, not a size: a page
 * that came back thin is reported as thin rather than as clean.
 */
const FLOORS = {
  home: 70,
  'my-work': 40,
  board: 70,
  backlog: 120,
  issue: 50,
  search: 25,
  landing: 100,
  legal: 30,
}

/**
 * The two pages measured **signed out**, in their own browser context.
 *
 * The ticket sampled six authenticated pages; these two are added because the
 * spec's own scope names anonymous readers (`.lp-*` and `.legal-prose` consume the
 * same tokens, and `.lp-licence` / `.lp-datanote` are muted text at 13.5px). They
 * also cover the one thing no signed-in page does: **a gradient background**, which
 * a naive harness silently skips and then reports a clean button that is not clean.
 */
const PUBLIC_ROUTES = [
  { key: 'landing', label: 'Landing (signed out)', url: '/' },
  { key: 'legal', label: 'Terms (signed out)', url: '/terms' },
]

// ── Measurement (Node side — all arithmetic lives in src/colour.ts) ───────────

/**
 * Every opaque background an element could be sitting on, given the layer stack the
 * page reported. Usually one; a gradient ancestor yields one per colour stop.
 *
 * **A gradient is measured at its worst stop, not at a nominal one.** The spec's
 * prose says "darkest stop for light-ink measurement and the lightest stop for
 * dark-ink measurement", but its own worked example takes the *darkest* stop for
 * the *dark* ink (`#04211F` on `#0EA5A4` = 5.58, the binding end, versus 6.79 on
 * `#14B8A6`). The sentence is inverted; the example is right, and the property both
 * are reaching for is simply "the stop that measures lowest". That is what the
 * caller does with these candidates.
 */
function backgroundCandidates(layers) {
  let candidates = [null]
  for (let i = layers.length - 1; i >= 0; i--) {
    const layer = layers[i]
    const next = []
    const tops = layer.kind === 'gradient' ? layer.stops : [layer.hex]
    for (const base of candidates) {
      for (const top of tops) {
        const fg = parseColour(top)
        if (!fg) continue
        if (base === null) {
          // Bottom of the stack: an alpha here would mean the walk never reached an
          // opaque layer, which the page reports as `indeterminate` instead.
          next.push(fg.a >= 1 ? { r: fg.r, g: fg.g, b: fg.b, a: 1 } : null)
        } else {
          next.push(fg.a >= 1 ? { r: fg.r, g: fg.g, b: fg.b, a: 1 } : compositeOver(fg, base))
        }
      }
    }
    candidates = next.filter((c) => c !== null)
    if (candidates.length === 0) return []
    if (candidates.length > 8) candidates = candidates.slice(0, 8)
  }
  return candidates
}

function hex(c) {
  const h = (n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0').toUpperCase()
  return '#' + h(c.r) + h(c.g) + h(c.b)
}

/** 3:1 applies at >= 24px, or >= 18.66px at weight >= 700 (WCAG 1.4.3). */
function thresholdFor(sizePx, weight) {
  if (sizePx >= 24) return AA_LARGE
  if (sizePx >= 18.66 && weight >= 700) return AA_LARGE
  return AA_NORMAL
}

/**
 * Measures one collected element. Returns `{ status, ratio, bg, required }` where
 * status is `pass`, `fail` or `indeterminate`.
 *
 * An ancestor `opacity` multiplies the glyph toward its background, so it is folded
 * into the foreground's alpha and `contrastRatio` composites it — a 0.6 disabled
 * control would otherwise be measured at a ratio nothing on screen has. (Inert
 * controls never reach here; the page filters them, because 1.4.3 exempts them.)
 */
function measure(entry, required) {
  if (entry.indeterminate) {
    return { status: 'indeterminate', reason: entry.indeterminate, ratio: null, bg: null, required }
  }
  const candidates = backgroundCandidates(entry.layers)
  if (candidates.length === 0) {
    return { status: 'indeterminate', reason: 'no-resolvable-background', ratio: null, bg: null, required }
  }
  const fg = parseColour(entry.colour)
  if (!fg) {
    return { status: 'indeterminate', reason: 'unparseable-colour', ratio: null, bg: null, required }
  }
  const alpha = Math.max(0, Math.min(1, fg.a * (entry.opacity ?? 1)))
  const fgHex = entry.colour.slice(0, 7) + Math.round(alpha * 255).toString(16).padStart(2, '0').toUpperCase()

  let worst = null
  for (const bg of candidates) {
    const bgHex = hex(bg)
    const ratio = contrastRatio(fgHex, bgHex)
    if (worst === null || ratio < worst.ratio) worst = { ratio, bg: bgHex }
  }
  const effective = alpha >= 1 ? entry.colour.slice(0, 7) : hex(compositeOver({ ...fg, a: alpha }, parseColour(worst.bg)))
  return {
    status: worst.ratio + 1e-9 >= required ? 'pass' : 'fail',
    ratio: worst.ratio,
    bg: worst.bg,
    fg: effective,
    required,
  }
}

// ── Browser driving ───────────────────────────────────────────────────────────

/**
 * Signs in, and tells the caller **when the credentials left the machine** rather
 * than when the sign-in was seen to work.
 *
 * `onSubmitted` fires immediately before the click, because that is the moment
 * after which a `refresh_tokens` row may exist. Deciding revocation on the *later*
 * signal — the access token appearing in `sessionStorage` — under-approximates it:
 * the server can have minted the row while `waitForFunction` times out (30 s is not
 * generous against a slow first render on a production-scale instance), and then the
 * run would skip the revocation it needs most, on the exact target the confirmation
 * guard exists for, leaving only a bare puppeteer `TimeoutError` on stderr. Revoking
 * without a session costs nothing: the endpoint is public and answers 204 whether or
 * not a cookie arrives.
 */
async function login(page, onSubmitted) {
  await page.goto(BASE_URL + '/login', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('input[type="email"]', { timeout: 20000 })
  await page.type('input[type="email"]', EMAIL)
  await page.type('input[type="password"]', PASSWORD)
  onSubmitted()
  await Promise.all([
    page.click('button[type="submit"]'),
    page.waitForFunction(() => !!sessionStorage.getItem('accessToken'), { timeout: 30000 }),
  ])
}

/**
 * **Gives the session back.** `login()` had no counterpart, and the asymmetry was
 * invisible because the *visible* half of a session — the access token in
 * `sessionStorage`, the cookie in the profile — dies with the browser. The half
 * that does not is the `refresh_tokens` row: `jwt.refresh-token-expiration` is
 * `P30D` and `AuthService.logout` is the only path that deletes it. So every run
 * left behind a credential nobody could enumerate, and ten reproductions left ten.
 *
 * Best-effort by construction and **loud when it fails**, because the failure is
 * the case that matters and a warning naming the remedy is worth more than a
 * silent `catch`. It runs from the page that submitted the login form, so the
 * browser attaches the `refresh_token` cookie itself (path `/api/auth`) if one was
 * set; the endpoint is public and answers 204 whether or not a cookie arrives, which
 * is why it is safe to call after a login whose outcome is unknown — and why the
 * 204 is a weaker signal than it looks (see the comment below).
 */
async function revokeSession(page) {
  // A revocation is CLAIMED only when the server ACCEPTED the call; `fetch` does
  // not reject on 404 or 500, so reporting the call rather than its status would
  // print "session revoked" at a proxy that never reached the backend — the one
  // sentence in this run's output nobody would re-check. That is as far as the
  // claim goes, and no further: `AuthService.logout` swallows an invalid or absent
  // token deliberately, for non-enumeration, so a 204 with no cookie is
  // indistinguishable from a 204 that deleted a row. In the case this guard exists
  // for — a proxy that strips the cookie — the reassuring line prints while the
  // 30-day row lives. The API offers no stronger signal than this one.
  const stillLive = (why) => console.error(
    'WARNING: this run\'s session was NOT revoked (' + why + ').\n' +
    '  Its refresh token stays valid for 30 days — jwt.refresh-token-expiration=P30D,\n' +
    '  and signing out is the only thing that deletes the row. Sign ' + (EMAIL || 'that account') + '\n' +
    '  out from a browser on ' + BASE_URL + ' to delete it.',
  )
  try {
    const status = await page.evaluate(async () => {
      const res = await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' })
      return res.status
    })
    if (status >= 200 && status < 300) {
      console.log('logout accepted — POST /api/auth/logout → ' + status +
        ' (the row is gone if the cookie reached the server; 204 is returned either way)')
    }
    else stillLive('POST /api/auth/logout → ' + status)
  } catch (err) {
    stillLive(String((err && err.message) || err))
  }
}

/**
 * The signed-in routes to sample, discovered from the API rather than hardcoded, so
 * the harness works against any seeded instance. These six are the ones the original
 * production measurement sampled; `PUBLIC_ROUTES` adds the two anonymous pages.
 * Extend both lists when a page is added — an unlisted page is an unmeasured page.
 */
async function discoverRoutes(page) {
  const data = await page.evaluate(async () => {
    const t = sessionStorage.getItem('accessToken')
    const h = { Authorization: 'Bearer ' + t }
    const get = async (u) => (await fetch(u, { headers: h })).json()
    const workspaces = await get('/api/workspaces')
    const ws = workspaces[0]
    const projectsRaw = await get('/api/workspaces/' + ws.id + '/projects')
    const projects = projectsRaw.content ?? projectsRaw
    const p = projects[0]
    const issuesRaw = await get('/api/workspaces/' + ws.id + '/projects/' + p.id + '/issues')
    const issues = issuesRaw.issues ?? issuesRaw.content ?? issuesRaw
    return { wsId: ws.id, projectId: p.id, issueNumber: issues[0] ? issues[0].number : null }
  })
  if (!data.wsId || !data.projectId) throw new Error('no workspace/project on this instance — the audit needs a seeded one')
  const p = '/w/' + data.wsId + '/p/' + data.projectId
  return [
    { key: 'home', label: 'Home', url: '/home' },
    { key: 'my-work', label: 'My work', url: '/my-work' },
    { key: 'board', label: 'Board', url: p },
    { key: 'backlog', label: 'Backlog', url: p + '/backlog' },
    { key: 'issue', label: 'Issue detail', url: p + '/issues/' + (data.issueNumber ?? 1) },
    { key: 'search', label: 'Search', url: '/w/' + data.wsId + '/search?q=' + encodeURIComponent('status != Done') },
  ]
}

/** Waits until the DOM stops growing — cheaper and far more robust than a per-page selector. */
async function settle(page) {
  let last = -1
  for (let i = 0; i < 30; i++) {
    const n = await page.evaluate(() => document.querySelectorAll('body *').length)
    if (n === last && n > 0) break
    last = n
    await new Promise((r) => setTimeout(r, 400))
  }
  await settleAnimations(page)
  return last
}

/**
 * **Waits for every finite animation to reach its resting state.**
 *
 * The spec exempts transient states and says the harness measures the page at
 * rest; a settled DOM is not a settled *render*. The landing page opens with
 * `lp-fadeup` / `lp-rise` (`opacity: 0` → `1`, one with a 0.5s delay), and an
 * element caught mid-fade has a *composited* foreground: the first run of this
 * check reported 56 failures on colours like `#A3A7AC` and `#BEC2C9`, which are
 * declared nowhere — they are `--color-text` and `--color-text-muted` part-way
 * through a fade. Every one of them was the harness reading a frame, not a bug.
 *
 * Infinite animations (`lp-float`, the palette's hairline pulse) never finish and
 * are excluded — they animate `transform` and `opacity` around a resting value,
 * so waiting on one would hang the run and measure nothing new.
 */
async function settleAnimations(page) {
  await page.evaluate(async () => {
    const finite = document.getAnimations().filter((a) => {
      try { return a.effect?.getTiming().iterations !== Infinity } catch { return false }
    })
    await Promise.race([
      Promise.allSettled(finite.map((a) => a.finished)),
      new Promise((r) => setTimeout(r, 5000)),
    ])
  })
  // One more frame, so the last committed style is the one getComputedStyle sees.
  await new Promise((r) => setTimeout(r, 150))
}

// ── Reporting ─────────────────────────────────────────────────────────────────

function combinationKey(f) {
  return [f.fg, f.bg, f.sizePx + 'px', f.weight].join(' | ')
}

function summarise(failures) {
  const table = new Map()
  for (const f of failures) {
    const key = combinationKey(f)
    let row = table.get(key)
    if (!row) {
      row = {
        fg: f.fg, bg: f.bg, sizePx: f.sizePx, weight: f.weight,
        ratio: f.ratio, required: f.required, count: 0, pages: new Set(), samples: [],
      }
      table.set(key, row)
    }
    row.count += 1
    row.pages.add(f.page)
    row.ratio = Math.min(row.ratio, f.ratio)
    // Source attribution: without it, 375 rendered failures cannot be collapsed
    // into the dozen source expressions that cause them, and the tail is a count
    // rather than a work list.
    if (row.samples.length < 3) row.samples.push({ path: f.path, html: f.html, text: f.sample })
  }
  return [...table.values()]
    .map((r) => ({ ...r, pages: [...r.pages].sort() }))
    .sort((a, b) => b.count - a.count)
}

function pad(s, n) { return String(s).padEnd(n) }

async function main() {
  // Before the credentials are read, let alone typed into anything: a run states
  // which box it hit, and a non-loopback one has to have been acknowledged.
  const targeting = requireTargetIsPermitted()
  console.log('target: ' + BASE_URL + '  (' + targeting + ')')

  if (!EMAIL || !PASSWORD) {
    console.error('AUDIT_EMAIL and AUDIT_PASSWORD are required — the audit measures the authenticated render.')
    process.exit(2)
  }

  const browser = await puppeteer.launch({
    // The system-signed Chrome. Never a downloaded Chromium: see the header.
    channel: process.env.AUDIT_CHROME ? undefined : 'chrome',
    executablePath: process.env.AUDIT_CHROME || undefined,
    headless: !HEADFUL,
    defaultViewport: VIEWPORT,
    args: ['--force-device-scale-factor=1', '--hide-scrollbars'],
  })

  const failures = []
  const indeterminate = []
  const nonText = []
  const pages = []

  async function auditRoutes(page, routes) {
    for (const route of routes) {
      await page.goto(BASE_URL + route.url, { waitUntil: 'domcontentloaded' })
      await settle(page)
      const result = await page.evaluate(collectInPage, 8000)

      let pass = 0
      for (const e of result.text) {
        const required = thresholdFor(e.sizePx, e.weight)
        const m = measure(e, required)
        if (m.status === 'pass') { pass += 1; continue }
        const record = {
          page: route.key, url: route.url, path: e.path, html: e.html, sample: e.sample,
          fg: m.fg ?? e.colour, bg: m.bg, sizePx: e.sizePx, weight: e.weight,
          ratio: m.ratio, required, ariaHidden: e.ariaHidden, reason: m.reason,
        }
        if (m.status === 'indeterminate') indeterminate.push(record)
        else failures.push(record)
      }

      for (const g of result.graphics) {
        if (g.inert) continue
        const m = measure({ ...g, sizePx: 0, weight: 400 }, NON_TEXT)
        if (m.status === 'fail') {
          nonText.push({
            page: route.key, path: g.path, html: g.html,
            fg: m.fg ?? g.colour, bg: m.bg, ratio: m.ratio, required: NON_TEXT,
          })
        }
      }

      pages.push({
        key: route.key, label: route.label, url: route.url,
        textElements: result.text.length, pass,
        fail: failures.filter((f) => f.page === route.key).length,
        indeterminate: indeterminate.filter((f) => f.page === route.key).length,
        graphics: result.graphics.length,
        floor: FLOORS[route.key] ?? 0,
        floorMet: result.text.length >= (FLOORS[route.key] ?? 0),
        skippedInert: result.counts.skippedInert,
      })
    }
  }

  // Hoisted so the `finally` can hand the session back before `browser.close()`
  // discards the profile — and with it the only cookie that could revoke it.
  let page = null
  // Set BEFORE the submit, not after a successful sign-in: see {@link login}. It
  // means "credentials were sent, so a row may exist", which is the only question
  // the `finally` has to answer.
  let mayHaveMintedSession = false
  try {
    page = await browser.newPage()
    await page.setViewport(VIEWPORT)
    await login(page, () => { mayHaveMintedSession = true })
    await auditRoutes(page, await discoverRoutes(page))

    // The signed-out pages get their own browser context: navigating to `/` with a
    // session in storage redirects to Home, so measuring the landing page from the
    // authenticated page would silently measure Home a second time.
    const anon = await browser.createBrowserContext()
    const anonPage = await anon.newPage()
    await anonPage.setViewport(VIEWPORT)
    await auditRoutes(anonPage, PUBLIC_ROUTES)
    await anon.close()
  } finally {
    if (mayHaveMintedSession && page) await revokeSession(page)
    await browser.close()
  }

  const combos = summarise(failures)
  const totalText = pages.reduce((a, p) => a + p.textElements, 0)
  const thinPages = pages.filter((p) => !p.floorMet)

  const report = {
    generatedAt: new Date().toISOString(),
    // **Inside the artefact, because the artefact is what gets pasted.** The
    // ignore rule protects the repository; it protects no ticket, no chat thread
    // and no email, and this file reaches those precisely because it is evidence.
    _warning:
      'Contains DOM excerpts captured from a logged-in session: issue titles, member and ' +
      'workspace names, element markup. Do not attach this file to a ticket, paste it into ' +
      'chat, or email it. Paste the stdout table instead: it is these same combination rows ' +
      'with the content left out. The combinations array here is NOT the safe half — every row ' +
      'carries samples[].html and samples[].text. Delete this file once the run has been acted on.',
    baseUrl: BASE_URL,
    viewport: VIEWPORT,
    thresholds: { normal: AA_NORMAL, large: AA_LARGE, nonText: NON_TEXT },
    totals: {
      textElements: totalText,
      failures: failures.length,
      indeterminate: indeterminate.length,
      distinctCombinations: combos.length,
      nonTextFindings: nonText.length,
    },
    pages,
    combinations: combos,
    failures,
    indeterminateElements: indeterminate,
    // Reported, never failed: 1.4.11 on borders and control boundaries is a
    // different criterion with a different threshold and is a follow-up ticket.
    nonTextFindings: nonText,
  }

  mkdirSync(dirname(REPORT_PATH), { recursive: true })
  writeFileSync(REPORT_PATH, JSON.stringify(report, null, 2))

  // ── stdout summary ──────────────────────────────────────────────────────────
  //
  // **A terminal is a second copy of this report with no ignore rule at all**, and
  // it is the copy that reaches a ticket. So every line below prints *structure* —
  // a DOM path, a colour, a size, a count — and none prints `html` or `sample`,
  // the two fields carrying customer content (`path` is tag/class/id only). That
  // restraint is the rule for anything printed here, not an accident of the lines
  // that happen to exist today.
  console.log('\nContrast audit — ' + BASE_URL + ' @ ' + VIEWPORT.width + 'x' + VIEWPORT.height)
  console.log('─'.repeat(96))
  console.log(pad('page', 14) + pad('text', 8) + pad('pass', 8) + pad('fail', 8) + pad('indet', 8) + pad('floor', 8) + 'floor met')
  for (const p of pages) {
    console.log(
      pad(p.key, 14) + pad(p.textElements, 8) + pad(p.pass, 8) + pad(p.fail, 8) +
      pad(p.indeterminate, 8) + pad(p.floor, 8) + (p.floorMet ? 'yes' : 'NO'),
    )
  }
  console.log('─'.repeat(96))
  console.log(
    totalText + ' text elements · ' + failures.length + ' failures · ' +
    indeterminate.length + ' indeterminate · ' + combos.length + ' distinct failing combinations · ' +
    nonText.length + ' non-text (1.4.11) findings, reported only',
  )

  if (combos.length) {
    console.log('\nDistinct failing combinations (fg x bg x size x weight):')
    console.log(pad('#', 6) + pad('fg', 11) + pad('bg', 11) + pad('size', 8) + pad('wt', 6) + pad('ratio', 8) + pad('need', 7) + 'pages')
    combos.forEach((c, i) => {
      console.log(
        pad(i + 1, 6) + pad(c.fg, 11) + pad(c.bg, 11) + pad(c.sizePx + 'px', 8) + pad(c.weight, 6) +
        pad(c.ratio.toFixed(2), 8) + pad(c.required, 7) + c.count + 'x ' + c.pages.join(','),
      )
      console.log('        ' + (c.samples[0] ? c.samples[0].path : ''))
    })
  }

  if (indeterminate.length) {
    console.log('\nIndeterminate (each one is a hole, not a success):')
    for (const e of indeterminate.slice(0, 20)) console.log('  ' + e.reason + '  ' + e.path)
  }

  if (nonText.length) {
    const byColour = new Map()
    for (const n of nonText) byColour.set(n.fg + ' on ' + n.bg, (byColour.get(n.fg + ' on ' + n.bg) ?? 0) + 1)
    console.log('\n1.4.11 (non-text) findings — reported, NOT failed; follow-up ticket:')
    for (const [k, v] of [...byColour.entries()].sort((a, b) => b[1] - a[1])) console.log('  ' + pad(k, 28) + v)
  }

  console.log('\nreport: ' + REPORT_PATH)

  const bad = failures.length > 0 || indeterminate.length > 0 || thinPages.length > 0
  if (thinPages.length) {
    console.log('\nFLOOR MISS: ' + thinPages.map((p) => p.key).join(', ') +
      ' — a thin page produces no failures and would otherwise read as a clean run.')
  }
  process.exit(bad ? 1 : 0)
}

main().catch((err) => {
  console.error(err)
  process.exit(2)
})
