// HD-186 load harness — the WRITING mix.
// Spec: §4.5 row 3.
//
//   k6 run k6/write.js -e BASE_URL=... -e VUS=10 -e TOKENS_FILE=/abs/path/tokens.json
//
// The question: this is THE ONLY MIX THAT TAKES ROW LOCKS, so the only one that can
// produce 409 + Retry-After — and, via the concentrated ranking, the per-project rebalance
// 429.
//
// ---------------------------------------------------------------------------
// Composition, and why each share is what it is (§4.5):
//
//   80%  distinct-issue PATCH / transition / comment.  DISTINCT is the operative word: a
//        mix that hammered one issue would measure lock convoy on a single row, which is a
//        different (and much rarer) phenomenon than the ordinary contention a team
//        produces. Each iteration picks a fresh issue number.
//   15%  drag-to-rank, CONCENTRATED IN ONE PROJECT. The rank rebalance is a PER-PROJECT
//        cooldown, so spreading drags across four projects would divide the pressure by
//        four and the 429 this share exists to provoke would never fire.
//    5%  sprint scope changes.
//
// ---------------------------------------------------------------------------
// TWO THINGS THIS MIX MUST NOT DO, both of which are easy to do by accident.
//
// 1. IT NEVER SENDS `position`. issues.position is server-written only, computed from
//    neighbour anchors (afterIssueId / beforeIssueId) and never accepted from a client
//    payload. A harness that wrote it would be exercising a path the product does not have.
//
// 2. IT SENDS OPTIONAL FIELDS BOXED-SHAPED, i.e. it OMITS what it does not mean. Jackson 3
//    has FAIL_ON_NULL_FOR_PRIMITIVES on, and the request records coalesce null to a
//    default in their canonical constructors — so omitting a flag is correct and sending
//    an explicit null is also correct, but sending `clearAssignee: false` alongside an
//    `assigneeId` is a contradiction the server has to resolve. Omission is the honest
//    partial update and it is what the SPA sends.
//
// ---------------------------------------------------------------------------
// A 409 IS A RESULT, NOT AN ERROR. The optimistic-lock refusal is the product working:
// §4.3 budgets it at 1% because above roughly one in a hundred the product is asking users
// to retry more often than they will tolerate. The mix therefore does NOT retry a 409 —
// retrying would hide the very rate the target is about.

import { sleep } from 'k6';
import { BASE_URL, VUS, RAMP, HOLD, STAGE, SYSTEM_TAGS, thinkSeconds } from './lib/config.js';
import { CLASS, thresholdsFor } from './lib/classes.js';
import { get, post, patch, accounts, canaryAccounts, canaryOwnAccounts, canaryPrincipal, requirePrincipals } from './lib/auth.js';
import { resolveFixture, randomIssueNumber, pick } from './lib/fixture.js';
import { canary } from './lib/canary.js';

export const options = {
  scenarios: {
    write: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [{ duration: RAMP, target: VUS }, { duration: HOLD, target: VUS }],
      exec: 'writeScenario',
      gracefulRampDown: '30s',
    },
    canary: {
      executor: 'constant-vus', vus: 1, duration: STAGE,
      exec: 'canaryScenario', startTime: '0s',
    },
  },
  // BROWSE is declared too: every write is preceded by the read that supplies the
  // @Version, exactly as the SPA does, and that read's latency belongs to `browse`. Left
  // untagged it would be charged to `write` and the write target would breach for a reason
  // that is not writing.
  thresholds: thresholdsFor([CLASS.WRITE, CLASS.BROWSE]),
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

/** Read an issue and return {number, id, version}, or null if it could not be read. */
function loadIssue(fx, projectId) {
  // Against THIS project's issue count. A number drawn from the biggest project and asked
  // of a small one is a 404, and a write mix whose reads mostly 404 does no writing while
  // its browse class reports excellent latency.
  const n = randomIssueNumber(fx, projectId);
  const res = get(`${BASE_URL}/api/workspaces/${fx.wsId}/projects/${projectId}/issues/${n}`,
    CLASS.BROWSE, { op: 'read-for-write' });
  if (res.status !== 200) return null;
  try {
    const b = JSON.parse(res.body);
    return { number: b.number, id: b.id, version: b.version };
  } catch (e) {
    return null;
  }
}

function editIssue(fx) {
  const p = pick(fx.projects).id;
  const issue = loadIssue(fx, p);
  if (!issue) return;
  const b = `/api/workspaces/${fx.wsId}/projects/${p}/issues/${issue.number}`;

  const roll = Math.random();
  if (roll < 0.35) {
    // A transition: the status change is the write that moves the workflow, writes history
    // and (on a move into DONE) stamps closed_at.
    patch(`${BASE_URL}${b}`,
      { statusId: pick(fx.statusIds), version: issue.version },
      CLASS.WRITE, { op: 'transition' });
  } else if (roll < 0.70) {
    // An ordinary field edit. Title only — deliberately small, because the question here is
    // the LOCK and the history write, not how fast Postgres stores a paragraph.
    patch(`${BASE_URL}${b}`,
      { title: `Load edit ${__VU}-${Date.now() % 100000}`, version: issue.version },
      CLASS.WRITE, { op: 'edit' });
  } else {
    post(`${BASE_URL}${b}/comments`,
      { body: `Load comment from VU ${__VU} at ${new Date().toISOString()}` },
      CLASS.WRITE, { op: 'comment' });
  }
}

function rankIssue(fx) {
  // ONE project, always: fx.rankProjectId. The cooldown is per project.
  const p = fx.rankProjectId;
  const issue = loadIssue(fx, p);
  if (!issue) return;

  // A neighbour to drop after. Reading it is how the SPA gets an anchor id, and it is the
  // read a drag actually performs.
  const anchor = loadIssue(fx, p);
  if (!anchor || anchor.id === issue.id) return;

  post(`${BASE_URL}/api/workspaces/${fx.wsId}/projects/${p}/issues/${issue.number}/rank`,
    // afterIssueId only. Never `position`: the rank is computed server-side from neighbour
    // anchors and is not exposed in a response, let alone accepted in a request.
    { afterIssueId: anchor.id, version: issue.version },
    CLASS.WRITE, { op: 'rank' });
}

function sprintScope(fx) {
  const p = fx.rankProjectId;
  const issue = loadIssue(fx, p);
  if (!issue) return;
  const s = pick(fx.openSprintIds);
  if (!s) return;

  // Moving an issue into a sprint writes a sprint_scope_event. The rank is preserved
  // either way, which is why this is a scope change and not a rank change.
  patch(`${BASE_URL}/api/workspaces/${fx.wsId}/projects/${p}/issues/${issue.number}`,
    { sprintId: s, version: issue.version },
    CLASS.WRITE, { op: 'sprint-scope' });
}

export function writeScenario(fx) {
  const roll = Math.random();
  if (roll < 0.80) editIssue(fx);
  else if (roll < 0.95) rankIssue(fx);
  else sprintScope(fx);

  sleep(thinkSeconds(10, 20));
}

export function canaryScenario(fx) {
  canary(fx);
}
