#!/usr/bin/env node
// Stop-hook gate checker for the /feature-pipeline (see docs/design/dev-team-pipeline.md).
// Contract: reads the hook JSON on stdin; inspects .claude/pipeline/run.json.
//   - No run.json / class "trivial" / stop_hook_active  -> ALLOW (exit 0, no output).
//   - Any REQUIRED gate not "pass" (or a class/area under-declaration vs the real
//     git diff)                                         -> BLOCK: print {"decision":"block","reason":...}.
// The hook can only block + remind; it cannot dispatch an agent (engine limit).
// Dependency-free (node built-ins only); runs on every Stop, so keep it fast.

import { readFileSync } from "node:fs";
import { execSync } from "node:child_process";

// Resolve the repo root independently of the shell's current directory — a `cd`
// into a subdir (e.g. src/main/frontend) must not change which run.json/diff we read.
function repoRoot() {
  if (process.env.CLAUDE_PROJECT_DIR) return process.env.CLAUDE_PROJECT_DIR.replace(/\\/g, "/");
  try {
    return execSync("git rev-parse --show-toplevel", { encoding: "utf8" }).trim();
  } catch {
    return process.cwd().replace(/\\/g, "/");
  }
}
const ROOT = repoRoot();
const RUN = `${ROOT}/.claude/pipeline/run.json`;

function allow() { process.exit(0); }                 // let the session finish
function block(reason) {                              // force another orchestrator turn
  process.stdout.write(JSON.stringify({ decision: "block", reason }));
  process.exit(0);
}

// --- 1. read hook input; avoid infinite Stop loops -------------------------
let hook = {};
try { hook = JSON.parse(readFileSync(0, "utf8") || "{}"); } catch { /* no stdin */ }
if (hook.stop_hook_active) allow();  // we already blocked once this turn; don't loop

// --- 2. no active pipeline => don't interfere with ad-hoc chat --------------
let run;
try { run = JSON.parse(readFileSync(RUN, "utf8")); }
catch { allow(); }

const cls = run.class ?? "feature";
const gates = run.gates ?? {};

// --- 3. recompute touched areas from the REAL diff (anti-gaming, Q5) --------
// Trust git, not the orchestrator's self-declared `areas`.
function changedFiles() {
  const out = new Set();
  for (const cmd of [
    "git diff --name-only HEAD",              // staged + unstaged vs HEAD
    "git ls-files --others --exclude-standard" // new untracked files
  ]) {
    try {
      execSync(cmd, { cwd: ROOT, encoding: "utf8" })
        .split("\n").map(s => s.trim()).filter(Boolean).forEach(f => out.add(f));
    } catch { /* no HEAD yet, etc. */ }
  }
  return [...out];
}
function deriveAreas(files) {
  const a = { backend: false, frontend: false, migration: false, config: false, api: false };
  for (const f of files) {
    if (f.startsWith("src/main/frontend/")) a.frontend = true;
    else if (f.startsWith("src/main/java/")) {
      a.backend = true;
      if (/Controller\.java$/.test(f) || f.includes("/controller/") || f.includes("/dto/")) a.api = true;
    }
    if (f.startsWith("src/main/resources/db/migration/")) a.migration = true;
    if (/\.properties$/.test(f) || /(^|\/)docker-compose.*\.ya?ml$/.test(f) ||
        /\.env.*\.example$/.test(f) || /(^|\/)Caddyfile$/.test(f)) a.config = true;
  }
  return a;
}
const files = changedFiles();
const areas = deriveAreas(files);
const touchesCode = areas.backend || areas.frontend || areas.migration || areas.config;

// --- 4. trivial must actually be trivial ------------------------------------
if (cls === "trivial") {
  if (touchesCode)
    block("Run is class=\"trivial\" but the diff touches code " +
      `(${Object.entries(areas).filter(([, v]) => v).map(([k]) => k).join(", ")}). ` +
      "Reclassify to light/feature in .claude/pipeline/run.json and run the required gates.");
  allow();
}

// --- 5. required gate set from class + REAL areas ---------------------------
const required = new Set();
if (cls === "feature") { required.add("spec"); required.add("security"); required.add("tests"); }
if (cls === "light")   { required.add("tests"); }
if (areas.backend)   required.add("tenancy");
if (areas.migration) required.add("migration");
if (areas.config)    required.add("dc_cloud");
if (areas.api)       required.add("api_docs");

// --- 6. evaluate ------------------------------------------------------------
const unmet = [...required].filter(g => gates[g] !== "pass");
if (unmet.length === 0) allow();

block(
  `Pipeline gate(s) not satisfied: ${unmet.map(g => `${g}=${gates[g] ?? "missing"}`).join(", ")}. ` +
  `Real diff touched: ${Object.entries(areas).filter(([, v]) => v).map(([k]) => k).join(", ") || "nothing"}. ` +
  "Dispatch the matching agent(s) — tenancy->tenancy-reviewer, security->security-officer, tests->test-runner, " +
  "migration->migration-reviewer, dc_cloud->dc-cloud-guard, api_docs->api-docs-sync, spec->systems-analyst — " +
  "record each verdict in .claude/pipeline/run.json, then finish. See docs/design/dev-team-pipeline.md."
);
