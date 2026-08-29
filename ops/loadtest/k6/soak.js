// HD-186 load harness — the soak.
// Spec: §4.5 (second paragraph), §5.2 (the Soak phase).
//
//   k6 run k6/soak.js -e MIX=browse -e RATE=240 -e BASE_URL=... -e TOKENS_FILE=/abs/path/tokens.json
//
// Each mix is replayed at its breach point as a CONSTANT-ARRIVAL-RATE run for 15 minutes.
//
// Why both executor models are needed, and why one alone is not enough:
//
//   ramping-vus (closed, with think time) answers "HOW MANY PEOPLE". A VU with think time
//   is a person, and the primary question is a headcount.
//
//   constant-arrival-rate (open) exists because a CLOSED MODEL HIDES COORDINATED OMISSION.
//   In a closed model, when the instance slows down the harness sends fewer requests — the
//   load falls exactly when the server is struggling, and the latency reported is the
//   latency of a system that was politely given a break. An open model keeps arriving at
//   the stated rate regardless, which is what real users do.
//
// So a capacity figure that only holds for four minutes of a closed ladder is not a
// capacity figure. This confirms it holds for fifteen minutes under arrivals that do not
// slow down when the server does.
//
// RATE is requests per minute and is set from the ladder's result — run-ladder.sh prints
// the observed request rate at the highest passing stage, and that number is what goes in
// here. Guessing it defeats the purpose: the soak's job is to confirm THE FIGURE THE
// LADDER PRODUCED, not to find a new one.

import { BASE_URL, SYSTEM_TAGS } from './lib/config.js';
import { CLASS, thresholdsFor } from './lib/classes.js';
import { get, post, accounts, canaryAccounts, canaryOwnAccounts, canaryPrincipal, requirePrincipals } from './lib/auth.js';
import { resolveFixture, randomIssueNumber, pick } from './lib/fixture.js';
import { canary } from './lib/canary.js';

const MIX = (__ENV.MIX || 'browse').toLowerCase();
const RATE = Number(__ENV.RATE || 60);
const DURATION = __ENV.SOAK_DURATION || '15m';

const CLASSES = { browse: CLASS.BROWSE, search: CLASS.SEARCH, report: CLASS.REPORT };
const CLS = CLASSES[MIX];
if (!CLS) throw new Error(`MIX must be one of ${Object.keys(CLASSES).join('|')}, got '${MIX}'`);

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1m', duration: DURATION,
      // preAllocatedVUs generously above the expected need. If k6 has to allocate VUs
      // mid-run it appears as a latency artefact that belongs to the harness, and §4.8's
      // last row makes that the harness's problem rather than the product's — but only if
      // it is avoided rather than merely detected.
      preAllocatedVUs: Math.max(20, Math.ceil(RATE / 4)),
      maxVUs: Math.max(50, Math.ceil(RATE / 2)),
      exec: 'soakScenario',
    },
    canary: { executor: 'constant-vus', vus: 1, duration: DURATION, exec: 'canaryScenario' },
  },
  // The soak has no ramp, so the whole run is the hold. `phase` is still tagged (config.js
  // computes it from elapsed time against RAMP, which defaults to 1m) — set RAMP=0s for a
  // soak, or the first minute is excluded from the thresholds it is supposed to be in.
  thresholds: thresholdsFor([CLS]),
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

export function soakScenario(fx) {
  const b = `/api/workspaces/${fx.wsId}/projects/${fx.bigProjectId}`;
  if (MIX === 'browse') {
    const n = randomIssueNumber(fx, fx.bigProjectId);
    get(`${BASE_URL}${b}/issues/${n}`, CLS, { op: 'soak' });
    get(`${BASE_URL}${b}/issues/${n}/comments`, CLS, { op: 'soak' });
  } else if (MIX === 'search') {
    post(`${BASE_URL}/api/workspaces/${fx.wsId}/search`,
      { query: `text ~ "${pick(['checkout', 'retry', 'cache'])}" ORDER BY created DESC`,
        page: 0, size: 50 }, CLS, { op: 'soak' });
  } else {
    get(`${BASE_URL}${b}/reports/flow`, CLS, { op: 'soak' });
  }
}

export function canaryScenario(fx) {
  canary(fx);
}
