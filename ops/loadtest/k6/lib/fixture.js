// HD-186 load harness — resolving the fixture through the API in setup().
// Spec: §7 ("the harness is a CLIENT of the published API and must use only it"), §9.
//
// No id is ever typed into a scenario or an environment variable. Every workspace,
// project, sprint, label, component and version id the mixes use is discovered at run time
// from the published API, using the slug prefix the fixture was generated with.
//
// That is not tidiness. Hand-carried ids are how a harness ends up pointing at a REAL
// workspace after a regenerate — one stale id in a shell profile and the writing mix files
// two thousand issues into a customer's project. Resolution through a slug that only the
// fixture uses makes that impossible to do by accident, and it is also what lets the same
// scripts run against a laptop and against production without a diff (§9).

import http from 'k6/http';
import { BASE_URL, headers } from './config.js';

// The base prefix, without the run id. The fixture's slugs carry a random run id
// (fixture/lib.sh) so the handle cannot be produced by naming a workspace, and this
// matches on the base and reads the rest — so the generator does not have to be told the
// run id, and TWO matching fixtures are an error rather than a coin flip.
export const SLUG_PREFIX = __ENV.LOAD_WS_SLUG_PREFIX || 'hd186-load-';

function getJson(path, token, label) {
  const res = http.get(`${BASE_URL}${path}`, { headers: headers(token) });
  if (res.status !== 200) {
    throw new Error(`setup: GET ${path} returned ${res.status} (${label}). ` +
      `Body: ${String(res.body).slice(0, 300)}`);
  }
  return JSON.parse(res.body);
}

/**
 * A collection endpoint's items, whether it answers with a bare array or a Spring page.
 *
 * NOT cosmetic. The product's collection endpoints are not uniform — `/projects`,
 * `/labels` and `/components` return arrays, `/sprints` returns
 * `{content, page, size, …}` — and setup() assumed an array for all of them. So
 * `sprints.filter(...)` threw `TypeError: Object has no member 'filter'` and EVERY MIX
 * FAILED IN setup(), before a single measured request. It surfaced as a k6 script exception
 * rather than as a bad number, which is the good end of the failure spectrum, but it means
 * this harness could not have been run at all.
 *
 * Reading the shape rather than asserting it is deliberate: which endpoints paginate is the
 * product's decision to change, and a harness that hard-codes today's answer breaks on a
 * release note nobody connected to it. What is NOT tolerated is a shape that is neither —
 * that is a real disagreement about the API and it should stop the run.
 */
function items(x, label) {
  if (Array.isArray(x)) return x;
  if (x && Array.isArray(x.content)) return x.content;
  throw new Error(`setup: ${label} answered with neither an array nor a page ` +
    `({content: [...]}). Got: ${JSON.stringify(x).slice(0, 200)}`);
}

/** Workspaces whose slug is <prefix>[<runid>-]<suffix>, e.g. hd186-load-3f2a-a. */
function matching(workspaces, suffix) {
  const re = new RegExp(`^([a-z0-9]+-)?${suffix}$`);
  return workspaces.filter((w) => w.slug.indexOf(SLUG_PREFIX) === 0
    && re.test(w.slug.slice(SLUG_PREFIX.length)));
}

/**
 * Resolve everything the mixes need, once, in setup(). The return value is serialised to
 * every VU by k6, so it must stay small — ids and counts, never row payloads.
 *
 * Takes THREE principals, because three different claims are being checked and each one is
 * about a different account:
 *
 *   token         a workspace-A LOAD account (accountsA). The subject of every mix.
 *   foreignToken  a workspace-B account (accountsB). Not load: it exists only to prove the
 *                 canary's target EXISTS, which is the half a 404 cannot demonstrate.
 *   canaryToken   the workspace-A account the canary actually asks WITH (accountsCanary).
 *
 * The third used to be the first. That was safe only while the canary's principal WAS
 * accountsA[0]; now that the canary has its own pool — so that no load VU can hold its
 * account and rotate its refresh token — "the requester is not a member of B" has to be
 * asserted about the requester, not about a different account that happens to be in the
 * same workspace. An assertion about the wrong principal is the shape of a canary that
 * passes by construction.
 */
