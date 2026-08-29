// HD-186 load harness — shared configuration.
// Spec: docs/design/load-capacity-measurement-proposal.md §4.1, §4.3, §9.
//
// Everything here comes from the environment. There is no AWS in this file, no SSM, no
// Cloudflare, and no assumption about a reverse proxy: pointing the harness at
// http://localhost:8080 and a local docker Postgres works unchanged, which is how it is
// developed and how the teardown is rehearsed (§9). A harness that could only run against
// one company's box would be a Cloud-only tool wearing a portable name — and the useful
// consequence of it not being one is that a self-hoster can run this to size their own
// box, which is the DC half of the answer this ticket owes.

import exec from 'k6/execution';

function required(name) {
  const v = __ENV[name];
  if (!v) throw new Error(`${name} is required — see ops/loadtest/README.md`);
  return v;
}

// The origin, NOT the edge. The production site is proxied by Cloudflare; a sustained
// synthetic burst through the edge measures Cloudflare's caching, connection reuse and bot
// handling, and risks being challenged by a system whose configuration is not ours. Point
// BASE_URL at the origin and set HOST_HEADER to the public hostname so the app's routing,
// cookies and absolute URLs behave exactly as they do for a real request.
//
// THE PUBLISHED CAPACITY IS ORIGIN CAPACITY. Record that the edge was excluded: Cloudflare
// only ever adds latency and removes load, so an origin figure is the conservative one.
export const BASE_URL = required('BASE_URL').replace(/\/+$/, '');
export const HOST_HEADER = __ENV.HOST_HEADER || '';

// Ladder position. run-ladder.sh invokes k6 once per stage rather than encoding the whole
// ladder in one run, because §4.3 defines the reported capacity as "the highest completed
// stage at which every target held FOR THE WHOLE HOLD PERIOD". One k6 run per stage makes
// that a mechanism: the stage's own thresholds decide its own exit code.
export const VUS = Number(__ENV.VUS || 1);
export const RAMP = __ENV.RAMP || '1m';
export const HOLD = __ENV.HOLD || '4m';
export const RAMP_MS = durationMs(RAMP);

// The whole stage, as a k6 duration string. The canary and any other always-on scenario
// is sized from this rather than from a literal, so a stage run with a longer hold does
// not end with a canary that stopped watching four minutes in — a canary that stops early
// still reports "no leak" for the part of the stage it did not see.
export const STAGE = `${Math.round((RAMP_MS + durationMs(HOLD)) / 1000)}s`;

export const SEED_TAG = __ENV.RUN_TAG || 'hd186';

// ---------------------------------------------------------------------------
// THE RAW CAPTURE IS SCRUBBED BY CONSTRUCTION, NOT BY REMEMBERING.
//
// run-ladder.sh records every stage with `--out json=…`, and that file is attached to the
// ticket. k6's DEFAULT system tags include `url`, and the SSE endpoint authenticates by
// QUERY PARAMETER because EventSource cannot set headers (browse.js) — so the default set
// puts up to 100 principals' live access tokens into an artefact that leaves the machine.
//
// `url` is dropped here, for every script, rather than being remembered at each call site.
// `name` is kept because a metric with no request identity is not attributable — and
// because k6 DEFAULTS `name` TO THE URL, the one request that carries a token in its query
// string sets an explicit `name` tag of its own. That is the whole mechanism: one
// omission in this list plus one tag at that call site.
//
// Everything else is k6's default set. Listing it explicitly is the price of removing one
// entry, and a k6 upgrade that adds a new default tag will not add it here silently —
// which is the right direction for a file whose output is published.
export const SYSTEM_TAGS = [
  'proto', 'subproto', 'status', 'method', 'name', 'group', 'check',
  'error', 'error_code', 'tls_version', 'scenario', 'service', 'expected_response',
];

function durationMs(d) {
  const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(d);
  if (!m) throw new Error(`unparseable duration: ${d}`);
  return Number(m[1]) * { ms: 1, s: 1e3, m: 6e4, h: 36e5 }[m[2]];
}

// The ramp is warm-up and its numbers are reported but NOT used for the breach
// determination (§5.4): "a stage that breaches on the harness's own warm-up" is prevented
// by requiring the target to hold for the whole hold, not on average across ramp + hold.
// Every request carries this tag and every threshold below is scoped to phase:hold.
export function phase() {
  return exec.instance.currentTestRunDuration > RAMP_MS ? 'hold' : 'ramp';
}

export function headers(token, extra) {
  const h = Object.assign({ Authorization: `Bearer ${token}` }, extra || {});
  if (HOST_HEADER) h.Host = HOST_HEADER;
  return h;
}

export function jsonHeaders(token) {
  return headers(token, { 'Content-Type': 'application/json' });
}

// Log-normal-ish think time in seconds. A virtual user with think time is a person, and
// the primary question is "how many people" (§4.5). Uniform think time would synchronise
// the fleet into a lockstep the product never sees.
export function thinkSeconds(lo, hi) {
  const u = Math.random();
  return lo + (hi - lo) * Math.pow(u, 1.6);
}
