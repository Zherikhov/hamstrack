// HD-186 load harness — the probes.
// Spec: §4.6, acceptance criteria 17 and 18.
//
//   k6 run k6/probes.js -e PROBE=p1 -e BASE_URL=... -e TOKENS_FILE=/abs/path/tokens.json
//   k6 run k6/probes.js -e PROBE=p2 ...
//   k6 run k6/probes.js -e PROBE=l  ...
//
// THESE ARE NOT EXTRA LOAD. They are the experiments the ticket actually asks for, and
// each has a stated way to come out AGAINST the prediction. A load test that only confirms
// what was predicted has been run badly, so each probe below names its own falsification
// before it runs.

import { sleep } from 'k6';
import { BASE_URL, STAGE, SYSTEM_TAGS, thinkSeconds } from './lib/config.js';
import { CLASS, thresholdsFor } from './lib/classes.js';
import { get, post, accounts, canaryAccounts, canaryOwnAccounts, canaryPrincipal, requirePrincipals } from './lib/auth.js';
import { resolveFixture, randomIssueNumber, pick } from './lib/fixture.js';
import { canary } from './lib/canary.js';

const PROBE = (__ENV.PROBE || 'p1').toLowerCase();

// ===========================================================================
// PROBE P1 — the entitlement probe. It measured HD-182, and now it verifies the fix.
//
// One principal spends EXACTLY their documented allowance and no more: 120 search + 60
// report requests per minute, no think time, capped CLIENT-SIDE at the entitlement so the
// run cannot be dismissed as abuse. Simultaneously a VICTIM probe — a DIFFERENT principal
// running the browsing mix at 5 VUs — measures what an ordinary user experiences.
//
//   THE PREDICTION THIS PROBE ORIGINALLY CARRIED HAS BEEN DELIBERATELY FALSIFIED BY THE
//   PRODUCT, AND THE WORDING BELOW IS THE INVERSION (HD-182). It used to read: the
//   prediction HOLDS if the victim degrades WHILE the entitled principal receives no 429 at
//   all. It did hold — worse than predicted, the 2026-08-31 run aborted after 32 s on
//   dropped iterations — and the answer shipped was an OCCUPANCY bound: no principal, and
//   no set of principals, may hold more than a stated share of a replica's connection pool
//   through the expensive-read surface. So "the entitled principal receives no 429" is now
//   the REGRESSION, not the confirmation. A harness that kept documenting the old
//   prediction would be read as the product being wrong.
//
//   THE FIX HOLDS if the victim's `browse` class stays INSIDE its target while the entitled
//   principal receives 429s of the new kind — `hs_occupancy_429`, i.e. `errorType`
//   TOO_MANY_IN_FLIGHT (their own share) or EXPENSIVE_SURFACE_BUSY (the instance's). Their
//   own latency is still not a target: an entitled principal may be slow, and may now also
//   be refused. Neither is the claim under test.
//
//   THE FIX DID NOT ENGAGE if there are no occupancy 429s at all. That is not a failure —
//   it means this box absorbed one fully entitled principal without the surface ever
//   filling, so the probe says nothing about the bulkhead either way. Record it as such
//   rather than as a pass, and report the number below, which is what makes the difference
//   legible.
//
//   REPORT REGARDLESS: the REAL MEAN CONNECTION-HOLD TIME PER REQUEST CLASS, from Hikari's
//   `hikaricp_connections_usage_seconds`, and `hamstrack_expensive_read_in_flight` (max over
//   replicas, never sum). The first is what the original arithmetic could only estimate; the
//   second says whether the share was ever full. A value pinned at the ceiling AFTER the run
//   ends has TWO readings, and `hamstrack_expensive_read_permit_force_released_total` separates
//   them: FLAT means a LEAKED PERMIT — a permanent capacity loss on that replica until
//   restart — while CLIMBING means slots were being HELD (a request whose body read or
//   response write outlived the ceiling) and the watchdog took them back. k6 does not
//   produce the second on purpose, so seeing it during a run means something else on that
//   box was holding a connection open.
//
// The rates below are the ENTITLEMENT, not a load level. Do not raise them to "get a
// better result": a probe that exceeds the allowance is measuring abuse and answers a
// question nobody asked.
// ===========================================================================
const SEARCH_PER_MINUTE = Number(__ENV.SEARCH_ENTITLEMENT || 120);
const REPORT_PER_MINUTE = Number(__ENV.REPORT_ENTITLEMENT || 60);

