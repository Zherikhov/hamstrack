// Seal for the Stop hook's history (HD-303): every exit of check-gates.mjs states whether it is a finish and what
// it records; the checkpoint turns the records into three numbers. Run:  node --test .claude/pipeline/check-gates.test.mjs
// Everything here runs under HAMSTRACK_* overrides against temp files — no run reaches the real history.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync, readFileSync, existsSync, statSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = join(DIR, "..", "..");
const HOOK = join(DIR, "check-gates.mjs");
const CHECKPOINT = join(DIR, "checkpoint.mjs");
const FIX = join(DIR, "fixtures");
const TMP = mkdtempSync(join(tmpdir(), "hd303-"));
let seq = 0;
const tmp = (name) => join(TMP, `${++seq}-${name}`);

// The table of exits — §6 of docs/design/pipeline-history-proposal.md. A new exit is a deliberate row here, never an omission.
// A1 has two behavioural rows (a would-block Stop records `escaped`, a clean one records `pass`); they re-use the A4/B2 sites.
const EXITS = [
  { id: "A1", fixture: "b2-gates-unmet.json", files: " ", stdin: { stop_hook_active: true }, exit: "finish", verdict: "escaped" },
  { id: "A1", fixture: "a4-pass.json", files: "docs/x.md", stdin: { stop_hook_active: true }, exit: "finish", verdict: "pass" },
  { id: "A2", fixture: null, files: "docs/x.md", exit: "allow" },
  { id: "A3", fixture: "a3-trivial.json", files: "docs/x.md", exit: "finish", verdict: "pass", categoryNull: true },
  { id: "A4", fixture: "a4-pass.json", files: "docs/x.md", exit: "finish", verdict: "pass" },
  { id: "B1", fixture: "b1-trivial-code.json", files: "src/main/java/com/hamstrack/x/Foo.java", exit: "block", reason: /class="trivial" but the diff touches backend/ },
  { id: "B2", fixture: "b2-gates-unmet.json", files: " ", exit: "block", reason: /Gate\(s\) not satisfied: security=pending, tests=pending/ },
  { id: "B3", fixture: "b3-category-shape.json", files: "docs/x.md", exit: "block", reason: /no valid "category" block/ },
  { id: "B4", fixture: "b4-one-member-guard.json", files: "src/main/java/com/hamstrack/x/Foo.java", added: "+    @Size(max = 10) String name", exit: "block", reason: /lists one site \(FooRequest\.name\) but the diff adds a bound/ },
  { id: "B5", fixture: "b5-sealedby-missing.json", files: "docs/x.md", exit: "block", reason: /sealedBy names "NoSuchTestAnywhereInThisTree" but no such test/ },
  { id: "B6", fixture: "b6-no-negative-control.json", files: "docs/x.md", exit: "block", reason: /carries no negative control/ },
];

function cleanEnv() {
  const env = { ...process.env };
  for (const k of Object.keys(env)) if (k.startsWith("HAMSTRACK_")) delete env[k];
  env.CLAUDE_PROJECT_DIR = ROOT;
  return env;
}
function runHook({ runPath, files = "docs/x.md", added = "", stdin = {}, history, keepHistoryUnset = false }) {
  const env = cleanEnv();
  env.HAMSTRACK_RUN_JSON = runPath;
  env.HAMSTRACK_FAKE_FILES = files;
  env.HAMSTRACK_FAKE_ADDED = added;
  if (!keepHistoryUnset) env.HAMSTRACK_HISTORY = history;
  const t0 = process.hrtime.bigint();
  const r = spawnSync(process.execPath, [HOOK], { input: typeof stdin === "string" ? stdin : JSON.stringify(stdin), env, encoding: "utf8" });
  return { status: r.status, stdout: r.stdout, stderr: r.stderr, ms: Number(process.hrtime.bigint() - t0) / 1e6 };
}
const fixture = (name) => (name ? join(FIX, name) : join(TMP, "no-such-run.json"));
const lines = (p) => (existsSync(p) ? readFileSync(p, "utf8").split("\n").filter((l) => l.trim()) : []);
const records = (p) => lines(p).map((l) => JSON.parse(l));

