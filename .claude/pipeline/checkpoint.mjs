#!/usr/bin/env node
// Two-week checkpoint over .claude/pipeline/history.jsonl (HD-303; procedure: docs/design/dev-team-pipeline.md §12.6).
//   node .claude/pipeline/checkpoint.mjs [--since <sha>] [--dry-run]
// Three numbers, each flagged at >= 50 %:
//   N1  share of feature/light passes whose `category` is an n/a text          (X1 closed with words)
//   N2  share of feature/light passes whose negative control is null or "n/a:" (X2 closed with words)
//   N3  keyed, non-merge, non-release commits since the last checkpoint with no `pass` record sharing an HD key
//       (the hook was not there — unregistered, broken, or the task never ran under it), listed by sha and subject
// Exit 1 iff any flag. Appends one line to checkpoints.jsonl unless --dry-run. Dependency-free.
// Overrides for the seal (check-gates.test.mjs): HAMSTRACK_HISTORY, HAMSTRACK_CHECKPOINTS,
// HAMSTRACK_FAKE_LOG (a file in `%H<TAB>%P<TAB>%D<TAB>%s` format; no git is run when it is set).

import { readFileSync, existsSync, appendFileSync } from "node:fs";
import { execSync, execFileSync } from "node:child_process";

const DEFAULT_SINCE = "5c2adfe"; // HD-295 — the last commit made before the hook recorded anything; `A..HEAD` excludes A.
const THRESHOLD = 0.5;
const KEY_RE = /\bHD-(\d+)\b/g;

const args = process.argv.slice(2);
const dryRun = args.includes("--dry-run");
const sinceArg = args.includes("--since") ? args[args.indexOf("--since") + 1] : undefined;
if (args.includes("--since") && !sinceArg) die("--since needs a commit");

