#!/usr/bin/env bash
# Hamstrack production database backup (HD-187).
# Spec: docs/design/production-backups-proposal.md
#
# Runs as root from hamstrack-backup.service. Takes a logical dump out of the RUNNING
# postgres container (so the pg_dump client can never drift from the server version),
# verifies it, uploads it to object storage, and publishes freshness metrics for
# node-exporter's textfile collector. Retention is NOT this script's job: it is an S3
# lifecycle rule, because a script that can delete one backup can be made to delete all
# of them, and this host must not be able to.
#
# Divergences from the spec, each of them a review finding that won:
#   * §10 case 3 says a partial dump is left on disk until the next successful run. It is
#     now removed on the failing run itself, and the staged-copy sweep moved into
#     pre-flight (see sweep_staged), so nothing depends on a later run succeeding.
#   * §6.5 treats ETag == MD5 as an invariant. It is one only on AWS, so it is now
#     BACKUP_S3_VERIFY_ETAG=auto|on|off and warns rather than fails behind an endpoint
#     override (see put()).
#   * §9.2's variable table gains BACKUP_LABEL, BACKUP_COMPOSE_PROJECT,
#     BACKUP_S3_PATH_STYLE_ACCESS, BACKUP_S3_VERIFY_ETAG and BACKUP_MIN_TOC_ENTRIES.
#   * §10 case 8 said --stop-post stamps a failure whenever SERVICE_RESULT is not
#     `success`. It no longer does: when the EXIT trap ran, its answer is both truthful
#     and more specific, so the handler stands down (see the run-stamp marker below).
set -euo pipefail

# Every file this script writes is, or describes, the database: emails, bcrypt password
# hashes, refresh-token hashes, live verification/reset tokens, and in the globals dump
# `ALTER ROLE ... PASSWORD 'SCRAM-SHA-256$...'` — a verifier that is enough to
# authenticate as that PostgreSQL role. The node-exporter container mounts / read-only and
# runs as `nobody`, so world-readable here is genuinely readable from another container.
# One single file is deliberately exempted, in write_metrics; that exemption is
# load-bearing and is explained there.
umask 077

# Defined here rather than beside the other helpers below: the config source a few lines
# down has to be able to say that it failed, and it runs before anything else in this file.
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "FATAL $*"; exit 1; }

CONF=/etc/hamstrack/backup.env

# --- configuration -----------------------------------------------------------
# Sourced with `set -a`, and with the environment winning over the file. Both halves are
# load-bearing and they pull in opposite directions:
#
#   set -a   — without it the values are ordinary shell variables, so systemd's
#              `EnvironmentFile=` path works (systemd exports them) while a hand-run does
#              not: the `aws` child process never sees AWS_ACCESS_KEY_ID from the file. A
#              difference that appears only on the invocation path an operator debugs with
#              is the worst diagnostic shape there is.
#   env wins — a plain `KEY=value` in a sourced file overwrites the environment, and
#              `BACKUP_S3_PREFIX=manual /usr/local/bin/hamstrack-backup` is the documented
#              way (docs/release-checklist.md) to take a copy no lifecycle rule expires.
#              With the file winning, that command lands in daily/ and is deleted in 30
#              days — silently, and precisely when somebody was being careful.
#
# The snapshot is NUL-delimited (`env -0`) and replayed with `export`, which takes one
# `NAME=value` word and never re-evaluates the value. The previous form snapshotted
# `export -p` line by line and replayed it through `eval`; fuzzing that form with
# newlines, quotes, backticks and `$(id)` produced a literal round-trip and executed
# nothing, so this is defence in depth rather than a closed hole — the empirical result
# disagreed with the analysis, and this version removes the question instead of settling
# it. No `eval`, no line-based parsing, and nothing a multi-line value can smuggle past a
# `grep`.

