// HD-186 load harness — the BROWSING mix.
// Spec: §4.5 row 1, §8.
//
//   k6 run k6/browse.js -e BASE_URL=... -e VUS=20 -e TOKENS_FILE=/abs/path/tokens.json
//
// The question: HOW MANY PEOPLE CAN DO WHAT MOST PEOPLE ACTUALLY DO.
//
// ---------------------------------------------------------------------------
// THE REQUEST SET IS DERIVED FROM THE PAGES, NOT FROM A HAND-PICKED LIST OF ENDPOINTS.
//
// A request set assembled by taste drifts from the product silently — it keeps passing
// while the SPA changes underneath it, and nothing in the numbers says so. Each function
// below is named for the screen it reproduces (BoardPage, BacklogPage, IssueDetail,
// HomePage) and fans out into the same requests that screen does, including the ones a
// naive harness drops because they look like noise: the project config the board needs
// before it can render a column, the label/component/version catalogs the filter bar
// loads, and the notification poll the shell runs on every page.
//
// ---------------------------------------------------------------------------
// THE HELD SSE STREAM IS DELIBERATE AND IS THE KIND OF THING A NAIVE HARNESS OMITS.
//
// The real SPA opens one per session and holds it. "100 people using the instance" is 100
// held connections PLUS their requests, and the held connection may turn out to be the
// binding resource before anything else is — a run without it would have measured a
// product nobody uses.
//
// It runs as its OWN scenario at the same VU count rather than inside the browsing
// iteration, because a VU blocked on a streaming GET cannot also issue the page requests,
// and folding them together would silently halve the request rate at every stage.
//
// LIMITATION, STATED RATHER THAN HIDDEN: k6's http module holds the connection but does
// not parse events, so this measures the RESOURCE (a held connection plus a registered
// emitter per session) and not event delivery. That is the resource question §4.8 asks.
// Event delivery would need xk6-sse, which means a custom k6 build — and "a single static
// binary, nothing to install on the generator" is one of the reasons k6 was chosen (§4.1),
// so paying that cost for a signal this run does not use would be a bad trade.

import { sleep } from 'k6';
import http from 'k6/http';
import { BASE_URL, HOST_HEADER, VUS, RAMP, HOLD, STAGE, SYSTEM_TAGS, thinkSeconds, phase } from './lib/config.js';
import { CLASS, thresholdsFor, record } from './lib/classes.js';
import { get, currentToken, refreshCurrent, accounts, canaryAccounts, canaryOwnAccounts, canaryPrincipal, requirePrincipals } from './lib/auth.js';
import { resolveFixture, randomIssueNumber, pick } from './lib/fixture.js';
import { canary } from './lib/canary.js';

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [{ duration: RAMP, target: VUS }, { duration: HOLD, target: VUS }],
      exec: 'browseScenario',
      gracefulRampDown: '10s',
    },
    sse: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [{ duration: RAMP, target: VUS }, { duration: HOLD, target: VUS }],
      exec: 'sseScenario',
      gracefulRampDown: '10s',
    },
    // Sized from the STAGE, never from a literal: a canary that stops watching before the
    // stage ends still reports "no leak" for the part it did not see, and nothing in the
    // summary distinguishes that from a clean stage.
    canary: {
      executor: 'constant-vus',
      vus: 1,
      duration: STAGE,
      exec: 'canaryScenario',
      startTime: '0s',
    },
  },
  thresholds: thresholdsFor([CLASS.BROWSE, CLASS.PLANNING, CLASS.SSE]),
  // Connection reuse on, like a browser. Turning it off would measure TCP setup and
  // attribute it to the application.
  noConnectionReuse: false,
  discardResponseBodies: false,
  // `url` is NOT in this list, and this mix is the reason: the SSE request carries an
  // access token in its query string (the product's design — EventSource cannot set
  // headers), and the ladder writes every stage to a JSON file that is attached to a
  // ticket. See lib/config.js.
  systemTags: SYSTEM_TAGS,
};

