#!/usr/bin/env node
// Stop-hook gate checker for the /feature-pipeline (see docs/design/dev-team-pipeline.md).
// Contract: reads the hook JSON on stdin; inspects .claude/pipeline/run.json.
//   - No run.json / class "trivial" with no code touched                 -> ALLOW (exit 0, no output).
//   - Any REQUIRED gate not "pass", a class/area under-declaration vs the real git diff,
//     a missing/invalid `category` block (X1), or a tests gate without a negative control (X2)
//                                                        -> BLOCK: print {"decision":"block","reason":...}.
//   - stop_hook_active (the Stop after a block, until the next user turn) -> evaluate and RECORD,
//     never block: a would-block finish is appended with verdict "escaped" (HD-303, D6).
// The hook can only block + remind; it cannot dispatch an agent (engine limit).
// Dependency-free (node built-ins only); runs on every Stop, so keep it fast.
//
// 2026-09-07 (HD-293, retro codification): areas `ops`, `tests`, `frontend_ui`; gates `ops_witness`,
// `ui_qa`; the `category` block; the negative-control field on the tests gate. Rationale:
// docs/retro/2026-09-bug-rca.md — 52 of 194 defects and 6 of 12 CRITs lived in layers this file
// did not classify; 13 guards could not fail; 81 defects were a rule applied to one door.
// 2026-09-08 (HD-303): every finishing exit appends one line to .claude/pipeline/history.jsonl
// (gitignored, append-only) — `checkpoint.mjs` turns it into the two-week numbers (§12.6 of the
// pipeline doc). The table of exits and what each records is sealed by check-gates.test.mjs.
// 2026-10-08 (HD-330): ONE value shape for every field this file judges — `class` and every judged
// gate take a bare token or {"status": "<token>", "note": "<evidence>"}, the form `gates.tests` has
// always used. Three refusals were misdiagnoses and are now their own exits: a prose gate value was
// reported as an unrun gate and prescribed a re-dispatch (B8); an unrecognised `class` silently
// became "feature" (B7); a malformed `category` sub-field was reported as a missing block (B3 now
// names the field). `category.sealedBy` takes a list, resolves every member, and names only the
// ones that did not resolve — and it searches the WHOLE repository, so a change to the pipeline can
// name its own seal (see resolveSeal). Measured over the 123 lines of history.jsonl: the shape rule
// newly refuses 0 runs — all 20 records carrying a prose value on a judged gate were already
// refused, so this ticket changed messages, never verdicts.

import { readFileSync, existsSync, appendFileSync, openSync, readSync, closeSync, fstatSync } from "node:fs";
import { execSync } from "node:child_process";
import { createHash } from "node:crypto";

function repoRoot() {
  if (process.env.CLAUDE_PROJECT_DIR) return process.env.CLAUDE_PROJECT_DIR.replace(/\\/g, "/");
  try { return execSync("git rev-parse --show-toplevel", { encoding: "utf8" }).trim(); }
  catch { return process.cwd().replace(/\\/g, "/"); }
}
const ROOT = repoRoot();
const RUN = process.env.HAMSTRACK_RUN_JSON || `${ROOT}/.claude/pipeline/run.json`;
// Fixture isolation (HD-303 §4.4): a run under any HAMSTRACK_* override never reaches the real history
// unless it names its own file. `null` means "record nothing" — and say so on stderr, never silently.
const FIXTURE = ["HAMSTRACK_RUN_JSON", "HAMSTRACK_FAKE_FILES", "HAMSTRACK_FAKE_ADDED"].some((k) => k in process.env);
const HISTORY = "HAMSTRACK_HISTORY" in process.env ? (process.env.HAMSTRACK_HISTORY || null) : (FIXTURE ? null : `${ROOT}/.claude/pipeline/history.jsonl`);
const OVERRIDES = Object.keys(process.env).filter((k) => k.startsWith("HAMSTRACK_")).sort();

// --- 1. hook input; the Stop after a block carries stop_hook_active — evaluate, record, never block ----
let hook = {};
try { hook = JSON.parse(readFileSync(0, "utf8") || "{}") ?? {}; } catch { /* no stdin */ }
const LOOP_GUARD = Boolean(hook && hook.stop_hook_active);

