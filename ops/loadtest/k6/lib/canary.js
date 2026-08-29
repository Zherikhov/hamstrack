// HD-186 load harness — the tenancy canary.
// Spec: §4.5 (last row), §5.3 condition 5, and the project's top bug class.
//
// ---------------------------------------------------------------------------
// Every ten seconds: a member of workspace A asks for a resource in workspace B, and — in
// the same iteration — for a workspace id that does not exist at all. ANY STATUS OTHER
// THAN 404, OR ANY DIFFERENCE BETWEEN THE TWO ANSWERS, IS AN ABORT AND A SECURITY
// INCIDENT, NOT A DATA POINT.
//
// This exists because the fixture spans two workspaces, and a load fixture spanning
// several workspaces must not accidentally test cross-tenant reads as if they were normal.
// The cross-tenant request therefore exists EXACTLY ONCE in the whole harness — here — at
// negligible rate, as an assertion. Every other request in every mix is scoped to the
// workspace its principal belongs to.
//
// ---------------------------------------------------------------------------
// WHY TWO REQUESTS AND NOT ONE.
//
// "Non-members get 404" is the weaker half of the rule. The property the product actually
// enforces is that NON-EXISTENCE AND NON-MEMBERSHIP ARE INDISTINGUISHABLE FROM OUTSIDE: a
// 403 would confirm that workspace B exists, which is the leak. A single request asserting
// `status === 404` cannot see the difference between "refused correctly" and "refused
// because there was nothing there" — which is exactly how a canary aimed at a stale id
// passes forever.
//
// So each iteration asks twice: once for the REAL foreign workspace (resolved in setup(),
// and proved to exist there by a pinned workspace-B principal), once for a freshly
// generated UUID that certainly does not exist. Both must be 404, and their bodies must
// agree. A 403 fails just as loudly as a 200 — and that is deliberate, because a reviewer
// skimming this would read "403 is at least a refusal" and be wrong.
//
// It runs ALONGSIDE every mix, as its own scenario, so a leak that only appears under load
// is caught under load. A canary run once in pre-flight would only ever prove that the
// idle instance is correct.

import { sleep } from 'k6';
import { CLASS, canaryLeak } from './classes.js';
import { BASE_URL } from './config.js';
import { authed, canaryPrincipal, isAuthFailure } from './auth.js';

/**
 * The part of a refusal that could carry information the caller did not already have.
 *
 * THE BODIES CANNOT BE COMPARED RAW, AND COMPARING THEM RAW FAILED EVERY RUN. Spring's
 * RFC 9457 ProblemDetail carries `instance`, which is the REQUEST URI — so the two probes'
 * bodies differ in the one field that is guaranteed to differ, by construction, on a
 * perfectly correct server:
 *
 *   foreign: {"detail":"Workspace not found","instance":"/api/workspaces/0198…fff/projects",…}
 *   absent:  {"detail":"Workspace not found","instance":"/api/workspaces/11bd…ead/projects",…}
 *
 * That is not a leak: it is the caller's own request read back to it. A canary that calls it
 * one would abort every window as a security incident, which is exactly what it did the
 * first time this was run end to end — and an assertion that always fires is worth no more
 * than one that never does.
 *
 * So the comparison drops `instance` and neutralises the workspace id the caller itself
 * supplied, and then requires the rest to be IDENTICAL — status, type, title, detail, and
 * any field a future release adds. The property under test is unchanged and is stated
 * positively: A REFUSAL MAY CONTAIN NOTHING THE CALLER DID NOT ALREADY KNOW.
 *
 * ORDERING IS NEUTRALISED AT EVERY LEVEL, AND THE ONE-LEVEL VERSION HID THE LEAK CLASS THIS
 * EXISTS FOR. It used to sort keys with `JSON.stringify(j, Object.keys(j).sort())`, whose
 * second argument is a REPLACER APPLIED AT EVERY DEPTH: a nested object keeps only the
 * properties named in that top-level list, and since a nested object's own keys are not in
 * it, every nested object serialised as `{}`. So a ProblemDetail that grew an extension —
 *
 *   {"detail":"Workspace not found","context":{"workspaceName":"acme-b"}}
 *
 * — would compare EQUAL between the foreign probe and the absent-id probe, both reduced to
 * `{"context":{},"detail":"Workspace not found"}`, and the canary would report no leak while
 * one tenant's workspace name was handed to another. A canonicaliser that discards the
 * payload cannot witness the payload. This one recurses.
 */
function canonicalise(v) {
  if (Array.isArray(v)) return v.map(canonicalise);
  if (v && typeof v === 'object') {
    const out = {};
    for (const k of Object.keys(v).sort()) out[k] = canonicalise(v[k]);
    return out;
  }
  return v;
}

function comparable(body, wsId) {
  let text = String(body);
  try {
    const j = JSON.parse(text);
    if (j && typeof j === 'object' && !Array.isArray(j)) delete j.instance;
    // No replacer: the ordering is already normalised by canonicalise(), which REBUILDS the
    // object with sorted keys instead of filtering it.
    text = JSON.stringify(canonicalise(j));
  } catch (e) {
    // Not JSON. Compared raw, which is the stricter answer and the right one for a shape
    // nobody predicted.
  }
  return text.split(String(wsId)).join('{the-id-the-caller-sent}');
}

/** A random v4-shaped UUID. Its only requirement is that no workspace has it. */
function randomUuid() {
  const hex = '0123456789abcdef';
  let s = '';
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) s += '-';
    else if (i === 14) s += '4';
    else s += hex[Math.floor(Math.random() * 16)];
  }
  return s;
}

