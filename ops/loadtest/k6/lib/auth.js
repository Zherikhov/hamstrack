// HD-186 load harness — principals, tokens and the refresh flow.
// Spec: §4.4.
//
// ---------------------------------------------------------------------------
// The rule: ONE DISTINCT ACCOUNT PER VIRTUAL USER, and the account pool is at least the
// number of VUs k6 will ALLOCATE — which is the sum over every scenario, not the headline
// VUS.
//
// The expensive-surface budgets key on the USER ID (PerPrincipalMinuteBudget, one fixed
// window per principal per minute: 120 search and 60 report requests). The instruction is
// to CAPTURE what the limiters do, not to disable them — a run with the throttles off
// measures a product nobody ships — so the harness must be able to saturate the INSTANCE
// without any one principal exhausting its own allowance first. It does that with
// principals, not with exemptions. A VU takes one and keeps it for the whole run.
//
// ---------------------------------------------------------------------------
// THE POOL IS PARTITIONED BY ROLE, IN tokens.json, AND THAT IS THE LOAD-BEARING PART OF
// THIS FILE.
//
// tokens.json carries THREE arrays and each is a different job:
//
//   accountsA       workspace-A members. THE LOAD. myAccount() draws from here and from
//                   nowhere else.
//   accountsCanary  workspace-A members that no load VU can ever draw, because they are
//                   not in accountsA. The tenancy canary's principal.
//   accountsB       workspace-B members. NEVER load. They exist only so the canary's
//                   foreign target can be proved to EXIST.
//
// The three are disjoint AT THE ADDRESS: the fixture mints load-a-NNN, load-ac-NNN and
// load-b-NNN as three separate families (fixture/10-generate.sql section 1) and
// mint-tokens.js asks for each family BY NAME. No index arithmetic relates them, so no
// change to a VU count, a scenario list or an executor's bookkeeping can make one of them
// reach into another.
//
// WHAT THIS REPLACED, AND WHY THE REPLACEMENT IS A DIFFERENT KIND OF ANSWER.
//
// When these were one flat list and a VU's principal was `__VU % pool.length`, two things
// happened at shipped configurations:
//
//   * the canary's VU landed on a workspace-B account, asked B for its own projects, got a
//     correct 200 from a correct server, and aborted the window as a SECURITY INCIDENT;
//   * from 60 VUs up, ~15 browsing VUs held B credentials while driving A's paths. Every
//     one of those requests 404'd — correctly — and a 404 was counted as nothing, so a
//     growing share of cheap refusals was averaged into `http_req_duration{class:browse}`,
//     deflating exactly the p95/p99 the breach point is read from.
//
// The first repair pinned the canary to `accounts[0]` — an index INSIDE the range
// myAccount() draws from, which is to say it fixed the symptom with the mechanism that
// caused it. It also left the deeper half untouched: browse.js declares THREE scenarios
// (browse at VUS, sse at VUS, canary at 1), so a 100-VU stage instantiates 201 VUs against
// a 120-account pool. `% pool.length` handed 81 accounts to two VUs each — and because
// /api/auth/refresh ROTATES, two holders of one account destroy each other's session the
// moment either refreshes.
//
// ---------------------------------------------------------------------------
// ONE ACCOUNT PER VU, ENFORCED BY INJECTIVITY RATHER THAN BY ARITHMETIC.
//
// myAccount() is `accountsA[vuId() - 1]`. NO MODULO. The id is unique per VU across every
// scenario, so VU -> principal is injective BY CONSTRUCTION: there is no VU count, scenario
// mix or executor at which two VUs can be handed the same account, and there is no
// arithmetic to get wrong. Wrapping is what made sharing possible and silent; removing the
// wrap is what makes it impossible.
//
// THE ID IS `exec.vu.idInTest`, NOT `__VU`. `__VU` is `exec.vu.idInInstance` — unique
// within ONE k6 process. The harness runs one `k6 run` per stage, so today those two are
// the same number and the injectivity holds; under distributed execution they are not, and
// every instance would start numbering at 1, hand the same accounts to several VUs, and
// rotate each other's single-use refresh tokens out from under one another — with every
// guard in this file still green, because each instance's own view is injective. The
// property being claimed is "unique in the TEST", so the id read is the one that means it.
//
// The cost of injectivity is that the pool must be at least the number of VUs k6 allocates.
// requirePrincipals() computes exactly that from the RESOLVED options and refuses in
// setup(); run-ladder.sh asks k6 itself for the same number
// (`k6 inspect --execution-requirements`) before a stage starts. Two independent refusals,
// neither of which is a warning.
//
// ---------------------------------------------------------------------------
// Three consequences that are designed for here rather than discovered mid-window:
//
// 1. AT REALISTIC THINK TIMES THE LIMITERS DO NOT BIND, AND THAT IS ITSELF A FINDING. A
//    reporting VU with 20 s think time makes 3 requests a minute against a 60/minute
//    allowance. So the mixes will reach the connection pool long before they reach any
//    budget — which means the limiters are NOT what protects this instance from ordinary
//    use, and the number that does the protecting is DB_POOL_MAX_SIZE. The results must
//    say so either way.
//
// 2. TOKEN MINTING RUNS INTO THE PER-IP AUTH LIMITER. /api/auth/login is in the auth
//    filter's URL set at RATE_LIMIT_AUTH_IP_PER_MINUTE (15/min), keyed on the peer — and
//    where RATE_LIMIT_TRUST_FORWARDED_FOR is on, on the rightmost X-Forwarded-For. All
//    logins come from one generator address. So minting is a PRE-FLIGHT phase, throttled
//    CLIENT-SIDE to 10 logins/minute, before the measured window opens (mint-tokens.js).
//    Raising the limiter for the window is rejected: it changes the configuration under
//    measurement.
//
// 3. A REFRESH TOKEN IS SINGLE-USE, AND tokens.json IS A SNAPSHOT OF ONE. /api/auth/refresh
//    ROTATES: AuthService.refresh deletes the presented row and issues a new one. So the
//    string in tokens.json can be spent EXACTLY ONCE, ever, by exactly one holder — and
//    every k6 process that reads that file seeds its VUs from the same string.
//
//    A LADDER IS A SEQUENCE OF SEPARATE k6 PROCESSES. It runs ~66 minutes against 30-minute
//    access tokens, so "each VU refreshes once on a 401" is correct only for the FIRST
//    stage that crosses the half hour. That stage rotates every account's token; every
//    later stage then presents a string the server has already deleted, gets 400/401, and
//    aborts on hs_auth_failures. The upper half of the ladder — where the capacity number
//    comes from — was unmeasurable, and nothing in the summary said so.
//
//    So the invariant is written as an invariant: ONE REFRESH PER ACCOUNT, EVER, PER
//    tokens.json. Two mechanisms hold it, neither of which is a comment:
//
//      * run-ladder.sh REFUSES to start a stage that would end after the access tokens
//        expire, naming k6/refresh-tokens.js;
//      * k6/refresh-tokens.js spends the whole file's chain in ONE pass and rewrites
//        tokens.json with the new pairs. /api/auth/refresh is outside the auth filter's URL
//        set, so that pass costs about a minute where re-minting costs twenty.
//
//    The in-flight refresh below is therefore a SAFETY NET, not the plan. If it fires, that
//    account's entry in tokens.json is already dead, and the next stage says so loudly
//    rather than measuring a fleet that has quietly stopped authenticating.

