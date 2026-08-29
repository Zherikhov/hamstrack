#!/usr/bin/env bash
# HD-186 — the abort watchdog.
# Spec: §5.3.
#
#   capture/watchdog.sh [output-dir]      (run ON THE BOX, for the whole window)
#
# Checks the machine-readable abort conditions every few seconds and, when one fires,
# writes a STOP FILE that the generator polls. It does not itself kill k6 — it cannot; it
# is on the other machine. What it can do is make the decision unambiguous and immediate,
# and put the reason in one place.
#
#   On the box:        capture/watchdog.sh /path/to/results &
#   On the generator:  the operator watches for the stop file (or the watchdog's SSM/ssh
#                      output) and runs the documented abort command.
#
# The abort command, which §5.1 precondition 9 requires the operator to have ACTUALLY RUN
# ONCE before the window, is:
#
#     pkill -INT -f 'k6 run'
#
# SIGINT rather than SIGKILL: k6 stops the scenarios, runs handleSummary and writes the
# partial results, so an aborted stage still produces evidence. A SIGKILL discards the
# stage's measurements, which is a second loss on top of whatever caused the abort.
#
# ---------------------------------------------------------------------------
# WHAT IS HERE AND WHAT IS NOT.
#
# Only the conditions a script can evaluate. Conditions 1, 2, 3, 4 and 6 are checked below.
# The remaining four are NOT, and their absence here is a stated gap rather than an
# omission — a watchdog that silently covered five of nine would read as covering all nine:
#
#   5. THE TENANCY CANARY returns anything other than 404. Enforced on the GENERATOR, by
#      the k6 threshold `hs_canary_leak: count==0, abortOnFail` (lib/classes.js), because
#      that is where the request is made. It is the one abort that is not about capacity:
#      stop, preserve everything, treat it as a SECURITY INCIDENT.
#   7. THE REAL-USER PROBE degrades — a low-rate scripted journey against a REAL workspace,
#      showing any 5xx or two consecutive minutes above 5 s p95. Deliberately NOT automated
#      here: it runs against real tenant data with a real account's credentials, and a
#      script in this repository that did that would be a script that could be pointed at a
#      customer. The operator runs it by hand and watches it.
#   8. CPUCreditBalance below 25% of its starting value IN `standard` MODE. Needs AWS
#      credentials this box does not have, and it does not apply at all to an instance in
#      `unlimited` mode. THE MODE IS READ IN PRE-FLIGHT, not assumed here: fingerprint.sh
#      prints it and RESULTS-TEMPLATE.md carries it as a named checkbox, because it is a
#      per-instance setting anyone with the console can change and it decides whether this
#      condition exists.
#   9. THE OPERATOR'S JUDGEMENT. Written down as a condition on purpose, so that USING it is
#      following the procedure rather than departing from it.
set -euo pipefail

HERE_CFG="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# $LOAD_CONFIG's [BOX] half — the container names and the database connection — used to be
# read by nothing on this side, so an operator with a correct configuration file still got a
# fail-fast on a container name. See capture/lib-config.sh.
# shellcheck source=./lib-config.sh
. "$HERE_CFG/lib-config.sh"


# Outside the harness directory by default, like every other output here: `ops/` is synced
# and files added to a synced directory are what the drift check reports.
: "${LOAD_RESULTS_DIR:=/var/tmp/hd186}"
OUT="${1:-$LOAD_RESULTS_DIR}"
STOP="$OUT/ABORT"
LOG="$OUT/watchdog.log"
mkdir -p "$OUT"

: "${INTERVAL:=5}"
: "${PG_CONTAINER:=hamstrack-postgres-1}"
# The documented names, with the bare ones as a fallback (see capture.sh).
: "${LOAD_DB_USER:=${DB_USER:-hamstrack}}"
: "${LOAD_DB_NAME:=${DB_NAME:-hamstrack}}"
DB_USER="$LOAD_DB_USER"
DB_NAME="$LOAD_DB_NAME"
: "${DISK_FLOOR_MB:=500}"
: "${MEM_FLOOR_MB:=150}"
: "${SWAP_CEILING_MB:=512}"

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }
say() { printf '%s %s\n' "$(ts)" "$*" | tee -a "$LOG"; }