function die(msg) { process.stderr.write(`checkpoint: ${msg}\n`); process.exit(2); }
function git(...args) { try { return execFileSync("git", args, { cwd: ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }); } catch { return null; } }
const REF_RE = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$/; // a sha or a plain ref — never a shell fragment from a file
function repoRoot() {
  if (process.env.CLAUDE_PROJECT_DIR) return process.env.CLAUDE_PROJECT_DIR.replace(/\\/g, "/");
  try { return execSync("git rev-parse --show-toplevel", { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim(); }
  catch { return process.cwd().replace(/\\/g, "/"); }
}
const ROOT = repoRoot();
const HISTORY = process.env.HAMSTRACK_HISTORY ?? `${ROOT}/.claude/pipeline/history.jsonl`;
const CHECKPOINTS = process.env.HAMSTRACK_CHECKPOINTS ?? `${ROOT}/.claude/pipeline/checkpoints.jsonl`;
const FAKE_LOG = process.env.HAMSTRACK_FAKE_LOG;

// --- inputs ---------------------------------------------------------------------------------
function readJsonl(path) {
  const ok = [], bad = [];
  if (!existsSync(path)) return { ok, bad };
  readFileSync(path, "utf8").split("\n").forEach((l, i) => { if (!l.trim()) return; try { const v = JSON.parse(l); if (v && typeof v === "object") ok.push(v); else bad.push(i + 1); } catch { bad.push(i + 1); } });
  return { ok, bad };
}
const history = readJsonl(HISTORY);
const previous = readJsonl(CHECKPOINTS).ok.at(-1);
const since = previous?.head || sinceArg || DEFAULT_SINCE;
if (!REF_RE.test(since)) die(`refusing "${since}" as a starting commit`);
const prevTs = previous?.ts ?? "";

let logText, head;
if (FAKE_LOG !== undefined) {
  logText = readFileSync(FAKE_LOG, "utf8");
  head = logText.split("\n").find((l) => l.trim())?.split("\t")[0] ?? "";
} else {
  head = git("rev-parse", "HEAD")?.trim();
  if (!head) die("git is required for N3 (no HEAD)");
  logText = git("log", "--format=%H%x09%P%x09%D%x09%s", `${since}..HEAD`);
  if (logText === null) die(`git log ${since}..HEAD failed — is "${since}" a commit of this repository?`);
}
const commits = logText.split("\n").filter((l) => l.trim()).map((l) => {
  const [sha, parents = "", refs = "", ...rest] = l.split("\t");
  return { sha, parents: parents.split(" ").filter(Boolean), refs, subject: rest.join("\t") };
});

// --- definitions (§4.5) ---------------------------------------------------------------------
const keyed = (s) => new Set([...String(s ?? "").matchAll(KEY_RE)].map((m) => m[1]));
const intersects = (a, b) => [...a].some((x) => b.has(x));
const W = history.ok.filter((r) => (r.ts ?? "") > prevTs);
const lastPerTask = new Map();
for (const r of W) { const k = r.task ?? ""; const prev = lastPerTask.get(k); if (!prev || (r.ts ?? "") >= (prev.ts ?? "")) lastPerTask.set(k, r); }
const F = [...lastPerTask.values()];
const Ffl = F.filter((r) => (r.class === "feature" || r.class === "light") && r.verdict === "pass");
const n1 = Ffl.filter((r) => r.category && typeof r.category === "object" && "n/a" in r.category).length;
const n2 = Ffl.filter((r) => r.negativeControl == null || /^n\/a\s*:/.test(String(r.negativeControl))).length;

const passRecords = history.ok.filter((r) => r.verdict === "pass");
const coverOf = (c) => { const k = keyed(c.subject); return passRecords.filter((r) => intersects(keyed(r.task), k)); };
const merges = [], releases = [], unkeyed = [], K = [];
for (const c of commits) {
  if (c.parents.length > 1) merges.push(c);
  else if (/(^|, )tag: v/.test(c.refs)) releases.push(c);
  else if (keyed(c.subject).size === 0) unkeyed.push(c);
  else K.push(c);
}
const uncovered = [], trivialCovered = [];
for (const c of K) {
  const cov = coverOf(c);
  if (cov.length === 0) uncovered.push(c);
  else if (cov.every((r) => r.class === "trivial")) trivialCovered.push(c);
}
const escaped = new Map();
for (const r of W) if (r.verdict === "escaped") { const k = r.task ?? ""; escaped.set(k, (escaped.get(k) ?? 0) + 1); }
const endedEscaped = F.filter((r) => r.verdict === "escaped").map((r) => r.task ?? "");

// --- output ---------------------------------------------------------------------------------
const pct = (n, d) => d ? `${n}/${d} = ${Math.round((100 * n) / d)}%` : "no data";
const flagged = (n, d) => d > 0 && n / d >= THRESHOLD;
const flags = [];
if (flagged(n1, Ffl.length)) flags.push("N1");
if (flagged(n2, Ffl.length)) flags.push("N2");
if (flagged(uncovered.length, K.length)) flags.push("N3");
const line = (name, n, d, flag) => `${name}: ${pct(n, d)}${flag ? " [FLAG]" : ""}`;
const s7 = (s) => (s ?? "").slice(0, 7);
const date = (sha) => FAKE_LOG !== undefined || !REF_RE.test(sha) ? "" : (git("log", "-1", "--format=%cs", sha)?.trim() ?? "");
const out = [];
out.push(`Two-week checkpoint — window ${s7(since)}..${s7(head)} ${[date(since), date(head)].filter(Boolean).join(" → ")}`.trimEnd());
out.push(`history: ${history.ok.length} records (${W.length} in window, ${bad(history.bad)} skipped), ${F.length} tasks, ${Ffl.length} feature/light passes; commits since: ${commits.length}`);
out.push(line("N1 category n/a", n1, Ffl.length, flags.includes("N1")));
out.push(line("N2 negative control missing or n/a", n2, Ffl.length, flags.includes("N2")));
out.push(line("N3 uncovered commits", uncovered.length, K.length, flags.includes("N3")));
for (const c of uncovered) out.push(`  ${s7(c.sha)}  ${c.subject}`);
out.push(`never counted — covered by a trivial run (${trivialCovered.length}):`);
for (const c of trivialCovered) out.push(`  ${s7(c.sha)}  ${c.subject}`);
out.push(`never counted — merges and release tags (${merges.length + releases.length}):`);
for (const c of [...merges, ...releases]) out.push(`  ${s7(c.sha)}  ${c.subject}`);
out.push(`never counted — unkeyed commits (${unkeyed.length}):`);
for (const c of unkeyed) out.push(`  ${s7(c.sha)}  ${c.subject}`);
out.push(`escaped in window: ${[...escaped.values()].reduce((a, b) => a + b, 0)} records over ${escaped.size} tasks; tasks whose last record is escaped (${endedEscaped.length}):`);
for (const t of endedEscaped) out.push(`  ${t}`);
for (const [t, n] of escaped) if (!endedEscaped.includes(t)) out.push(`  (${n} intermediate) ${t}`);
out.push(flags.length ? `FLAGS: ${flags.join(", ")} — open one ticket under HD-294 naming the runs; never move the threshold.` : "no flags");
process.stdout.write(out.join("\n") + "\n");
function bad(list) { return list.length ? `${list.length} bad lines` : "0 bad lines"; }

if (!dryRun) {
  const rec = { schema: 1, ts: new Date().toISOString(), since, head, n1: { num: n1, den: Ffl.length }, n2: { num: n2, den: Ffl.length }, n3: { num: uncovered.length, den: K.length }, flags };
  try { appendFileSync(CHECKPOINTS, JSON.stringify(rec) + "\n"); } catch (e) { process.stderr.write(`checkpoint: not recorded (${e.code ?? e.message})\n`); }
}
process.exit(flags.length ? 1 : 0);