import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { BASE_URL, HOST_HEADER, headers, phase } from './config.js';
import { CLASS, record, authFailures } from './classes.js';

// REQUIRED AND ABSOLUTE — THERE IS NO DEFAULT, AND REMOVING IT IS THE FIX.
//
// k6's open() resolves relative to THE SCRIPT THAT CALLS IT, not to the working directory,
// so the '../tokens.json' that used to sit here meant k6/tokens.json — INSIDE the synced
// ops/ tree, which is the one place this credential file must never be. Every real caller
// (run-ladder.sh, and the README's bare-k6 examples) passes an absolute TOKENS_FILE; the
// default's only remaining job was to put that relative string in the operator's head,
// where it became the value someone passes to refresh-tokens.js by hand.
const TOKENS_FILE = __ENV.TOKENS_FILE;
if (!TOKENS_FILE || (TOKENS_FILE[0] !== '/' && !/^[A-Za-z]:[\\/]/.test(TOKENS_FILE))) {
  throw new Error('TOKENS_FILE is required and must be ABSOLUTE. It is a credential file ' +
    'for ~227 accounts and its home is outside the synced ops/ tree; k6 resolves a relative ' +
    'path here against k6/lib/, and against the working directory in the scripts that WRITE ' +
    'it, so no single relative value is correct at both ends. ' +
    'Example: TOKENS_FILE=/var/tmp/hd186/tokens.json');
}

