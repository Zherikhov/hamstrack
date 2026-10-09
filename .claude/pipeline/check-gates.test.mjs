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
//
// Two UNITS live in this table and they are deliberately not interchangeable (HD-330):
//   * a SITE row — one `allow()` / `finish(…)` / `block(…)` call site in the hook. The call-site
//     arithmetic in the "seal:" test below counts these and only these, so a new exit of the hook
//     cannot ship without a row.
//   * a REUSE row — `reuses: "<site id>"`. A behaviour that travels through an existing exit site
//     and would otherwise be invisible: a value SHAPE the hook must accept, or a refusal whose
//     WORDING is the deliverable. These sit outside the arithmetic; counting them as sites would
//     make the hook look like it grew exits it did not grow, and the two units merging back into
//     one is what the granularity control at the end of the seal test fires on.
// A1's two rows are the older instance of the same idea (a would-block Stop records `escaped`, a
// clean one records `pass`) and keep their original `stdin`-keyed handling.
// The four direction HEADINGS of the §7b refusal (HD-328), by name. Every row below asserts
// presence and absence against these and never against a loose word, because the refusal's ADVICE
// legitimately contains some of those words: "ignored paths are never staged" holds "never staged",
// and "an untracked file that is NOT part of this change" holds "untracked". Both caught a notReason
// of mine red — a seal keyed on a word accuses the wording of saying something it does not, and
// would then have to be weakened until it accused nothing.
const DIRECTION = {
  stale: "staged, then edited again on disk",
  unstaged: "changed on disk, never staged",
  untracked: "untracked — in no index population",
  unmerged: "unmerged — the work tree holds",
};
const rx = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
/** Every direction heading EXCEPT the ones named: what a single-direction refusal must not say. */
const onlyDirection = (...keep) => new RegExp(Object.entries(DIRECTION)
  .filter(([k]) => !keep.includes(k)).map(([, v]) => rx(v)).join("|"));

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
  { id: "B5", fixture: "b5-sealedby-missing.json", files: "docs/x.md", exit: "block", reason: /sealedBy: 1 of 1 named seal\(s\) resolve to no file in this repository: "NoSuchTestAnywhereInThisTree"/ },
  { id: "B6", fixture: "b6-no-negative-control.json", files: "docs/x.md", exit: "block", reason: /carries no negative control/ },
  // HD-330 — two new exit SITES. Both replace a misdiagnosis, not a missing check.
  { id: "B7", fixture: "b7-class-unknown.json", files: "docs/x.md", exit: "block",
    reason: /"class" is "epic", which is not one of feature \| light \| trivial/ },
  { id: "B8", fixture: "b8-gate-shape.json", files: "docs/x.md", exit: "block",
    reason: /1 of 3 judged gate value\(s\) are not a verdict: security="pass \(round 2 verified\).*reads as "pass" \+ a note.*SHAPE fault, not an unrun gate — do NOT re-dispatch security-officer/s,
    notReason: /Dispatch security->security-officer/ },
  // HD-330 — REUSE rows: the shapes the hook must accept, and the refusals whose wording is the ticket.
  { id: "C1", reuses: "A4", fixture: "c1-sealedby-list.json", files: "docs/x.md", exit: "finish", verdict: "pass" },
  { id: "C2", reuses: "A4", fixture: "c2-sealedby-csv.json", files: "docs/x.md", exit: "finish", verdict: "pass" },
  { id: "C3", reuses: "A4", fixture: "c3-sealedby-own-seal.json", files: "docs/x.md", exit: "finish", verdict: "pass" },
  { id: "C4", reuses: "B5", fixture: "c4-sealedby-partial.json", files: "docs/x.md", exit: "block",
    reason: /sealedBy: 1 of 3 named seal\(s\) resolve to no file in this repository: "NoSuchSealInThisTree"\. Resolved: "DoorsHarnessTest", "\.claude\/pipeline\/check-gates\.test\.mjs" — only the named one\(s\) above need fixing/,
    notReason: /no such test exists|"DoorsHarnessTest", "NoSuchSealInThisTree"/ },
  { id: "C5", reuses: "A4", fixture: "c5-gate-note-object.json", files: "src/main/java/com/hamstrack/x/Foo.java", exit: "finish", verdict: "pass" },
  { id: "C6", reuses: "B3", fixture: "c6-category-field-named.json", files: "docs/x.md", exit: "block",
    reason: /no valid "category" block: "members" must be a JSON array of names \(got "A, B"\)/, notReason: /"rule" must be|"sealedBy" must name/ },
  { id: "C7", reuses: "A4", fixture: "c7-tests-note-toplevel-control.json", files: "docs/x.md", exit: "finish", verdict: "pass" },
  { id: "C8", reuses: "A4", fixture: "c8-sealedby-method.json", files: "docs/x.md", exit: "finish", verdict: "pass" },
  // HD-328 — two new exit SITES. The run.json is `a4-pass.json` in both: the point of the check is
  // that the SAME gate record finishes on one tree and is refused on another, so a dedicated
  // fixture would hide the only variable. `status` is a fake `git status --porcelain -z
  // --no-renames -uall` listing; the four directions are the four the refusal must distinguish.
  { id: "B9", fixture: "a4-pass.json", files: "docs/x.md",
    status: "MM a-stale\n M b-unstaged\n?? c-untracked\nUU d-conflict", exit: "block",
    reason: /disagree on 4 path\(s\) \(1 staged-then-edited, 1 unstaged, 1 untracked, 1 unmerged\)[\s\S]*a commit publishes the STAGED bytes[\s\S]*a-stale {2}\[MM\][\s\S]*never staged[\s\S]*b-unstaged {2}\[ M\][\s\S]*untracked — in no index population[\s\S]*c-untracked[\s\S]*unmerged[\s\S]*d-conflict {2}\[UU\][\s\S]*git add -A[\s\S]*It does not establish that the green run measured this tree/,
    // Three things this refusal must STOP saying, each of which it would be natural to write.
    //   * a destructive remedy, EVEN AS A PROHIBITION: the first draft ended "do not reach for `git
    //     stash` / `git checkout --` / `git restore`", which put all three command lines in front of
    //     a reader hunting for a command to run, and made the absence check unassertable. The advice
    //     belongs in CLAUDE.md and Phase 7 of the skill; a refusal offers the cure only.
    //   * the predicate the ticket prescribed and measurement refuted — `git diff --quiet` AND
    //     `git diff --cached --quiet` both hold on the CLEAN TREE ONLY, and both call a never-added
    //     file clean. A refusal that quoted it would send its reader to a blind check.
    //   * the overclaim. This establishes one TREE, never one MEASUREMENT.
    notReason: /git stash|git checkout --|git restore|diff --cached --quiet|establishes that the (suite|green run|recorded run|tests gate) (measured|read)/ },
  { id: "B10", fixture: "a4-pass.json", files: "docs/x.md", root: "notARepo", keepStatusUnset: true, exit: "block",
    reason: /`git status --porcelain -z --no-renames -uall` did not run \(exit \d+, stderr `fatal: not a git repository[\s\S]*A measurement that failed is not a clean tree[\s\S]*Finish from the checkout root/,
    notReason: /index and the work tree disagree|git add -A/ },
  // HD-328 — REUSE rows. Each single direction must accuse only itself: a tree with one untracked
  // file must not be told its edits are unstaged. And the legitimate finishing state must FINISH —
  // a check that refuses every tree reads exactly like one that works.
  { id: "C9", reuses: "B9", fixture: "a4-pass.json", files: "docs/x.md", status: "?? deploy/dc/.env.example\n?? deploy/dc/docker-compose.yml",
    exit: "block", reason: /disagree on 2 path\(s\) \(2 untracked\)[\s\S]*so `git ls-files --cached` cannot name it:\n {4}deploy\/dc\/\.env\.example\n {4}deploy\/dc\/docker-compose\.yml/,
    // Besides the other three directions: the refusal owes its reader the ACTION, so the HD-314
    // provenance lives in the hook's comment and in §12.3 of the pipeline doc and not in the
    // message (prose budget). Asserted absent so it cannot drift back in.
    notReason: new RegExp(`${onlyDirection("untracked").source}|guardedCount|HD-314`) },
  { id: "C10", reuses: "B9", fixture: "a4-pass.json", files: "docs/x.md", status: "MM deploy/dc/docker-compose.yml",
    exit: "block", reason: /disagree on 1 path\(s\) \(1 staged-then-edited\)[\s\S]*STAGED bytes, which no reader read:\n {4}deploy\/dc\/docker-compose\.yml {2}\[MM\]/,
    notReason: onlyDirection("stale") },
  { id: "C11", reuses: "B9", fixture: "a4-pass.json", files: "docs/x.md", status: " M ops/deploy.sh",
    exit: "block", reason: /disagree on 1 path\(s\) \(1 unstaged\)[\s\S]*invisible to every population drawn from `git ls-files --cached`:\n {4}ops\/deploy\.sh {2}\[ M\]/,
    notReason: onlyDirection("unstaged") },
  // A staged modify, a staged add, a staged delete and a staged type change: X set, Y blank. These
  // are the four codes a fully staged change actually produces (`R` cannot appear — `--no-renames`).
  { id: "C12", reuses: "A4", fixture: "a4-pass.json", files: "docs/x.md", status: "M  mod\nA  added\nD  gone\nT  retyped",
    exit: "finish", verdict: "pass" },
];

function cleanEnv() {
  const env = { ...process.env };
  for (const k of Object.keys(env)) if (k.startsWith("HAMSTRACK_")) delete env[k];
  env.CLAUDE_PROJECT_DIR = ROOT;
  return env;
}
// `status` defaults to "" — a CLEAN tree — for the same reason `files` and `added` are faked: every
// row must mean the same thing on any developer's checkout. Without the default, §7b (HD-328) would
// read the real work tree and every `finish` row here would block while this very change was being
// written. `keepStatusUnset` is the opt-out for the rows that must exercise REAL git.
function runHook({ runPath, files = "docs/x.md", added = "", status = "", stdin = {}, history, root,
                   keepHistoryUnset = false, keepStatusUnset = false }) {
  const env = cleanEnv();
  env.HAMSTRACK_RUN_JSON = runPath;
  env.HAMSTRACK_FAKE_FILES = files;
  env.HAMSTRACK_FAKE_ADDED = added;
  if (!keepStatusUnset) env.HAMSTRACK_FAKE_STATUS = status;
  if (root) env.CLAUDE_PROJECT_DIR = root;
  if (!keepHistoryUnset) env.HAMSTRACK_HISTORY = history;
  const t0 = process.hrtime.bigint();
  const r = spawnSync(process.execPath, [HOOK], { input: typeof stdin === "string" ? stdin : JSON.stringify(stdin), env, encoding: "utf8" });
  return { status: r.status, stdout: r.stdout, stderr: r.stderr, ms: Number(process.hrtime.bigint() - t0) / 1e6 };
}
const fixture = (name) => (name ? join(FIX, name) : join(TMP, "no-such-run.json"));
// A directory that is NOT a git repository and never becomes one: the B10 input. Memoised so the
// row and any later reader get the same path, which is the one the refusal must name.
let NOT_A_REPO;
function notARepo() {
  if (!NOT_A_REPO) { NOT_A_REPO = join(TMP, "not-a-repo"); mkdirSync(NOT_A_REPO, { recursive: true }); }
  return NOT_A_REPO;
}
const lines = (p) => (existsSync(p) ? readFileSync(p, "utf8").split("\n").filter((l) => l.trim()) : []);
const records = (p) => lines(p).map((l) => JSON.parse(l));

// Every row is checked and the failures are reported TOGETHER. Failing fast on the first row hid the
// other fourteen behind it, which made each negative control of HD-328 cost one run per row and —
// worse — would report a single regression when the whole table had gone dark.
test("AC-1 every finishing exit appends exactly one line; every blocking exit and A2 append none", () => {
  const broken = [];
  for (const row of EXITS) {
    try { checkRow(row); } catch (e) { broken.push(`${row.id}: ${e.message.split("\n")[0]}`); }
  }
  assert.deepEqual(broken, [], `${broken.length} of ${EXITS.length} rows of the EXITS table do not hold:\n  ${broken.join("\n  ")}`);
});

function checkRow(row) {
  {
    const history = tmp(`${row.id}.jsonl`);
    const r = runHook({ runPath: fixture(row.fixture), files: row.files, added: row.added ?? "",
      status: row.status ?? "", stdin: row.stdin ?? {}, history,
      root: row.root === "notARepo" ? notARepo() : undefined, keepStatusUnset: Boolean(row.keepStatusUnset) });
    assert.equal(r.status, 0, `${row.id}: hook exit code`);
    const recs = records(history);
    if (row.exit === "block") {
      assert.match(r.stdout, /"decision":"block"/, `${row.id}: must block`);
      assert.match(JSON.parse(r.stdout).reason, row.reason, `${row.id}: reason`);
      // `notReason` is how a row says what the refusal must STOP saying — the half a row carrying
      // only a positive match cannot hold, and the half HD-330 was filed about (a shape fault
      // prescribing a re-dispatch; a partial-seal refusal quoting back the seals that resolved).
      if (row.notReason) assert.doesNotMatch(JSON.parse(r.stdout).reason, row.notReason, `${row.id}: the refusal still says what it must not`);
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
}

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

// HD-328. The EXITS rows above drive §7b through `HAMSTRACK_FAKE_STATUS`, which is the only way to
// make four tree states deterministic on anybody's checkout — but a fake is also the only way to be
// green about a `git status` invocation that does not exist. So this runs the hook against a REAL
// throwaway repository, with the fake unset, and walks the four states in the order a change goes
// through them. Three things only the real path can hold:
//   * the ignored file is present in every state and must never be counted — verified against git's
//     own ignore handling rather than against a claim that `--porcelain` omits them;
//   * the repository sets `status.showUntrackedFiles=no`, which (measured 2026-10-09, git 2.54.0)
//     hides untracked files from `--porcelain` entirely: 5 reported -> 0. Drop `-uall` from the
//     hook and this test reds on the untracked state, which is the state the ticket is about;
//   * it is the granularity control between the fake and the real input: a parser change that suits
//     the fake and not git's actual bytes cannot stay green here.
test("real git, four states: ignored paths never count, a fully staged change finishes, untracked and staged-but-stale are refused", () => {
  const dir = tmp("live-repo");
  mkdirSync(dir);
  const git = (...args) => {
    const r = spawnSync("git", args, { cwd: dir, encoding: "utf8" });
    assert.equal(r.status, 0, `git ${args.join(" ")} failed: ${r.stdout}${r.stderr}`);
    return r.stdout;
  };
  git("init", "-q", ".");
  git("config", "user.email", "seal@hamstrack.invalid");
  git("config", "user.name", "seal");
  git("config", "core.autocrlf", "false");
  git("config", "status.showUntrackedFiles", "no");
  writeFileSync(join(dir, ".gitignore"), "ignored-by-rule\n");
  writeFileSync(join(dir, "tracked.txt"), "v1\n");
  // State 6 renames this one. It is long and is renamed with its content UNTOUCHED because git only
  // reports `R` above its rename-similarity threshold — measured 2026-10-09: `git mv` on a 3-byte
  // file whose content had also changed reported `A  renamed.txt` + `D  tracked.txt` and no `R` at
  // all, so the first draft of state 6 was green because the shape it tests never occurred.
  writeFileSync(join(dir, "long.txt"), Array.from({ length: 40 }, (_, i) => `line ${i} of a file long enough to be seen as a rename`).join("\n") + "\n");
  git("add", ".");
  git("commit", "-qm", "init");
  writeFileSync(join(dir, "ignored-by-rule"), "noise an ignore rule keeps out of every commit\n");

  let state = 0;
  const live = () => runHook({ runPath: fixture("a4-pass.json"), root: dir, keepStatusUnset: true,
    history: tmp(`live-${++state}.jsonl`) });
  const reason = (r) => (r.stdout ? JSON.parse(r.stdout).reason : "");

  // 1. clean but for the ignored file.
  let r = live();
  assert.equal(r.stdout, "", `an ignored path was counted as part of the change: ${reason(r)}`);

  // 2. the legitimate finishing state: the whole change staged, nothing else on disk.
  writeFileSync(join(dir, "deploy.yml"), "image: hamstrack:1\n");
  writeFileSync(join(dir, "tracked.txt"), "v2\n");
  git("add", "-A");
  r = live();
  assert.equal(r.stdout, "", `the hook refused the state the workflow actually finishes in — stage, run the hook, commit: ${reason(r)}`);

  // 3. failure shape 1: a new file never added. Invisible to `git diff --quiet`, to
  //    `git diff --cached --quiet` and to `git diff --name-only HEAD` (all three say clean), and
  //    invisible to `status.showUntrackedFiles=no` unless the hook passes -uall.
  writeFileSync(join(dir, "untracked.env.example"), "SECRET=\n");
  r = live();
  assert.match(reason(r), /\(1 untracked\)/, "a never-added file must be refused — this is the shape that reported guardedCount=8 and PASS on HD-314 while three new files went unmeasured");
  assert.match(reason(r), /untracked\.env\.example/);
  assert.doesNotMatch(reason(r), /ignored-by-rule/, "the ignored file is still not part of the change");
  assert.doesNotMatch(reason(r), onlyDirection("untracked"), "an untracked-only tree is accused of nothing else");
  assert.equal(spawnSync("git", ["diff", "--quiet"], { cwd: dir }).status, 0, "`git diff --quiet` calls this tree clean");
  assert.equal(spawnSync("git", ["diff", "--cached", "--quiet"], { cwd: dir }).status, 1, "and the index differs from HEAD, so the ticket's 'both hold' would refuse this correctly-staged half too");
  git("add", "untracked.env.example");

  // 4. failure shape 2: staged, then edited again. A commit from here publishes the staged bytes.
  //    Both are re-edited, because the staged half of the pair differs: `deploy.yml` was staged as
  //    an ADD and reads `AM`, `tracked.txt` as a MODIFY and reads `MM`. The bucket is keyed on the
  //    work-tree column alone, which is the only reading that catches both.
  writeFileSync(join(dir, "deploy.yml"), "image: hamstrack:2\n");
  writeFileSync(join(dir, "tracked.txt"), "v3\n");
  r = live();
  assert.match(reason(r), /\(2 staged-then-edited\)/);
  assert.match(reason(r), /deploy\.yml {2}\[AM\]/, "a staged ADD that was then edited is staged-but-stale too");
  assert.match(reason(r), /tracked\.txt {2}\[MM\]/);
  assert.doesNotMatch(reason(r), new RegExp(`ignored-by-rule|untracked\\.env\\.example|${rx(DIRECTION.untracked)}`),
    "only the stale paths are named, and only in their own direction");

  // 5. staging the edit returns the tree to the finishing state.
  git("add", "-A");
  r = live();
  assert.equal(r.stdout, "", `re-staging must clear the refusal, or the check has no exit: ${reason(r)}`);

  // 6. a staged RENAME, which is consistent and must finish — and is why `--no-renames` is on the
  //    command. Without it, `-z` emits `R  <new>` and then a SECOND NUL field holding `<old>`
  //    (measured 2026-10-09, git 2.54.0: `R  ren2\0del\0`); a one-record-per-field parse reads that
  //    stray field as a path whose first two characters are its status code, lands it in a
  //    direction bucket and refuses a clean tree. Drop the flag and this assertion reds.
  git("mv", "long.txt", "moved.txt");
  // The positive control first: without an `R` record there is no stray field and the assertion
  // below would pass whatever the hook did. This is the check that was missing from the first draft.
  const raw = spawnSync("git", ["status", "--porcelain", "-z"], { cwd: dir, encoding: "utf8" }).stdout;
  assert.match(raw, /(^|\0)R[ MD] moved\.txt\0long\.txt\0/,
    `git did not detect the rename, so this state does not exercise --no-renames at all. Raw -z output: ${JSON.stringify(raw)}. Rename detection needs enough similarity — keep the renamed file long and its content untouched.`);
  r = live();
  assert.equal(r.stdout, "", `a staged rename is a consistent tree and must finish: ${reason(r)}`);
});

test("seal: the exits of check-gates.mjs are exactly the rows of the table above", () => {
  const src = readFileSync(HOOK, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").split("\n").filter((l) => !/^\s*\/\//.test(l)).join("\n");
  const calls = (name) => (src.match(new RegExp(`(?<!function\\s)\\b${name}\\s*\\(`, "g")) || []).length;
  const exits = (src.match(/\bprocess\.exit\s*\(/g) || []).length;
  // Only SITE rows enter the arithmetic; a reuse row travels through a site another row already owns.
  const sites = EXITS.filter((r) => !r.reuses);
  const reuse = EXITS.filter((r) => r.reuses);
  const rows = {
    finish: sites.filter((r) => r.exit === "finish" && r.verdict === "pass" && !r.stdin).length + sites.filter((r) => r.exit === "finish" && r.verdict === "escaped").length,
    allow: sites.filter((r) => r.exit === "allow").length,
    block: sites.filter((r) => r.exit === "block").length,
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

  // Granularity control (HD-330). The population of this table is SITES and REUSE ROWS, and the
  // arithmetic above asks its question of sites only. If a future change merges the two units back
  // into one — a reuse row losing `reuses`, or a site row acquiring it — the arithmetic silently
  // changes what it measures while staying green, which is the shape that reads as coverage and is
  // not. So: every reuse row names a site row that exists, no site row names one, and both
  // populations carry a floor. The only honest count is the one this message computes.
  const siteIds = new Set(sites.map((r) => r.id));
  const orphans = reuse.filter((r) => !siteIds.has(r.reuses));
  assert.deepEqual(orphans.map((r) => `${r.id}->${r.reuses}`), [],
    `reuse row(s) name a site that is not in the table: ${orphans.map((r) => `${r.id} reuses ${r.reuses}`).join(", ")}. A reuse row travels through an existing exit; if that exit is gone, the row is a site row and belongs in the arithmetic.`);
  assert.equal(new Set(reuse.map((r) => r.id)).size, reuse.length, "every reuse row has its own id — two rows sharing one id hide a deleted behaviour");
  // The floors are set EQUAL to today's populations, not below them (they lagged by two until
  // HD-328, which permitted deleting two exits in silence). A floor whose only job is to notice a
  // deletion has no reason to leave slack: removing an exit is a deliberate edit to the row, the
  // floor and §6 of the spec, in one change. The live counts are computed by the messages.
  assert.ok(sites.length >= 15, `${sites.length} site rows; the floor is 15 (A1 x2, A2, A3, A4, B1-B10) — a removed exit row is never a legal shortening`);
  assert.ok(reuse.length >= 12,
    `${reuse.length} reuse rows; the floor is 12. Each one is a value SHAPE the hook must accept or a refusal WORDING that was the deliverable of a ticket (HD-301's Class#method seal, HD-330's seal list, own-seal-by-name, note-bearing gates, field-named category faults, HD-328's three directions and its finishing state). Deleting one deletes the only thing that notices the behaviour came back.`);
  assert.equal(EXITS.length, sites.length + reuse.length, "every row is exactly one of the two units");
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
  const skill = readFileSync(join(ROOT, ".claude/skills/feature-pipeline/SKILL.md"), "utf8");
  assert.match(skill, /history\.jsonl/);
  // HD-328: the hook refuses the unstaged state, so the one workflow that satisfies it has to be
  // written down where the orchestrator reads it. A check nobody can satisfy is worse than none.
  assert.match(skill, /git add -A/, "Phase 7 must name the staging step the §7b check requires");
  assert.match(pipelineDoc, /index and work tree disagree|index and the work tree disagree|index==worktree/, "§12.3 must state the X9 rule");
  assert.match(pipelineDoc, /guardedCount=8/, "§12.3 keeps the measurement the rule came from");
  for (const f of [".claude/pipeline/history.jsonl", ".claude/pipeline/checkpoints.jsonl"]) {
    const r = spawnSync("git", ["check-ignore", "-v", f], { cwd: ROOT, encoding: "utf8" });
    assert.equal(r.status, 0, `${f} must be ignored`);
    assert.match(r.stdout, /\.gitignore:\d+:/, `${f}: git check-ignore -v names the rule`);
  }
  assert.equal(spawnSync("git", ["check-ignore", "-q", ".claude/pipeline/fixtures/a4-pass.json"], { cwd: ROOT }).status, 1, "fixtures are tracked");
});

// HD-330 round 2. §6 of the HD-303 proposal states the SAME category this table states, and nothing
// compared them — so B7 and B8 reached that section only because a human remembered to put them
// there. The comparison was not worth writing before this file ran on an automated path
// (PipelineSealExecutionTest): a seal nobody runs cannot hold a document either. Compared over the
// SITE ids and nothing else — the doc's line-number column declares itself unmaintained in its own
// prose, and the reuse rows live in that section as prose rather than as table rows, so demanding
// them from the table would be demanding a different table.
test("the proposal's §6 exit table lists exactly the site ids of the table above", () => {
  const doc = readFileSync(join(ROOT, "docs/design/pipeline-history-proposal.md"), "utf8");
  const start = doc.indexOf("## 6. Category");
  assert.ok(start >= 0, "docs/design/pipeline-history-proposal.md has no '## 6. Category' heading: the section was renamed or removed. Re-point this test at it — the category still has to be stated in one place that a reader of the spec reaches.");
  const next = doc.indexOf("\n## ", start + 1);
  const section = next < 0 ? doc.slice(start) : doc.slice(start, next);
  const listed = [...section.matchAll(/^\| ([AB]\d+) \|/gm)].map((m) => m[1]);
  assert.ok(listed.length >= 14, `§6's exit table holds ${listed.length} row(s); the floor is 14 (A1-A4, B1-B10, read 2026-10-09). A scan that can pass over an empty table is not a scan — if the table really did shrink, an exit was deleted and the call-site arithmetic above is the thing to read.`);
  const sites = [...new Set(EXITS.filter((r) => !r.reuses).map((r) => r.id))].sort();
  assert.deepEqual([...new Set(listed)].sort(), sites,
    "§6 of docs/design/pipeline-history-proposal.md and the EXITS table above state one category and disagree. Add the missing row (id, what the exit is, what it records) or delete the stale one. The doc's line-number column is declared unmaintained there and is NOT compared; the reuse rows (C*) are prose in that section, not table rows.");
});

// HD-301. The counterpart of the B5 row, which the EXITS table cannot hold: B5 asserts the REFUSAL
// fires on a seal that does not exist, and a fixture that only ever asserts a refusal cannot notice
// that the same refusal fires on a VALID input too. Until this ticket the hook resolved the whole
// `sealedBy` string as a file name, so `Class#method` — the form every "test:" pointer in CLAUDE.md
// uses, and the form that says WHICH test holds the category — was refused as a missing file.
test("a seal named in CLAUDE.md's own Class#method form resolves to the class that holds it", () => {
  const r = runHook({ runPath: fixture("c8-sealedby-method.json"), history: join(TMP, "c8-history.jsonl") });
  assert.equal(r.status, 0, `the hook refused a seal that exists: ${r.stdout}${r.stderr}`);
  assert.doesNotMatch(r.stdout, /sealedBy names/, "the hook resolved the #method half as part of the file name");
  // The class really is there, so a green here is not green-because-absent.
  const cls = "src/test/java/com/hamstrack/common/testsupport/SuiteCoverageGuardTest.java";
  assert.ok(existsSync(join(ROOT, cls)), `${cls} must exist for this test to mean anything`);
});