function ask(wsId, what) {
  // authed(), with an EXPLICIT principal from the canary's OWN pool. The canary is its own
  // one-VU scenario and the VU number k6 gives it is bookkeeping, not identity: deriving
  // the principal from __VU is what once put a workspace-B MEMBER behind this request,
  // which then received a correct 200 and aborted the window as a security incident.
  // Pinning it to accountsA[0] fixed that and left a second route open — a load VU held the
  // same account, and /api/auth/refresh rotates, so whichever spent the token first left
  // the other unable to authenticate. accountsCanary is a family of addresses no load VU
  // can draw.
  return authed('GET', `${BASE_URL}/api/workspaces/${wsId}/projects`, null, CLASS.CANARY,
    { probe: what }, canaryPrincipal());
}

/**
 * The canary scenario body. Exported as a k6 scenario `exec` target by every mix.
 *
 * The target path is a workspace-scoped LIST, not a single resource: a list is resolved
 * through the same membership check as everything else, and it cannot 404 for the boring
 * reason that one id happened not to exist. If this ever returns 200 with an empty array,
 * that is a leak too — the assertion is on the STATUS, and an empty 200 fails it.
 */
export function canary(fx) {
  const wsB = fx && fx.canaryWsId;
  if (!wsB) {
    // Refusing to run silently is the point. A canary with no target is a canary that
    // cannot fail, and a run that reports "no tenancy problem" on the strength of a check
    // that never executed is worse than one with no check at all.
    throw new Error(
      'the tenancy canary has no target. It is resolved in setup() from a workspace-B ' +
      'principal and asserted to be both real and foreign (lib/fixture.js); reaching here ' +
      'means setup() returned without it, and the run would report a clean tenancy result ' +
      'it never tested.');
  }

  const ghostId = randomUuid();
  const real = ask(wsB, 'foreign');
  const ghost = ask(ghostId, 'absent');

  // A RESPONSE PRODUCED BY THE AUTH PATH IS NEVER ALLOWED TO STAND IN FOR THE PROBE'S OWN.
  //
  // When authed() cannot authenticate it has no answer to the URL it was asked about, so it
  // returns /api/auth/refresh's response — with a mark. Without reading that mark this
  // function saw a 400 from an already-spent refresh token and reported
  // "GET /api/workspaces/{B}/projects returned 400, expected 404": a SECURITY INCIDENT
  // declared against a server that had done nothing wrong, aborting the window under
  // exactly the conditions the window exists to reach. A canary that cannot tell "refused"
  // from "could not authenticate" manufactures incidents.
  //
  // The failure is not swallowed: authed() has already counted hs_auth_failures, whose own
  // threshold (count==0, abortOnFail) stops the run and names the real cause. This branch
  // only declines to ALSO call it a leak.
  if (isAuthFailure(real) || isAuthFailure(ghost)) {
    console.error(
      `TENANCY CANARY COULD NOT AUTHENTICATE — this is NOT a tenancy result and NOT a ` +
      `security incident. The canary principal's access token expired and its refresh ` +
      `token in tokens.json had already been spent (refresh ROTATES: one use, ever, per ` +
      `file). Counted as hs_auth_failures, which aborts the run on its own. Fix the cause: ` +
      `run k6/refresh-tokens.js between stages, or re-mint — see k6/lib/auth.js point 3.`);
    sleep(10);
    return;
  }

  if (real.status !== 404) {
    canaryLeak.add(1, { status: String(real.status), probe: 'foreign' });
    console.error(
      `TENANCY CANARY FAILED: GET /api/workspaces/${wsB}/projects returned ${real.status}, ` +
      `expected 404. STOP THE RUN, PRESERVE EVERYTHING, AND TREAT THIS AS A SECURITY ` +
      `INCIDENT (§5.3 condition 5) — it is the one abort that is not about capacity. ` +
      `Body: ${String(real.body).slice(0, 200)}`);
  }

  // The comparison, which is the half a status assertion cannot make. Bodies are compared
  // whole: a detail that names the workspace in one case and not the other is a leak
  // whose status code is identical.
  const realBody = comparable(real.body, wsB);
  const ghostBody = comparable(ghost.body, ghostId);
  if (real.status !== ghost.status || realBody !== ghostBody) {
    canaryLeak.add(1, { status: String(real.status), probe: 'distinguishable' });
    console.error(
      `TENANCY CANARY FAILED: a workspace that EXISTS but is not ours answered differently ` +
      `from one that does not exist. ${real.status} vs ${ghost.status}. That difference IS ` +
      `the information leak — non-existence and non-membership must be indistinguishable ` +
      `from outside (§5.3 condition 5). The comparison already drops \`instance\` and the id ` +
      `the caller itself sent, so what differs below is something the SERVER added. ` +
      `foreign: ${realBody.slice(0, 160)} | absent: ${ghostBody.slice(0, 160)}`);
  }

  sleep(10);
}

// Each mix declares its own `canary` scenario (executor: constant-vus, vus: 1,
// duration: STAGE) and exports a `canaryScenario` that calls the function above. It is one
// VU OUTSIDE the ladder, so escalating a mix never changes the canary's rate and a 429 on
// the canary could never be mistaken for a tenancy result.
//
// There is deliberately no shared "build the scenario block" helper here. k6 resolves
// `exec` against the names a SCRIPT exports, not against this module, so a factory would
// have to return a string naming a function it cannot see — which is the shape of a
// canary that silently does not run.