function loadPool(which) {
  const raw = JSON.parse(open(TOKENS_FILE));
  if (raw.accounts && !raw.accountsA) {
    throw new Error(TOKENS_FILE + ' is in the old single-pool format. Re-mint: the pools ' +
      'are separated because a flat list put workspace-B principals on workspace-A ' +
      'paths, and merging them back is the bug, not a compatibility shim.');
  }
  if (raw.accountsA && !raw.accountsCanary) {
    throw new Error(TOKENS_FILE + ' predates the canary pool: it has accountsA and ' +
      'accountsB but no accountsCanary. The canary principal used to be accountsA[0] — an ' +
      'index INSIDE the range myAccount() draws from, so a load VU and the canary shared ' +
      'one single-use refresh token and rotated it out from under each other. Re-mint ' +
      'with k6/mint-tokens.js. There is deliberately no compatibility shim: a shim here ' +
      'would restore the sharing silently.');
  }
  const pool = raw[which] || [];
  if (!pool.length) {
    throw new Error(TOKENS_FILE + ' has no ' + which + ' — run k6/mint-tokens.js first');
  }
  return pool;
}

// SharedArray: parsed once and shared across VUs. 227 tokens per VU in a 100-VU run would
// otherwise be tens of thousands of copies of the same JSON in the generator's memory,
// which is the generator competing with itself — and section 4.8's last row makes the
// generator's own saturation a first-class abort condition, so spending memory on it is
// not neutral.
export const accounts = new SharedArray('accountsA', function () { return loadPool('accountsA'); });

/** Workspace B's principals. NEVER load — see the header. The canary's TARGET, not its identity. */
export const canaryAccounts = new SharedArray('accountsB', function () { return loadPool('accountsB'); });

/**
 * Workspace-A principals reserved for the canary. NOT a slice of `accounts` and not an
 * index into it — a separate family of addresses (load-ac-NNN) that myAccount() has no
 * expression capable of reaching.
 */
export const canaryOwnAccounts = new SharedArray('accountsCanary', function () { return loadPool('accountsCanary'); });

/**
 * The peak number of VUs a scenario can instantiate, from the options k6 RESOLVED.
 *
 * Returns null for an executor this function does not know, and the caller refuses. That is
 * deliberate: a new executor is a deliberate edit here, never a silent under-count — and an
 * under-count is exactly what let `browse` instantiate 2xVUS+1 VUs while every guard in the
 * harness compared its pool against VUS.
 */
function scenarioPeakVUs(s) {
  switch (s.executor) {
    case 'shared-iterations':
    case 'per-vu-iterations':
    case 'constant-vus':
      return Number(s.vus || 1);
    case 'ramping-vus':
      return Math.max(Number(s.startVUs || 0),
        ...(s.stages || []).map(function (st) { return Number(st.target || 0); }));
    case 'constant-arrival-rate':
    case 'ramping-arrival-rate':
      return Number(s.maxVUs || s.preAllocatedVUs || 0);
    case 'externally-controlled':
      return Number(s.maxVUs || s.vus || 0);
    default:
      return null;
  }
}

