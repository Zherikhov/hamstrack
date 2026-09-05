#!/usr/bin/env bash
# HD-186 — drive one mix up the concurrency ladder and stop one stage after the breach.
# Spec: §4.3 (the breach definition), §4.5 (the ladder), §5.3.
#
#   k6/run-ladder.sh browse|report-search|write [output-dir]
#
# ---------------------------------------------------------------------------
# ONE k6 RUN PER STAGE, AND THAT IS THE MECHANISM, NOT A CONVENIENCE.
#
# §4.3 defines the reported capacity as:
#
#   "the highest completed stage at which every target held FOR THE WHOLE HOLD PERIOD and
#    no provisioned alert rule's condition was met for its `for:` duration."
#
# Encoding the whole ladder in one k6 run cannot express that. k6 evaluates thresholds over
# the WHOLE run, so a single 55-minute run would produce one verdict smeared across eleven
# concurrency levels — the early stages would mask the late ones and the reported p95 would
# belong to no stage at all. One invocation per stage gives each stage its own thresholds,
# its own exit code and its own summary file, and the ladder becomes a sequence of
# independently falsifiable claims.
#
# The requests made during the ramp are tagged `phase:ramp` and excluded from the latency
# thresholds (lib/config.js), which is the other half of "held for the whole hold": the
# first stage of each mix is warm-up and its numbers are reported but not used for the
# breach determination (§5.4).
#
# ---------------------------------------------------------------------------
# ONE k6 RUN PER STAGE ALSO MEANS ONE FRESH READ OF tokens.json PER STAGE, AND THAT COSTS.
#
# /api/auth/refresh ROTATES (AuthService.refresh deletes the presented row), so a refresh
# token is SINGLE-USE. Every stage is a separate process seeded from the same file, so the
# first stage that runs past the access tokens' expiry spends the chain for every account
# and every later stage then presents a string the server has already deleted: 400, then
# hs_auth_failures, then abort. The ladder is ~66 minutes and access tokens live 30, so the
# upper half — where the capacity number comes from — was unmeasurable, and nothing in any
# summary said so.
#
# So this script now REFUSES to start a stage that would end after the expiry, and (unless
# LOAD_AUTO_REFRESH=0) advances the whole file's chain first with k6/refresh-tokens.js,
# recording the pause in the ladder log. The invariant it is holding is:
# ONE REFRESH PER ACCOUNT, EVER, PER tokens.json.
#
# ---------------------------------------------------------------------------
# WHAT THIS SCRIPT DOES NOT DECIDE.
#
# It stops escalating on the first CAPACITY breach and runs ONE more stage to characterise
# it. It does NOT decide the reported capacity: the second half of §4.3's definition is
# about the provisioned ALERT RULES, which live on the box and which this script cannot see.
# capture/alert-rules.sh lists them, and the operator confirms none fired for its `for:`
# duration before a stage is recorded as passed.
#
# That gap is deliberate and is stated so nobody reads a green exit code as the verdict.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIX="${1:?usage: run-ladder.sh browse|report-search|write [output-dir]}"

# NOT inside ops/loadtest/. `ops/` is a synced path, and a directory of files added to one
# is what the drift check reports — with ConfigDrift (for: 30m) among the provisioned rules
# that make up half the breach bar, a results directory in here would mean no stage could
# be recorded as passed. The harness would invalidate its own run.
: "${LOAD_RESULTS_DIR:=/var/tmp/hd186}"
OUT="${2:-$LOAD_RESULTS_DIR/$(date -u +%Y%m%dT%H%M%SZ)-$MIX}"

SCRIPT="$HERE/$MIX.js"
[[ -f "$SCRIPT" ]] || { echo "no such mix: $SCRIPT" >&2; exit 2; }

: "${BASE_URL:?set BASE_URL}"
: "${LADDER:=1 2 5 10 15 20 30 45 60 80 100}"
: "${RAMP:=1m}"
: "${HOLD:=4m}"
# ABSOLUTE, and that is not a preference: k6's open() resolves a relative path against THE
# SCRIPT THAT CALLS IT (lib/auth.js), not against the working directory, so './tokens.json'
# means k6/lib/tokens.json — which is not where mint-tokens.js writes it. The file's home is
# also outside the synced ops/ tree, because it is a credential file for ~227 accounts.
: "${TOKENS_FILE:=$LOAD_RESULTS_DIR/tokens.json}"

# The canary's target is RESOLVED in setup() from a workspace-B principal and asserted to
# be both real and foreign (lib/fixture.js). It is no longer required here, and setting it
# is now a CROSS-CHECK rather than an input: a mismatch is fatal, which is what catches the
# stale id from a previous fixture that a pasted value used to hide.
: "${CANARY_WORKSPACE_ID:=}"