// ===========================================================================
// PROBE P2 — the report heap probe. Tests the ~1.9 KB/row costing.
//
// A step ramp of concurrent requests against a row-level report on the 25 000-issue
// project, so every response materialises the full REPORTS_MAX_ROWS cap. Heap-after-
// collection is captured at each step BY THE BOX-SIDE ACTUATOR SAMPLER — the loop in
// capture/capture.sh that appends the whole /actuator/prometheus exposition every
// INTERVAL, from which jvm_gc_live_data_size_bytes is read afterwards. Not here: k6 cannot
// see the JVM, and a harness that claimed to measure heap from the client side would be
// reporting response size and calling it memory.
//
// (Named as the thing it is rather than as a filename. The filename this used to give did
// not exist, and a reader looking for it would have concluded the measurement did not.)
//
//   HELD if measured transient heap per concurrent capped request lands within a factor of
//   two of the costed 38 MB (~19-76 MB).
//   DID NOT HOLD in either direction, and both directions are useful: materially less
//   means REPORTS_MAX_ROWS is more conservative than it needs to be and the docs row
//   overstates the danger; materially more means the default is closer to the heap than
//   the documentation claims.
//
// AND THE THIRD PROPOSITION, which is the one nobody has written down and the one this run
// is uniquely able to settle: every row-level report holds a POOLED CONNECTION for its
// whole assembly (the service is @Transactional(readOnly = true) end to end), so the number
// of reports materialising simultaneously is bounded by DB_POOL_MAX_SIZE, and the true
// heap exposure is `pool size x max rows x bytes per row` = 10 x 20 000 x 1.9 KB ~= 380 MB
// of a 512 MB heap. If that holds, the pool is not only the concurrency bound but the HEAP
// bound — and the advice in docs/self-hosting.md to raise DB_POOL_MAX_SIZE alongside
// DB_STATEMENT_TIMEOUT_MS silently raises heap exposure at the same time.
//
// That interaction is a FINDING for HD-182, not a fix here. Nothing in this ticket changes
// a cap.
//
// The steps deliberately walk THROUGH the pool size and past it, because the proposition
// is about what happens at and above the bound.
// ===========================================================================
const P2_STEPS = (__ENV.P2_STEPS || '1,2,4,6,8,10,12,16').split(',').map(Number);
const P2_STEP_DURATION = __ENV.P2_STEP_DURATION || '90s';

// ===========================================================================
// PROBE L — the limiter probe.
//
// One principal deliberately EXCEEDING both budgets, for one minute, at low concurrency.
// Its only purpose is to confirm the refusals are the shape the documentation claims —
// 429 with a Retry-After, counted under hamstrack_ratelimit_hit_total with the right
// `kind` — so that a 429 appearing during a real mix can be attributed with CONFIDENCE
// rather than assumed.
//
// This is the one scenario in the harness that is SUPPOSED to be refused, so its 429s must
// not count against any budget. It therefore declares no rate thresholds: a probe whose
// success condition is a 429 cannot share a threshold set with mixes whose success
// condition is the absence of one.
// ===========================================================================

