#!/usr/bin/env node
// Stop-hook gate checker for the /feature-pipeline (see docs/design/dev-team-pipeline.md).
// Contract: reads the hook JSON on stdin; inspects .claude/pipeline/run.json.
//   - No run.json / class "trivial" with no code touched / stop_hook_active -> ALLOW (exit 0, no output).
//   - Any REQUIRED gate not "pass", a class/area under-declaration vs the real git diff,
//     a missing/invalid `category` block (X1), or a tests gate without a negative control (X2)
//                                                        -> BLOCK: print {"decision":"block","reason":...}.
// The hook can only block + remind; it cannot dispatch an agent (engine limit).
// Dependency-free (node built-ins only); runs on every Stop, so keep it fast.
//
// 2026-09-07 (HD-293, retro codification): areas `ops`, `tests`, `frontend_ui`; gates `ops_witness`,
// `ui_qa`; the `category` block; the negative-control field on the tests gate. Rationale:
// docs/retro/2026-09-bug-rca.md — 52 of 194 defects and 6 of 12 CRITs lived in layers this file
// did not classify; 13 guards could not fail; 81 defects were a rule applied to one door.

import { readFileSync, existsSync } from "node:fs";
import { execSync } from "node:child_process";

function repoRoot() {
  if (process.env.CLAUDE_PROJECT_DIR) return process.env.CLAUDE_PROJECT_DIR.replace(/\\/g, "/");
  try { return execSync("git rev-parse --show-toplevel", { encoding: "utf8" }).trim(); }
  catch { return process.cwd().replace(/\\/g, "/"); }
}
const ROOT = repoRoot();
const RUN = process.env.HAMSTRACK_RUN_JSON || `${ROOT}/.claude/pipeline/run.json`;

function allow() { process.exit(0); }
function block(reason) { process.stdout.write(JSON.stringify({ decision: "block", reason })); process.exit(0); }

// --- 1. hook input; avoid infinite Stop loops ---------------------------------------------
let hook = {};
try { hook = JSON.parse(readFileSync(0, "utf8") || "{}"); } catch { /* no stdin */ }
if (hook.stop_hook_active) allow();

// --- 2. no active pipeline => don't interfere with ad-hoc chat ------------------------------
let run;
try { run = JSON.parse(readFileSync(RUN, "utf8")); } catch { allow(); }
const cls = run.class ?? "feature";
const gates = run.gates ?? {};

// --- 3. touched files and areas from the REAL diff (trust git, not the declaration) --------
function sh(cmd) { try { return execSync(cmd, { cwd: ROOT, encoding: "utf8" }); } catch { return ""; } }
function changedFiles() {
  if (process.env.HAMSTRACK_FAKE_FILES) return process.env.HAMSTRACK_FAKE_FILES.split(",").map((s) => s.trim()).filter(Boolean);
  const out = new Set();
  for (const cmd of ["git diff --name-only HEAD", "git ls-files --others --exclude-standard"])
    sh(cmd).split("\n").map((s) => s.trim()).filter(Boolean).forEach((f) => out.add(f));
  return [...out];
}
function addedLines() {
  if (process.env.HAMSTRACK_FAKE_ADDED) return process.env.HAMSTRACK_FAKE_ADDED;
  return sh("git diff -U0 HEAD").split("\n").filter((l) => l.startsWith("+") && !l.startsWith("+++")).join("\n");
}
const OPS_RE = /^(ops\/|observability\/|\.github\/workflows\/|Dockerfile$|Caddyfile$|pom\.xml$|docker-compose[^/]*\.ya?ml$)/;
const TEST_RE = /^src\/test\/|\.test\.(ts|tsx|js|mjs)$|(^|\/)vitest\.config\.[cm]?[jt]s$/;
function deriveAreas(files) {
  const a = { backend: false, frontend: false, frontend_ui: false, migration: false, config: false, api: false, ops: false, tests: false };
  for (const f of files) {
    if (TEST_RE.test(f)) a.tests = true;
    if (f.startsWith("src/main/frontend/") && !TEST_RE.test(f)) {
      a.frontend = true;
      if (/^src\/main\/frontend\/src\/(pages|components)\/|^src\/main\/frontend\/src\/index\.css$/.test(f)) a.frontend_ui = true;
    } else if (f.startsWith("src/main/java/")) {
      a.backend = true;
      if (/Controller\.java$/.test(f) || f.includes("/controller/") || f.includes("/dto/")) a.api = true;
    }
    if (f === "DESIGN.md") a.frontend_ui = true;
    if (f.startsWith("src/main/resources/db/migration/")) a.migration = true;
    if (/\.properties$/.test(f) || /(^|\/)docker-compose[^/]*\.ya?ml$/.test(f) || /\.env[^/]*\.example$/.test(f) || /(^|\/)Caddyfile$/.test(f)) a.config = true;
    if (OPS_RE.test(f)) a.ops = true;
  }
  return a;
}
const files = changedFiles();
const areas = deriveAreas(files);
const touchesCode = areas.backend || areas.frontend || areas.migration || areas.config || areas.ops || areas.tests;
const touched = () => Object.entries(areas).filter(([, v]) => v).map(([k]) => k).join(", ") || "nothing";