abort() {
    say "!!! ABORT: $*"
    { echo "aborted_utc=$(ts)"; echo "reason=$*"; } > "$STOP"
    say "!!! Stop file written: $STOP"
    say "!!! 1. ON THE GENERATOR, RUN:  pkill -INT -f 'k6 run'"
    # THE LITERAL DATABASE NAME, NOT THE VARIABLE. This line is read by a human in ANOTHER
    # shell — an SSM session, a second terminal, a pasted log — which does not have
    # $LOAD_DB_NAME set. And if it DID inherit it, from the same configuration file this
    # watchdog read, then LOAD_TARGET would be set to whatever this side already BELIEVES
    # the database is called; require_named_target would compare that belief against
    # current_database() and agree with itself. The guard exists to catch a wrong belief
    # about which database this is, so letting a variable answer for it made it a
    # tautology in exactly the situation it is for.
    say "!!! 2. ON THIS BOX, RUN:       LOAD_CONFIRM=\$(date -u +%Y-%m-%d) \\"
    say "!!!                              LOAD_TARGET=${LOAD_DB_NAME} bash fixture/revoke.sh"
    say "!!!    (that is the literal name this watchdog is watching. revoke.sh reads"
    say "!!!     current_database() back over its own connection and refuses if they"
    say "!!!     differ — so type it; do not let a variable answer for it.)"
    say "!!!    An aborted window otherwise LEAVES EVERY LOAD ACCOUNT WORKING ON"
    say "!!!    PRODUCTION: they share one password, hold 30-day refresh tokens, and hold"
    say "!!!    LIVE ACCESS TOKENS in the generator's tokens.json that are accepted on a"
    say "!!!    signature check alone for up to another thirty minutes. Nothing about"
    say "!!!    stopping k6 or terminating the generator revokes any of the three."
    say "!!!    revoke.sh deletes the refresh rows, breaks the password hash AND sets the"
    say "!!!    accounts to DISABLED, which is the only thing that stops a live JWT. It"
    say "!!!    does NOT delete any rows, so whatever caused the abort can still be"
    say "!!!    investigated afterwards."
    say "!!! 3. THEN, when there is time: fixture/teardown.sh"
    exit 10
}

[[ -f "$STOP" ]] && rm -f "$STOP"
say "watchdog started (disk floor ${DISK_FLOOR_MB}MB, mem floor ${MEM_FLOOR_MB}MB, swap ceiling ${SWAP_CEILING_MB}MB)"

# Baseline for the counters that are only meaningful as a delta.
BASE_CONTAINERS="$(docker ps -q | wc -l)"
BASE_OOM="$(awk '/^oom_kill /{print $2}' /proc/vmstat 2>/dev/null || echo 0)"
say "baseline: $BASE_CONTAINERS running containers, oom_kill counter $BASE_OOM"

# The role-scope-violation counter. A DATA-INTEGRITY signal that must NEVER be masked by
# "we were load testing" (§5.3 condition 6) — it means a role id was used in the wrong
# scope, which is the failure mode HD-123's 422 exists to prevent, and it does not become
# acceptable because the box is busy.
scope_violations() {
    docker exec "${APP_CONTAINER:-hamstrack-app-1}" \
        sh -c 'wget -qO- http://localhost:8080/actuator/prometheus 2>/dev/null' 2>/dev/null \
        | awk '/^hamstrack_role_scope_violation_total/{s+=$2} END{printf "%d", s+0}'
}
BASE_SCOPE="$(scope_violations || echo 0)"
say "baseline: hamstrack_role_scope_violation_total = $BASE_SCOPE"

