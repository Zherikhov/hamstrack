// HD-186 load harness — endpoint classes, custom metrics and the §4.3 thresholds.
// Spec: §4.3, §4.8, acceptance criteria 3, 4 and 5.
//
// THE TARGETS ARE FIXED HERE, BEFORE THE RUN, AND THEY ARE EXECUTABLE. A target invented
// after seeing the graphs is not a target; a target written in a design document is a
// discipline. Expressed as k6 thresholds it becomes a mechanism: the process exits
// non-zero when a target is breached, and the verdict does not depend on anybody reading a
// chart.
//
// Every threshold is scoped to `phase:hold` (see config.js). The ramp is warm-up.

import { Counter, Rate, Trend } from 'k6/metrics';
import { phase } from './config.js';

// ---------------------------------------------------------------------------
// The classes. Latency is reported per endpoint CLASS, not as one aggregate, because
// "the concurrency at which the target is breached" is a question about a class: a
// browsing p95 and a report p95 averaged together describe nobody's experience.
// ---------------------------------------------------------------------------
export const CLASS = {
  // DELIBERATELY COMPOSED ONLY OF READS THE PRODUCT DOES NOT BUDGET, which is a property of
  // the class and not a claim about the product. That distinction is the whole reason PLANNING
  // below exists: `browse` used to include the two .../backlog/sections/... reads, and its
  // threshold rationale said "nothing in the product budgets ordinary browsing" - true when
  // written, false the moment HD-174 gave the planning surface a budget and a place in the
  // expensive-read bulkhead. A harness whose failure rationale is false is worse than one with
  // no rationale, and probe P1 is where it bites: P1's success criterion is "the VICTIM's browse
  // class stays inside its target", so a victim class containing a bounded endpoint makes a P1
  // pass stop meaning what it says.
  //
  // SO THE RULE FOR WHOEVER ADDS AN ENDPOINT HERE: if the product budgets it per principal, or
  // it takes a permit from the expensive-read share, it does not belong in `browse`.
  BROWSE: 'browse',   // board, issue detail, comments, history, config, catalogs, shell poll
  PLANNING: 'planning', // GET .../backlog and its section reads - budgeted AND occupancy-bounded
  SEARCH: 'search',   // POST /search, /search/schema, /search/suggest, saved-filter CRUD
  REPORT: 'report',   // the project reports, their .csv siblings, insights
  WRITE: 'write',     // issue PATCH, transition, comment, rank, sprint scope change
  AUTH: 'auth',       // login, refresh
  SSE: 'sse',         // the held event stream
  CANARY: 'canary',   // the cross-tenant assertion
};

// ---------------------------------------------------------------------------
// Custom metrics.
//
// http_req_failed is deliberately NOT the error signal here. It counts any non-2xx/3xx,
// which folds a correct 429 refusal, a correct 409 and a correct 404 into the same number
// as a 500 — and the whole point of the 0.17.0 work is that saturation produces NAMED
// refusals rather than silence or 500s. So each shape is counted separately and each one
// gets its own budget.
// ---------------------------------------------------------------------------

// A 5xx is a FINDING, not a budget item. HighErrorRate's 5% is a paging threshold, not a
// target: the target is zero, and any 5xx is written up individually in the results.
export const errors5xx = new Counter('hs_errors_5xx');

// 422 STATEMENT_BUDGET_EXCEEDED. Zero at target concurrency is what lets the run answer
// "at what concurrency does the statement bound begin to fire" — the FIRST 422 is a data
// point and a stage boundary, not noise to be averaged away.
export const budget422 = new Counter('hs_budget_422');

// 429s, split by what they mean. `kind` is the attribution (§4.8): a per-principal budget,
// a per-IP auth budget and a per-project rank-rebalance cooldown are three different
// findings and only the kind tells them apart.
export const refused429 = new Rate('hs_refused_429');
export const rebalance429 = new Rate('hs_rebalance_429');