# ---------------------------------------------------------------------------
# EVERYTHING THIS SCRIPT WRITES IS PRIVATE TO THE OPERATOR, AND THE DEFAULTS ARE NOT.
#
# LOAD_RESULTS_DIR defaults to /var/tmp/hd186 — created by `mkdir -p` under a world-readable
# sticky /var/tmp, so 0755 — and tokens.json lands in it. k6's handleSummary writes with
# os.Create, i.e. 0644. That made ~227 live access tokens and ~227 THIRTY-DAY refresh tokens
# readable by every local account on the generator. The harness already gets this right for
# its two SMALLER secrets (.loadtest.env at 0600, verify-api.sh's curl config at 0600) and
# missed it for by far the largest one.
#
# umask first so nothing created below this line can be group- or world-readable, then
# `install -d -m 0700` for the directories themselves (mkdir -p does not narrow an existing
# directory, and this one usually already exists from a previous window).
umask 077
install -d -m 0700 "$LOAD_RESULTS_DIR"
install -d -m 0700 "$OUT"
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$OUT/ladder.log"; }
die() { log "FATAL: $*"; exit 2; }

# jq IS REQUIRED, AND SAYING WHY IS CHEAPER THAN A SILENT DEGRADATION.
#
# Three things below read structured data: the pool sizes and token expiry out of
# tokens.json, k6's own peak VU count out of `k6 inspect`, and — the one that decides what a
# stage MEANS — which threshold failed, out of the stage summary. A grep-based fallback for
# the last of those would answer "some threshold failed", which is exactly the answer that
# publishes a harness fault as a capacity number.
command -v jq >/dev/null 2>&1 || die "jq is required (apt-get install -y jq).
    It is used to tell a CAPACITY breach from a BROKEN HARNESS in each stage's summary. A
    fallback that could only see 'k6 exited non-zero' is how a self-saturated generator gets
    written up as 'capacity is N VU' — conservative-looking, defensible and wrong."
command -v k6 >/dev/null 2>&1 || die "k6 is not on PATH"
[[ -f "$TOKENS_FILE" ]] || die "no tokens file at $TOKENS_FILE — run k6/mint-tokens.js first
    (TOKENS_FILE must be absolute and outside the synced ops/ tree)."

