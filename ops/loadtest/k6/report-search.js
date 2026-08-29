// HD-186 load harness — the REPORTING & SEARCHING mix.
// Spec: §4.5 row 2.
//
//   k6 run k6/report-search.js -e BASE_URL=... -e VUS=10 -e TOKENS_FILE=/abs/path/tokens.json
//
// The question: THE EXPENSIVE SURFACE — where the budgets, the statement bound and the
// heap live.
//
// ---------------------------------------------------------------------------
// What this mix is expected to find, and the honest prediction about it.
//
// At the think times below (15-30 s) a VU makes 2-4 requests a minute against allowances
// of 120 search and 60 report requests per minute per principal. THE LIMITERS WILL NOT
// BIND. That is not a flaw in the mix; it is the finding: the per-principal budgets are
// not what protects this instance from ordinary use, and the number that does the
// protecting is DB_POOL_MAX_SIZE. The results must say so whichever way it comes out — and
// probe L (probes.js) is what confirms the limiters work AT ALL, so a 429 seen here can be
// attributed with confidence rather than guessed at.
//
// The searches below are not decoration either. Each leaf shape is chosen because it
// costs something specific:
//
//   text ~ "..."      two unanchored LIKEs over a TEXT column, per matching row. Its cost
//                     is a function of the DATA's length, which is why the fixture draws
//                     description length from a distribution instead of a filler string.
//   labelMatch=all    compiles a sub-select PER LABEL.
//   a label/component/version name
//                     forces HQL name resolution, which loads the workspace's WHOLE label
//                     catalog and each visible project's component and version catalogs —
//                     on EVERY /search, /search/schema and /search/suggest. That load is
//                     why the fixture's 400 labels are sized the way they are.
//   fixVersion        a correlated EXISTS.
//
// Saved-filter CRUD is in this mix and on the SEARCH budget for a reason worth restating:
// validating a filter's HQL builds the same ResolutionContext /search/schema pays for. A
// harness that treated it as cheap CRUD would under-load the surface it is measuring.

import { sleep } from 'k6';
import { BASE_URL, VUS, RAMP, HOLD, STAGE, SYSTEM_TAGS, thinkSeconds } from './lib/config.js';
import { CLASS, thresholdsFor } from './lib/classes.js';
import { get, post, patch, del, accounts, canaryAccounts, canaryOwnAccounts, canaryPrincipal, requirePrincipals } from './lib/auth.js';
import { resolveFixture, pick } from './lib/fixture.js';
import { canary } from './lib/canary.js';

export const options = {
  scenarios: {
    expensive: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [{ duration: RAMP, target: VUS }, { duration: HOLD, target: VUS }],
      exec: 'expensiveScenario',
      gracefulRampDown: '30s',
    },
    canary: {
      executor: 'constant-vus', vus: 1, duration: STAGE,
      exec: 'canaryScenario', startTime: '0s',
    },
  },
  thresholds: thresholdsFor([CLASS.SEARCH, CLASS.REPORT]),
  // See lib/config.js: `url` is dropped from the recorded tags because one request in this
  // harness carries a credential in its query string, and the raw capture leaves the machine.
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

// Marker words the generator seeds into titles and descriptions at a known density, so a
// `text ~` leaf returns a plausible FRACTION of the project. A term that matches nothing
// measures an index probe; a term that matches everything measures a sequential scan and a
// huge result set. Neither is the question.
const TERMS = ['checkout', 'cursor', 'retry', 'deploy', 'cache', 'router', 'pool',
  'session', 'replica', 'regression', 'latency'];

const REPORTS = ['flow', 'cycle-time', 'aging', 'velocity', 'sprint-burnup', 'sprint-review'];