export function resolveFixture(token, foreignToken, canaryToken) {
  const workspaces = items(getJson('/api/workspaces', token, 'workspace list'), 'workspace list');

  const candidatesA = matching(workspaces, 'a');
  if (!candidatesA.length) {
    throw new Error(`no workspace with slug ${SLUG_PREFIX}[<run-id>-]a is visible to this ` +
      `principal. Generate the fixture first (ops/loadtest/fixture/generate.sh).`);
  }
  if (candidatesA.length > 1) {
    throw new Error(`${candidatesA.length} workspaces match ${SLUG_PREFIX}…a ` +
      `(${candidatesA.map((w) => w.slug).join(', ')}). Two fixtures exist; tear the older ` +
      `one down rather than letting the harness pick.`);
  }
  const wsA = candidatesA[0];

  // ---------------------------------------------------------------------------
  // THE CANARY'S TARGET IS RESOLVED HERE, AND BOTH HALVES ARE ASSERTED.
  //
  // What this replaced said, in a comment, that the id was "resolved here through the
  // DIRECT path … a canary that could not have leaked proves nothing" — and then copied an
  // environment variable a human had pasted. Every generation mints new UUIDs, so a stale
  // id from a previous fixture makes every canary request 404 FOR THE BORING REASON, and
  // the run records a clean tenancy result for a check that could not have failed. The
  // only thing asserted downstream was that the string was non-empty.
  //
  // Two assertions, and it needs both:
  //
  //   (a) NOT OURS   — the id must not appear in the A principal's own workspace list. An
  //                    id we are a member of cannot test non-membership.
  //   (b) REAL       — a pinned workspace-B principal must get 200 on it. A 404 that means
  //                    "there is nothing there" proves nothing about refusal.
  //
  // Resolved from B's OWN list, so it is discovered rather than typed. If
  // CANARY_WORKSPACE_ID is also set it is CROSS-CHECKED and a mismatch is fatal — that is
  // the stale-paste case, named.
  // ---------------------------------------------------------------------------
  const foreignWorkspaces = items(getJson('/api/workspaces', foreignToken, 'workspace list (B)'), 'workspace list (B)');
  const candidatesB = matching(foreignWorkspaces, 'b');
  if (!candidatesB.length) {
    throw new Error(`the foreign principal is not a member of any ${SLUG_PREFIX}…b ` +
      `workspace, so the tenancy canary has no target it can prove exists. Re-mint ` +
      `(tokens.json needs accountsB) or re-generate the fixture.`);
  }
  const wsB = candidatesB[0];

  // THE CANARY'S OWN PRINCIPAL, asked about its own memberships. Not the load principal's
  // list: the canary requests with accountsCanary[0], and only that account's memberships
  // decide whether its 404 means anything.
  if (!canaryToken) {
    throw new Error('resolveFixture was called without a canary principal. The canary ' +
      'asks with accountsCanary[0], and its assertion is only falsifiable if THAT account ' +
      'is proved to be a member of A and a non-member of B. Passing the load principal ' +
      'instead makes the check true about somebody who is not asking.');
  }
  const canaryWorkspaces = items(getJson('/api/workspaces', canaryToken, 'workspace list (canary principal)'), 'workspace list (canary principal)');
  if (!canaryWorkspaces.some((w) => w.id === wsA.id)) {
    throw new Error(`the canary principal is not a member of workspace A (${wsA.slug}). It ` +
      `must be: the assertion is that a member of ONE tenant cannot see ANOTHER, and a ` +
      `principal that is a member of nothing would 404 everywhere for the boring reason ` +
      `and report a clean tenancy result it never tested.`);
  }
  if (canaryWorkspaces.some((w) => w.id === wsB.id)) {
    throw new Error(`the canary target ${wsB.id} (${wsB.slug}) is in the CANARY ` +
      `principal's own workspace list. A member cannot test non-membership: this canary ` +
      `would pass by construction. The fixture's two workspaces have merged, or the ` +
      `memberships are wrong.`);
  }
  if (workspaces.some((w) => w.id === wsB.id)) {
    throw new Error(`the canary target ${wsB.id} (${wsB.slug}) is in the LOAD principal's ` +
      `own workspace list. Every mix would then be driving two tenants at once and the ` +
      `fixture's two workspaces have merged.`);
  }

  const declared = __ENV.CANARY_WORKSPACE_ID || '';
  if (declared && declared !== wsB.id) {
    throw new Error(`CANARY_WORKSPACE_ID=${declared} does not match the workspace B ` +
      `resolved through the API (${wsB.id}, slug ${wsB.slug}). Every generation mints new ` +
      `UUIDs, so this is a stale id from a previous fixture — with it the canary would ` +
      `404 for the boring reason and report a tenancy result it never tested. Remove it ` +
      `from the configuration; it is resolved, not typed.`);
  }

  const projects = items(getJson(`/api/workspaces/${wsA.id}/projects`, token, 'project list'), 'project list');
  if (!projects.length) throw new Error('workspace A has no projects');

  // The largest project by key, matching the generator's LDA. Resolved by key rather than
  // by counting issues: counting would make the harness's own choice of subject depend on
  // data the run is about to change.
  const big = projects.find((p) => p.key === 'LDA') || projects[0];

  // PER-PROJECT issue counts, and the cost of four extra setup requests is worth what it
  // buys. The mixes pick a random project and then a random issue NUMBER; with one global
  // count taken from the biggest project, every draw above a small project's size was a
  // 404 — 92% of them on the 2 000-issue project. Those refusals are cheap, correct, and
  // were averaged into the browse latency, which is the same deflation the flat account
  // pool caused. With a count per project, a 404 from a mix is a broken principal or a
  // broken fixture, which is why classes.js can now count one as a failure.
  //
  // OPEN SPRINTS ARE PER PROJECT FOR THE SAME REASON, AND THAT WAS ONE BUG LATER. Only the
  // BIG project's sprints were resolved, while BacklogPage picks a RANDOM project and then
  // asked it for a sprint belonging to LDA — a guaranteed 404, from a correct server, on a
  // read whose latency then landed in `browse`. It is the identical shape to the global
  // issue count above, in the identical function, written at the identical time. So the
  // rule is the shape and not the field: ANY id a mix will use against a project it drew at
  // random must be resolved PER PROJECT. `hs_unexpected_404` is what caught it, on the
  // first end-to-end run.
  const counts = {};
  const sprintsByProject = {};
  for (const p of projects) {
    const board = getJson(`/api/workspaces/${wsA.id}/projects/${p.id}/issues`, token,
      `board (${p.key})`);
    counts[p.id] = board.totalAvailable || board.issues.length;

    const ps = items(getJson(`/api/workspaces/${wsA.id}/projects/${p.id}/sprints`, token,
      `sprints (${p.key})`), `sprints (${p.key})`);
    sprintsByProject[p.id] = ps
      .filter((x) => x.state === 'FUTURE' || x.state === 'ACTIVE')
      .map((x) => x.id);
  }

  const config = getJson(`/api/workspaces/${wsA.id}/projects/${big.id}/config`, token, 'config');
  const sprints = items(getJson(`/api/workspaces/${wsA.id}/projects/${big.id}/sprints`, token, 'sprints'), 'sprints');
  const labels = items(getJson(`/api/workspaces/${wsA.id}/labels`, token, 'labels'), 'labels');
  const components = items(getJson(`/api/workspaces/${wsA.id}/projects/${big.id}/components`, token, 'components'), 'components');

  const openSprints = sprints.filter((s) => s.state === 'FUTURE' || s.state === 'ACTIVE');
  const activeSprint = sprints.find((s) => s.state === 'ACTIVE');

  return {
    wsId: wsA.id,
    canaryWsId: wsB.id,
    projects: projects.map((p) => ({ id: p.id, key: p.key })),
    // The write mix concentrates its ranking in ONE project on purpose (§4.5): the rank
    // rebalance is a PER-PROJECT cooldown, so spreading drags across four projects would
    // divide the pressure by four and the 429 the mix exists to provoke would never fire.
    rankProjectId: big.id,
    bigProjectId: big.id,
    issueCounts: counts,
    issueCount: counts[big.id],
    statusIds: (config.statuses || []).map((s) => s.id),
    typeIds: (config.types || config.issueTypes || []).map((t) => t.id),
    priorityIds: (config.priorities || []).map((p) => p.id),
    labelIds: labels.slice(0, 40).map((l) => l.id),
    componentIds: components.slice(0, 20).map((c) => c.id),
    // The BIG project's open sprints. Used only where the project is also the big one —
    // the write mix's sprint-scope changes, which concentrate there on purpose.
    openSprintIds: openSprints.map((s) => s.id),
    // Open sprints PER PROJECT, for any mix that drew its project at random. See the note
    // beside the per-project issue counts: an id resolved from one project and asked of
    // another is a 404 that is cheap, correct, and averaged into the class's latency.
    openSprintsByProject: sprintsByProject,
    activeSprintId: activeSprint ? activeSprint.id : null,
  };
}

/**
 * A uniformly random issue NUMBER in the given project. Numbers are 1..N by construction.
 *
 * The project id is REQUIRED: drawing from a global maximum and asking a small project for
 * it is how a mix generates thousands of cheap 404s and averages them into its own latency.
 */
export function randomIssueNumber(fx, projectId) {
  const n = (fx.issueCounts && fx.issueCounts[projectId]) || fx.issueCount;
  return 1 + Math.floor(Math.random() * Math.max(1, n));
}

export function pick(arr) {
  if (!arr || !arr.length) return null;
  return arr[Math.floor(Math.random() * arr.length)];
}