# Set here so `set -u` is satisfied when there is no config file at all, and read by the
# validation block far below, which is where a broken one becomes fatal.
CONF_BROKEN=0
if [ -r "$CONF" ]; then
  _preset=()
  while IFS= read -r -d '' _kv; do
    case "$_kv" in
      BACKUP_*=*|AWS_*=*) _preset+=("$_kv") ;;
    esac
  done < <(env -0)
  # THE CONFIG FILE IS EXECUTED TWICE, AND BOTH EXECUTIONS ARE DELIBERATE: a PROBE in a child
  # bash, and then — only if the probe passed — the real source in this shell. Neither half
  # does the other's job.
  #
  #   why a probe at all — `set -e` is suppressed for the whole DYNAMIC EXTENT of the left
  #     operand of `||`, and that suppression propagates into the sourced file. So
  #     `. "$CONF" || CONF_BROKEN=1` catches a file that does not PARSE (the source builtin
  #     returns 2) but NOT a line that parses and then FAILS AT RUN TIME —
  #     `BACKUP_S3_PREFIX=$(lookup-prefix)` with no such tool on PATH. The source runs on to
  #     EOF and returns the status of its LAST command, so unless the failing line happens to
  #     be the last one the run continues with that variable silently degraded to its default
  #     and reports success — the object then lands in daily/, where the 30-day lifecycle
  #     rule deletes the very copy docs/release-checklist.md tells the operator to take
  #     before a migration that rewrites flyway_schema_history. Measured rather than
  #     reasoned: of the three obvious forms only the separate PROCESS catches this, because
  #     a plain `( set -e; . "$CONF" )` subshell inherits the suppression and returns 0 too.
  #   why not the probe alone — `bash -c` is a separate process, so nothing it assigns
  #     reaches us. It can answer "would this file run clean" and nothing else.
  #   why the real source still tests its return value — `|| CONF_BROKEN=1` is what keeps
  #     that line from being the end of the run. A bare `. "$CONF"` at top level under
  #     `set -e` ABORTS the script right there, above both the --stop-post branch and
  #     `trap cleanup EXIT`: ExecStart died at that line, systemd then ran ExecStopPost,
  #     which re-executes this same prologue and died at the IDENTICAL line, nothing wrote a
  #     .prom, yesterday's `hamstrack_backup_last_status{stage="dump"} 1` stood,
  #     BackupRunFailed never fired, and the only remaining signal was BackupStale 26 hours
  #     later — the exact shape the two blocks below were moved up to eliminate, surviving in
  #     the one subclass that could never reach them.
  #
  # The probe is the SAME interpreter ($BASH, not whatever `bash` resolves to on PATH), runs
  # under the same options this shell does, and inherits this process's exported environment —
  # which is everything a plain `KEY=value` file can read, so it agrees with the source that
  # follows it. The two things it does NOT inherit are this shell's own non-exported variables
  # and its functions, and that asymmetry fails CLOSED: a file reaching for one of those trips
  # the probe's `set -u` (or `command not found`) and is refused, which is the right answer for
  # a file the header of backup.env.example already forbids from doing anything of the kind.
  # Executing the file twice adds no exposure: sourcing already runs it as root.
  # The probe's output is deliberately NOT swallowed — bash's own message names the offending
  # line and the failing command, and on the probe-failure path it is the only diagnostic
  # anybody gets, because we then do not source the file at all.
  #
  # A failed probe means the file is NOT APPLIED AT ALL — not half-applied: what survives is
  # the defaults below plus the environment. The run continues only far enough to publish a
  # failure metric and `die`s in the validation block with the trap installed. It is the
  # likeliest variant in the field, too: docs/ops-prod-hardening.md §6.2 and
  # docs/self-hosting.md both tell the operator to hand-EDIT this file, and systemd's own
  # EnvironmentFile= parser accepts files bash cannot, so nothing else warns them either.
  # The path travels as the child's "$1" rather than being interpolated into the command
  # string, so nothing in it is re-parsed by the child shell; the backslashes are what keeps
  # that `$1` from expanding HERE, in this shell.
  if "${BASH:-bash}" -c "set -euo pipefail; set -a; . \"\$1\"" hamstrack-backup-conf-probe "$CONF"; then
    set -a
    # shellcheck source=/dev/null
    . "$CONF" || CONF_BROKEN=1
    set +a
    # `set -a` marked that assignment for export on the way past; nothing downstream wants it
    # in its environment.
    export -n CONF_BROKEN
  else
    CONF_BROKEN=1
  fi
  [ "$CONF_BROKEN" = 0 ] || log "WARN $CONF could not be run cleanly by bash; continuing with defaults and the environment so this run can report the failure — it will not take a backup with them"
  # The snapshot above is filtered to BACKUP_*/AWS_*, which is also what keeps this replay
  # from being able to reach CONF_BROKEN and clear the refusal that follows it.
  for _kv in "${_preset[@]}"; do
    export "${_kv?}"
  done
  unset _preset _kv
fi

BACKUP_TARGET="${BACKUP_TARGET:-s3}"
BACKUP_S3_BUCKET="${BACKUP_S3_BUCKET:-}"
BACKUP_S3_PREFIX="${BACKUP_S3_PREFIX:-daily}"
BACKUP_S3_REGION="${BACKUP_S3_REGION:-eu-north-1}"
BACKUP_S3_ENDPOINT="${BACKUP_S3_ENDPOINT:-}"
BACKUP_S3_PATH_STYLE_ACCESS="${BACKUP_S3_PATH_STYLE_ACCESS:-false}"
BACKUP_S3_VERIFY_ETAG="${BACKUP_S3_VERIFY_ETAG:-auto}"
BACKUP_LABEL="${BACKUP_LABEL:-}"
BACKUP_COMPOSE_DIR="${BACKUP_COMPOSE_DIR:-/opt/hamstrack}"
BACKUP_COMPOSE_FILE="${BACKUP_COMPOSE_FILE:-docker-compose.prod.yml}"
BACKUP_COMPOSE_PROJECT="${BACKUP_COMPOSE_PROJECT:-}"
BACKUP_PG_SERVICE="${BACKUP_PG_SERVICE:-postgres}"
BACKUP_LOCAL_DIR="${BACKUP_LOCAL_DIR:-/var/backups/hamstrack}"
BACKUP_KEEP_LOCAL="${BACKUP_KEEP_LOCAL:-2}"
BACKUP_KEEP_LOCAL_DAYS="${BACKUP_KEEP_LOCAL_DAYS:-30}"
BACKUP_MIN_BYTES="${BACKUP_MIN_BYTES:-50000}"
BACKUP_MIN_TOC_ENTRIES="${BACKUP_MIN_TOC_ENTRIES:-50}"
BACKUP_MIN_FREE_MB="${BACKUP_MIN_FREE_MB:-2048}"
BACKUP_TEXTFILE_DIR="${BACKUP_TEXTFILE_DIR:-/var/lib/node_exporter/textfile_collector}"
BACKUP_STATE_DIR="${BACKUP_STATE_DIR:-/var/lib/hamstrack-backup}"

# --- run state ---------------------------------------------------------------
TS="$(date -u +%Y-%m-%dT%H%M%SZ)"
START="$(date +%s)"
DUMP_OK=0
UPLOAD_OK=0
SIZE=0
DUMP=""
GLOBALS=""
AWS_CFG_DIR=""
# UPLOAD is derived twice: PERMISSIVELY here, STRICTLY in the validation block far below.
# --stop-post runs before any validation — it has to, because a config error is one of the
# failures it exists to stamp — and it still has to decide whether the `upload` series
# exists at all: emitting it under BACKUP_TARGET=local invents a stage that never runs,
# and omitting it under `s3` hides the stage that just failed. "Anything that is not
# exactly `local` is an upload target" is the safe half of that choice; refusing the value
# outright belongs on the path that would actually upload, which is the strict branch.
case "$BACKUP_TARGET" in
  local) UPLOAD=0 ;;
  *)     UPLOAD=1 ;;