function searchBurst(fx) {
  const ws = `/api/workspaces/${fx.wsId}`;
  const roll = Math.random();

  if (roll < 0.15) {
    // The schema call the search box makes on mount. Cheap-looking and it is the one that
    // loads every catalog in the workspace.
    get(`${BASE_URL}${ws}/search/schema`, CLASS.SEARCH, { op: 'schema' });
    return;
  }
  if (roll < 0.25) {
    get(`${BASE_URL}${ws}/search/suggest?field=label&q=area`, CLASS.SEARCH, { op: 'suggest' });
    return;
  }
  if (roll < 0.35) {
    // Saved-filter CRUD: create, read back, delete. On the search budget because
    // validating the HQL builds the same ResolutionContext /search/schema pays for.
    const name = `load ${__VU}-${Date.now()}`;
    const created = post(`${BASE_URL}${ws}/filters`,
      { name: name, hql: 'status != "Done" ORDER BY created DESC', shared: false },
      CLASS.SEARCH, { op: 'filter-create' });
    if (created.status === 200 || created.status === 201) {
      let id = null;
      try { id = JSON.parse(created.body).id; } catch (e) { /* body shape is the finding */ }
      if (id) {
        get(`${BASE_URL}${ws}/filters/${id}`, CLASS.SEARCH, { op: 'filter-get' });
        patch(`${BASE_URL}${ws}/filters/${id}`, { name: `${name} v2` },
          CLASS.SEARCH, { op: 'filter-patch' });
        // Deleted in the same iteration so the fixture does not accumulate filters
        // proportional to the run's length. It is NOT a teardown mechanism: teardown is by
        // tenancy and would remove these anyway (§5.5) — this only keeps the workspace's
        // filter list a realistic size while the measurement is running.
        del(`${BASE_URL}${ws}/filters/${id}`, CLASS.SEARCH, { op: 'filter-delete' });
      }
    }
    return;
  }

  // The main event: a POST /search with a realistic predicate mix.
  const term = pick(TERMS);
  const queries = [
    `text ~ "${term}" ORDER BY created DESC`,
    `status != "Done" AND text ~ "${term}" ORDER BY updated DESC`,
    `assignee = currentUser() AND status != "Done" ORDER BY priority DESC`,
    `created >= -90d AND text ~ "${term}" ORDER BY created DESC`,
    `status = "Done" AND created >= -180d ORDER BY updated DESC`,
  ];
  post(`${BASE_URL}${ws}/search`,
    { query: pick(queries), page: Math.floor(Math.random() * 3), size: 50 },
    CLASS.SEARCH, { op: 'search' });
}

function reportBurst(fx) {
  const b = `/api/workspaces/${fx.wsId}/projects/${fx.bigProjectId}`;
  const r = pick(REPORTS);

  // Default window on purpose: no parameters at all means "the last 90 days, weekly,
  // unfiltered", which is what the UI asks for and what the sizing guidance will be about.
  // A harness that always passed an explicit narrow window would measure a request nobody
  // makes.
  get(`${BASE_URL}${b}/reports/${r}`, CLASS.REPORT, { op: r });

  // One in five reads the CSV sibling. It is a separate endpoint with its own refusals and
  // it materialises the same row set — folding it into the same tag as the JSON report
  // would hide a difference between them if one exists.
  if (Math.random() < 0.2) {
    get(`${BASE_URL}${b}/reports/${r}.csv`, CLASS.REPORT, { op: `${r}.csv` });
  }

  // Insights hangs off /search, NOT /reports — its dataset is an HQL query. It is on the
  // SEARCH budget for that reason (and the throttle it inherits was, for five slices,
  // no throttle at all — see CLAUDE.md). Tagged `report` here because its COST is a
  // report's cost, and tagged with its own op so the two facts stay separable.
  if (Math.random() < 0.25) {
    post(`${BASE_URL}/api/workspaces/${fx.wsId}/search/insights`,
      { query: '', page: 0, size: 50 }, CLASS.REPORT, { op: 'insights' });
  }
}

export function expensiveScenario(fx) {
  // 60/40 toward search: the search box is used far more often than a report page is
  // opened, and weighting them equally would over-report the report surface's share of a
  // realistic load.
  if (Math.random() < 0.6) searchBurst(fx);
  else reportBurst(fx);

  sleep(thinkSeconds(15, 30));
}

export function canaryScenario(fx) {
  canary(fx);
}