test("AC-1 every finishing exit appends exactly one line; every blocking exit and A2 append none", () => {
  for (const row of EXITS) {
    const history = tmp(`${row.id}.jsonl`);
    const r = runHook({ runPath: fixture(row.fixture), files: row.files, added: row.added ?? "", stdin: row.stdin ?? {}, history });
    assert.equal(r.status, 0, `${row.id}: hook exit code`);
    const recs = records(history);
    if (row.exit === "block") {
      assert.match(r.stdout, /"decision":"block"/, `${row.id}: must block`);
      assert.match(JSON.parse(r.stdout).reason, row.reason, `${row.id}: reason`);
      assert.equal(recs.length, 0, `${row.id}: a blocking exit records nothing`);
    } else {
      assert.equal(r.stdout, "", `${row.id}: an allowed finish prints nothing`);
      assert.equal(recs.length, row.exit === "finish" ? 1 : 0, `${row.id}: records`);
      if (row.exit === "finish") {
        const rec = recs[0];
        assert.equal(rec.schema, 1);
        assert.equal(rec.verdict, row.verdict, `${row.id}: verdict`);
        assert.match(rec.ts, /^\d{4}-\d{2}-\d{2}T.*Z$/);
        assert.match(rec.runHash, /^[0-9a-f]{64}$/);
        assert.ok(Array.isArray(rec.touched));
        if (row.categoryNull) assert.equal(rec.category, null, `${row.id}: a trivial record carries no category`);
        if (row.verdict === "escaped") assert.match(rec.unmet[0], /Gate\(s\) not satisfied/, `${row.id}: unmet names the block reason`);
        else assert.equal("unmet" in rec, false, `${row.id}: a pass has no unmet`);
      }
    }
  }
});

test("AC-2 once per (runHash, verdict): same run thrice -> 1 line; an edited gate -> 2; reverted -> 2", () => {
  const history = tmp("dedupe.jsonl");
  const original = readFileSync(join(FIX, "a4-pass.json"), "utf8");
  const runPath = tmp("run.json");
  writeFileSync(runPath, original);
  for (let i = 0; i < 3; i++) runHook({ runPath, history });
  assert.equal(lines(history).length, 1, "three identical finishes are one record");
  writeFileSync(runPath, original.replace("fixture: the change adds no rule", "fixture: edited after a fix loop"));
  runHook({ runPath, history });
  assert.equal(lines(history).length, 2, "an edited run is a second record with the same task");
  writeFileSync(runPath, original);
  runHook({ runPath, history });
  assert.equal(lines(history).length, 2, "reverting to an already-recorded state adds nothing (tail-window dedupe, not last-line)");
  assert.equal(new Set(records(history).map((r) => r.task)).size, 1, "both records belong to one task");
});

test("AC-3 survives the next task: two fixtures in sequence -> 2 lines, the first byte-identical", () => {
  const history = tmp("sequence.jsonl");
  runHook({ runPath: fixture("a4-pass.json"), history });
  const first = lines(history)[0];
  runHook({ runPath: fixture("a3-trivial.json"), history });
  const after = lines(history);
  assert.equal(after.length, 2);
  assert.equal(after[0], first, "append-only: the earlier line is untouched");
  assert.equal(records(history)[1].class, "trivial");
});

test("AC-4 no override reaches the real history file, and the hook says so on stderr", () => {
  const real = join(DIR, "history.jsonl");
  const before = existsSync(real) ? statSync(real).size : -1;
  const r = runHook({ runPath: fixture("a4-pass.json"), keepHistoryUnset: true });
  assert.equal(r.status, 0);
  assert.equal(r.stdout, "");
  assert.match(r.stderr, /check-gates: history not recorded — override in env: .*HAMSTRACK_RUN_JSON/, "a silent off switch is a defect; the names of the overrides are the witness");
  const after = existsSync(real) ? statSync(real).size : -1;
  assert.equal(after, before, "a fixture run (HAMSTRACK_RUN_JSON set, HAMSTRACK_HISTORY unset) must not write the real file");
});