# A CREDENTIAL FILE'S MODE IS CHECKED, NOT ASSUMED, AND THE RUN REFUSES ON IT.
#
# k6 has no way to say "create this 0600": handleSummary goes through os.Create, which is
# 0644 before umask. `umask 077` above covers everything THIS process creates, but
# tokens.json is written by a SEPARATE k6 invocation (mint-tokens.js), usually in a
# different shell, minutes to hours earlier. So the mode is asserted here, where the file is
# about to be read by up to 201 VUs, rather than hoped for where it was written.
require_tokens_private() {
    local mode
    mode="$(stat -c %a "$TOKENS_FILE" 2>/dev/null || echo '?')"
    [[ "$mode" == "600" ]] && return 0
    die "$TOKENS_FILE is mode $mode, and it must be 600.
    It holds ~227 live access tokens and ~227 refresh tokens good for thirty days; at 0644
    every local account on this generator can read them, and a stolen refresh token cannot
    be revoked from the client side. k6's handleSummary creates files 0644 (os.Create), so a
    file minted without \`umask 077\` gets this by default. Fix and re-run:
        chmod 600 $TOKENS_FILE
        chmod 700 $LOAD_RESULTS_DIR
    Then re-mint or advance the chain if the file may have been read."
}
require_tokens_private

# --- the pool, per role ------------------------------------------------------
# accountsA only. accountsCanary is the tenancy canary's own principal and accountsB are
# members of the foreign workspace; neither is ever load, and counting them here would
# report a pool bigger than the one the VUs draw from.
POOL="$(jq '.accountsA | length' "$TOKENS_FILE")"
POOL_CANARY="$(jq '.accountsCanary | length // 0' "$TOKENS_FILE")"
POOL_B="$(jq '.accountsB | length // 0' "$TOKENS_FILE")"
[[ "$POOL_CANARY" -gt 0 ]] || die "$TOKENS_FILE has no accountsCanary.
    The tenancy canary's principal is its OWN address family (load-ac-NNN) precisely so no
    load VU can hold the same account and rotate its single-use refresh token out from under
    it. A file without that pool predates the fix; re-mint."
[[ "$POOL_B" -gt 0 ]] || die "$TOKENS_FILE has no accountsB — the canary cannot prove its
    target exists, and every mix will refuse to start."

log "mix=$MIX ladder='$LADDER' ramp=$RAMP hold=$HOLD"
log "pool: $POOL load (accountsA) + $POOL_CANARY canary + $POOL_B foreign"
log "output: $OUT"

# --- durations ---------------------------------------------------------------
dur_s() {   # k6 duration string -> seconds
    local d="$1" n u
    [[ "$d" =~ ^([0-9]+(\.[0-9]+)?)(ms|s|m|h)$ ]] || die "unparseable duration: $d"
    n="${BASH_REMATCH[1]}"; u="${BASH_REMATCH[3]}"
    case "$u" in
        ms) awk "BEGIN{printf \"%d\", $n/1000}" ;;
        s)  awk "BEGIN{printf \"%d\", $n}" ;;
        m)  awk "BEGIN{printf \"%d\", $n*60}" ;;
        h)  awk "BEGIN{printf \"%d\", $n*3600}" ;;
    esac
}
STAGE_SECONDS=$(( $(dur_s "$RAMP") + $(dur_s "$HOLD") ))

# ---------------------------------------------------------------------------
# THE PEAK VU COUNT IS ASKED OF k6, NOT COMPUTED HERE.
#
# This guard used to compare POOL against the ladder's top VUS — 100 against 120, silently
# fine — while `browse` declares THREE scenarios (browse at VUS, sse at VUS, canary at 1)
# and therefore instantiates 2xVUS+1 = 201 VUs. 81 accounts were held twice, and because
# /api/auth/refresh rotates, both holders of an account destroyed each other's session at
# the first refresh. THE GUARD COULD NOT SEE IT BECAUSE IT WAS COMPARING THE WRONG NUMBER.
#
# Re-deriving `2xVUS+1` here would move the arithmetic rather than remove it, and it would
# be stale the first time a mix gains a scenario. `k6 inspect --execution-requirements`
# resolves the same options k6 will run and reports the maxVUs IT will allocate, so the
# guard and the run cannot disagree. lib/auth.js:requirePrincipals() computes the same
# number from inside setup() — two independent refusals, from opposite ends.
#
# And it REFUSES. A warning was defensible while sharing merely blunted the per-principal
# budgets; it is not defensible now that sharing breaks authentication for both holders.
peak_vus() {
    local vus="$1" out
    out="$(k6 inspect --execution-requirements "$SCRIPT" \
            -e "BASE_URL=$BASE_URL" \
            -e "HOST_HEADER=${HOST_HEADER:-}" \
            -e "TOKENS_FILE=$TOKENS_FILE" \
            -e "VUS=$vus" -e "RAMP=$RAMP" -e "HOLD=$HOLD" 2>/dev/null)" || return 1
    printf '%s' "$out" | jq -e '.maxVUs' 2>/dev/null
}

# ---------------------------------------------------------------------------
# THE ACCESS TOKENS HAVE AN EXPIRY AND THE LADDER IS LONGER THAN IT.
# ---------------------------------------------------------------------------
: "${LOAD_AUTO_REFRESH:=1}"
: "${TOKEN_EXPIRY_MARGIN_SECONDS:=120}"

tokens_expire_at() {
    local minted lifetime
    minted="$(jq -r '.mintedAt // empty' "$TOKENS_FILE")"
    lifetime="$(jq -r '.accessTokenLifetimeSeconds // empty' "$TOKENS_FILE")"
    [[ -n "$minted" && -n "$lifetime" && "$lifetime" != "null" ]] || return 1
    echo $(( $(date -u -d "$minted" +%s) + lifetime ))
}

advance_chain() {
    log "advancing the token chain (k6/refresh-tokens.js) — the refresh tokens in"
    log "        $TOKENS_FILE are SINGLE-USE, and this spends them once, deliberately,"
    log "        HERE rather than accidentally inside a measured stage."
    local t0 t1
    t0="$(date -u +%s)"
    if ! k6 run "$HERE/refresh-tokens.js" \
            -e "BASE_URL=$BASE_URL" \
            -e "HOST_HEADER=${HOST_HEADER:-}" \
            -e "TOKENS_FILE=$TOKENS_FILE" \
            >> "$OUT/refresh.log" 2>&1; then
        log "the chain advance FAILED — see $OUT/refresh.log"
        die "tokens.json can no longer be advanced. A rotated refresh token cannot be
    recovered from the client side, so the repair is a RE-MINT:
        TOKENS_FILE=$TOKENS_FILE k6 run $HERE/mint-tokens.js
    Record the pause in the run record; a re-mint takes about twenty-three minutes at the
    per-IP auth limiter's 10 logins/minute, and it is a pause in the window, not a gap in
    the measurement."
    fi
    # refresh-tokens.js REWRITES the file through handleSummary -> os.Create, so the mode is
    # re-applied here after every advance rather than only at mint time. This is the same
    # file, with the same thirty-day refresh tokens, rewritten several times per ladder.
    chmod 600 "$TOKENS_FILE"
    t1="$(date -u +%s)"
    log "chain advanced in $((t1 - t0))s. RECORD THIS PAUSE — it is time the instance spent"
    log "        not under load, between two stages that are compared with each other."
}

ensure_tokens_valid_for_stage() {
    local expires now finish
    if ! expires="$(tokens_expire_at)"; then
        log "WARNING: $TOKENS_FILE carries no mintedAt/accessTokenLifetimeSeconds, so the"
        log "         expiry cannot be checked. It was minted before those fields existed;"
        log "         re-mint to get the guard. Continuing WITHOUT it means a stage may run"
        log "         past the expiry, spend every account's single-use refresh token, and"
        log "         leave every LATER stage unable to authenticate."
        return 0
    fi
    now="$(date -u +%s)"
    finish=$(( now + STAGE_SECONDS + TOKEN_EXPIRY_MARGIN_SECONDS ))
    [[ "$finish" -lt "$expires" ]] && return 0

    log "the access tokens in $TOKENS_FILE expire at $(date -u -d "@$expires" +%Y-%m-%dT%H:%M:%SZ);"
    log "        this stage would still be running then ($((finish - now))s of stage + margin)."
    if [[ "$LOAD_AUTO_REFRESH" == "1" ]]; then
        advance_chain
        return 0
    fi
    die "refusing to start a stage that would cross the access-token expiry.
    Running it anyway is not a smaller problem than stopping: every VU would refresh, and
    /api/auth/refresh ROTATES, so this file's single-use tokens would be spent and EVERY
    LATER STAGE would fail hs_auth_failures on a correct server. The upper half of the
    ladder is where the capacity number comes from.
    Either:
        TOKENS_FILE=$TOKENS_FILE k6 run $HERE/refresh-tokens.js     (about a minute)
    or re-mint:
        TOKENS_FILE=$TOKENS_FILE k6 run $HERE/mint-tokens.js        (about 23 minutes)
    LOAD_AUTO_REFRESH=1 (the default) does the first automatically and logs the pause."
}

# ---------------------------------------------------------------------------
# WHAT A NON-ZERO EXIT MEANS — SIX THRESHOLDS, AND ONLY SOME OF THEM ARE CAPACITY.
#
# This used to write ANY non-zero k6 exit into the log as "stage N VU: BREACHED". Six
# thresholds can abort a stage and only three of them are a statement about the product:
# a self-saturated generator (dropped_iterations), a fleet that stopped authenticating
# (hs_auth_failures) and a fleet that is 404ing (hs_unexpected_404) all describe THE
# HARNESS. Publishing one of those as "capacity is N VU" produces a number that looks
# conservative, reads as defensible, and is wrong.
#
#   harness   dropped_iterations · hs_auth_failures · hs_unexpected_404
#             -> NOT a capacity result. Nothing below this stage is invalidated; fix the
#                harness and re-run THIS stage.
#   incident  hs_canary_leak -> stop everything, §5.3 condition 5.
#   breach    latency p95/p99 · hs_budget_422 · hs_refused_429 · hs_conflict_409 ·
#             hs_rebalance_429 · hs_errors_5xx -> the capacity result.
# ---------------------------------------------------------------------------
# BOTH OUTPUTS ARE GLOBALS, AND THAT IS WHY THE CALLER MUST NOT USE A COMMAND SUBSTITUTION.
#
# This function has two results: the verdict, and the LIST OF THRESHOLDS that produced it. A
# bash function can only return one of them through stdout, so the list was a global — and
# the caller wrote `KIND="$(classify_stage …)"`, which runs the whole thing in a SUBSHELL,
# where that assignment dies with the subshell. Every non-zero stage this script has ever
# run therefore logged
#
#   stage 45 VU: k6 exit 99 — failed thresholds: <none parsed>
#
# on the one line that records WHICH §4.3 target broke — the line the results table is
# written from. So the verdict is a global too, and the caller invokes this in the CURRENT
# shell rather than in a substitution.
FAILED_THRESHOLDS=""
STAGE_KIND=""
classify_stage() {                       # sets STAGE_KIND: harness | incident | breach | unknown
    local summary="$1"
    FAILED_THRESHOLDS=""
    STAGE_KIND="unknown"
    [[ -s "$summary" ]] || { FAILED_THRESHOLDS="(no summary written)"; return 0; }

    # THE BOOLEAN IN --summary-export MEANS "BREACHED", NOT "OK". Verified against a real
    # export: hs_canary_leak with count=2 exports {"count==0": true} while every passing
    # threshold exports false. Getting this backwards inverts the whole classification —
    # every clean stage would be reported as six simultaneous breaches — so it is read from
    # an actual file rather than from memory. The object form ({"ok": …}) is k6's newer
    # summary shape and is handled the other way round, because there the field means what
    # it says.
    FAILED_THRESHOLDS="$(jq -r '
        (.metrics // {}) | to_entries[] | .key as $m
        | ((.value.thresholds // {}) | to_entries[]
           | select(.value | if type == "object" then (.ok == false) else (. == true) end)
           | "\($m) [\(.key)]")' "$summary" | sort -u | tr '\n' ' ')"

    if printf '%s' "$FAILED_THRESHOLDS" | grep -q 'hs_canary_leak'; then STAGE_KIND=incident; return 0; fi
    if printf '%s' "$FAILED_THRESHOLDS" \
        | grep -qE 'dropped_iterations|hs_auth_failures|hs_unexpected_404'; then
        STAGE_KIND=harness; return 0
    fi
    [[ -n "$FAILED_THRESHOLDS" ]] && { STAGE_KIND=breach; return 0; }
    STAGE_KIND=unknown
    return 0
}

# ---------------------------------------------------------------------------
# THE SEAL ON THE OTHER HALF: A THRESHOLD THAT CANNOT RECEIVE A SAMPLE.
#
# A custom-metric sample carries only the tags passed to .add(). record() built its tag
# object without `phase`, so four budget thresholds keyed on {phase:hold}
# (hs_refused_429{class:search|report}, hs_conflict_409, hs_rebalance_429) selected
# sub-metrics that could never receive one — a vacuous pass, or a spurious no-data failure,
# and no reader could tell either from a real verdict.
#
# The fix is in record(). THIS is what stops it coming back: every threshold key the stage
# declares is read back and a sub-metric that took no samples fails the ladder here, loudly,
# rather than passing quietly.
#
# ---------------------------------------------------------------------------
# THE KEY LIST IS ASKED OF k6. IT USED TO BE SCRAPED OUT OF THE STAGE LOG, AND THAT MADE THE
# SEAL ITSELF VACUOUS — IT REPORTED SUCCESS ON EVERY STAGE EVER RUN.
#
# setup() printed the list with console.log and this function sed'd it back out of the
# tee'd stage log. k6 does not print console.log verbatim: it goes through logrus, so the
# line on disk is
#
#   time="…" level=info msg="HD186_THRESHOLD_KEYS [\"dropped_iterations\",…]" source=console
#
# and `sed -n 's/.*HD186_THRESHOLD_KEYS //p'` yielded
#
#   [\"dropped_iterations\",…]" source=console
#
# which is not JSON. `jq --argjson` exited 2 before evaluating a single key, `2>/dev/null`
# hid the message, `|| true` swallowed the status, $vacuous came back empty and the function
# returned 0 — with no NOTE branch either, because $keys was non-empty. So record()'s
# `phase` tag could have been deleted again by a one-word edit and the ladder would still
# have published a capacity number attested by four targets that were never measured. The
# diagnosis was fixed; the seal was not. Falsify the old form in one command:
#
#   grep HD186_THRESHOLD_KEYS stage-1vu.log
#
# `k6 inspect` prints the resolved options object as JSON — the same source of truth the run
# itself uses, and the same one `peak_vus` already asks for the principal guard — so no log
# formatting stands between the seal and its input.
#
# AND IT NO LONGER FAILS OPEN. An unobtainable key list is a FAILURE, not a note: a seal that
# cannot see its input is a seal that did not run, and the only thing worse than an
# unmeasured threshold is an unmeasured threshold with a green tick beside it.
threshold_keys() {
    local vus="$1" out
    out="$(k6 inspect "$SCRIPT" \
            -e "BASE_URL=$BASE_URL" \
            -e "HOST_HEADER=${HOST_HEADER:-}" \
            -e "CANARY_WORKSPACE_ID=${CANARY_WORKSPACE_ID:-}" \
            -e "LOAD_WS_SLUG_PREFIX=${LOAD_WS_SLUG_PREFIX:-hd186-load-}" \
            -e "TOKENS_FILE=$TOKENS_FILE" \
            -e "VUS=$vus" -e "RAMP=$RAMP" -e "HOLD=$HOLD" 2>/dev/null)" || return 1
    # An EMPTY `k6 inspect` is not an empty threshold list. jq reads empty input as no input,
    # emits nothing and exits 0 — so without this line a failed inspect would hand the caller
    # a zero status and an empty string, which is the fail-open shape this seal was rewritten
    # to remove.
    [[ -n "$out" ]] || return 1
    printf '%s' "$out" | jq -c '(.thresholds // {}) | keys' || return 1
}

check_thresholds_received_samples() {
    local summary="$1" vus="$2" keys vacuous
    if [[ ! -s "$summary" ]]; then
        log "!!! no summary at $summary, so the vacuous-threshold seal has nothing to read."
        return 1
    fi
    if ! keys="$(threshold_keys "$vus")"; then
        log "!!! the threshold key list could not be read from \`k6 inspect $SCRIPT\`, so the"
        log "!!! vacuous-threshold seal did not run — and a seal that did not run is a"
        log "!!! FAILURE here, not a note. (Its predecessor scraped this list out of the"
        log "!!! stage log, silently got a non-JSON string back and passed every stage.)"
        return 1
    fi
    if [[ "$keys" == "[]" ]]; then
        log "!!! $SCRIPT declares NO thresholds. Every §4.3 target for this mix is absent, so"
        log "!!! the stage cannot breach and cannot pass — it can only exit 0, which is not"
        log "!!! the same thing and must never be read as one."
        return 1
    fi
    # Three shapes of "received nothing", because k6 exports three shapes of sub-metric:
    #   absent            no sample ever carried these tags;
    #   Rate              passes + fails == 0;
    #   Trend             every statistic is 0, which no real duration sample can produce.
    # A COUNTER IS NOT ASKED FOR SAMPLES — that is a property of the METRIC TYPE and not a
    # list of names, because a list here goes stale one entry before it looks wrong. A
    # Counter key in this harness states a count (`count==0` for the ones that must not
    # happen, `count>=0` for the ones that are merely recorded), and its correct outcome is
    # an EMPTY metric; demanding a sample from any of them would fail every clean stage. They are
    # still checked for ABSENCE, but AN ABSENT KEY IS A BACKSTOP THAT DOES NOT FIRE, AND THE
    # DRY RUN AGAINST REAL k6 v2.2.0 IS WHERE THAT WAS MEASURED: k6 materialises a sub-metric
    # for every threshold key it is DECLARED, whether or not any sample ever carried those
    # tags. A sabotaged summary and a clean one are byte-identical for such a key —
    # {"count":0,"rate":0,"thresholds":{"count==0":false}} — so for a Counter-only key set
    # this function cannot tell the two apart at all.
    #
    # THE CONSEQUENCE IS A RULE ON THE MIXES, NOT ON THIS FUNCTION. The seal can only witness
    # a record()-tagging regression through a Rate or Trend key scoped to {phase:hold}, so
    # every class with a latency target declares hs_refused_429{class:…,phase:hold}
    # (lib/classes.js) — it takes a sample on every response, in a read-only mix as much as
    # in a writing one. Before that, deleting `phase` from record() passed an entire browse
    # ladder here and failed only from the write mix onward.
    #
    # NO `2>/dev/null`, NO `|| true`. A jq that cannot evaluate is the failure mode this
    # whole function was rewritten for.
    if ! vacuous="$(jq -r --argjson keys "$keys" '
        . as $root
        | $keys[]
        | . as $k
        | ($root.metrics[$k] // null) as $m
        | if $m == null then "\($k) (absent from the summary: no sample ever carried these tags)"
          elif ($m.passes != null and $m.fails != null and ($m.passes + $m.fails) == 0)
            then "\($k) (a rate sub-metric with 0 samples)"
          elif ($m.avg != null and $m.max != null and $m.avg == 0 and $m.max == 0)
            then "\($k) (a latency sub-metric with 0 samples: this class was never exercised)"
          else empty end' "$summary")"; then
        log "!!! the vacuous-threshold seal could not evaluate $summary against the key list"
        log "!!! \`k6 inspect\` reported. That is a harness fault, and it is reported as one"
        log "!!! rather than swallowed: swallowing it is precisely how the previous seal"
        log "!!! passed every stage it was ever asked about."
        return 1
    fi
    [[ -z "$vacuous" ]] && return 0
    log "!!! VACUOUS THRESHOLDS — these were declared and could not receive a sample:"
    printf '%s\n' "$vacuous" | sed 's/^/!!!   /' | tee -a "$OUT/ladder.log"
    log "!!! A threshold with no samples is not a pass. Either the tag set on the metric"
    log "!!! and the tag set on the threshold key disagree (this is what happened: record()"
    log "!!! omitted \`phase\`), or the mix does not exercise that class and should not be"
    log "!!! declaring the threshold. Both are defects in the harness, not results."
    return 1
}

BREACHED=""
EXTRA_DONE=0
HARNESS_FAULT=0

for VUS in $LADDER; do
    STAMP="$(date -u +%H%M%SZ)"
    log "--- stage ${VUS} VU (start $STAMP) ---"

    # --- principals, from k6's own view of what it will allocate ---------
    PEAK="$(peak_vus "$VUS" || true)"
    if [[ -z "$PEAK" || "$PEAK" == "null" ]]; then
        die "\`k6 inspect --execution-requirements $SCRIPT\` did not report maxVUs.
    That number is what decides whether every VU can be handed its own principal, and it is
    asked of k6 rather than computed here precisely so the guard and the run cannot
    disagree. Refusing rather than falling back to arithmetic: the arithmetic is what was
    wrong before (it compared the pool against VUS while \`browse\` allocates 2xVUS+1)."
    fi
    if [[ "$PEAK" -gt "$POOL" ]]; then
        log "REFUSING stage ${VUS} VU: it allocates $PEAK VUs (k6's own maxVUs, across every"
        log "         scenario the mix declares) and accountsA holds only $POOL."
        log "         lib/auth.js maps VU -> principal injectively (accountsA[__VU - 1], no"
        log "         modulo) so that two VUs cannot share one account. Sharing is not a"
        log "         blunt instrument here: /api/auth/refresh ROTATES, so two holders of one"
        log "         account destroy each other's session, one of them 400s, and the canary"
        log "         used to report that 400 as a tenancy incident."
        log "         Mint more (ACCOUNTS=$((PEAK + 10)) k6 run $HERE/mint-tokens.js) — and"
        log "         the FIXTURE must hold that many workspace-A load accounts first"
        log "         (fixture/10-generate.sql section 1)."
        exit 5
    fi
    log "principals: $PEAK VUs to be allocated, $POOL available (k6 inspect)"

    # --- the tokens must outlive the stage, and stay private --------------
    # Re-checked per stage, not once at startup: a ladder is an hour long, the file is
    # rewritten by every chain advance, and the whole point of the check is the window
    # between writes.
    require_tokens_private
    ensure_tokens_valid_for_stage

    # THIS IS THE GENERATOR'S OWN DISK, AND IT IS NOT A SECOND OPINION ON THE BOX'S.
    #
    # It used to run `df` on LOAD_DISK_PATH and the README presented it as independent
    # redundancy for abort condition 1 — "the only failure here that can damage real data".
    # It never was: this script runs on the GENERATOR, and condition 1 is about the
    # filesystem holding the production database, on the other machine.
    #
    # What it does check is real: k6 writes a compressed raw stream and a summary per stage
    # into $OUT, and a generator that fills its own disk mid-ladder loses the evidence for
    # the stage it is running. That is a lost measurement, not lost data.
    #
    # The box's disk is watched by capture/watchdog.sh, which writes a STOP FILE. If that
    # file is reachable from here — a shared mount, an rsync, an SSM copy — name it in
    # LOAD_ABORT_FILE and this loop honours it. Unset, it is not silently assumed.
    GEN_FREE="$(df -Pm "$OUT" | awk 'NR==2 {print $4}')"
    if [[ "$GEN_FREE" -lt "${GEN_MIN_FREE_MB:-500}" ]]; then
        log "ABORT: the GENERATOR's free disk under $OUT is ${GEN_FREE} MB, below the"
        log "       ${GEN_MIN_FREE_MB:-500} MB floor. The captures for this stage would be"
        log "       truncated. This says nothing about the box — that is the watchdog's."
        exit 3
    fi
    if [[ -n "${LOAD_ABORT_FILE:-}" && -f "$LOAD_ABORT_FILE" ]]; then
        log "ABORT: the box-side watchdog wrote a stop file ($LOAD_ABORT_FILE):"
        sed 's/^/       /' "$LOAD_ABORT_FILE" | tee -a "$OUT/ladder.log"
        exit 4
    fi

    SUMMARY="$OUT/stage-${VUS}vu.summary.json"
    STAGELOG="$OUT/stage-${VUS}vu.log"

    set +e
    T0="$(date -u +%s)"
    k6 run "$SCRIPT" \
        -e "BASE_URL=$BASE_URL" \
        -e "HOST_HEADER=${HOST_HEADER:-}" \
        -e "CANARY_WORKSPACE_ID=${CANARY_WORKSPACE_ID:-}" \
        -e "LOAD_WS_SLUG_PREFIX=${LOAD_WS_SLUG_PREFIX:-hd186-load-}" \
        -e "TOKENS_FILE=$TOKENS_FILE" \
        -e "VUS=$VUS" -e "RAMP=$RAMP" -e "HOLD=$HOLD" \
        --summary-export "$SUMMARY" \
        --out "json=$OUT/stage-${VUS}vu.raw.json.gz" \
        2>&1 | tee "$STAGELOG"
    RC=${PIPESTATUS[0]}
    T1="$(date -u +%s)"
    set -e
    ELAPSED=$(( T1 - T0 ))

    # ---------------------------------------------------------------------
    # CLASSIFY FIRST, ACT SECOND. THE SEAL MUST NOT PREEMPT THE SECURITY ABORT.
    #
    # This used to run the vacuous-threshold seal here, before RC was even looked at. Every
    # abort in this harness fires INSIDE THE RAMP, because the things it aborts on are
    # properties of the SERVER and are therefore present from t=0, not properties of the
    # load that build up during the hold:
    #
    #   hs_canary_leak     delayAbortEval 15s, and the canary probes every 10s from 0s
    #   hs_errors_5xx      delayAbortEval 30s
    #   hs_auth_failures   delayAbortEval 60s
    #
    # RAMP defaults to 1m. So a real cross-tenant leak stops the stage about fifteen seconds
    # in, and every {phase:hold} sub-metric in that stage's summary is empty BY CONSTRUCTION
    # — which is exactly what the seal is built to call a harness fault. Ordered the old way,
    # and with the seal actually working, a tenancy incident would have exited 6 ("both are
    # defects in the harness, not results") and `exit 7`, "preserve everything" and the whole
    # incident path would never have run. The loudest failure in the harness would have been
    # relabelled as the quietest.
    #
    # So: classify, dispatch the incident, and only then ask the seal — and ask it only about
    # a stage that actually reached the hold.
    KIND=""
    if [[ "$RC" -ne 0 ]]; then
        classify_stage "$SUMMARY"        # in THIS shell — see the note on classify_stage()
        KIND="$STAGE_KIND"
        log "stage ${VUS} VU: k6 exit $RC — failed thresholds: ${FAILED_THRESHOLDS:-<none parsed>}"
        if [[ "$KIND" == "incident" ]]; then
            log "!!! TENANCY CANARY LEAK (§5.3 condition 5). THIS IS NOT A CAPACITY RESULT."
            log "!!! Stop the run, preserve everything (this directory, the box-side"
            log "!!! capture, the raw stream) and treat it as a SECURITY INCIDENT."
            log "!!! $STAGELOG IS ITSELF LEAKED TENANT DATA: canary.js prints part of the"
            log "!!! foreign response body into it so the leak can be characterised. Handle"
            log "!!! the whole of $OUT accordingly — README §5.3 condition 5."
            exit 7
        fi
    fi

    # ---------------------------------------------------------------------
    # THE SEAL IS HOLD-SCOPED, SO IT ONLY SPEAKS ABOUT A STAGE THAT REACHED THE HOLD.
    #
    # ELAPSED is the wall clock around `k6 run` and therefore includes setup() and teardown:
    # it is a FLOOR on the k6 run's own duration, so a stage that ran to completion always
    # clears STAGE_SECONDS and a stage aborted fifteen seconds in never does (unless setup()
    # alone outlasted the entire stage, which would be its own finding). `--summary-export`
    # carries only `metrics` and `root_group` — there is no test-duration field in it to read
    # instead, which is why this is measured out here rather than parsed out of the summary.
    if [[ "$ELAPSED" -ge "$STAGE_SECONDS" ]]; then
        if ! check_thresholds_received_samples "$SUMMARY" "$VUS"; then
            log "!!! stage ${VUS} VU produced a verdict from at least one threshold that could"
            log "!!! not receive a sample. STOPPING: the ladder cannot mean anything while a"
            log "!!! declared target is unmeasured, and a later stage would inherit the defect."
            exit 6
        fi
    else
        log "      this stage ended after ${ELAPSED}s of a ${STAGE_SECONDS}s stage: it aborted"
        log "      during the ramp, so the hold-scoped seal is not applicable and did NOT run."
        log "      The classification is the verdict for this stage."
    fi

    if [[ "$RC" -eq 0 ]]; then
        log "stage ${VUS} VU: PASSED all §4.3 targets for the whole hold"
        # SEAL THE CLASSIFIER'S POLARITY, ON EVERY CLEAN STAGE.
        #
        # classify_stage() reads a BOOLEAN out of --summary-export and that boolean means
        # "breached", not "ok" — the opposite of what the field name suggests. Reading it
        # backwards does not produce a visible mess: k6 only exits non-zero when something
        # actually failed, so classify_stage() is only consulted in the `else` below, and an
        # inverted reading would quietly relabel a GENUINE BREACH as "harness fault, nothing
        # below is invalidated". Worse than noise, and quieter.
        #
        # So every clean stage proves the polarity: the same jq, on a summary k6 has just
        # certified as all-passing, must find NOTHING. A k6 release that flips the field is
        # caught on stage 1 of the first mix, before any number is published.
        classify_stage "$SUMMARY"
        if [[ -n "$FAILED_THRESHOLDS" ]]; then
            log "!!! CLASSIFIER POLARITY SEAL FAILED. k6 exited 0 — every threshold passed —"
            log "!!! and classify_stage() read these out of the same summary as FAILED:"
            log "!!!   ${FAILED_THRESHOLDS}"
            log "!!! The boolean in --summary-export means BREACHED, not OK. If that has"
            log "!!! changed, every 'harness fault' and every 'breach' this script has ever"
            log "!!! printed is inverted, and a real breach would be published as a harness"
            log "!!! fault. Fix classify_stage() before running another stage."
            exit 8
        fi
        if [[ -n "$BREACHED" ]]; then
            # A stage passing after a breach is worth a sentence in the run record: it means
            # the breach was not monotonic, and a capacity figure taken from a
            # non-monotonic ladder needs the caveat.
            log "NOTE: this stage passed AFTER a breach at ${BREACHED} VU. The ladder is not"
            log "      monotonic — record both, and do not report the higher number as the"
            log "      capacity without saying so."
        fi
    else
        case "$KIND" in
            harness)
                log "!!! HARNESS FAULT, NOT A CAPACITY RESULT."
                log "!!! dropped_iterations means the GENERATOR saturated; hs_auth_failures"
                log "!!! means the fleet stopped authenticating (almost always a tokens.json"
                log "!!! whose single-use refresh tokens were already spent — see"
                log "!!! k6/refresh-tokens.js); hs_unexpected_404 means the mix asked for"
                log "!!! resources its own principals do not own. None of the three says"
                log "!!! anything about the instance."
                log "!!! NOTHING BELOW THIS STAGE IS INVALIDATED. Fix the cause and re-run"
                log "!!! THIS stage; do not record ${VUS} VU as a breach, and do not report"
                log "!!! the stage below it as the capacity on the strength of this."
                HARNESS_FAULT=1
                break
                ;;
            unknown)
                log "!!! k6 exited non-zero and no failed threshold could be read from the"
                log "!!! summary. That is neither a breach nor a clean stage: it is usually a"
                log "!!! script error, a refused setup() (not enough principals, a stale"
                log "!!! canary target) or an operator interrupt. Read $STAGELOG."
                HARNESS_FAULT=1
                break
                ;;
            breach)
                log "stage ${VUS} VU: BREACHED — a §4.3 target, which IS a capacity result."
                log "      See §4.8 for the resource each threshold points at."
                if [[ -z "$BREACHED" ]]; then
                    BREACHED="$VUS"
                    log "      Escalating ONE more stage to characterise the breach, then stopping."
                else
                    EXTRA_DONE=1
                fi
                ;;
        esac
    fi

    if [[ -n "$BREACHED" && "$EXTRA_DONE" -eq 1 ]]; then
        log "stopping: one stage past the first breach, as §4.5 prescribes"
        break
    fi

    # Between stages, a settle that ends on a CONDITION rather than a clock (§4.5): heap
    # after collection, Hikari active connections and the PostgreSQL backend count all back
    # to their pre-mix baseline. This script cannot see those — they are on the box — so it
    # pauses a floor and defers to the watcher.
    log "settle: waiting ${SETTLE_SECONDS:-60}s, THEN confirm on the Grafana board that"
    log "        jvm_gc_live_data_size_bytes, hikaricp_connections_active and the PG backend"
    log "        count are back at baseline before continuing. A clock is not the condition."
    sleep "${SETTLE_SECONDS:-60}"
done

log ""
log "=== ladder complete ==="
if [[ "$HARNESS_FAULT" == "1" ]]; then
    log "THE LADDER STOPPED ON A HARNESS FAULT, NOT ON A CAPACITY RESULT."
    log "Do not publish a capacity number from this run without re-running the stage that"
    log "faulted. The stages BELOW it are still valid measurements and their evidence is in"
    log "this directory."
    exit 1
fi
if [[ -n "$BREACHED" ]]; then
    log "first breach at ${BREACHED} VU."
    log "The reported capacity is the highest stage BELOW that which passed AND during which"
    log "no provisioned alert rule met its condition for its 'for:' duration. This script"
    log "cannot check the second half — run capture/alert-rules.sh and confirm by hand."
else
    TOP="$(printf '%s\n' $LADDER | sort -n | tail -1)"
    log "NO BREACH across the whole ladder (top stage $TOP VU)."
    log "That is a result, not a failure to find one — and it means the ladder's top is below"
    log "this instance's capacity. Extend LADDER, or record that capacity exceeds $TOP VU for"
    log "this mix and this fixture. Do NOT report $TOP as 'the capacity': it is a floor."
fi
log "summaries: $OUT/stage-*.summary.json"