export function setup() {
  // Refuses the whole run when this mix would allocate more VUs than there are principals.
  // setup() is the only place a k6 script can refuse before a measured request is made, and
  // `browse` is the mix that proved it necessary: three scenarios, 2xVUS+1 VUs, checked for
  // years against a pool sized to VUS.
  requirePrincipals();
  if (!canaryAccounts.length) throw new Error('tokens.json has no accountsB — the tenancy canary cannot be made falsifiable without one');
  if (!canaryOwnAccounts.length) throw new Error('tokens.json has no accountsCanary — the canary would have to borrow a load principal, which is what it stopped doing');
  return resolveFixture(accounts[0].accessToken, canaryAccounts[0].accessToken,
                        canaryPrincipal().accessToken);
}

// ---------------------------------------------------------------------------
// BoardPage: the kanban. Config first (it decides the columns), then the capped issue
// list, then the filter bar's catalogs.
// ---------------------------------------------------------------------------
function boardPage(fx, p) {
  const b = `/api/workspaces/${fx.wsId}/projects/${p}`;
  get(`${BASE_URL}${b}/config`, CLASS.BROWSE, { page: 'board' });
  get(`${BASE_URL}${b}/issues`, CLASS.BROWSE, { page: 'board' });
  get(`${BASE_URL}/api/workspaces/${fx.wsId}/labels`, CLASS.BROWSE, { page: 'board' });
  get(`${BASE_URL}${b}/components`, CLASS.BROWSE, { page: 'board' });
}

// ---------------------------------------------------------------------------
// BacklogPage: the backlog section plus one sprint section. Both are capped at
// AGILE_SECTION_MAX_ISSUES and the planning view assembles (open sprints + 1) of them, so
// asking for two is the cheap end of what this screen does.
//
// THE TWO .../backlog/... READS ARE CLASS.PLANNING, NOT CLASS.BROWSE (HD-174).
//
// They used to be `browse`, and that was correct until the product budgeted them. Since
// HD-174 they carry a per-principal minute budget (PLANNING_REQUESTS_PER_MINUTE, 240) AND a
// permit from the expensive-read occupancy share, so a 429 on either can be the product
// working as designed. Two things broke while they stayed in `browse`: the class's threshold
// rationale ("nothing in the product budgets ordinary browsing") became false, and probe P1's
// victim class — whose staying inside its target IS P1's success criterion — became partly
// bounded, so a P1 pass stopped meaning what it says.
//
// The /sprints read stays in `browse` deliberately: it is NOT on the planning pattern
// (/api/workspaces/*/projects/*/backlog/**), and putting it here would make this class a
// screen rather than a budget, which is the mistake being corrected.
//
// ARRIVAL RATE, CHECKED RATHER THAN ASSUMED: a browse VU thinks 4-8 s and rolls into this
// function 25% of the time, so it produces roughly 5 planning requests a minute — two orders
// of magnitude below the 240/min entitlement. IT MUST STAY THAT WAY, or the ladder measures
// the limiter instead of the app.
// ---------------------------------------------------------------------------
function backlogPage(fx, p) {
  const b = `/api/workspaces/${fx.wsId}/projects/${p}`;
  get(`${BASE_URL}${b}/sprints`, CLASS.BROWSE, { page: 'backlog' });
  get(`${BASE_URL}${b}/backlog/sections/backlog`, CLASS.PLANNING, { page: 'backlog' });
  // THIS project's open sprints, not the big project's. Asking project X for a sprint that
  // belongs to project LDA is a 404 — correct, cheap, and averaged straight into this
  // class's p95, which is the deflation hs_unexpected_404 exists to catch. It did.
  const s = pick((fx.openSprintsByProject || {})[p] || []);
  if (s) get(`${BASE_URL}${b}/backlog/sections/${s}`, CLASS.PLANNING, { page: 'backlog' });
}