esac

require_int() { # $1 = name, $2 = value
  case "$2" in
    ''|*[!0-9]*)
      # Not pedantry: these values reach `$((...))`, and arithmetic expansion evaluates its
      # operand as code. A typo would also abort the run AFTER a successful upload, which
      # turns a good backup into a reported failure.
      die "$1 must be a non-negative integer, got '$2'" ;;
  esac
}

# --- freshness metrics -------------------------------------------------------
# Last-success timestamps live in state files so that a FAILED run preserves them:
# "this run failed" and "nothing has succeeded since Tuesday" are different facts and
# the alerting in the spec (§8.3) depends on being able to tell them apart.
read_state() {
  local v now
  v="$(cat "$BACKUP_STATE_DIR/$1" 2>/dev/null || true)"
  # Garbage here is worse than a zero: node-exporter rejects the WHOLE textfile on a single
  # malformed line, every hamstrack_backup_* series disappears at once, and
  # `noDataState: OK` then silences both rules. A state file that is not an integer — a
  # half-written value, a CR, a hand-edit — reads as "never succeeded".
  case "$v" in
    ''|*[!0-9]*) echo 0; return ;;
  esac
  # All-digits is not the same as sane. BackupStale alerts on `time() - <this value>`, so
  # anything in the future makes that difference negative and the rule can never fire
  # again — twenty nines would switch backup alerting off permanently and look healthy
  # doing it. Nothing writes such a value today, so this is a bound rather than a
  # mechanism: past tomorrow reads as "never succeeded". The length test comes first
  # because a 20-digit number overflows shell arithmetic before a comparison can reject it.
  now="$(date +%s)"
  if [ "${#v}" -gt 11 ] || [ "$v" -gt "$(( now + 86400 ))" ]; then
    echo 0
    return
  fi
  echo "$v"
}

# read_state's unvalidated twin, for the one value that is deliberately not a number: the
# run marker holds a systemd INVOCATION_ID (32 hex characters), which read_state would
# reject as garbage and report as `0` — and `0` is a value that can compare EQUAL to
# something else. Nothing read here reaches a metric or an arithmetic expansion; it is only
# ever compared for equality with a string this process already holds.
read_raw() { cat "$BACKUP_STATE_DIR/$1" 2>/dev/null || true; }

write_metrics() {
  local now dur out tmp
  now="$(date +%s)"
  dur=$(( now - START ))
  out="$BACKUP_TEXTFILE_DIR/hamstrack_backup.prom"
  tmp="$out.$$"
  {
    echo '# HELP hamstrack_backup_last_success_timestamp_seconds Unix time of the last successful completion of a backup stage.'
    echo '# TYPE hamstrack_backup_last_success_timestamp_seconds gauge'
    echo "hamstrack_backup_last_success_timestamp_seconds{stage=\"dump\"} $(read_state last_success_dump)"
    if [ "$UPLOAD" = 1 ]; then
      echo "hamstrack_backup_last_success_timestamp_seconds{stage=\"upload\"} $(read_state last_success_upload)"
    fi
    echo '# HELP hamstrack_backup_last_status Whether the most recent run completed this stage (1) or not (0).'
    echo '# TYPE hamstrack_backup_last_status gauge'
    echo "hamstrack_backup_last_status{stage=\"dump\"} $DUMP_OK"
    if [ "$UPLOAD" = 1 ]; then
      echo "hamstrack_backup_last_status{stage=\"upload\"} $UPLOAD_OK"
    fi
    echo '# HELP hamstrack_backup_size_bytes Size in bytes of the dump produced by the most recent run.'
    echo '# TYPE hamstrack_backup_size_bytes gauge'
    echo "hamstrack_backup_size_bytes $SIZE"
    echo '# HELP hamstrack_backup_duration_seconds Wall-clock duration of the most recent run.'
    echo '# TYPE hamstrack_backup_duration_seconds gauge'
    echo "hamstrack_backup_duration_seconds $dur"
  } > "$tmp"
  # DELIBERATELY WIDER THAN EVERY OTHER FILE THIS SCRIPT WRITES, and it must stay that way.
  # node-exporter runs as `nobody` inside its container; under `umask 077` this file would
  # be 0600, the collector could not read it, every hamstrack_backup_* series would vanish
  # and `noDataState: OK` would turn backup alerting off silently and permanently — a
  # mechanism that has stopped watching looks exactly like one with nothing to report. The
  # file holds two timestamps, two flags, a size and a duration: no secret, and nothing an
  # attacker who can read the host learns from.
  chmod 0644 "$tmp"
  # Atomic: a scrape must never see a half-written file.
  mv -f "$tmp" "$out"
  log "metrics written: dump=$DUMP_OK upload=$UPLOAD_OK size=$SIZE duration=${dur}s"
}