test("AC-7 the write never blocks a Stop: history pointed at a directory -> exit 0, empty stdout, one stderr line", () => {
  const dir = tmp("a-directory");
  mkdirSync(dir);
  const r = runHook({ runPath: fixture("a4-pass.json"), history: dir });
  assert.equal(r.status, 0);
  assert.equal(r.stdout, "");
  assert.match(r.stderr, /check-gates: history not written/);
  assert.equal(r.stderr.trim().split("\n").length, 1);
  const plain = runHook({ runPath: fixture("a4-pass.json"), history: tmp("timing.jsonl") });
  assert.ok(plain.ms < 5000, `a Stop with the append took ${plain.ms.toFixed(0)} ms`);
});

test("edge lines: a foreign or torn history line neither silences the recorder nor glues records; a null run.json or a non-string task cannot crash the hook", () => {
  const history = tmp("edge.jsonl");
  const torn = '{"schema":1,"ts":"2026-09-08T00:00:00.000Z","task":"torn","runHash":"';
  writeFileSync(history, "null\n" + torn);
  const r = runHook({ runPath: fixture("a4-pass.json"), history });
  assert.equal(r.status, 0);
  assert.equal(r.stdout, "");
  assert.equal(r.stderr, "", "a foreign line is skipped, never reported as a failed append");
  const raw = readFileSync(history, "utf8").split("\n");
  assert.equal(raw[0], "null");
  assert.equal(raw[1], torn, "the torn fragment stays as it was");
  assert.equal(JSON.parse(raw[2]).verdict, "pass", "the new record starts on its own line");
  const cp = runCheckpoint({ historyLines: ["null", '{"torn":', raw[2]] });
  assert.match(cp.out, /2 bad lines skipped/, cp.out);
  assert.match(cp.out, /, 1 tasks, 1 feature\/light passes/, cp.out);
  const nullRun = tmp("null-run.json");
  writeFileSync(nullRun, "null");
  const h2 = tmp("null.jsonl");
  const r2 = runHook({ runPath: nullRun, history: h2, stdin: { stop_hook_active: true } });
  assert.deepEqual([r2.status, r2.stdout, lines(h2).length], [0, "", 0], "a run.json that parses to null is no run: allow, nothing recorded, no stack trace");
  const r3 = runHook({ runPath: nullRun, history: h2, stdin: "null" });
  assert.deepEqual([r3.status, r3.stdout], [0, ""], "a null stdin is no flag");
  const numRun = tmp("num-run.json");
  writeFileSync(numRun, readFileSync(join(FIX, "a4-pass.json"), "utf8").replace('"task": "HD-904 feature with every gate satisfied"', '"task": 904'));
  const h3 = tmp("num.jsonl");
  runHook({ runPath: numRun, history: h3 });
  assert.equal(records(h3)[0].task, "904", "task is recorded as a string whatever run.json held");
  const cp2 = runCheckpoint({ historyLines: [JSON.stringify({ ...records(h3)[0], task: 904 })], log: "a".repeat(40) + "\t" + "1".repeat(40) + "\t\tfeat: keyed (HD-904)" });
  assert.equal(cp2.status, 1, cp2.err);
  assert.match(cp2.out, /N3 uncovered commits: 1\/1 = 100% \[FLAG\]/, "a non-string task in an old record covers nothing and crashes nothing");
});