// ---------------------------------------------------------------------------
// IssueDetail: the browsing mix's heaviest single read, and the one with both a cheap and
// an expensive case (the comment count has a long tail by construction, §4.2), which is
// what makes a p99 over this class mean something.
//
// Attachments are LISTED and never downloaded: the fixture's attachment rows are metadata
// whose storage keys name nothing in any backend (see 10-generate.sql §12). Downloading
// one would measure an error path and charge it to `browse`.
// ---------------------------------------------------------------------------
function issueDetail(fx, p) {
  const b = `/api/workspaces/${fx.wsId}/projects/${p}`;
  // The number is drawn against THIS project's issue count, not against a global maximum.
  // Drawn globally, a read of the 2 000-issue project asked for a number up to 25 000 and
  // 404'd nine times in ten — cheap, correct, and averaged straight into this class's p95.
  const n = randomIssueNumber(fx, p);
  get(`${BASE_URL}${b}/issues/${n}`, CLASS.BROWSE, { page: 'issue' });
  get(`${BASE_URL}${b}/issues/${n}/comments`, CLASS.BROWSE, { page: 'issue' });
  get(`${BASE_URL}${b}/issues/${n}/history`, CLASS.BROWSE, { page: 'issue' });
  get(`${BASE_URL}${b}/issues/${n}/attachments`, CLASS.BROWSE, { page: 'issue' });
  get(`${BASE_URL}${b}/issues/${n}/children`, CLASS.BROWSE, { page: 'issue' });
}

// The app shell polls this on every screen. Cheap per call and constant per user, which is
// exactly the shape that disappears from a hand-picked endpoint list and then accounts for
// a third of the request volume.
function shellPoll(fx) {
  get(`${BASE_URL}/api/notifications/unread-count`, CLASS.BROWSE, { page: 'shell' });
}

export function browseScenario(fx) {
  const p = pick(fx.projects).id;
  const roll = Math.random();

  shellPoll(fx);
  if (roll < 0.45) boardPage(fx, p);
  else if (roll < 0.70) backlogPage(fx, p);
  else issueDetail(fx, p);

  // 4-8 s, log-normal. A person reads a board for a few seconds before clicking.
  sleep(thinkSeconds(4, 8));
}

export function sseScenario(fx) {
  // One held stream per session, reconnecting when the server closes it — which the real
  // browser's EventSource also does. SseRegistry's emitter timeout is 30 minutes; the
  // client timeout here is deliberately shorter than a stage so a stage cannot end with
  // k6 blocked on a socket.
  //
  // SSE authenticates by QUERY PARAMETER, not by header: EventSource cannot set headers,
  // so the SPA passes ?token= and so must this. Using a header here would 401 and the
  // "held connection" would be a 100-times-per-stage reconnect loop instead.
  //
  // THIS IS THE REQUEST THE SCRUBBING IS FOR. `url` is out of systemTags (lib/config.js),
  // and k6 defaults the `name` tag TO THE URL — so without the explicit name below, the
  // token would travel into the raw capture under a different key. The name is static on
  // purpose: it keeps every stage's SSE samples attributable to one metric while carrying
  // neither the workspace id nor the credential.
  // ONE credential, in ONE place. This used to send the token BOTH in the query string and
  // in an Authorization header — and the server reads the header, so the harness was
  // measuring the header path while paying the query-parameter path's log-exposure cost in
  // every access log, proxy log and raw capture on the way. Since the point of this scenario
  // is to reproduce what EventSource does, the query parameter is the one that stays and the
  // header goes: it is the request the SPA actually makes.
  const res = http.get(
    `${BASE_URL}/api/workspaces/${fx.wsId}/sse?token=${encodeURIComponent(currentToken())}`,
    { headers: HOST_HEADER ? { Host: HOST_HEADER } : {}, timeout: '120s',
      tags: { class: CLASS.SSE, phase: phase(), name: 'GET /api/workspaces/:ws/sse' } });
  record(res, CLASS.SSE);

  // §4.4(3)'s refresh rule, applied by hand because this is the one request that cannot go
  // through authed(). Access tokens live 30 minutes and a mix's ladder runs about 45; these
  // VUs are their own, so nobody else's refresh reaches them. Without this, every SSE
  // request past the half hour is a 401 — counted as neither a 5xx nor a 404 nor an auth
  // failure, so the held-connection measurement would silently become a measurement of
  // refusals with excellent latency.
  if (res.status === 401) refreshCurrent();

  sleep(1);
}

export function canaryScenario(fx) {
  canary(fx);
}