cleanup() {
  local rc=$?
  # A dump that FAILED verification is bytes nobody may restore from and nobody should be
  # able to read either — drop it on the way out rather than leaving it for a later run
  # that may never come. A dump that succeeded and merely failed to UPLOAD is kept on
  # purpose: it is a good backup, it is 0600 in a 0700 directory, and it is what §6.4's
  # dump-succeeds/upload-fails check and a same-day manual recovery both read. Pre-flight's
  # sweep is what bounds how long it lives.
  if [ "$DUMP_OK" = 0 ] && [ -n "$DUMP" ]; then
    rm -f -- "$DUMP" "$GLOBALS"
  fi
  [ -z "$AWS_CFG_DIR" ] || rm -rf -- "$AWS_CFG_DIR"
  write_metrics
  # THE LAST ACT, and the whole reason --stop-post can stand down. It records WHICH RUN this
  # trap spoke for, and the handler leaves a matching stamp alone. Without it the handler
  # overwrote the trap's answer on every ordinary `exit 1`: the flagship failure — dump fine,
  # upload failed — was published truthfully for a few milliseconds and then rewritten as
  # `dump=0 upload=0 size=0`, which sends the operator hunting for a dump that is sitting
  # right there on disk.
  # The value is the systemd INVOCATION_ID — unique per start-stop cycle and the same string
  # in ExecStart= and ExecStopPost= — with $START as the fallback for a hand run, which has
  # neither an invocation nor an ExecStopPost to read this. It must NOT be a timestamp under
  # systemd: the handler used to compare it with `run_start`, which the lock winner writes
  # only after validation, so both files held the previous run's value for the whole of this
  # run's prologue and matched before this run had done anything. Non-fatal on purpose: a
  # state directory we cannot write is already visible in the metrics above, and failing here
  # would replace a truthful exit code with this one.
  printf '%s\n' "${INVOCATION_ID:-$START}" > "$BACKUP_STATE_DIR/last_trap_run" 2>/dev/null || true
  exit "$rc"
}

# --- ExecStopPost: the failure that never reaches the EXIT trap --------------
# A cgroup OOM kill is SIGKILL, and SIGKILL runs no trap: the .prom would keep the PREVIOUS
# run's `last_status 1`, BackupRunFailed would stay silent, and the only remaining signal
# would be BackupStale 26 hours later. systemd survives a SIGKILL of the main process, so
# the unit calls back in here to stamp the failure. The last_success_* state files are
# untouched, so "nothing has succeeded since Tuesday" still reads correctly.
#
# THIS BLOCK RUNS BEFORE ANY VALIDATION, and that position is the fix rather than an
# accident. Below it sit ten `die`s covering exactly the mistakes an operator makes while
# editing /etc/hamstrack/backup.env — five integers, BACKUP_TARGET, the endpoint scheme,
# path-style, verify-etag, the label charset — plus the `install -d` that fails when
# BACKUP_LOCAL_DIR is repointed without adding the new path to the unit's ReadWritePaths=,
# an edit docs/self-hosting.md actively invites, and the config file itself when it does not
# parse (which is why the source at the top of this script tests its return value instead of
# letting `set -e` abort above here). With this block underneath any of them, ExecStart died
# at one of those lines and ExecStopPost re-ran the same prologue and died at the identical
# line: nothing wrote a .prom, so the file kept the previous run's `{stage="dump"} 1` and
# BackupRunFailed never fired. Stamping a failure is this mode's entire job, so it runs none
# of that validation and derives what little it needs permissively.
if [ "${1:-}" = "--stop-post" ]; then
  if [ "${SERVICE_RESULT:-success}" = "success" ]; then
    exit 0
  fi
  # Non-fatal: if the directory cannot be created, write_metrics says so by failing, and
  # there is nothing better to do about it from here.
  install -d -m 0755 "$BACKUP_TEXTFILE_DIR" 2>/dev/null || true
  # The trap knows more than this handler does — that the dump succeeded and only the
  # upload failed — and when it ran, what it published is correct. So the handler stamps
  # ONLY when no trap ran at all, which is the SIGKILL case it was written for.
  #
  # The question is "did a trap run FOR THIS RUN", and only the systemd invocation can
  # answer it. The marker used to hold the run's START timestamp, compared against
  # `run_start` — but `run_start` is written by the lock WINNER, i.e. after validation, so
  # between process start and that line both files still hold the PREVIOUS run's value and
  # therefore matched each other: a SIGKILL anywhere in the prologue made this handler stand
  # down over a marker no trap of this run had written, and yesterday's success stood.
  # $INVOCATION_ID is unique per systemd start-stop cycle and identical in ExecStart= and
  # ExecStopPost= (verified on systemd 252, which is AL2023's), so a marker left by any
  # earlier invocation cannot match and no timing assumption is involved. Read RAW: it is 32
  # hex characters, and read_state rejects non-digits by design — reporting them as 0, a
  # value that can then compare equal to something else.
  if [ -n "${INVOCATION_ID:-}" ] && [ "$(read_raw last_trap_run)" = "$INVOCATION_ID" ]; then
    log "run ended with SERVICE_RESULT=${SERVICE_RESULT:-?} but its EXIT trap already published metrics for this invocation; leaving them alone"
    exit 0
  fi
  # Reached only when no trap ran, and used for nothing but the duration. `run_start` is the
  # lock winner's timestamp, so it is this run's — unless the kill landed in the handful of
  # statements between process start and that write, where it is still the previous run's
  # and the duration below is measured from a dead run (86400 of it, on a daily timer). No
  # rule reads duration, that window allocates nothing worth OOMing on, and closing it would
  # cost a second state file whose only job is a cosmetic number.
  START="$(read_state run_start)"
  [ "$START" != 0 ] || START="$(date +%s)"
  log "run ended with SERVICE_RESULT=${SERVICE_RESULT:-?} EXIT_CODE=${EXIT_CODE:-?} EXIT_STATUS=${EXIT_STATUS:-?} and no EXIT trap ran; stamping both stages as failed"
  write_metrics
  exit 0
fi