function allow() { process.exit(0); }
function finish(verdict, unmet) { record(verdict, unmet); process.exit(0); }
function block(reason) {
  if (LOOP_GUARD) finish("escaped", [reason.split("\n")[0].slice(0, 240)]);
  process.stdout.write(JSON.stringify({ decision: "block", reason })); process.exit(0);
}

// --- 2. no active pipeline => don't interfere with ad-hoc chat ------------------------------
let run;
try { run = JSON.parse(readFileSync(RUN, "utf8")); } catch { run = null; }
if (!run || typeof run !== "object") allow();
const gates = (run.gates && typeof run.gates === "object") ? run.gates : {};

// --- 2b. ONE value shape, for `class` and for every gate this file judges (HD-330) --------------
// A judged value is a bare verdict token, or an object carrying that token plus its evidence:
// {"status": "pass", "note": "…"} — the shape `gates.tests` has carried since HD-293. Prose is
// NEVER parsed into a token. That is not fastidiousness: history.jsonl holds the values
// `"FAIL (first pass) - RE-REVIEW IN FLIGHT"` and `"pass: … The verdict stays FAIL until
// ops-reviewer says otherwise; it is not the orchestrator's to flip."` — reading the leading word
// of free text would make this hook an oracle that answers the opposite of its own evidence. So a
// non-token value is refused as a SHAPE fault with the note's destination named (§5b), never as an
// unrun gate. Values are judged only where the hook judges the field; a gate the diff did not arm
// is recorded verbatim and never parsed, which is why this rule refuses none of the 123 existing
// records (measured 2026-10-08: 20 carried prose on a judged gate and all 20 were already refused).
const VERDICT_TOKEN = /^[a-z][a-z0-9_/-]{0,15}$/;
const isObj = (v) => Boolean(v) && typeof v === "object" && !Array.isArray(v);
const statusOf = (v) => (isObj(v) ? v.status : v);
const token = (v) => { const s = statusOf(v); return typeof s === "string" && VERDICT_TOKEN.test(s.trim()) ? s.trim() : undefined; };
const declared = (v) => v !== undefined && v !== null && !(typeof v === "string" && !v.trim());
const malformed = (v) => declared(v) && token(v) === undefined;
// The only hint a shape refusal may offer: the first word-run, folded, when it IS a known verdict.
const VERDICTS = ["pass", "fail", "pending", "n/a"];
const suspect = (v) => { const s = statusOf(v); const m = typeof s === "string" ? s.match(/[A-Za-z/]+/) : null;
  const t = m && m[0].toLowerCase(); return VERDICTS.includes(t) ? t : null; };
// Quoted always, so an empty or blank value is visible in the refusal rather than reading as absent.
const show = (v) => { const s = JSON.stringify(v) ?? String(v); return s.length > 64 ? s.slice(0, 61) + "…\"" : s; };

const CLASSES = ["feature", "light", "trivial"];
const cls = declared(run.class) ? (token(run.class) ?? null) : "feature";