// The two 429s HD-182 added, split from the per-minute budgets they share a status with.
// A refusal here says "too many of these were RUNNING", not "you asked too often", and the
// two answer different questions about an instance: a minute-budget refusal at an entitled
// rate means the harness and the box disagree about the entitlement, while an occupancy
// refusal at that same rate is the bulkhead doing its job. Both are still counted in
// hs_refused_429 above — a refused user is refused — so the ladder's breach point is
// unaffected; these two are ATTRIBUTION, and they are separate Rates rather than a `kind`
// tag on one because a Rate needs its false samples in the same sub-metric, and a tag that
// only exists on the true ones makes every rate read 1.
export const occupancy429 = new Rate('hs_occupancy_429');
export const minuteBudget429 = new Rate('hs_minute_budget_429');

// 409 + Retry-After on writes: row-lock contention. A retryable refusal is a correct
// answer at low rates; above about one in a hundred the product is asking users to retry
// more often than they will tolerate.
export const conflict409 = new Rate('hs_conflict_409');

// The tenancy canary. ANY status other than 404 aborts the run and is a security incident,
// not a data point (§4.5, §5.3 condition 5).
export const canaryLeak = new Counter('hs_canary_leak');

// A VU that could not re-authenticate. §4.4: one refresh on a 401, then fail loudly. A
// silent auth failure would empty the load while every latency number kept looking fine.
export const authFailures = new Counter('hs_auth_failures');

// A 404 FROM A MIX IS A BROKEN PRINCIPAL, NOT A DATA POINT.
//
// Every request a mix makes is for a resource its own principal owns: the workspace is
// resolved from that principal's list, the project from that workspace, the issue number
// from THAT project's count. There is no legitimate 404 in that set, so one means the
// harness is asking the wrong server the wrong question — and this used to be counted as
// nothing at all, which is how two separate defects stayed invisible:
//
//   * a flat account pool put workspace-B credentials on ~15 of the browsing VUs above 60,
//     and every request they made 404'd correctly;
//   * a single global issue count made the mixes ask small projects for issue numbers that
//     only exist in the big one — 92% of reads against the 2 000-issue project.
//
// Both produce fast, cheap, correct refusals that were averaged into
// http_req_duration{class:browse} and DEFLATED the p95/p99 the breach point is read from.
// A capacity number computed from a fleet that is mostly 404ing is not conservative, it is
// wrong in the flattering direction.
//
// Excludes the canary, whose 404 is the assertion, and the auth class, whose 404s belong
// to a request nobody's principal owns.
export const unexpected404 = new Counter('hs_unexpected_404');

// Response bytes per class — the cheap proxy for "how much did the server materialise",
// and the only client-side signal that speaks to probe P2's heap question at all.
export const respBytes = new Trend('hs_response_bytes');

// ---------------------------------------------------------------------------
// The §4.3 table, encoded.
//
// Anchored where possible to opinions this project has ALREADY SHIPPED in
// observability/grafana/provisioning/alerting/rules.yml, so they are not one more thing
// invented in a design document:
//
//   * The aggregate ceiling is the project's own HighLatency rule (HTTP p95 > 1 s for
//     10 minutes). The per-class numbers are chosen so the mix-weighted p95 stays under it
//     at target concurrency, browsing dominating the volume. A target looser than an alert
//     we already ship would declare acceptable a state that pages an operator.
//   * The report p99 sits deliberately BELOW DB_STATEMENT_TIMEOUT_MS (10 s). A p99 above
//     the statement bound cannot exist without the instance cancelling statements, so a
//     target above it would contradict the refusal contract 0.17.0 shipped.
// ---------------------------------------------------------------------------
const LATENCY = {
  [CLASS.BROWSE]: { p95: 600, p99: 1500 },
  // Looser than browse and tighter than search: one planning aggregate assembles
  // (open sprints + 1) capped sections in a single read-only transaction spanning 12+N
  // statements, so it is the most expensive read on any page a person opens by clicking a
  // nav link. NOT anchored to a measurement - THERE IS NONE YET, which is stated rather than
  // hidden: every occupancy estimate in HD-174 rests on "a planning response is short in the
  // healthy case" and nobody has taken the number. Replacing this target with a measured one
  // is the deliverable in RESULTS-TEMPLATE.md, and the browse ladder already exercises the
  // endpoints.
  [CLASS.PLANNING]: { p95: 1200, p99: 3000 },
  [CLASS.SEARCH]: { p95: 1500, p99: 4000 },
  [CLASS.REPORT]: { p95: 3000, p99: 8000 },
  [CLASS.WRITE]: { p95: 800, p99: 2500 },
  [CLASS.AUTH]: { p95: 1000, p99: 3000 },
};