# --- traps -------------------------------------------------------------------
# Installed before the directory creation and the validation below, so that every `die`
# down there still publishes metrics. It used to sit after both, which is how a typo in
# backup.env produced a failed run whose .prom went on insisting the last one succeeded.
trap cleanup EXIT
# Bash runs no EXIT trap for an untrapped fatal signal, so TimeoutStartSec=1800's SIGTERM
# would skip cleanup entirely and leave a partial, unverified dump on disk with stale
# metrics beside it. Trapping TERM as an ordinary exit (128+15) makes the existing cleanup
# run on the graceful half of the timeout; the SIGKILL that follows it is what --stop-post
# above is for.
trap 'exit 143' TERM
# One asymmetry to know before aborting a run BY HAND: bash runs a trap only after the
# current foreground command has returned, so a `kill -TERM` sent while `docker exec …
# pg_dump` is still streaming is remembered and acted on when that child finishes — measured
# at 300s against a real dump. Under systemd it does not arise: KillMode=control-group (the
# default) signals the whole cgroup, the docker client returns immediately, and the
# TimeoutStartSec= path published its metrics in 8s. To stop a hand run sooner, TERM the
# child too — `pkill -TERM -P <pid>` — which lets the current command return and the trap
# below publish.

# --- directories -------------------------------------------------------------
# `install -d` applies the mode explicitly, so a directory left over from an earlier
# install at 0755 is tightened too — which `mkdir -p` would not do. The textfile directory
# is the deliberate exception: node-exporter has to read it. See write_metrics. It is also
# created FIRST, because it is where the trap above writes when either of the others fails.
install -d -m 0755 "$BACKUP_TEXTFILE_DIR"
install -d -m 0700 "$BACKUP_LOCAL_DIR" "$BACKUP_STATE_DIR"

# --- configuration validation ------------------------------------------------
# FIRST, because every value checked below it may be a default rather than what the operator
# wrote. The config block at the top of this script DEGRADES a backup.env that bash could not
# run instead of aborting there — the reason is spelled out at that line — and degrading is
# not continuing: nothing from the file is applied, so what survives is the defaults above
# plus the environment. A dump taken with those is not the backup anybody asked for (another
# bucket, another prefix, no label, quite possibly BACKUP_TARGET=s3 with an empty
# BACKUP_S3_BUCKET), and it would be reported as a success. So the run ends here instead,
# loudly, with the EXIT trap already installed to publish the failure and --stop-post above
# it able to reach its handler.
#
# UNCONDITIONALLY — deliberately not "unless the environment happens to hold everything".
# Under systemd that environment IS THIS SAME FILE, parsed by systemd's own and more lenient
# EnvironmentFile= reader, which expands nothing and therefore hands the script the LITERAL
# TEXT `$(lookup-prefix)` as the value (measured on systemd 252, AL2023's), while the failing
# line bash refused to run is by definition the one whose value nobody has. And the
# environment-wins replay puts those values back AFTER the source, so they are what a
# continuing run would use — this is not a hypothetical ordering. A
# "complete enough to proceed" branch would therefore take a backup under values bash refused
# to produce and nobody audited. The cost of refusing is one missed nightly upload, and
# BackupRunFailed fires within the hour; the cost of continuing is a backup taken under
# settings nobody wrote, stored where nobody will look for it.
[ "$CONF_BROKEN" = 0 ] || die "$CONF could not be sourced: bash either refused to PARSE it (an unbalanced quote, or an unquoted value such as BACKUP_LABEL=pre-release(1)), or ran a command in it that exited non-zero (a substitution such as BACKUP_S3_PREFIX=\$(lookup-prefix) whose tool is missing). Bash's own message, naming the offending line, is in the journal just above the WARN. 'bash -n $CONF' finds the first kind only — the second needs the file to actually RUN, which is what this script does in a child shell before sourcing it. systemd's EnvironmentFile= parser accepts files bash cannot, so nothing else warns you."

require_int BACKUP_KEEP_LOCAL      "$BACKUP_KEEP_LOCAL"
require_int BACKUP_KEEP_LOCAL_DAYS "$BACKUP_KEEP_LOCAL_DAYS"
require_int BACKUP_MIN_BYTES       "$BACKUP_MIN_BYTES"
require_int BACKUP_MIN_TOC_ENTRIES "$BACKUP_MIN_TOC_ENTRIES"
require_int BACKUP_MIN_FREE_MB     "$BACKUP_MIN_FREE_MB"

# ONE validated value, both branches derived from it. Two independent `= "s3"` / `= "local"`
# tests are not complementary: `S3`, or `s3 ` with the trailing CR a Windows checkout of
# backup.env leaves behind, took the upload path while never emitting the `upload` metric
# series — backups kept leaving the box and BackupStale could never fire.
case "$BACKUP_TARGET" in
  s3)    UPLOAD=1 ;;
  local) UPLOAD=0 ;;
  *)     die "BACKUP_TARGET must be exactly 's3' or 'local', got '$BACKUP_TARGET' (a trailing CR from a CRLF backup.env looks exactly like this)" ;;
esac

# An `http://` endpoint ships the entire production database — every tenant's data and
# every password hash — in cleartext. The bucket policy's aws:SecureTransport deny cannot
# help, because the request never reaches AWS.
case "$BACKUP_S3_ENDPOINT" in
  ''|https://*) ;;
  *) die "BACKUP_S3_ENDPOINT must start with https:// (got '$BACKUP_S3_ENDPOINT'); a plaintext endpoint uploads the database in the clear" ;;
esac

case "$BACKUP_S3_PATH_STYLE_ACCESS" in
  true|false) ;;
  *) die "BACKUP_S3_PATH_STYLE_ACCESS must be 'true' or 'false', got '$BACKUP_S3_PATH_STYLE_ACCESS'" ;;