// --- 4. trivial must actually be trivial ---------------------------------------------------
if (cls === "trivial") {
  if (touchesCode) block(`Run is class="trivial" but the diff touches ${touched()}. Reclassify to light/feature in .claude/pipeline/run.json and run the required gates.`);
  allow();
}

// --- 5. required gate set from class + REAL areas ------------------------------------------
const required = new Set();
if (cls === "feature") { required.add("spec"); required.add("security"); required.add("tests"); }
if (cls === "light") required.add("tests");
if (areas.backend) required.add("tenancy");
if (areas.migration) required.add("migration");
if (areas.config) required.add("dc_cloud");
if (areas.api) required.add("api_docs");
if (areas.ops) { required.add("ops_witness"); required.add("tests"); }
if (areas.frontend_ui) required.add("ui_qa");
if (areas.tests) required.add("tests");

const AGENT = { tenancy: "tenancy-reviewer", security: "security-officer", tests: "test-runner", migration: "migration-reviewer",
  dc_cloud: "dc-cloud-guard", api_docs: "api-docs-sync", spec: "systems-analyst", ops_witness: "ops-reviewer", ui_qa: "browser-qa" };
const status = (g) => (g && typeof g === "object") ? g.status : g;
const unmet = [...required].filter((g) => status(gates[g]) !== "pass" && status(gates[g]) !== "n/a");
const strictlyUnmet = [...required].filter((g) => status(gates[g]) !== "pass");
// `n/a` is legal for conditional gates the diff did not really arm; never for the class-mandated ones.
const classMandated = new Set(cls === "feature" ? ["spec", "security", "tests"] : cls === "light" ? ["tests"] : []);
const hardUnmet = strictlyUnmet.filter((g) => classMandated.has(g) || unmet.includes(g));
if (hardUnmet.length)
  block(`Gate(s) not satisfied: ${hardUnmet.map((g) => `${g}=${status(gates[g]) ?? "missing"}`).join(", ")}. Real diff touched: ${touched()}. ` +
    `Dispatch ${hardUnmet.map((g) => `${g}->${AGENT[g]}`).join(", ")}, record each verdict in .claude/pipeline/run.json, then finish.`);

// --- 6. X1: the category block — a rule applied to one door is not a legal outcome ------------
const GUARD_RE = /@(Size|Min|Max|NotBlank|NotNull|Pattern|Positive|PositiveOrZero|Email|Scheduled)\(|Locale\.ROOT|requireAndRecord|PerPrincipalMinuteBudget|AfterCommit|afterCommit|require\(Permission\.|mem_limit|@Column\([^)]*length/;
const cat = run.category;
const catShape = (c) => c && typeof c === "object" && (typeof c["n/a"] === "string" && c["n/a"].trim().length > 0
  || (typeof c.rule === "string" && c.rule.trim() && Array.isArray(c.members) && c.members.length > 0 && typeof c.sealedBy === "string" && c.sealedBy.trim()));
if (!catShape(cat))
  block(`run.json has no valid "category" block. Add {"rule": "<the property this change adds>", "members": ["<every door/site/copy it applies to>"], "sealedBy": "<TestClass or *.test.ts>"} ` +
    `or {"n/a": "<why this change adds no rule>"}. The builder fills it from an enumeration of the code, the reviewer verifies the member list.`);
if (!cat["n/a"]) {
  if (cat.members.length === 1 && GUARD_RE.test(addedLines()))
    block(`category.members lists one site (${cat.members[0]}) but the diff adds a bound/guard/rule (${(addedLines().match(GUARD_RE) || [])[0]}). ` +
      `Enumerate the siblings (grep the category) and apply the rule to every member in this change, or list them and name the category test that enumerates them.`);
  const seal = cat.sealedBy.replace(/\.(java|ts|tsx)$/, "");
  const tracked = sh("git ls-files src/test src/main/frontend") + "\n" + sh("git ls-files --others --exclude-standard src/test src/main/frontend");
  const sealFound = tracked.split("\n").some((f) => f.endsWith(`/${seal}.java`) || f.endsWith(`/${seal}.test.ts`) || f.endsWith(`/${seal}.test.tsx`) || f.endsWith(`/${seal}`));
  if (!sealFound && !existsSync(`${ROOT}/${cat.sealedBy}`))
    block(`category.sealedBy names "${cat.sealedBy}" but no such test exists under src/test or src/main/frontend. Write the category test (Doors harness / *CoverageTest shape, with a floor) or name the one that already enumerates these members.`);
}

// --- 7. X2: a tests gate is evidence only with a negative control -----------------------------
if (required.has("tests")) {
  const t = gates.tests;
  const nc = (t && typeof t === "object") ? t.negativeControl : run.negativeControl;
  if (typeof nc !== "string" || !/^(seen|n\/a)\s*:\s*\S/.test(nc))
    block(`gates.tests is "pass" but carries no negative control. Set gates.tests = {"status": "pass", "negativeControl": "seen: <test> red against <what was planted or reverted>"} ` +
      `(or "n/a: <reason>"). test-runner pastes the red line before the green one; a test nobody watched fail is a belief.`);
}

allow();