/**
 * Thresholds for the classes a given scenario actually exercises.
 *
 * Passing the class list rather than always emitting all five is load-bearing: k6 fails a
 * threshold whose sub-metric received no samples in some configurations, and — worse — a
 * threshold on an empty class silently reads as "passed", so a browsing run that emitted
 * the report thresholds would report a report verdict it never measured.
 *
 * AND THE CLASS LIST DECIDES WHETHER THE VACUOUS-THRESHOLD SEAL CAN SEE ANYTHING AT ALL.
 * The seal demands samples only from Rate and Trend sub-metrics (a Counter's clean outcome
 * is an empty metric), and k6 materialises a sub-metric for every key it is DECLARED, so an
 * "absent from the summary" branch never fires. A mix whose only {phase:hold} keys are a
 * Counter and a metric tagged by the REQUEST rather than by record() therefore has no
 * witness for a record()-tagging regression. Any class added below must keep at least one
 * Rate or Trend {phase:hold} key that the mix genuinely exercises.
 */
export function thresholdsFor(classes) {
  const t = {
    // The harness's own saturation is on the same footing as every other resource (section
    // 4.8, last row). A harness that cannot detect its own saturation reports the
    // generator's limits as the product's, and there is no way to tell from the numbers
    // afterwards.
    dropped_iterations: [{ threshold: 'count==0', abortOnFail: true, delayAbortEval: '30s' }],

    // A 5xx is not a budget item. abortOnFail because the run's premise — that saturation
    // produces named refusals — has failed, and everything measured after it is measured
    // against a different product than the one being described.
    hs_errors_5xx: [{ threshold: 'count==0', abortOnFail: true, delayAbortEval: '30s' }],

    // Not a capacity threshold. This is the security abort (section 5.3 condition 5): stop,
    // preserve everything, and treat it as an incident. delayAbortEval is short because the
    // canary runs at 1 request per 10 s and there is nothing to average.
    hs_canary_leak: [{ threshold: 'count==0', abortOnFail: true, delayAbortEval: '15s' }],

    // A fleet that has quietly stopped authenticating still produces beautiful latency.
    hs_auth_failures: [{ threshold: 'count==0', abortOnFail: true, delayAbortEval: '60s' }],

    // A 404 FROM A MIX IS A BROKEN PRINCIPAL, AND THE RAMP GETS NO ALLOWANCE — read on.
    //
    // WHAT delayAbortEval DOES AND WHAT IT DOES NOT DO. It delays WHEN the verdict is
    // taken; it does not change WHAT is counted. These counters are cumulative over the
    // whole run, so a single 404 at t=5s still fails 'count==0' the moment the delay
    // elapses. The comment that used to sit here — "delayAbortEval covers the ramp, where a
    // VU can briefly outrun setup()'s view" — described a tolerance that has never existed.
    //
    // So the tolerance is expressed where it can actually be expressed: with the PHASE TAG
    // (record() emits it on every custom-metric sample). Two keys, two different jobs:
    //
    //   hs_unexpected_404              the STAGE VERDICT. Any 404 anywhere, ramp included,
    //                                  fails the stage — because it means the harness asked
    //                                  the wrong server the wrong question and the stage's
    //                                  latency is not about the product. It does NOT abort:
    //                                  the stage still finishes and its evidence is kept.
    //   hs_unexpected_404{phase:hold}  the ABORT. During the hold, every second the run
    //                                  continues makes the numbers the capacity is read
    //                                  from less true, so it stops immediately.
    //
    // Kept as an abort deliberately (and not demoted to a budget): a 404 storm is
    // undetectable after the fact, a lost stage costs six minutes, and the budgets that
    // could have recovered the distinction were themselves vacuous until record() started
    // tagging `phase`.
    hs_unexpected_404: [{ threshold: 'count==0' }],
    'hs_unexpected_404{phase:hold}': [{ threshold: 'count==0', abortOnFail: true, delayAbortEval: '15s' }],
  };

  for (const c of classes) {
    const l = LATENCY[c];
    if (l) {
      t[`http_req_duration{class:${c},phase:hold}`] = [`p(95)<${l.p95}`, `p(99)<${l.p99}`];
      // EVERY CLASS WITH A LATENCY TARGET ALSO DECLARES ITS REFUSAL RATE, AND THAT KEY DOES
      // TWO JOBS.
      //
      // (1) THE TARGET, AND WHAT IT MEANS DEPENDS ON THE CLASS.
      //
      //     For `browse` and `write` it is a finding about the CONFIGURATION IN FRONT OF THE
      //     APP (a limiter, a proxy, the edge): those two classes are composed only of
      //     requests the product does not budget per principal, so a 429 there is not the
      //     product working. Read that as a property of how the classes are COMPOSED, not as
      //     a claim about the product - the claim version ("nothing in the product budgets
      //     ordinary browsing") was true when written and false one release later, when
      //     HD-174 budgeted the planning reads that used to live in `browse`.
      //
      //     For `planning`, `search` and `report` a 429 CAN be the product working as
      //     designed - a per-principal minute budget or the expensive-read occupancy share.
      //     1% is still the target, because at these arrival rates the harness is two orders
      //     of magnitude below every entitlement (a browse VU thinks 4-8 s and rolls into the
      //     backlog page 25% of the time: ~5 planning requests a minute against 240). If the
      //     rate rises above 1%, read hs_occupancy_429 and hs_minute_budget_429 before
      //     concluding anything: the first says the SHARE was full (the bulkhead doing its
      //     job, or an under-provisioned box), the second says the harness and the box
      //     disagree about the entitlement.
      //
      // (2) THE SEAL'S WITNESS, AND THIS IS WHY IT IS DECLARED PER CLASS RATHER THAN ONLY
      //     WHERE A BUDGET EXISTS. run-ladder.sh proves no declared threshold is vacuous by
      //     demanding that each key resolved to a sub-metric WITH SAMPLES. Two properties
      //     of that seal decide what it can see:
      //
      //       * k6 (measured on v2.2.0) MATERIALISES a sub-metric for every threshold key it
      //         is DECLARED, even when no sample ever carried those tags — so the "absent
      //         from the summary" branch cannot fire, and a sabotaged summary is
      //         byte-identical to a clean one for such a key
      //         ({"count":0,"rate":0,"thresholds":{"count==0":false}});
      //       * a Counter is deliberately exempt from the sample demand, because its correct
      //         outcome IS an empty metric.
      //
      //     So only a Rate or a Trend keyed on {phase:hold} can witness the defect the seal
      //     exists for. Before this line the browse mix had none: its only {phase:hold} keys
      //     were http_req_duration{class:browse,phase:hold} — tagged by the REQUEST in
      //     lib/auth.js, not by record() — and the hs_unexpected_404 counter. Deleting
      //     `phase` from record()'s tag object below therefore gave exit 0 on an entire
      //     browse ladder and exit 6 on the write mix: the seal worked on some mixes and not
      //     others, which is worse than not having it, because the ladder that ran first is
      //     the one that reports it is fine.
      //
      //     hs_refused_429 is not a metric manufactured for the seal. record() calls
      //     refused429.add(false) on EVERY non-429 response, so this sub-metric takes a
      //     sample from every class a mix exercises, in a read-only mix as much as in a
      //     writing one — which is exactly the property a witness needs.
      t[`hs_refused_429{class:${c},phase:hold}`] = ['rate<0.01'];
    }
    if (c === CLASS.SEARCH || c === CLASS.REPORT) {
      t[`hs_budget_422{class:${c}}`] = ['count==0'];
    }
    if (c === CLASS.PLANNING) {
      // The witness that says WHICH refusal a planning 429 was. hs_refused_429 above folds
      // both, deliberately - a refused user is refused - so this is attribution and not a
      // second budget. It is a Rate that record() samples on every response, so it takes its
      // false samples from the same mix and cannot read 1 by construction.
      t[`hs_occupancy_429{class:${c},phase:hold}`] = ['rate<0.01'];
    }
    if (c === CLASS.WRITE) {
      t['hs_conflict_409{phase:hold}'] = ['rate<0.01'];
      t['hs_rebalance_429{phase:hold}'] = ['rate<0.02'];
    }
  }
  return t;
}