while :; do
    # --- 1. free disk. THE ONLY FAILURE HERE THAT CAN DAMAGE REAL DATA. -------
    # Non-negotiable and checked most frequently — it is first in the loop for that reason.
    # NOTHING ELSE CHECKS THIS. run-ladder.sh checks the GENERATOR's disk, on the other
    # machine, which is a different filesystem and a different consequence (a lost capture,
    # not lost data). If this watchdog dies, condition 1 is unwatched — say so in the run
    # record rather than inheriting a redundancy that only existed in single-host rehearsal.
    FREE_MB="$(df -Pm / | awk 'NR==2 {print $4}')"
    [[ "$FREE_MB" -ge "$DISK_FLOOR_MB" ]] \
        || abort "free disk ${FREE_MB}MB below the ${DISK_FLOOR_MB}MB floor (condition 1)"

    # --- 2. any container exits other than by our own action ------------------
    NOW_CONTAINERS="$(docker ps -q | wc -l)"
    [[ "$NOW_CONTAINERS" -ge "$BASE_CONTAINERS" ]] \
        || abort "container count fell $BASE_CONTAINERS -> $NOW_CONTAINERS (condition 2). \
Check exit codes: 137 is a kernel OOM kill, not an OutOfMemoryError, and it will be absent \
from every application log."

    # --- 3. pg_up ------------------------------------------------------------
    docker exec -i "$PG_CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1 \
        || abort "postgres is not accepting connections (condition 3)"

    # --- 4. host memory and swap ---------------------------------------------
    # Thresholds deliberately BELOW the provisioned alerts (HostMemoryLow at 200 MiB
    # available, HostSwapInUse at 128 MiB swap), so on a healthy run those alerts are the
    # WARNING THAT PRECEDES THE ABORT and not an incident. EXPECT THEM TO FIRE. Do not
    # silence them for the window: that loses the only host-side signal the run has. Note
    # the times they fired and put them in the results.
    MEM_MB=$(( $(awk '/^MemAvailable:/{print $2}' /proc/meminfo) / 1024 ))
    SWAP_USED_MB=$(( ( $(awk '/^SwapTotal:/{print $2}' /proc/meminfo) \
                     - $(awk '/^SwapFree:/{print $2}' /proc/meminfo) ) / 1024 ))
    [[ "$MEM_MB" -ge "$MEM_FLOOR_MB" ]] \
        || abort "host available memory ${MEM_MB}MB below ${MEM_FLOOR_MB}MB (condition 4). \
Under memory pressure the KERNEL picks the victim, among every container on the host and by \
its own accounting — a declared ceiling changes WHICH one is chosen, not whether. It may be \
the DATABASE."
    [[ "$SWAP_USED_MB" -le "$SWAP_CEILING_MB" ]] \
        || abort "swap used ${SWAP_USED_MB}MB above ${SWAP_CEILING_MB}MB (condition 4). \
With swap present, memory exhaustion presents as LATENCY rather than as a dead container — \
this is the signal that would otherwise be invisible."

    # HostKernelOOMKill is THE EXCEPTION among the expected alerts: it is not expected, and
    # if it fires the run is ALREADY INVALID. Something on the box was killed, and a
    # capacity number measured across a kill measures the kill.
    NOW_OOM="$(awk '/^oom_kill /{print $2}' /proc/vmstat 2>/dev/null || echo 0)"
    [[ "$NOW_OOM" -le "$BASE_OOM" ]] \
        || abort "the kernel OOM killer fired (vmstat oom_kill $BASE_OOM -> $NOW_OOM). \
The run is already invalid — a capacity number measured across a kill measures the kill."

    # --- 6. role scope violations --------------------------------------------
    NOW_SCOPE="$(scope_violations || echo "$BASE_SCOPE")"
    [[ "$NOW_SCOPE" -le "$BASE_SCOPE" ]] \
        || abort "hamstrack_role_scope_violation_total increased $BASE_SCOPE -> $NOW_SCOPE \
(condition 6). A data-integrity signal that must not be masked by 'we were load testing'."

    sleep "$INTERVAL"
done