test("seal: the exits of check-gates.mjs are exactly the rows of the table above", () => {
  const src = readFileSync(HOOK, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").split("\n").filter((l) => !/^\s*\/\//.test(l)).join("\n");
  const calls = (name) => (src.match(new RegExp(`(?<!function\\s)\\b${name}\\s*\\(`, "g")) || []).length;
  const exits = (src.match(/\bprocess\.exit\s*\(/g) || []).length;
  const rows = {
    finish: EXITS.filter((r) => r.exit === "finish" && r.verdict === "pass" && !r.stdin).length + EXITS.filter((r) => r.exit === "finish" && r.verdict === "escaped").length,
    allow: EXITS.filter((r) => r.exit === "allow").length,
    block: EXITS.filter((r) => r.exit === "block").length,
  };
  const msg = (what, got, want) => `${what}: check-gates.mjs has ${got} call sites, the table accounts for ${want}. A new exit of the hook is a deliberate row in EXITS (and §6 of the HD-303 spec) stating what it records — add the row, never leave the exit unlisted.`;
  assert.equal(calls("finish"), rows.finish, msg("finish(…)", calls("finish"), rows.finish));
  assert.equal(calls("allow"), rows.allow, msg("allow()", calls("allow"), rows.allow));
  assert.equal(calls("block"), rows.block, msg("block(…)", calls("block"), rows.block));
  assert.equal(exits, 3, `process.exit( appears ${exits} times; it lives only inside allow / finish / block — a direct exit is a finish the history never sees`);
  assert.match(src, /if \(LOOP_GUARD\) finish\("escaped"/, "the loop guard records an escape instead of blocking (D6)");
  // Floors on the table itself: the two A1 rows are the behavioural seal of D6 and sit outside the call-site arithmetic.
  const a1 = EXITS.filter((r) => r.id === "A1");
  assert.deepEqual(a1.map((r) => r.verdict).sort(), ["escaped", "pass"], "D6 is held by two A1 rows — a would-block Stop under stop_hook_active records escaped, a clean one records pass; deleting either leaves the loop guard sealed by source text only");
  assert.ok(a1.every((r) => r.stdin && r.stdin.stop_hook_active === true), "both A1 rows run with stop_hook_active on stdin");
  assert.equal(EXITS.length, 11, `the table has ${EXITS.length} rows; the spec has 10 exits and A1 has two behavioural rows — a removed row is never a legal shortening`);
});

// --- checkpoint ------------------------------------------------------------------------------
const rec = (task, { cls = "feature", verdict = "pass", category = { rule: "r", members: ["a", "b"], sealedBy: "T" }, negativeControl = "seen: T red", ts } = {}, i = 0) =>
  JSON.stringify({ schema: 1, ts: ts ?? `2026-09-10T10:${String(i).padStart(2, "0")}:00.000Z`, task, class: cls, head: "", touched: [], gates: {}, category, negativeControl, verdict, runHash: `${i}`.padStart(64, "0") });
function runCheckpoint({ historyLines, log = "" }) {
  const env = cleanEnv();
  env.HAMSTRACK_HISTORY = tmp("cp-history.jsonl");
  env.HAMSTRACK_CHECKPOINTS = tmp("cp-checkpoints.jsonl");
  env.HAMSTRACK_FAKE_LOG = tmp("cp-log.txt");
  writeFileSync(env.HAMSTRACK_HISTORY, historyLines.join("\n") + "\n");
  writeFileSync(env.HAMSTRACK_FAKE_LOG, log);
  const r = spawnSync(process.execPath, [CHECKPOINT], { env, encoding: "utf8" });
  return { status: r.status, out: r.stdout, err: r.stderr, checkpoints: lines(env.HAMSTRACK_CHECKPOINTS) };
}
const synthetic = (naCategory, naControl) => Array.from({ length: 10 }, (_, i) =>
  rec(`HD-${101 + i} task`, { category: i < naCategory ? { "n/a": "closed with text" } : undefined, negativeControl: i < naControl ? "n/a: no test touched" : undefined }, i));

test("AC-5 checkpoint on a synthetic history: 6 of 10 flags both, 4 of 10 flags neither, trivial records leave the denominators alone", () => {
  const six = runCheckpoint({ historyLines: synthetic(6, 6) });
  assert.equal(six.status, 1, six.err);
  assert.equal((six.out.match(/6\/10 = 60% \[FLAG\]/g) || []).length, 2, six.out);
  assert.match(six.out, /N3 uncovered commits: no data/);
  assert.equal(six.checkpoints.length, 1, "a non-dry run appends one checkpoint line");
  assert.deepEqual(JSON.parse(six.checkpoints[0]).flags, ["N1", "N2"]);
  const four = runCheckpoint({ historyLines: synthetic(4, 4) });
  assert.equal(four.status, 0, four.out);
  assert.equal((four.out.match(/4\/10 = 40%(?! \[FLAG\])/g) || []).length, 2, four.out);
  assert.match(four.out, /no flags/);
  const trivial = Array.from({ length: 5 }, (_, i) => rec(`HD-${201 + i} trivial`, { cls: "trivial", category: null, negativeControl: null }, 20 + i));
  const withTrivial = runCheckpoint({ historyLines: [...synthetic(6, 6), ...trivial] });
  assert.equal((withTrivial.out.match(/6\/10 = 60% \[FLAG\]/g) || []).length, 2, "trivial records are outside N1/N2");
});

test("AC-6 uncovered commits are listed by sha and subject; merges, tags, unkeyed and trivial-covered commits sit in their own listings", () => {
  const h = [rec("HD-101 covered task", {}, 0), rec("HD-777 trivial task", { cls: "trivial", category: null, negativeControl: null }, 1), rec("HD-888 escaped only", { verdict: "escaped", category: null }, 2)];
  const log = [
    "a".repeat(40) + "\t" + "1".repeat(40) + "\t\tfeat(x): something nobody recorded (HD-555)",
    "b".repeat(40) + "\t" + "2".repeat(40) + "\t\tfix(y): covered by a pass record (HD-101)",
    "c".repeat(40) + "\t" + "3".repeat(40) + " " + "4".repeat(40) + "\t\tMerge branch feat/z (HD-101)",
    "d".repeat(40) + "\t" + "5".repeat(40) + "\ttag: v0.19.0, origin/main\trelease 0.19.0 (HD-101)",
    "e".repeat(40) + "\t" + "6".repeat(40) + "\t\tchore: bump a dependency",
    "f".repeat(40) + "\t" + "7".repeat(40) + "\t\tdocs: covered only by a trivial run (HD-777)",
    "9".repeat(40) + "\t" + "8".repeat(40) + "\t\tfeat: an escaped record covers nothing (HD-888)",
  ].join("\n");
  const r = runCheckpoint({ historyLines: h, log });
  const section = (from, to) => r.out.slice(r.out.indexOf(from), to ? r.out.indexOf(to) : undefined);
  const n3 = section("N3 uncovered commits", "never counted — covered by a trivial run");
  assert.match(n3, /N3 uncovered commits: 2\/4 = 50% \[FLAG\]/, r.out);
  assert.match(n3, /aaaaaaa  feat\(x\): something nobody recorded \(HD-555\)/);
  assert.match(n3, /9999999  feat: an escaped record covers nothing \(HD-888\)/);
  assert.doesNotMatch(n3, /bbbbbbb|ccccccc|ddddddd|eeeeeee|fffffff/, "only uncovered keyed commits are listed under N3");
  assert.match(section("covered by a trivial run", "merges and release tags"), /fffffff/);
  assert.match(section("merges and release tags", "unkeyed commits"), /ccccccc[\s\S]*ddddddd/);
  assert.match(section("unkeyed commits", "escaped in window"), /eeeeeee/);
  assert.match(section("escaped in window"), /tasks whose last record is escaped \(1\):\n  HD-888 escaped only/);
  assert.equal(r.status, 1);
  const covered = runCheckpoint({ historyLines: [...h, rec("HD-555 now recorded", {}, 3), rec("HD-888 escaped only", {}, 4)], log });
  assert.match(covered.out, /N3 uncovered commits: 0\/4 = 0%(?! \[FLAG\])/, covered.out);
  assert.equal(covered.status, 0);
});

test("AC-9 documented: the procedure, the skill line and the ignore rules exist", () => {
  const pipelineDoc = readFileSync(join(ROOT, "docs/design/dev-team-pipeline.md"), "utf8");
  assert.match(pipelineDoc, /### 12\.6 Two-week checkpoint/);
  assert.match(pipelineDoc, /node \.claude\/pipeline\/checkpoint\.mjs/);
  assert.match(pipelineDoc, /2026-09-21/);
  assert.match(readFileSync(join(ROOT, ".claude/skills/feature-pipeline/SKILL.md"), "utf8"), /history\.jsonl/);
  for (const f of [".claude/pipeline/history.jsonl", ".claude/pipeline/checkpoints.jsonl"]) {
    const r = spawnSync("git", ["check-ignore", "-v", f], { cwd: ROOT, encoding: "utf8" });
    assert.equal(r.status, 0, `${f} must be ignored`);
    assert.match(r.stdout, /\.gitignore:\d+:/, `${f}: git check-ignore -v names the rule`);
  }
  assert.equal(spawnSync("git", ["check-ignore", "-q", ".claude/pipeline/fixtures/a4-pass.json"], { cwd: ROOT }).status, 1, "fixtures are tracked");
});