/**
 * Classify one response and feed every counter the attribution table (§4.8) reads.
 *
 * Called on EVERY response in the harness, including the ones a scenario does not care
 * about, because a refusal counted in one place and not another is how a run reports "no
 * 429s" about a stage that was throttled.
 */
export function record(res, cls, tags) {
  // `phase` IS NOT OPTIONAL HERE, AND ITS ABSENCE WAS INVISIBLE.
  //
  // A custom-metric sample carries ONLY the tags passed to .add() — k6 does not fold the
  // request's tags into it. So while every threshold below is scoped to {phase:hold}, this
  // function emitted every hs_* sample with no phase at all, and four thresholds
  // (hs_refused_429{class:search|report,phase:hold}, hs_conflict_409{phase:hold},
  // hs_rebalance_429{phase:hold}) selected a sub-metric that could never receive one.
  // Depending on the k6 version that is a vacuous pass or a spurious no-data failure — and
  // it is exactly the trap thresholdsFor() warns about eighteen lines above.
  //
  // Sealed by run-ladder.sh's vacuous-threshold seal, not by this comment — and the seal
  // can only see the defect on a mix that declares a Rate or Trend key scoped to
  // {phase:hold}. That is why every class with a latency target now declares
  // hs_refused_429{class:…,phase:hold} as well: with the browse mix's earlier key set,
  // deleting `phase` from the line below passed a whole browse ladder (exit 0) while
  // failing the write mix (exit 6). Measured on k6 v2.2.0.
  const t = Object.assign({ class: cls, phase: phase() }, tags || {});
  respBytes.add(res.body ? res.body.length : 0, t);

  if (res.status >= 500) {
    errors5xx.add(1, t);
    return;
  }

  // See hs_unexpected_404 above. The canary's 404 IS its assertion, and `auth` covers
  // requests made before a principal is established — everything else asks only for what
  // its own principal owns, so a 404 there is the harness, not the product.
  if (res.status === 404 && cls !== CLASS.CANARY && cls !== CLASS.AUTH) {
    unexpected404.add(1, t);
    // NAME THE REQUEST. The threshold aborts the stage saying "the harness is asking the
    // wrong server the wrong question" and used to leave the reader with a count and no
    // question — on a stage that has just been stopped, whose raw capture deliberately does
    // NOT carry `url` (lib/config.js). One line here is the difference between a diagnosis
    // and a re-run with print statements.
    //
    // The token is stripped because the SSE endpoint authenticates by query parameter and
    // this string goes to the console and into the stage log, which is attached to a ticket.
    const where = String((res.request && res.request.url) || "(url unavailable)")
      .replace(/([?&]token=)[^&]*/g, "$1REDACTED");
    console.error("UNEXPECTED 404 [class=" + cls + "]: " +
      ((res.request && res.request.method) || "?") + " " + where +
      " — every request a mix makes is for a resource its OWN principal owns, so this is " +
      "the harness, not the product. See hs_unexpected_404 in lib/classes.js.");
  }

  const isRebalance = res.status === 429 && /rank|rebalance/i.test(res.body || '');
  const isOccupancy = res.status === 429 &&
    /TOO_MANY_IN_FLIGHT|EXPENSIVE_SURFACE_BUSY/.test(res.body || '');
  refused429.add(res.status === 429 && !isRebalance, t);
  // Sampled on EVERY response, like refused429 and for the same reason: a Rate whose false
  // samples live somewhere else is a Rate that always reads 1.
  occupancy429.add(isOccupancy, t);
  minuteBudget429.add(res.status === 429 && !isRebalance && !isOccupancy, t);
  if (cls === CLASS.WRITE) {
    rebalance429.add(isRebalance, t);
    conflict409.add(res.status === 409, t);
  }
  if (res.status === 422 && /STATEMENT_BUDGET_EXCEEDED/.test(res.body || '')) {
    budget422.add(1, t);
  }
}