/**
 * Refuse to start unless every VU this run will allocate can be handed its own principal.
 *
 * Called from every mix's setup(), which is the only place a k6 script can refuse the WHOLE
 * run: a throw here aborts before a single measured request. run-ladder.sh refuses earlier
 * and from the outside; this is the half that also protects a bare `k6 run`.
 *
 * It sums over SCENARIOS rather than reading VUS, because the count that matters is the one
 * k6 allocates. `browse` declares three scenarios and instantiates 2xVUS+1; every guard
 * that compared a 120-account pool against a 100-VU headline was comparing the wrong number
 * and could not see 81 accounts being held twice.
 */
export function requirePrincipals() {
  const scenarios = (exec.test.options && exec.test.options.scenarios) || {};
  const names = Object.keys(scenarios);
  if (!names.length) {
    throw new Error('no scenarios resolved, so requirePrincipals() cannot check anything. ' +
      'It refuses rather than passing: a pool check that silently examined nothing is ' +
      'indistinguishable from one that passed.');
  }

  let peak = 0;
  const parts = [];
  for (const name of names) {
    const n = scenarioPeakVUs(scenarios[name]);
    if (n === null) {
      throw new Error(
        "scenario '" + name + "' uses executor '" + scenarios[name].executor + "', which " +
        'lib/auth.js:scenarioPeakVUs does not know how to size. Add it there. It is not ' +
        'skipped, because an unknown executor silently contributing 0 is how a mix comes ' +
        'to allocate more VUs than there are principals and hand one account to two of ' +
        'them — which /api/auth/refresh then rotates out from under both.');
    }
    peak += n;
    parts.push(name + '=' + n);
  }

  if (accounts.length < peak) {
    throw new Error(
      'NOT ENOUGH PRINCIPALS: this run allocates up to ' + peak + ' VUs (' +
      parts.join(' + ') + ') and accountsA holds ' + accounts.length + '. myAccount() is ' +
      'accountsA[exec.vu.idInTest - 1] with NO modulo, precisely so this cannot degrade ' +
      'into two VUs quietly sharing one single-use refresh token — so it refuses ' +
      'instead. Mint more: ' +
      'ACCOUNTS=' + (peak + 10) + ' k6 run k6/mint-tokens.js, and make sure the fixture ' +
      'holds that many workspace-A load accounts (fixture/10-generate.sql section 1 sets ' +
      'the number; changing it means regenerating the fixture).');
  }
  console.log('principals: ' + accounts.length + ' accountsA for a peak of ' + peak +
    ' VUs (' + parts.join(' + ') + '); canary pool ' + canaryOwnAccounts.length +
    ', foreign pool ' + canaryAccounts.length);

  // NO HD186_THRESHOLD_KEYS LINE HERE, AND ITS ABSENCE IS THE POINT.
  //
  // This used to console.log the stage's threshold keys for run-ladder.sh's vacuous-
  // threshold seal to scrape back out of the tee'd stage log. k6 does not print console.log
  // verbatim — it goes through logrus — so what reached the disk was
  //   time="…" level=info msg="HD186_THRESHOLD_KEYS [\"…\"]" source=console
  // and the seal's `sed` handed jq a string with a trailing `" source=console` on it. jq
  // exited before evaluating anything, the error was redirected away, and the seal reported
  // success on every stage it ever ran. The keys are now asked of `k6 inspect` directly
  // (run-ladder.sh:threshold_keys), which is the same options object k6 resolves for the
  // run and needs no log formatting to survive a round trip.

  return peak;
}

/**
 * The account this VU owns for the whole run, drawn from WORKSPACE A.
 *
 * `accountsA[exec.vu.idInTest - 1]`, with NO modulo. Deterministic in the id, so a stage
 * restarted at the same VU count re-binds the same principals and a per-principal budget
 * observed in one stage is comparable with the next — and INJECTIVE across the whole test,
 * so two VUs cannot be handed the same account whatever the scenario mix or the number of
 * k6 instances does.
 *
 * The throw is the backstop for a VU that reached here without setup() having refused (a
 * mix that forgot requirePrincipals(); `k6 run --no-setup`). It is loud on purpose: what it
 * replaced — wrapping, with a comment saying the caller had been warned — was silent, and
 * its symptom was an unexplained 400 on a refresh in the middle of a five-hour window.
 */