// --- 3. touched files and areas from the REAL diff (trust git, not the declaration) --------
function sh(cmd) { try { return execSync(cmd, { cwd: ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }); } catch { return ""; } }
function changedFiles() {
  if (process.env.HAMSTRACK_FAKE_FILES !== undefined) return process.env.HAMSTRACK_FAKE_FILES.split(",").map((s) => s.trim()).filter(Boolean);
  const out = new Set();
  for (const cmd of ["git diff --name-only HEAD", "git ls-files --others --exclude-standard"])
    sh(cmd).split("\n").map((s) => s.trim()).filter(Boolean).forEach((f) => out.add(f));
  return [...out];
}
let ADDED;                                    // memoised: `git diff -U0 HEAD` once, not once per reader
function addedLines() {
  if (ADDED !== undefined) return ADDED;
  if (process.env.HAMSTRACK_FAKE_ADDED !== undefined) return (ADDED = process.env.HAMSTRACK_FAKE_ADDED);
  return (ADDED = sh("git diff -U0 HEAD").split("\n").filter((l) => l.startsWith("+") && !l.startsWith("+++")).join("\n"));
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

// --- history: one immutable line per finish (HD-303 §4.2–4.3) ---------------------------------
function sortedJson(v) {
  if (Array.isArray(v)) return "[" + v.map(sortedJson).join(",") + "]";
  if (v && typeof v === "object") return "{" + Object.keys(v).sort().map((k) => JSON.stringify(k) + ":" + sortedJson(v[k])).join(",") + "}";
  return JSON.stringify(v);
}
function tail(path) {
  // The last 64 KiB, complete object lines only — never the whole file. A torn or foreign line is skipped, and the
  // next append starts on a fresh line so it cannot glue onto a fragment.
  const records = [];
  let endsWithNewline = true, fd;
  try {
    fd = openSync(path, "r");
    const size = fstatSync(fd).size, len = Math.min(size, 65536), buf = Buffer.alloc(len);
    readSync(fd, buf, 0, len, size - len);
    endsWithNewline = size === 0 || buf[len - 1] === 0x0a;
    const lines = buf.toString("utf8").split("\n");
    if (len < size) lines.shift();
    for (const l of lines) { if (!l.trim()) continue; try { const v = JSON.parse(l); if (v && typeof v === "object") records.push(v); } catch { /* torn or foreign line */ } }
  } catch { /* no file yet */ } finally { if (fd !== undefined) closeSync(fd); }
  return { records, endsWithNewline };
}
// The negative control has ONE resolution, read by the recorder and by X2 (it was two copies that
// agreed by hand). The gate object's own field wins; the top-level `run.negativeControl` is the
// fallback for BOTH forms — before HD-330 an object-shaped `tests` could not reach it, so adding a
// `note` to that gate would have moved a passing run's control out of sight.
function negativeControl() {
  const t = gates.tests;
  const nc = (isObj(t) ? t.negativeControl : undefined) ?? run.negativeControl;
  return typeof nc === "string" ? nc : null;
}
function record(verdict, unmet) {
  if (!HISTORY) { process.stderr.write(`check-gates: history not recorded — override in env: ${OVERRIDES.join(", ") || "HAMSTRACK_HISTORY empty"}\n`); return; }
  try {
    const nc = negativeControl();
    const rec = {
      schema: 1, ts: new Date().toISOString(), task: typeof run.task === "string" ? run.task : String(run.task ?? ""), class: cls,
      head: sh("git rev-parse HEAD").trim().match(/^[0-9a-f]{40}$/)?.[0] ?? "",
      touched: Object.entries(areas).filter(([, v]) => v).map(([k]) => k),
      gates, category: cls === "trivial" ? null : (run.category ?? null),
      negativeControl: nc, verdict,
    };
    if (verdict === "escaped") rec.unmet = unmet ?? [];
    rec.runHash = createHash("sha256").update(sortedJson(run)).digest("hex");
    const { records, endsWithNewline } = tail(HISTORY);
    if (records.some((r) => r.runHash === rec.runHash && r.verdict === rec.verdict)) return;
    appendFileSync(HISTORY, (endsWithNewline ? "" : "\n") + JSON.stringify(rec) + "\n");
  } catch (e) { process.stderr.write(`check-gates: history not written (${e.code ?? e.message})\n`); }
}

// --- 4. the class is one of three tokens -----------------------------------------------------
// Placed here, not beside `cls`: block() records under the loop guard and the recorder reads
// `areas`. Before HD-330 an unrecognised `class` fell through `?? "feature"` and the run was then
// refused for a gate the author never meant to owe — a silent coercion whose witness was a refusal
// about something else. A typo'd class now names itself.
if (cls === null || !CLASSES.includes(cls))
  block(`run.json "class" is ${show(run.class)}, which is not one of ${CLASSES.join(" | ")}` +
    `${cls === null ? ` (a class is a bare token, or {"status": "<token>", "note": "<why>"}; it is never read out of prose)` : ""}. ` +
    `Set .claude/pipeline/run.json "class" to the one that matches the diff — the hook derives the required gate set from it and from the real diff, and will not guess it for you.`);

// --- 4b. trivial must actually be trivial ---------------------------------------------------
if (cls === "trivial") {
  if (touchesCode) block(`Run is class="trivial" but the diff touches ${touched()}. Reclassify to light/feature in .claude/pipeline/run.json and run the required gates.`);
  finish("pass");
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
// --- 5b. X8: a value that is not a verdict is a SHAPE fault, never an unrun gate --------------
// The two refusals are deliberately distinct and deliberately both blocking. A gate whose value is
// prose has very often been RUN — the misdiagnosis this replaces told its reader to dispatch an
// agent that had already reported, which is a refusal prescribing an action its reader must not
// perform. The real "not run" refusal below is untouched.
const judged = [...required];
const badShape = judged.filter((g) => malformed(gates[g]));
if (badShape.length) {
  const alsoUnmet = judged.filter((g) => !badShape.includes(g) && token(gates[g]) !== "pass" && token(gates[g]) !== "n/a");
  block(`${badShape.length} of ${judged.length} judged gate value(s) are not a verdict: ` +
    badShape.map((g) => `${g}=${show(gates[g])}${suspect(gates[g]) ? ` (reads as "${suspect(gates[g])}" + a note)` : ""}`).join("; ") + `. ` +
    `This is a SHAPE fault, not an unrun gate — do NOT re-dispatch ${badShape.map((g) => AGENT[g]).join(", ")} on this message alone. ` +
    `A gate value is a bare token (${VERDICTS.join(" | ")}) or {"status": "<token>", "note": "<the evidence, a round number, a link>"}; ` +
    `the note belongs in "note", and the hook never reads a verdict out of prose. Edit .claude/pipeline/run.json and finish again.` +
    (alsoUnmet.length ? ` Still unmet once the shape is fixed: ${alsoUnmet.join(", ")}.` : ""));
}

const unmet = judged.filter((g) => token(gates[g]) !== "pass" && token(gates[g]) !== "n/a");
const strictlyUnmet = judged.filter((g) => token(gates[g]) !== "pass");
// `n/a` is legal for conditional gates the diff did not really arm; never for the class-mandated ones.
const classMandated = new Set(cls === "feature" ? ["spec", "security", "tests"] : cls === "light" ? ["tests"] : []);
const hardUnmet = strictlyUnmet.filter((g) => classMandated.has(g) || unmet.includes(g));
if (hardUnmet.length)
  block(`Gate(s) not satisfied: ${hardUnmet.map((g) => `${g}=${token(gates[g]) ?? "missing"}`).join(", ")}. Real diff touched: ${touched()}. ` +
    `Dispatch ${hardUnmet.map((g) => `${g}->${AGENT[g]}`).join(", ")}, record each verdict in .claude/pipeline/run.json, then finish.`);

// --- 6. X1: the category block — a rule applied to one door is not a legal outcome ------------
const GUARD_RE = /@(Size|Min|Max|NotBlank|NotNull|Pattern|Positive|PositiveOrZero|Email|Scheduled)\(|Locale\.ROOT|requireAndRecord|PerPrincipalMinuteBudget|AfterCommit|afterCommit|require\(Permission\.|mem_limit|@Column\([^)]*length/;
const cat = run.category;
// A seal may be named once, as one comma-separated string, or as a JSON array — HD-314 was held by
// three tests and had to demote two of them into prose because this took a single name. Splitting on
// a comma is safe: no seal name in this tree contains one (they are `Class`, `Class#method`, a
// `*.test.*` file name, or a repo-relative path).
const sealNames = (s) => (Array.isArray(s) ? s : typeof s === "string" ? s.split(",") : [])
  .map((x) => (typeof x === "string" ? x.trim() : "")).filter(Boolean);
// Which fields the block must carry, each fault named as itself. Before HD-330 all of these — a
// `members` string, an array `sealedBy`, a blank `n/a` — produced one generic "no valid category
// block" that never said which field was wrong.
function categoryFaults(c) {
  if (!isObj(c)) return [`the block is missing or is not a JSON object (got ${show(c)})`];
  if (typeof c["n/a"] === "string" && c["n/a"].trim()) return [];
  if ("n/a" in c) return [`"n/a" must be a non-empty string saying why this change adds no rule (got ${show(c["n/a"])})`];
  const f = [];
  if (!(typeof c.rule === "string" && c.rule.trim())) f.push(`"rule" must be a non-empty string naming the property this change adds (got ${show(c.rule)})`);
  if (!Array.isArray(c.members)) f.push(`"members" must be a JSON array of names (got ${show(c.members)}) — it is never split on commas, because a member name may contain one`);
  else if (!c.members.length) f.push(`"members" is empty: list every door/site/copy the rule applies to`);
  if (!sealNames(c.sealedBy).length) f.push(`"sealedBy" must name at least one test — a string, a comma-separated string, or an array (got ${show(c.sealedBy)})`);
  return f;
}
const catFaults = categoryFaults(cat);
if (catFaults.length)
  block(`run.json has no valid "category" block: ${catFaults.join("; ")}. ` +
    `Shape: {"rule": "<the property this change adds>", "members": ["<every door/site/copy it applies to>"], "sealedBy": "<TestClass, or a list of them>"} ` +
    `or {"n/a": "<why this change adds no rule>"}. The builder fills it from an enumeration of the code, the reviewer verifies the member list.`);
if (!cat["n/a"]) {
  if (cat.members.length === 1 && GUARD_RE.test(addedLines()))
    block(`category.members lists one site (${cat.members[0]}) but the diff adds a bound/guard/rule (${(addedLines().match(GUARD_RE) || [])[0]}). ` +
      `Enumerate the siblings (grep the category) and apply the rule to every member in this change, or list them and name the category test that enumerates them.`);
  // A seal is named the way CLAUDE.md names one: `Class#method`, a bare class, or a path. The
  // `#method` half says WHICH test in the class holds the category; the file lookup below can only
  // see the class, so strip it. Refusing the documented form was itself a defect (found by HD-301,
  // whose seal was named in the form every `⟶ test:` pointer in CLAUDE.md uses).
  //
  // The admitted roots are DERIVED — the whole repository, tracked plus untracked-and-not-ignored —
  // and never a stated list. The list this replaces was `src/test` + `src/main/frontend`, which
  // could not see THIS FILE's own seal (.claude/pipeline/check-gates.test.mjs, by name), so the
  // hook told the orchestrator that the pipeline had no test; HD-330 was filed `no-seal` on the
  // strength of that answer. A root list goes stale the first time a seal lands somewhere new; the
  // repository cannot. Untracked files are included so a seal written in THIS change resolves.
  let PATHS;
  const repoPaths = () => (PATHS ??= (sh("git ls-files") + "\n" + sh("git ls-files --others --exclude-standard"))
    .split("\n").map((s) => s.trim()).filter(Boolean));
  const SUFFIXES = ["", ".java", ".test.ts", ".test.tsx", ".test.mjs", ".test.js", ".spec.ts", ".spec.tsx"];
  function resolveSeal(name) {
    const file = name.split("#")[0].trim();
    if (!file) return false;
    // Both the name as written and the name with a code extension stripped are tried as the stem,
    // so `check-gates.test.mjs`, `check-gates.test` and `DoorsHarnessTest` all find their file.
    const cands = new Set();
    for (const stem of new Set([file, file.replace(/\.(java|ts|tsx|mjs|cjs|js)$/, "")]))
      for (const s of SUFFIXES) cands.add(stem + s);
    return repoPaths().some((f) => [...cands].some((c) => f === c || f.endsWith(`/${c}`))) || existsSync(`${ROOT}/${file}`);
  }
  const seals = sealNames(cat.sealedBy);
  const unresolved = seals.filter((s) => !resolveSeal(s));
  if (unresolved.length) {
    const resolved = seals.filter((s) => !unresolved.includes(s));
    block(`category.sealedBy: ${unresolved.length} of ${seals.length} named seal(s) resolve to no file in this repository: ` +
      `${unresolved.map((s) => `"${s}"`).join(", ")}.` +
      (resolved.length ? ` Resolved: ${resolved.map((s) => `"${s}"`).join(", ")} — only the named one(s) above need fixing.` : "") +
      ` Name each seal as TestClass, TestClass#method, a *.test.* file name, or a repo-relative path; several seals go in a JSON array or one comma-separated string. ` +
      `Write the category test (Doors harness / *CoverageTest shape, with a floor) or name the one that already enumerates these members.`);
  }
}

// --- 7. X2: a tests gate is evidence only with a negative control -----------------------------
if (required.has("tests")) {
  const nc = negativeControl();
  if (typeof nc !== "string" || !/^(seen|n\/a)\s*:\s*\S/.test(nc))
    block(`gates.tests is "pass" but carries no negative control. Set gates.tests = {"status": "pass", "negativeControl": "seen: <test> red against <what was planted or reverted>"} ` +
      `(or "n/a: <reason>"). test-runner pastes the red line before the green one; a test nobody watched fail is a belief.`);
}

finish("pass");
