// HD-186 load harness — advance every account's token chain, between ladder stages.
// Spec: §4.4(3), §5.2 (pre-flight phase).
//
//   export LOAD_PASSWORD=…                      # not needed here; kept out on purpose
//   k6 run k6/refresh-tokens.js \
//     -e BASE_URL=http://origin:8080 -e TOKENS_FILE=/var/tmp/hd186/tokens.json
//
// Reads tokens.json, spends every refresh token in it exactly once, and writes the file
// back with the new access/refresh pairs. Takes about a minute for 227 accounts.
//
// ---------------------------------------------------------------------------
// WHY THIS EXISTS: A LADDER IS A SEQUENCE OF PROCESSES AND A REFRESH TOKEN IS SINGLE-USE.
//
// AuthService.refresh ROTATES — it deletes the presented row and issues a new one. So the
// string in tokens.json can be spent exactly once, ever, by exactly one holder. Every k6
// process seeds its VUs from that same string (lib/auth.js), and run-ladder.sh runs ONE k6
// PROCESS PER STAGE over about sixty-six minutes against thirty-minute access tokens.
//
// The consequence was not theoretical and it was invisible in the summary: the first stage
// to cross the half hour refreshed, rotating every account's token; every later stage then
// presented a string the server had already deleted, got 400/401, and aborted on
// hs_auth_failures. THE UPPER HALF OF THE LADDER — WHERE THE CAPACITY NUMBER COMES FROM —
// WAS UNMEASURABLE. lib/auth.js stated the single-use property but scoped it to a MIX; the
// failure is per STAGE.
//
// So the invariant is: ONE REFRESH PER ACCOUNT, EVER, PER tokens.json — and this script is
// what produces the next tokens.json. run-ladder.sh refuses to start a stage that would run
// past the expiry and names this file.
//
// ---------------------------------------------------------------------------
// WHY THIS IS FAST AND MINTING IS NOT.
//
// /api/auth/login is in the auth filter's URL set at RATE_LIMIT_AUTH_IP_PER_MINUTE (15/min)
// keyed on the peer address, so mint-tokens.js throttles itself to 10 logins a minute and
// takes about twenty-three minutes. /api/auth/refresh is deliberately OUTSIDE that URL set
// (SecurityConfig permits it; AuthRateLimitFilter does not cover it) and is cookie-driven,
// so this pass costs about a minute.
//
// It is still throttled — REFRESHES_PER_SECOND, default 5 — because this runs between
// stages on a box that is under measurement, and a two-hundred-request burst against the
// instance in the settle window would be a small extra load nobody accounted for.
//
// ---------------------------------------------------------------------------
// IT REFUSES RATHER THAN WRITING A PARTIAL FILE.
//
// If any account cannot be refreshed, its chain is broken and the only repair is a re-mint:
// there is no way to recover a rotated token from the client side. Writing back a file with
// N-1 usable accounts would silently shrink the pool below the peak VU count and produce,
// one stage later, a "VU 208 has no principal" that names the wrong cause. So a single
// failure fails the whole pass, keeps the old file, and says what to run.

import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, HOST_HEADER, SYSTEM_TAGS } from './lib/config.js';

// THE PREDICATE MATCHES THE SENTENCE. It used to test only PRESENCE while its own message
// said "must be ABSOLUTE", and the gap is not cosmetic: with a relative value this script
// READS through open() — resolved against k6/ — and WRITES through handleSummary — resolved
// against the WORKING DIRECTORY. So it reads the real file, spends every account's
// single-use refresh token, and deposits the freshly rotated credential somewhere else
// entirely. Run from the harness root, "somewhere else" is INSIDE the synced ops/ tree,
// where .gitignore then hides it from `git status` — a live credential for ~227 accounts,
// in the repository, invisible. Lifted verbatim from mint-tokens.js, which already had it.
const TOKENS_FILE = __ENV.TOKENS_FILE;
if (!TOKENS_FILE || (TOKENS_FILE[0] !== '/' && !/^[A-Za-z]:[\\/]/.test(TOKENS_FILE))) {
  throw new Error('TOKENS_FILE must be set to an ABSOLUTE path. This script reads and ' +
    'rewrites a credential file for ~227 accounts, and k6 resolves the read (open(), ' +
    'against the SCRIPT) and the write (handleSummary, against the WORKING DIRECTORY) with ' +
    'two different bases — so a relative path spends the chain from one file and writes the ' +
    'replacement to another. Example: TOKENS_FILE=/var/tmp/hd186/tokens.json');
}
const PER_SECOND = Number(__ENV.REFRESHES_PER_SECOND || 5);
const GAP = 1 / PER_SECOND;

const SOURCE = JSON.parse(open(TOKENS_FILE));

export const options = {
  // The work is setup()'s, for the reason spelled out in mint-tokens.js: handleSummary is
  // the only place k6 may write a file, and it CANNOT see module-scope or globalThis state
  // set by the default function — only `data.setup_data`, which is setup()'s return value.
  setupTimeout: '30m',
  scenarios: {
    refresh: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '5m' },
  },
  // No latency thresholds: this runs between stages and its timings describe a deliberately
  // throttled client, not the instance.
  thresholds: {},
  // `url` is dropped for every script in the harness (lib/config.js). Nothing here carries a
  // credential in a query string, and that is not the reason the line is here: the tag set
  // is a property of the output, and the output of this script can be recorded like any
  // other k6 run.
  systemTags: SYSTEM_TAGS,
};

/**
 * Spend one account's refresh token and return a NEW account record.
 *
 * The cookie is set on the VU's jar at the server's own path (ResponseCookie … .path
 * ("/api/auth")) and cleared afterwards, so the next account cannot present the previous
 * account's cookie and have it rotated out from under its owner — the same reason
 * mint-tokens.js clears the jar between logins.
 */