esac

# The ETag of a single PUT equals the body's MD5 on AWS with SSE-S3, and that is what makes
# the integrity check possible WITHOUT s3:GetObject. It is not a law: an S3-compatible
# store may return anything, and a hard FATAL there stores the object and then reports the
# run failed — forever. `auto` therefore verifies on AWS and warns behind an endpoint.
case "$BACKUP_S3_VERIFY_ETAG" in
  auto) if [ -n "$BACKUP_S3_ENDPOINT" ]; then VERIFY_ETAG=warn; else VERIFY_ETAG=on; fi ;;
  on)   VERIFY_ETAG=on ;;
  off)  VERIFY_ETAG=off ;;
  *)    die "BACKUP_S3_VERIFY_ETAG must be 'auto', 'on' or 'off', got '$BACKUP_S3_VERIFY_ETAG'" ;;
esac

# The label is what tells two hand-taken backups apart (spec §6.2:
# manual/<label>-<ISO8601Z>.dump). It reaches both an object key and a filename, so it is
# restricted to characters that are safe in both.
case "$BACKUP_LABEL" in
  '') BASENAME="hamstrack-$TS" ;;
  *[!A-Za-z0-9._-]*) die "BACKUP_LABEL may contain only A-Z a-z 0-9 . _ - (got '$BACKUP_LABEL')" ;;
  *) BASENAME="$BACKUP_LABEL-$TS" ;;
esac

if [ -z "$BACKUP_COMPOSE_PROJECT" ]; then
  BACKUP_COMPOSE_PROJECT="$(basename "$BACKUP_COMPOSE_DIR" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]_-')"
fi

# --- pre-flight: the tools ---------------------------------------------------
# ABOVE the lock, and specifically above it because of `flock` itself. `if ! flock -n 9`
# cannot tell flock's exit 1 ("someone else holds it") from the shell's 127 ("no such
# command"), so on a host without util-linux EVERY invocation takes the loser branch: it
# exits 0 without touching the metrics, systemd reports success, and nothing notices for 26
# hours. A missing tool has to be fatal, and it can only be fatal if it is checked first.
need_tool() { command -v "$1" >/dev/null 2>&1 || die "$1 is not installed or not on PATH ($2)"; }
need_tool docker "the dump is taken with docker exec into the postgres container"
need_tool md5sum "used for the end-to-end integrity check"
need_tool flock  "used to serialise runs"
need_tool find   "used to sweep staged copies"
if [ "$UPLOAD" = 1 ]; then
  # Present on Amazon Linux 2023 and on essentially no Debian/Ubuntu host. Discovering that
  # as `aws: command not found` AFTER a successful dump wastes the run and reads as a
  # backup failure rather than a missing package.
  need_tool aws "BACKUP_TARGET=s3 needs the AWS CLI; install awscli v2 or set BACKUP_TARGET=local"
fi

# --- one run at a time -------------------------------------------------------
# A manual run racing the timer would fight over the same staging file. The loser exits
# WITHOUT touching the metrics, so a permanently stuck lock still shows up as staleness.
exec 9>/var/lock/hamstrack-backup.lock
if ! flock -n 9; then
  log "another hamstrack-backup run holds the lock; exiting without touching metrics"
  trap - EXIT
  exit 0
fi

# A run killed between the redirect and the mv leaves one of these behind. node-exporter
# only reads *.prom so they are never scraped, but they are dumps of nothing and they
# accumulate; clear them before adding another. UNDER the lock, because a concurrent run's
# temp file is live for the moment between its redirect and its `mv` — sweeping from
# outside the lock is a race whose prize is that run's metrics.
rm -f -- "$BACKUP_TEXTFILE_DIR"/hamstrack_backup.prom.[0-9]*

# Only the winner records a start time: it is what --stop-post uses to report a duration,
# and a loser overwriting it would shorten the running job's number.
# `$START`, NOT a fresh `date +%s`: the two differ by however long pre-flight took, and the
# handler subtracts this value from `now` to publish hamstrack_backup_duration_seconds.
# It is deliberately NOT what identifies the run — cleanup's marker holds the systemd
# INVOCATION_ID for that. The file below still holds the PREVIOUS run's timestamp all the
# way through this run's prologue, so anything that compares against it to decide "is this
# my run" is comparing against a dead one; that was the bug, one line away from here.
printf '%s\n' "$START" > "$BACKUP_STATE_DIR/run_start"

# --- pre-flight: space, and the container ------------------------------------
keep_newest() { # $1 = glob, $2 = how many newest to keep
  local pattern="$1" keep="$2"
  find "$BACKUP_LOCAL_DIR" -maxdepth 1 -type f -name "$pattern" -printf '%T@\t%p\0' \
    | sort -z -rn | tail -z -n "+$(( keep + 1 ))" | cut -z -f2- | xargs -0r rm -f --
}