function buildOptions() {
  if (PROBE === 'p1') {
    return {
      scenarios: {
        // The entitled principal. constant-arrival-rate, because an entitlement is a RATE
        // and a closed model would let the instance's own slowness reduce the pressure —
        // which is exactly the coordinated omission that would make an over-subscribed
        // instance look fine.
        entitled_search: {
          executor: 'constant-arrival-rate',
          rate: SEARCH_PER_MINUTE, timeUnit: '1m',
          duration: STAGE, preAllocatedVUs: 10, maxVUs: 20,
          exec: 'entitledSearch',
        },
        entitled_report: {
          executor: 'constant-arrival-rate',
          rate: REPORT_PER_MINUTE, timeUnit: '1m',
          duration: STAGE, preAllocatedVUs: 10, maxVUs: 20,
          exec: 'entitledReport',
        },
        // The victim: a DIFFERENT principal doing ordinary work. Its `browse` numbers are
        // the probe's actual output; the entitled scenarios are the stimulus.
        victim: {
          executor: 'constant-vus', vus: 5, duration: STAGE,
          exec: 'victimBrowse', startTime: '0s',
        },
        canary: { executor: 'constant-vus', vus: 1, duration: STAGE, exec: 'canaryScenario' },
      },
      // The victim's browse target is the pass/fail. The entitled principal's own LATENCY
      // is recorded and is NOT a target — it is allowed to be slow; that is not the claim
      // under test.
      //
      // ITS MINUTE-BUDGET REFUSALS ARE A TARGET, AND THAT IS THE HALF OF THE ENTITLEMENT
      // CROSS-CHECK THAT CAN BE EXECUTED. P1 drives the entitled principal at exactly
      // SEARCH_ENTITLEMENT / REPORT_ENTITLEMENT, which are asserted to be the box's own
      // per-principal budgets, so the expectation is not "few of them" but NONE AT ALL: a
      // principal inside its entitlement that is refused BY THAT ENTITLEMENT has not been
      // granted the entitlement this probe claims to be testing. Until this was a threshold
      // the cross-check lived only in a console.log asking the operator to compare two
      // numbers by eye, and an advisory note is not a check.
      //
      // IT IS THE MINUTE-BUDGET METRIC AND NOT hs_refused_429, WHICH IS THE HD-182 EDIT.
      // Occupancy refusals (hs_occupancy_429) are counted in hs_refused_429 as well — a
      // refused user is refused — so keying this threshold on that metric would now fail the
      // probe for the product working as designed. The two are separate metrics precisely so
      // one of them can stay a threshold while the other becomes an observation.
      //
      // IT IS ONE-SIDED, AND THE NOTE IN setup() COVERS THE OTHER SIDE. Configuring these
      // BELOW what the box grants also produces no 429 and passes — assuming too little is
      // invisible here and only the fingerprint comparison catches it. Assuming too MUCH now
      // fails the probe instead of quietly answering a question about a different instance.
      thresholds: Object.assign(thresholdsFor([CLASS.BROWSE]), {
        'hs_minute_budget_429{role:entitled}': ['rate==0'],
      }),
    };
  }

  if (PROBE === 'p2') {
    const scenarios = {};
    let offset = 0;
    for (const n of P2_STEPS) {
      scenarios[`step_${n}`] = {
        executor: 'constant-vus', vus: n, duration: P2_STEP_DURATION,
        exec: 'cappedReport', startTime: `${offset}s`,
        tags: { step: String(n) },
      };
      offset += parseInt(P2_STEP_DURATION, 10) + 30;  // 30 s gap so heap can settle between steps
    }
    return {
      scenarios: scenarios,
      // No latency thresholds. P2 deliberately drives the report surface past its target to
      // find where the heap goes; failing the run on the latency it is trying to provoke
      // would abort the measurement at the moment it became interesting.
      thresholds: {
        hs_errors_5xx: [{ threshold: 'count==0', abortOnFail: true, delayAbortEval: '30s' }],
      },
    };
  }

  // PROBE L
  return {
    scenarios: {
      over_search: {
        executor: 'constant-arrival-rate',
        rate: Math.round(SEARCH_PER_MINUTE * 2), timeUnit: '1m',
        duration: '1m', preAllocatedVUs: 10, maxVUs: 20, exec: 'entitledSearch',
      },
      over_report: {
        executor: 'constant-arrival-rate',
        rate: Math.round(REPORT_PER_MINUTE * 2), timeUnit: '1m',
        duration: '1m', preAllocatedVUs: 10, maxVUs: 20, exec: 'entitledReport',
      },
    },
    thresholds: {
      // The ONLY assertion: no 5xx. A limiter that answers 500 instead of 429 is a finding
      // about the refusal contract, and it is the thing this probe is really checking.
      hs_errors_5xx: [{ threshold: 'count==0', abortOnFail: true, delayAbortEval: '15s' }],
    },
  };
}