function vuId() {
  // exec.vu.idInTest is unique across the whole test; __VU (== idInInstance) is unique only
  // within one k6 process. Fall back to __VU only if a k6 build does not expose it, and say
  // so rather than silently degrading to a per-instance number.
  const v = exec.vu && exec.vu.idInTest;
  return v || __VU || 1;
}

export function myAccount() {
  const idx = vuId() - 1;
  if (idx >= accounts.length) {
    throw new Error('VU ' + vuId() + ' has no principal: accountsA holds ' + accounts.length +
      '. This run allocates more VUs than there are accounts and this function refuses to ' +
      'wrap (lib/auth.js). Mint more accounts, or lower the stage. setup() should have ' +
      'refused first — check that the mix calls requirePrincipals().');
  }
  return accounts[idx];
}

/**
 * The canary's principal: a member of workspace A, FROM ITS OWN POOL.
 *
 * Not `accounts[0]`, and not derived from __VU. The canary runs as its own one-VU scenario
 * whose VU number is whatever k6 assigns it, so any index arithmetic makes the identity of
 * the requesting principal a property of the executor's bookkeeping — and an index inside
 * accountsA makes it a principal some load VU also holds. It must be a member of A and it
 * must never be a member of B, or the assertion inverts and a correct server fails it.
 */
export function canaryPrincipal() {
  return canaryOwnAccounts[0];
}

/** A pinned principal from workspace B, used ONLY to prove the canary's target exists. */
export function foreignPrincipal() {
  return canaryAccounts[0];
}

// Per-VU mutable state, keyed by principal. Module scope in k6 is per-VU, so this is not
// shared between VUs — but ONE VU may act as two principals (its own, and the canary's
// pinned one), and a single `state.token` made the second silently reuse the first's.
const state = {};

function stateFor(acct) {
  let s = state[acct.email];
  if (s) return s;
  s = { token: acct.accessToken, email: acct.email };
  state[acct.email] = s;
  if (acct.refreshToken) {
    // The cookie was set on a response in the minting PROCESS, so this VU's jar has never
    // seen it. Path must match the server's (ResponseCookie ... .path("/api/auth")) or the
    // jar will not attach it to the refresh call and every VU would "fail to refresh" for
    // a reason that has nothing to do with the server.
    http.cookieJar().set(BASE_URL + '/api/auth', 'refresh_token', acct.refreshToken, {
      path: '/api/auth',
    });
  }
  return s;
}

/**
 * True when `res` is not the caller's own answer but the AUTH path's answer standing in for
 * it — the refresh call's response, returned because the request could not be authenticated
 * at all.
 *
 * EVERY CALLER THAT ASSERTS ON A STATUS MUST ASK THIS FIRST. The tenancy canary is the one
 * that must: it read a 400 from an already-spent refresh token as "GET
 * /api/workspaces/{B}/projects returned 400, expected 404" and aborted the window as a
 * security incident, from a server that had done nothing wrong. A check that cannot tell
 * "refused" from "could not authenticate" manufactures incidents, and it does so under
 * exactly the conditions the run exists to reach — a long window and an expired token.
 */
export function isAuthFailure(res) {
  return !!(res && res.hsAuthFailure);
}

function markAuthFailure(res, why) {
  authFailures.add(1, { reason: why });
  if (res) {
    // A k6 Response is an ordinary object here; the try is for the day it is not, and the
    // counter has already fired either way.
    try { res.hsAuthFailure = why; } catch (e) { /* counted, just not markable */ }
  }
  return res;
}

/**
 * One authenticated request, with the section 4.4(3) refresh rule.
 *
 * `cls` is the endpoint class the LATENCY belongs to. A refresh triggered inside a browse
 * request is tagged `auth` and counted there, never folded into the class whose latency it
 * would distort — a 900 ms refresh charged to `browse` is how a browsing p99 breaches for
 * a reason that is not browsing.
 *
 * `acct` overrides the VU's own principal. The canary passes one so that WHO is asking is
 * a decision in the canary rather than a consequence of which VU k6 gave it.
 *
 * ON AUTH FAILURE IT STILL RETURNS THE REFRESH RESPONSE — there is no other response to
 * return — but it MARKS it (isAuthFailure). A caller that asserts on a status must check the
 * mark, because that status belongs to /api/auth/refresh and not to the URL it asked for.
 */