sweep_staged() {
  # PRE-FLIGHT, and BEFORE the free-space check, on purpose. Sweeping only on the
  # successful-upload path wedges the whole mechanism permanently: once free space crosses
  # BACKUP_MIN_FREE_MB the run aborts before it can prune, so it can never again reach the
  # code that would free the space — most likely on a local target, which by design
  # accumulates BACKUP_KEEP_LOCAL_DAYS of dumps. Running it here also clears partial files
  # left by a run that died before its own cleanup could.
  if [ "$UPLOAD" = 1 ]; then
    # Two sweeps, and the second is not redundant. The count-based one matches
    # `hamstrack-*` only, so a LABELLED staged file — `pre-hd188-squash-<TS>.dump`, which
    # docs/release-checklist.md prescribes before every risky migration — survives it, on
    # purpose: a copy somebody took deliberately is not deleted by a rule written for
    # routine ones. But "deliberate" is not "forever", and without an upper bound that file
    # is a permanent plaintext copy of the entire database on the root volume, riding into
    # all seven EBS snapshots. The age sweep therefore ignores the name pattern entirely
    # and bounds every staged dump, labelled or not, by BACKUP_KEEP_LOCAL_DAYS. It is
    # scoped by suffix so an unrelated file an operator parked here is not collateral, and
    # the `local` branch below applies the same predicate for a stronger reason: there
    # BACKUP_KEEP_LOCAL_DAYS is the whole retention.
    keep_newest 'hamstrack-*.dump'        "$BACKUP_KEEP_LOCAL"
    keep_newest 'hamstrack-*.globals.sql' "$BACKUP_KEEP_LOCAL"
    find "$BACKUP_LOCAL_DIR" -maxdepth 1 -type f \( -name '*.dump' -o -name '*.globals.sql' \) \
      -mtime "+$BACKUP_KEEP_LOCAL_DAYS" -print0 | xargs -0r rm -f --
    log "swept staged copies, keeping the $BACKUP_KEEP_LOCAL newest of each and nothing older than ${BACKUP_KEEP_LOCAL_DAYS}d"
  else
    # The SAME suffix-scoped predicate as the s3 branch, and no longer `-name 'hamstrack-*'`.
    # In this mode BACKUP_KEEP_LOCAL_DAYS *is* the retention — there is no bucket and no
    # lifecycle rule behind it — so a bound that skips labelled files does not bound the only
    # copy this mode makes: `BACKUP_LABEL=pre-hd188-squash` under BACKUP_TARGET=local left
    # `pre-hd188-squash-<TS>.dump` on disk forever, a permanent plaintext copy of the whole
    # database, while backup.env.example promised that this value was the retention. `local`
    # is the self-hoster's path, which is where an unbounded copy costs the most.
    find "$BACKUP_LOCAL_DIR" -maxdepth 1 -type f \( -name '*.dump' -o -name '*.globals.sql' \) \
      -mtime "+$BACKUP_KEEP_LOCAL_DAYS" -print0 | xargs -0r rm -f --
    log "swept local backups older than ${BACKUP_KEEP_LOCAL_DAYS}d (every .dump/.globals.sql, labelled or not)"
  fi
}
sweep_staged

FREE_MB="$(df -Pm "$BACKUP_LOCAL_DIR" | awk 'NR==2 {print $4}')"
if [ "$FREE_MB" -lt "$BACKUP_MIN_FREE_MB" ]; then
  die "free space ${FREE_MB}MB below BACKUP_MIN_FREE_MB=${BACKUP_MIN_FREE_MB}MB"
fi

# -f and --project-name are both explicit. Compose derives the project name from the
# directory it is invoked in, and it picks up docker-compose.override.yml implicitly — an
# override file could redefine the postgres service under us. Naming both means we look up
# the container the deploy actually created, or fail loudly.
[ -d "$BACKUP_COMPOSE_DIR" ] || die "BACKUP_COMPOSE_DIR '$BACKUP_COMPOSE_DIR' does not exist"
[ -r "$BACKUP_COMPOSE_DIR/$BACKUP_COMPOSE_FILE" ] || die "no readable $BACKUP_COMPOSE_FILE in $BACKUP_COMPOSE_DIR"
CID="$(cd "$BACKUP_COMPOSE_DIR" && docker compose -f "$BACKUP_COMPOSE_FILE" --project-name "$BACKUP_COMPOSE_PROJECT" ps -q "$BACKUP_PG_SERVICE")"
[ -n "$CID" ] || die "no running container for compose service '$BACKUP_PG_SERVICE' in project '$BACKUP_COMPOSE_PROJECT' ($BACKUP_COMPOSE_DIR/$BACKUP_COMPOSE_FILE)"
CID_COUNT="$(printf '%s\n' "$CID" | grep -c . || true)"
# Replicas, or a leftover container from an interrupted `up`: dumping "one of them" is a
# coin toss nobody would notice afterwards, so the ambiguity itself is the failure.
[ "$CID_COUNT" = 1 ] || die "compose service '$BACKUP_PG_SERVICE' resolves to $CID_COUNT containers; refusing to guess which one is the database"

DUMP="$BACKUP_LOCAL_DIR/$BASENAME.dump"
GLOBALS="$BACKUP_LOCAL_DIR/$BASENAME.globals.sql"

# --- stage 1: dump -----------------------------------------------------------
log "dumping database from container $CID"
docker exec "$CID" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$DUMP"
docker exec "$CID" sh -c 'pg_dumpall -U "$POSTGRES_USER" --globals-only' > "$GLOBALS"

SIZE="$(stat -c %s "$DUMP")"
[ "$SIZE" -ge "$BACKUP_MIN_BYTES" ] || die "dump is ${SIZE}B, under BACKUP_MIN_BYTES=$BACKUP_MIN_BYTES"
# Custom-format dumps start with the literal magic "PGDMP". A truncated or
# error-page-shaped file will not.
[ "$(head -c 5 "$DUMP")" = "PGDMP" ] || die "dump does not start with the PGDMP magic"
# The magic is five bytes at the front and says nothing about the rest. `pg_restore -l`
# parses the whole table of contents, so an archive truncated at 90% fails HERE rather than
# during the restore that needed it. pg_restore lives in the container, not on the host,
# and reads a custom-format archive from stdin — `-i` (not `-t`) because we are feeding it
# a file, not talking to a terminal.
TOC_ENTRIES="$(docker exec -i "$CID" pg_restore -l < "$DUMP" 2>/dev/null | grep -cv '^;' || true)"
[ "$TOC_ENTRIES" -ge "$BACKUP_MIN_TOC_ENTRIES" ] || die "dump lists only $TOC_ENTRIES restorable objects, under BACKUP_MIN_TOC_ENTRIES=$BACKUP_MIN_TOC_ENTRIES (a truncated archive, or a database that is not Hamstrack's)"