function refreshOne(acct, failures) {
  const jar = http.cookieJar();
  jar.set(`${BASE_URL}/api/auth`, 'refresh_token', acct.refreshToken, { path: '/api/auth' });

  const headers = HOST_HEADER ? { Host: HOST_HEADER } : {};
  const res = http.post(`${BASE_URL}/api/auth/refresh`, null,
    { headers: headers, tags: { class: 'auth', op: 'chain-refresh' } });

  let out = null;
  if (res.status !== 200) {
    failures.push({ email: acct.email, status: res.status });
    console.error(`refresh failed for ${acct.email}: HTTP ${res.status} ` +
      `${String(res.body).slice(0, 200)}`);
  } else {
    const body = JSON.parse(res.body);
    const cookies = jar.cookiesForURL(`${BASE_URL}/api/auth/refresh`);
    const next = cookies.refresh_token ? cookies.refresh_token[0] : null;
    if (!next) {
      failures.push({ email: acct.email, status: 'no-cookie' });
      console.error(`no refresh_token cookie returned for ${acct.email} — the chain for ` +
        `this account ends here and cannot be continued from the client side`);
    } else {
      out = {
        email: acct.email,
        userId: acct.userId,
        accessToken: body.accessToken,
        refreshToken: next,
        expiresIn: body.expiresIn,
      };
    }
  }

  jar.clear(`${BASE_URL}/api/auth`);
  sleep(GAP);
  return out;
}

function refreshAll(list, failures, label) {
  const out = [];
  for (let i = 0; i < (list || []).length; i++) {
    const a = refreshOne(list[i], failures);
    if (a) out.push(a);
    if ((i + 1) % 25 === 0) console.log(`  ${label} ${i + 1}/${list.length}`);
  }
  return out;
}

export function setup() {
  if (!SOURCE.accountsA || !SOURCE.accountsCanary || !SOURCE.accountsB) {
    throw new Error(`${TOKENS_FILE} does not carry all three pools (accountsA, ` +
      `accountsCanary, accountsB). Re-mint with k6/mint-tokens.js: the pools are separate ` +
      `so that no load VU can hold the canary's account and rotate its single-use refresh ` +
      `token, and a file predating that split cannot be repaired by refreshing it.`);
  }

  const total = SOURCE.accountsA.length + SOURCE.accountsCanary.length + SOURCE.accountsB.length;
  console.log(`advancing the token chain for ${total} accounts at ${PER_SECOND}/s ` +
    `(~${Math.ceil((total * GAP) / 60)} min) against ${BASE_URL}`);

  const failures = [];
  const accountsA = refreshAll(SOURCE.accountsA, failures, 'A');
  const accountsCanary = refreshAll(SOURCE.accountsCanary, failures, 'canary');
  const accountsB = refreshAll(SOURCE.accountsB, failures, 'B');

  if (failures.length) {
    // No partial write. See the header: a shorter pool fails one stage later, with a
    // message that names the wrong cause.
    throw new Error(
      `${failures.length} of ${total} account(s) could not refresh, so ${TOKENS_FILE} has ` +
      `NOT been rewritten and the old file is still on disk — but the accounts that DID ` +
      `refresh have already rotated, which means that file is now stale for them too. ` +
      `A rotated token cannot be recovered from the client side, so the repair is a ` +
      `re-mint: k6 run k6/mint-tokens.js. First failures: ` +
      `${failures.slice(0, 5).map((f) => f.email + '=' + f.status).join(', ')}. ` +
      `A 400/401 here means the chain had already been spent — by a stage that ran past ` +
      `the access-token expiry, or by a second copy of this script.`);
  }

  const out = {
    mintedAt: new Date().toISOString(),
    baseUrl: BASE_URL,
    accessTokenLifetimeSeconds: accountsA.length ? accountsA[0].expiresIn
      : SOURCE.accessTokenLifetimeSeconds,
    poolSize: accountsA.length,
    canaryPoolSize: accountsCanary.length,
    foreignPoolSize: accountsB.length,
    // Carried forward so the run record can still say when the ACCOUNTS were created, as
    // distinct from when this file's tokens were issued. run-ladder.sh's expiry guard reads
    // mintedAt above, which is the one that moved.
    originallyMintedAt: SOURCE.originallyMintedAt || SOURCE.mintedAt,
    failures: [],
    accountsA: accountsA,
    accountsCanary: accountsCanary,
    accountsB: accountsB,
  };
  console.log(`refreshed ${accountsA.length} A + ${accountsCanary.length} canary + ` +
    `${accountsB.length} B`);
  return out;
}

// Nothing to do per iteration; see the note on options above.
export default function () {}

export function handleSummary(data) {
  const out = data && data.setup_data;
  if (!out || !out.poolSize) {
    // setup() threw, or produced nothing. Writing here would overwrite a working tokens.json
    // with an empty one — which is unrecoverable, because the tokens it held have already
    // been rotated.
    return { stdout: '\n  tokens.json NOT rewritten — the refresh pass failed above.\n\n' };
  }
  const summary = {};
  summary[TOKENS_FILE] = JSON.stringify(out, null, 2);
  return Object.assign(summary, {
    stdout: `\n  rewrote ${TOKENS_FILE} — ${out.poolSize} A + ${out.canaryPoolSize} canary ` +
      `+ ${out.foreignPoolSize} B\n` +
      `  New access tokens expire in ${out.accessTokenLifetimeSeconds}s from ` +
      `${out.mintedAt}.\n` +
      `  The PREVIOUS tokens in this file are now dead: refresh rotates, and this pass\n` +
      `  spent them. Any other copy of the old file is worthless, and any k6 process still\n` +
      `  running with it will start failing hs_auth_failures.\n\n`,
  });
}