export function authed(method, url, body, cls, extraTags, acct) {
  const s = stateFor(acct || myAccount());
  const tags = Object.assign({ class: cls, phase: phase() }, extraTags || {});
  const params = { headers: headers(s.token, body ? { 'Content-Type': 'application/json' } : {}), tags };

  let res = http.request(method, url, body, params);
  if (res.status !== 401) {
    record(res, cls, extraTags);
    return res;
  }

  // One refresh, then one retry. Never a re-login: /api/auth/login is per-IP limited at
  // 15/min and 100 VUs discovering an expired token at once would produce a burst of 429s
  // on the auth class that has nothing to say about capacity.
  const rHeaders = HOST_HEADER ? { Host: HOST_HEADER } : {};
  const refreshed = http.post(BASE_URL + '/api/auth/refresh', null, {
    headers: rHeaders,
    tags: { class: CLASS.AUTH, phase: phase(), op: 'refresh' },
  });
  record(refreshed, CLASS.AUTH);

  if (refreshed.status !== 200) {
    // A 400/401 here is almost always the SINGLE-USE property rather than the server: this
    // account's refresh token in tokens.json has already been spent, by an earlier ladder
    // stage or by another process reading the same file. See point 3 in the header.
    return markAuthFailure(refreshed, 'refresh-' + refreshed.status);
  }
  try {
    s.token = JSON.parse(refreshed.body).accessToken;
  } catch (e) {
    return markAuthFailure(refreshed, 'refresh-unparseable');
  }

  res = http.request(method, url, body, {
    headers: headers(s.token, body ? { 'Content-Type': 'application/json' } : {}),
    tags,
  });
  record(res, cls, extraTags);
  if (res.status === 401) {
    // A second 401 after a SUCCESSFUL refresh is not an expiry — it is an authorization
    // change or a broken principal, and continuing would measure a fleet that is quietly
    // doing nothing. This one IS the caller's own response, so it is recorded before it is
    // marked.
    return markAuthFailure(res, 'reauth-401');
  }
  return res;
}

export const get = (url, cls, tags) => authed('GET', url, null, cls, tags);
export const post = (url, body, cls, tags) => authed('POST', url, JSON.stringify(body), cls, tags);
export const patch = (url, body, cls, tags) => authed('PATCH', url, JSON.stringify(body), cls, tags);
export const del = (url, cls, tags) => authed('DELETE', url, null, cls, tags);

/** The raw token, for the one caller that cannot use a header: SSE takes ?token=. */
export function currentToken() {
  return stateFor(myAccount()).token;
}

/**
 * Refresh this VU's own principal, out of band. For the SSE scenario, which is the ONE
 * request in the harness that does not go through authed() — it cannot, because it
 * authenticates by query parameter — and therefore never got section 4.4(3)'s refresh rule.
 *
 * SAME SAFETY-NET STATUS AS THE REFRESH INSIDE authed(): the plan is that no stage crosses
 * the access tokens' expiry (run-ladder.sh refuses) and that the chain is advanced BETWEEN
 * stages by refresh-tokens.js. Reaching here means that plan has already failed for this
 * account, and the loud counter is the point.
 *
 * Returns true if the token was replaced.
 */
export function refreshCurrent() {
  const s = stateFor(myAccount());
  const rHeaders = HOST_HEADER ? { Host: HOST_HEADER } : {};
  const refreshed = http.post(BASE_URL + '/api/auth/refresh', null, {
    headers: rHeaders,
    tags: { class: CLASS.AUTH, phase: phase(), op: 'refresh' },
  });
  record(refreshed, CLASS.AUTH);
  if (refreshed.status !== 200) {
    markAuthFailure(refreshed, 'sse-refresh-' + refreshed.status);
    return false;
  }
  try {
    s.token = JSON.parse(refreshed.body).accessToken;
    return true;
  } catch (e) {
    markAuthFailure(refreshed, 'sse-refresh-unparseable');
    return false;
  }
}