// `url` is dropped from the recorded tags for every script in this harness, because one
// request in it carries a credential in its query string and the raw capture leaves the
// machine (lib/config.js). Applied here too: a probe run is captured the same way, and a
// rule that holds only for the files somebody remembered is not a rule.
export const options = Object.assign(buildOptions(), { systemTags: SYSTEM_TAGS });

export function setup() {
  // THE ENTITLEMENT IS AN INPUT AND IT MUST MATCH THE INSTANCE, so it is printed rather than
  // assumed. Probe P1 asks whether one FULLY ENTITLED principal degrades everybody else;
  // "fully entitled" is the per-principal budget the APPLICATION is configured with
  // (SEARCH_REQUESTS_PER_MINUTE / REPORTS_REQUESTS_PER_MINUTE in the app container's
  // environment, which capture/fingerprint.sh prints). If these disagree with those, the
  // probe still passes or fails — it just answers the question about a different
  // entitlement than the one the box grants, and nothing else in the output would say so.
  console.log("entitlements under test: search=" + SEARCH_PER_MINUTE + "/min, report=" +
    REPORT_PER_MINUTE + "/min. CROSS-CHECK against capture/fingerprint.sh's reading of " +
    "SEARCH_REQUESTS_PER_MINUTE and REPORTS_REQUESTS_PER_MINUTE, and record both in the " +
    "run record. These are set by SEARCH_ENTITLEMENT / REPORT_ENTITLEMENT.");
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

export function entitledSearch(fx) {
  post(`${BASE_URL}/api/workspaces/${fx.wsId}/search`,
    { query: 'text ~ "checkout" ORDER BY created DESC', page: 0, size: 50 },
    CLASS.SEARCH, { probe: PROBE, role: 'entitled' });
}

export function entitledReport(fx) {
  get(`${BASE_URL}/api/workspaces/${fx.wsId}/projects/${fx.bigProjectId}/reports/flow`,
    CLASS.REPORT, { probe: PROBE, role: 'entitled' });
}

export function victimBrowse(fx) {
  const b = `/api/workspaces/${fx.wsId}/projects/${fx.bigProjectId}`;
  const n = randomIssueNumber(fx, fx.bigProjectId);
  get(`${BASE_URL}${b}/issues`, CLASS.BROWSE, { probe: PROBE, role: 'victim' });
  get(`${BASE_URL}${b}/issues/${n}`, CLASS.BROWSE, { probe: PROBE, role: 'victim' });
  get(`${BASE_URL}${b}/issues/${n}/comments`, CLASS.BROWSE, { probe: PROBE, role: 'victim' });
  sleep(thinkSeconds(4, 8));
}

/**
 * A row-level report over the WHOLE history of the 25 000-issue project, so the response
 * materialises the full REPORTS_MAX_ROWS cap and sets meta.truncated. from=1970-01-01
 * rather than the default 90-day window: the default would return a fraction of the rows
 * and the probe would measure a cheap report while claiming to measure a capped one.
 *
 * (The endpoint clamps the window to REPORTS_MAX_WINDOW_DAYS itself, so this asks for more
 * than it can get and takes what the cap gives — which is the point.)
 */
export function cappedReport(fx) {
  const res = get(
    `${BASE_URL}/api/workspaces/${fx.wsId}/projects/${fx.bigProjectId}/reports/cycle-time?from=1970-01-01`,
    CLASS.REPORT, { probe: 'p2', step: String(__VU) });
  if (res.status === 200 && !/"truncated"\s*:\s*true/.test(res.body || '')) {
    // Loud, once, rather than a silent measurement of the wrong thing. If the cap is not
    // binding, P2 is measuring an uncapped report and every heap number it produces
    // answers a question about a case that does not occur.
    console.warn('P2: the report did NOT set meta.truncated — the cap is not binding and ' +
      'this probe is measuring the wrong case. Check the fixture size (acceptance ' +
      'criterion 10) before using these numbers.');
  }
}

export function canaryScenario(fx) {
  canary(fx);
}