DUMP_OK=1
date +%s > "$BACKUP_STATE_DIR/last_success_dump"
log "dump OK: $DUMP ($SIZE bytes, $TOC_ENTRIES objects)"

# --- stage 2: upload ---------------------------------------------------------
if [ "$UPLOAD" = 0 ]; then
  log "BACKUP_TARGET=local; kept in $BACKUP_LOCAL_DIR, retention ${BACKUP_KEEP_LOCAL_DAYS}d (swept at the START of each run)"
  exit 0
fi

[ -n "$BACKUP_S3_BUCKET" ] || die "BACKUP_S3_BUCKET is unset and BACKUP_TARGET=s3"

AWS_ARGS=(--region "$BACKUP_S3_REGION")
if [ -n "$BACKUP_S3_ENDPOINT" ]; then
  AWS_ARGS+=(--endpoint-url "$BACKUP_S3_ENDPOINT")
  # AWS CLI >= 2.23 sends a default request checksum (CRC32) and expects one in return;
  # several S3-compatible stores reject or omit it, which surfaces as an unexplained 400.
  # Asking for checksums only where the protocol requires them restores the older
  # behaviour. Scoped to the endpoint branch, so real AWS keeps its defaults.
  export AWS_REQUEST_CHECKSUM_CALCULATION=when_required
  export AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
fi

if [ "$BACKUP_S3_PATH_STYLE_ACCESS" = "true" ]; then
  # Same knob, and deliberately the same vocabulary, as the application's own S3 backend
  # (`app.storage.s3.path-style-access`). The AWS CLI takes this setting from a config FILE
  # and from no environment variable, so we supply a throwaway one — and refuse to do that
  # behind an operator's own AWS_CONFIG_FILE/AWS_PROFILE rather than silently shadow it.
  if [ -n "${AWS_CONFIG_FILE:-}" ] || [ -n "${AWS_PROFILE:-}" ]; then
    log "WARN BACKUP_S3_PATH_STYLE_ACCESS=true but AWS_CONFIG_FILE/AWS_PROFILE is already set; leaving it alone — put 'addressing_style = path' under an 's3 =' block in that profile yourself"
  else
    AWS_CFG_DIR="$(mktemp -d)"
    printf '[default]\ns3 =\n    addressing_style = path\n' > "$AWS_CFG_DIR/config"
    export AWS_CONFIG_FILE="$AWS_CFG_DIR/config"
    log "path-style addressing enabled (credentials must then come from the environment or ~/.aws/credentials, not from ~/.aws/config)"
  fi
fi

put() { # $1 = local file, $2 = key ; verifies the stored bytes end-to-end
  local f="$1" key="$2" md5 etag
  md5="$(md5sum "$f" | awk '{print $1}')"
  # --if-none-match "*" makes the key write-once: the request fails with 412
  # PreconditionFailed if anything is already stored there. Together with the matching Deny
  # in the bucket policy — which covers the WHOLE bucket, `manual/` included — it closes
  # the one path that `PutObject` + prefix `ListBucket` would otherwise leave open:
  # overwrite every backup with an empty body, wait for NoncurrentVersionExpiration, and
  # the archive is gone with nothing to notice it, because these metrics measure the local
  # run and never the remote state. Needs AWS CLI >= 2.19; an older one exits 252 on the
  # unknown option, loudly, which is why the runbook checks the version before the timer is
  # armed.
  # --server-side-encryption AES256 asserts what the bucket default already does, so a
  # bucket whose default was weakened answers 400 instead of quietly storing plaintext.
  # AES256 (not KMS) is also what keeps ETag == MD5 below.
  etag="$(aws "${AWS_ARGS[@]}" s3api put-object \
            --bucket "$BACKUP_S3_BUCKET" --key "$key" --body "$f" \
            --if-none-match "*" \
            --server-side-encryption AES256 \
            --query ETag --output text | tr -d '"')"
  case "$VERIFY_ETAG" in
    off)
      log "uploaded s3://$BACKUP_S3_BUCKET/$key (ETag check disabled)" ;;
    warn)
      if [ "$etag" = "$md5" ]; then
        log "uploaded s3://$BACKUP_S3_BUCKET/$key (md5 verified)"
      else
        log "WARN uploaded s3://$BACKUP_S3_BUCKET/$key but ETag $etag != local md5 $md5 — this store does not return the body MD5 as the ETag, so the bytes are UNVERIFIED (set BACKUP_S3_VERIFY_ETAG=on if yours does)"
      fi ;;
    *)
      # Single PUT into an SSE-S3 bucket: the ETag IS the MD5 of the body. This check needs
      # no s3:GetObject, which is what keeps the instance role write-only. It would NOT hold
      # under SSE-KMS or a multipart upload — see spec §6.5 before changing either.
      [ "$etag" = "$md5" ] || { log "FATAL ETag $etag != local md5 $md5 for $key"; return 1; }
      log "uploaded s3://$BACKUP_S3_BUCKET/$key (md5 verified)" ;;
  esac
}

put "$DUMP"    "$BACKUP_S3_PREFIX/$BASENAME.dump"
put "$GLOBALS" "$BACKUP_S3_PREFIX/$BASENAME.globals.sql"

UPLOAD_OK=1
date +%s > "$BACKUP_STATE_DIR/last_success_upload"
log "backup complete"
