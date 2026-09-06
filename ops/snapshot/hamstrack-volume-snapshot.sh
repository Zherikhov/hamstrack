#!/usr/bin/env bash
# Hamstrack root-volume snapshot age check (HD-262).
# Spec: docs/design/root-volume-snapshot-age-proposal.md
#
#   hamstrack-volume-snapshot.sh
#
# Answers ONE question — "how old is the newest completed snapshot of the volume this
# instance is running on RIGHT NOW?" — and publishes the answer as node-exporter textfile
# metrics. It is run hourly by hamstrack-volume-snapshot.timer.
#
# IT IS AN OUTCOME CHECK, NOT A MECHANISM CHECK, and that is the whole design (ADR-0037).
# Backup layer 3 — the daily EBS snapshot of the root volume, docs/ops-prod-hardening.md
# §6.1 — produced nothing usable for two consecutive outages with one shared cause, and
# every signal available said it was fine:
#
#   2026-08-29 -> 09-03  the root volume was replaced with an encrypted one and the
#                        Backup=hamstrack tag was not carried over, so the DLM policy went
#                        on selecting the OLD, DETACHED volume. The schedule ran nightly
#                        and SUCCEEDED nightly. "Did the job run" was green for five days
#                        over a volume nobody was running on.
#   2026-09-04 -> open   the tag was added to the live volume on 09-03, which ARMED the
#                        second failure: the schedule does CopyTags:true AND
#                        TagsToAdd:[{Name: hamstrack-auto}], the new volume carries a Name
#                        tag, and every run since dies on "Duplicate tag key 'Name'". That
#                        is reported only in the policy's State field, which nothing reads.
#
# One question catches both, and it catches the next one too, because it never asks WHY:
# is there a recent restorable image of THIS volume. Hence, and each of these is
# load-bearing rather than incidental:
#
#   * A TIMESTAMP, NEVER A PRE-COMPUTED AGE. An age gauge frozen in a .prom that nobody
#     rewrites reads as permanently fresh — the collector dies, the file keeps saying
#     "3600", and the rule never fires. A timestamp in the same frozen file gets older on
#     its own, so a stopped collector CONVERGES ON THE ALERT instead of hiding behind it.
#     It also decouples detection latency from this timer's cadence: an hourly check still
#     fires at the correct wall-clock minute.
#   * NO STORED VOLUME ID. There is no SNAPSHOT_VOLUME_ID, no config for one and no
#     override — see resolve_volume(). A pinned id is the same class of stored fact as the
#     tag that caused outage 1: correct on the day it is written and silently wrong from
#     the moment a volume is replaced. A check that inherits the staleness it exists to
#     catch is worse than no check, because it is a green light over the exact failure.
#   * FILTERED BY VOLUME ID, NEVER BY TAG, and ANY snapshot counts whoever took it. A tag
#     is somebody's INTENTION about a volume; the question here is whether a restorable
#     image of it exists. It is also what makes the refusal actionable: an operator who
#     cannot fix DLM right now can clear the alert by taking a snapshot by hand, which is
#     an action its reader can actually perform.
#   * AMBIGUITY IS A FAILURE, NOT A GUESS. Same rule as hamstrack-backup.sh's refusal to
#     choose between two postgres containers: the ambiguity itself is the failure.
#
# WHAT THIS PRINTS IS PUBLIC — stated deliberately rather than left to chance. This
# collector is NOT on the deploy path (apply-config.sh does not run it, on purpose: it
# would make a deploy depend on the EC2 control plane and on IMDS being reachable, and it
# would publish a fresh check_timestamp at deploy time, recreating the "only ever as fresh
# as the last deploy" gap that ConfigDriftCheckStale had to be invented for), so its
# output reaches the journal on the box and nothing else. The bound is written down
# anyway, because apply-config.sh runs ops scripts at deploy time and that output travels
# through SSM into a GitHub Actions log of a PUBLIC repository, and a future change could
# put this script there:
#
#   * VOLUME IDS AND SNAPSHOT IDS ARE NOT SECRETS and may be logged and labelled. They are
#     identifiers within one account, not capability tokens; an attacker holding a volume
#     id and no IAM credentials can do nothing with it, and anyone who can read this box
#     can read its block devices anyway.
#   * THE AWS ACCOUNT ID MUST NOT BE PRINTED, and it is the one value that arrives by
#     accident: an AccessDenied message quotes the assumed-role ARN
#     (arn:aws:sts::<account>:assumed-role/hamstrack-ec2/i-...). So a failed AWS call is
#     reported as WHICH CALL failed and ITS ERROR CODE — never as the CLI's raw stderr.
#     See aws_ec2(). To read the full message, run the same command by hand.
#   * Nothing here reads /opt/hamstrack/.env, and there is no path by which a secret
#     reaches this script's stdout.
#
# THIS SCRIPT DOES NOT SOURCE ITS CONFIG FILE; the unit does, through
# EnvironmentFile=-/etc/hamstrack/snapshot.env. Same arrangement as
# hamstrack-config-drift.sh and deliberately NOT hamstrack-backup.sh's: that script
# sources backup.env, which needs some sixty lines of probe-then-source machinery so that
# a file bash cannot run becomes a refusal rather than a backup taken with defaults. There
# is nothing of that weight here — the settings are one off switch and two testing seams,
# and a wrong one cannot produce a wrong artefact, only a wrong reading that the metrics
# then report. The consequence to know before debugging: a HAND run of
# /usr/local/bin/hamstrack-volume-snapshot does NOT read /etc/hamstrack/snapshot.env.
# Start it through systemd (systemctl start hamstrack-volume-snapshot.service), which is
# the only invocation the timer uses anyway, or pass the variables on the command line.
#
# TWO IAM ACTIONS, BOTH READ-ONLY, AND IT WILL NEVER HOLD A WRITE: ec2:DescribeVolumes and
# ec2:DescribeSnapshots (inline policy ec2-snapshot-read on role hamstrack-ec2,
# conditioned on aws:RequestedRegion). A monitor that can create a snapshot can be made to
# delete one. ec2:DescribeInstances is deliberately ABSENT — the instance id comes from
# IMDS and the volume from a DescribeVolumes filter — so do not reach for it to make a
# more convenient query work. The region condition also means the region is never
# implicit: every call passes --region explicitly, resolved from this instance's own
# placement, and an AWS_DEFAULT_REGION leaking in from somewhere is not a supported input.
set -euo pipefail

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "FATAL $*"; exit 1; }

# --- configuration -----------------------------------------------------------
# Deliberately absent: SNAPSHOT_VOLUME_ID (see the header) and both alert thresholds —
# those are provisioned in observability/grafana/provisioning/alerting/rules.yml, following
# HostMemoryLow, HostSwapInUse and MailDailyVolumeHigh. If 30 h is wrong for your host,
# change it in that file; there is no variable to set.
SNAPSHOT_SOURCE="${SNAPSHOT_SOURCE:-ebs}"
SNAPSHOT_REGION="${SNAPSHOT_REGION:-}"
SNAPSHOT_TEXTFILE_DIR="${SNAPSHOT_TEXTFILE_DIR:-/var/lib/node_exporter/textfile_collector}"
# Testing seams, the way CONFIG_DRIFT_UNIT_DIR exists so the drift comparison can be
# exercised off a production box. Neither is a supported production setting.
SNAPSHOT_IMDS_BASE="${SNAPSHOT_IMDS_BASE:-http://169.254.169.254}"
SNAPSHOT_AWS_BIN="${SNAPSHOT_AWS_BIN:-aws}"

# The one file node-exporter scrapes. Named once because TWO branches touch it and they must
# touch the same path: the `ebs` run rewrites it, and `none` REMOVES it.
PROM_FILE="$SNAPSHOT_TEXTFILE_DIR/hamstrack_volume_snapshot.prom"

# AWS CLI v2 pipes output through a pager, and a monitor that can block on `less` is a
# monitor that can wedge a systemd unit until TimeoutStartSec.
export AWS_PAGER=""

# --- run state ---------------------------------------------------------------
# Start pessimistic. A run that could not complete a stage has not shown that the stage is
# healthy, and the EXIT trap below is what makes an unexpected failure say so instead of
# leaving the previous run's reading in place.
RESOLVE_OK=0
DESCRIBE_OK=0
REGION=""
VOLUME=""
# EMPTY IS "NO ANSWER" AND IS NOT THE SAME AS 0. `0` is published only for the successful
# answer "this volume has no completed snapshots at all" — which is the 08-29 case, and
# making it a real value is what makes the alert fire that same evening rather than reading
# as noData, which reads as OK, which is silence. When the run could not find out, the
# series is OMITTED instead: a value we do not have must not be published as a value we do,
# and the stage gauge plus VolumeSnapshotCheckFailing is what carries "we could not ask".
NEWEST=""
SNAP_COUNT=0

# A label value carrying a quote or a backslash makes the WHOLE textfile malformed, and
# node-exporter then drops every series in it at once — silently, because noDataState: OK
# reads an absent series as health. Volume ids are vol-[0-9a-f]+ today; the guard is about
# the category, not about this value. (Drift's sanitize_label, same body, same reason.)
sanitize_label() {
  local v="$1"
  v="${v//[^A-Za-z0-9._-]/}"
  printf '%s' "${v:-unknown}"
}

# sanitize_label's twin for the JOURNAL, and ITS CATEGORY IS STATED NARROWLY ON PURPOSE: a
# value that arrived OVER THE NETWORK must not choose how many lines it occupies. Everything
# IMDS answers goes through this before it is quoted into a message, because a newline in an
# IMDS body FORGES A JOURNAL LINE — the same primitive HD-275 closed at the log sink, one layer
# down — and an unbounded body would otherwise be echoed whole into a message an operator reads
# under pressure.
#
# TWO VALUES ARE QUOTED INTO REFUSALS WITHOUT PASSING THROUGH HERE, and naming them is cheaper
# than a category broad enough to be wrong. $SNAPSHOT_SOURCE and $SNAPSHOT_REGION arrive from
# /etc/hamstrack/snapshot.env, a root-owned 0640 file that only somebody who is already root
# can write, so they are not a trust boundary. And the SNAPSHOT_SOURCE refusal positively WANTS
# the raw value: its whole subject is a trailing CR, which rewinds the line so the value prints
# as if it were clean — which is why that same message says the word "CR" in full, and why
# putting it through this belt would delete the thing it is about. If either value ever gains a
# second source — a flag, an API, anything a non-root caller can reach — it joins the category
# above in the same commit.
#
# Control characters out, 120 characters kept, AND THE TRUNCATION SAYS SO. A legitimate instance
# id, region or device name is far shorter, so a truncated one is already a refusal that says
# enough; but an operator comparing a quoted value against what they believe is configured has
# to be able to tell "this is the whole value" from "this is the first 120 characters of one",
# and a silent cut looks exactly like a short answer.
printable() {
  local v="${1//[[:cntrl:]]/}"
  if [ "${#v}" -gt 120 ]; then
    printf '%s... (truncated, %s characters in all)' "${v:0:120}" "${#v}"
  else
    printf '%s' "$v"
  fi
}

write_metrics() {
  local now out tmp
  now="$(date +%s)"
  out="$PROM_FILE"
  tmp="$out.$$"
  if ! mkdir -p "$SNAPSHOT_TEXTFILE_DIR" 2>/dev/null; then
    log "WARN $SNAPSHOT_TEXTFILE_DIR is missing and cannot be created — no metrics will be published."
    log "WARN create it (the install step does) or set SNAPSHOT_TEXTFILE_DIR."
  fi
  {
    echo '# HELP hamstrack_volume_snapshot_newest_timestamp_seconds Unix time (StartTime) of the newest completed snapshot of a volume attached to this instance. 0 means no snapshot exists.'
    echo '# TYPE hamstrack_volume_snapshot_newest_timestamp_seconds gauge'
    if [ -n "$VOLUME" ] && [ -n "$NEWEST" ]; then
      echo "hamstrack_volume_snapshot_newest_timestamp_seconds{volume=\"$(sanitize_label "$VOLUME")\"} $NEWEST"
    fi
    echo '# HELP hamstrack_volume_snapshot_check_status Whether this run completed the stage (1) or not (0).'
    echo '# TYPE hamstrack_volume_snapshot_check_status gauge'
    # `volume` is deliberately NOT a label here: at the resolve stage there is no volume
    # yet, and a status series that sometimes carries a label and sometimes does not is two
    # metrics wearing one name.
    echo "hamstrack_volume_snapshot_check_status{stage=\"resolve\"} $RESOLVE_OK"
    echo "hamstrack_volume_snapshot_check_status{stage=\"describe\"} $DESCRIBE_OK"
    echo '# HELP hamstrack_volume_snapshot_check_timestamp_seconds Unix time this check last ran. Distinguishes a fresh answer from a frozen one.'
    echo '# TYPE hamstrack_volume_snapshot_check_timestamp_seconds gauge'
    echo "hamstrack_volume_snapshot_check_timestamp_seconds $now"
  } > "$tmp"
  # Deliberately wider than the umask, and it must stay that way: node-exporter runs as
  # `nobody` inside its container and could not read a 0600 file, which would take every
  # series here off the air while looking installed. Same exemption, same reason, as the
  # backup and drift collectors. The file holds a volume id, two flags and two timestamps —
  # no secret.
  chmod 0644 "$tmp"
  mv -f "$tmp" "$out"   # atomic: a scrape must never see a half-written file
  log "snapshot check: volume=${VOLUME:-<unresolved>} newest=${NEWEST:-<unknown>} snapshots=$SNAP_COUNT resolve=$RESOLVE_OK describe=$DESCRIBE_OK"
}

# The captured-stderr scratch file, removed on EVERY exit rather than only on the happy path:
# a `die` anywhere below aws_ec2's first call would otherwise leave one behind on each failed
# run. Under the unit's PrivateTmp= that namespace goes away with the invocation anyway; a
# hand run has no such luck, and a hand run is what an operator does while debugging.
cleanup_err() { [ -z "${AWS_ERR_FILE:-}" ] || rm -f -- "$AWS_ERR_FILE"; }
# The IMDSv2 token's header file (see imds()). Removed on EVERY exit for the same reason and
# with more urgency: it holds a live credential for its 60-second life, and a `die` between
# minting it and the end of the run would otherwise leave it in a hand run's /tmp.
cleanup_token() { [ -z "${IMDS_HDR_FILE:-}" ] || rm -f -- "$IMDS_HDR_FILE"; }

# EVERY CLEANUP IS BEST-EFFORT AND THE PUBLISH IS LAST AND UNCONDITIONAL. This trap runs under
# `set -e`, so a failing `rm -f` in either helper — a read-only /tmp, a path whose directory
# stopped being writable — would abort the trap WHERE IT STANDS and never reach write_metrics,
# leaving the previous run's reading in place. That is the exact outcome this trap's position
# above every validation exists to forbid, and it is a failure mode the tidying introduced
# rather than one it found. The `|| true` is what makes the ordering safe: tidying is
# best-effort, publishing is the contract. A fourth line here needs a fourth `|| true`.
on_exit() { cleanup_err || true; cleanup_token || true; write_metrics; }

# --- traps -------------------------------------------------------------------
# ABOVE every validation and every pre-flight check below, so that each `die` down there
# still publishes the two stage gauges as 0 rather than leaving the previous run's answer
# standing. hamstrack-backup.sh learned this the expensive way: its trap used to sit below
# the config validation, so a typo in backup.env produced a failed run whose .prom went on
# insisting the last one succeeded.
trap on_exit EXIT
# Bash runs no EXIT trap for an untrapped fatal signal, so TimeoutStartSec's SIGTERM would
# skip the trap entirely and leave the previous reading in place. Trapping TERM as an
# ordinary exit (128+15) routes the graceful half of the timeout through the trap above.
trap 'exit 143' TERM
# INT and HUP for the same reason and with one extra one: a hand run is what an operator
# does while debugging, and Ctrl-C on it used to leave behind a 0600 header file holding a
# live IMDSv2 token for the rest of its 60-second life. Routing them through the trap sweeps
# that file and the AWS scratch file. THE TRADE-OFF IS NAMED: the run then also publishes its
# pessimistic zeroes, so a Ctrl-C'd hand run fires VolumeSnapshotCheckFailing until the next
# hourly run clears it. That is the correct direction and the same one TERM already takes —
# a run that did not finish has not shown the stage is healthy — and it self-heals within
# the hour, which "leave yesterday's answer standing" does not.
trap 'exit 130' INT
trap 'exit 129' HUP
#
# THERE IS DELIBERATELY NO ExecStopPost= HANDLER, unlike hamstrack-backup.service, and the
# reason is the timestamp. ANY FATAL SIGNAL THAT RUNS NO TRAP freezes this .prom, and the
# CLASS is what matters rather than today's membership — which has already grown once, when
# the unit gained SystemCallFilter= and with it a SIGSYS. Today that class is the cgroup OOM
# killer, `systemctl kill -s KILL`, the ungraceful half of TimeoutStartSec, and seccomp
# answering a syscall the AWS CLI needs. For the backup job a frozen file is a stale CLAIM OF
# SUCCESS that never decays, so it needs a handler. Here the primary gauge is a timestamp
# that ages by itself: such a run converges on VolumeSnapshotStale regardless, and
# VolumeSnapshotCheckStale reaches the operator first. The cost is named rather than
# glossed — between the kill and the 3 h stale threshold the two stage gauges still read 1
# and VolumeSnapshotCheckFailing does not fire. That is an accepted three-hour window, not an
# oversight. The second cost is the diagnosis: CheckStale's first move is written for a timer
# that is not running, which is the likeliest cause and not the only one, so it also names
# `systemctl show -p Result -p ExecMainStatus` for the case where the timer is running fine
# and the unit is being killed on every fire.

# --- configuration validation ------------------------------------------------
# ONE validated value, both branches derived from it. Two independent equality tests are
# not complementary: `EBS`, or `ebs` with the trailing CR a Windows checkout of
# snapshot.env leaves behind, would take NEITHER branch while looking configured. Exactly
# the shape that once let BACKUP_TARGET take the upload path while never emitting the
# `upload` series.
case "$SNAPSHOT_SOURCE" in
  ebs)
    ;;
  none)
    # An explicit off switch for a box that installed this unit and later moved off EC2. It
    # ends with NO SERIES AT ALL — not a zero, not a stale timestamp — so the three rules go
    # dormant through noDataState: OK rather than firing about a mechanism nobody wants.
    #
    # IT GETS THERE BY DELETING THE FILE, NOT BY DECLINING TO WRITE ONE, and the difference
    # is the whole switch. node-exporter scrapes whatever is in the textfile directory, for
    # ever; a branch that exits without touching it does not turn the check OFF, it FREEZES
    # it — check_timestamp_seconds stops advancing, VolumeSnapshotCheckStale fires at 3 h and
    # never clears, and a frozen newest_timestamp_seconds takes the CRITICAL
    # VolumeSnapshotStale with it at 30 h. And because the default is `ebs`, this branch is
    # only ever REACHED by a box that has already been publishing — so "leave the file alone"
    # hands a permanent critical alert to the exact operator the escape hatch was written for.
    #
    # THE BACKUP_TARGET=local ANALOGY ONLY HOLDS FOR A BRANCH THAT REMOVES, which is why it
    # is not repeated as if it were free: hamstrack-backup.sh rewrites its whole .prom on
    # every run and OMITS the `upload` series, so flipping that switch actively takes the
    # series off the air. Not writing removes nothing.
    #
    # The DEFAULT is `ebs` and not `none`, which is the direction that matters: with an
    # `off` default the likeliest field outcome is a unit installed, snapshot.env
    # forgotten, a collector that runs and does nothing, layer 3 unwatched and a
    # green-looking box — HD-262 reproduced by the mechanism built to prevent it.
    # Installing this unit IS the declaration of intent.
    log "SNAPSHOT_SOURCE=none; removing any published metrics and exiting (set SNAPSHOT_SOURCE=ebs in /etc/hamstrack/snapshot.env to re-enable)"
    # UNDER THE LOCK when there is a flock to take, so the removal cannot land between a
    # concurrent run's redirect and its `mv` and delete a file that then comes back. Probed
    # with `command -v` rather than by running it, for the reason need_tool exists: `if !
    # flock -n 9` cannot tell exit 1 ("someone holds it") from 127 ("no such command"). A
    # host without util-linux still gets the removal — it is idempotent, and this branch must
    # work on the bare-metal box that has neither flock nor an AWS CLI, which is why the
    # whole `none` path sits ABOVE need_tool.
    # THE `exec` IS ITSELF GUARDED, AND THE LOAD-BEARING TOKEN IS THE `if` — NOT THE BRACES AND
    # NOT THE `2>/dev/null`. Under `set -e` a failed redirect on a bare `exec` ends the run:
    # `{ exec 9>/nonexistent/f; } 2>/dev/null` on its own still exits, which was RUN rather than
    # reasoned about. What makes it survivable is sitting inside a TESTED CONDITION; the braces
    # only group the redirect so the `&&` has something to test, and the 2>/dev/null only keeps
    # the message out of the journal. So the obvious tidy-up —
    #
    #     if command -v flock >/dev/null 2>&1; then
    #       exec 9>/var/lock/…            # now unguarded
    #
    # — silently restores a critical bug, and restores it INVISIBLY: this branch is the one path
    # that must also work where /var/lock is not writable by this process, and everywhere it IS
    # writable (CI, every healthy box) the redirect never fails, so nothing notices. An
    # unguarded failure here abandons the removal, which is the exact outcome the branch exists
    # to prevent. Sealed by VolumeSnapshotCollectorContractTest, which drives a copy of this
    # script with the lock path pointed at a directory that does not exist.
    if command -v flock >/dev/null 2>&1 && { exec 9>/var/lock/hamstrack-volume-snapshot.lock; } 2>/dev/null; then
      flock -n 9 || log "another hamstrack-volume-snapshot run holds the lock; removing anyway — the removal is idempotent and the next hourly run removes anything that run writes after us"
    fi
    if [ ! -e "$PROM_FILE" ]; then
      log "no published metrics to remove at $PROM_FILE; nothing was scraping this box"
    elif rm -f -- "$PROM_FILE"; then
      log "removed $PROM_FILE — the three VolumeSnapshot* rules now go dormant through noDataState: OK instead of firing on a frozen reading"
    else
      log "WARN could not remove $PROM_FILE. node-exporter goes on scraping it, so VolumeSnapshotCheckStale will fire within 3 h and VolumeSnapshotStale within 30 h on a reading that can never change. Remove it by hand."
    fi
    # The last run there will ever be is also the last chance to sweep a temp file from a run
    # that was killed between its redirect and its `mv`. Never scraped (node-exporter reads
    # *.prom only), but nothing else will ever come back for them.
    rm -f -- "$SNAPSHOT_TEXTFILE_DIR"/hamstrack_volume_snapshot.prom.[0-9]* 2>/dev/null || true
    trap - EXIT
    exit 0
    ;;
  *)
    die "SNAPSHOT_SOURCE must be exactly 'ebs' or 'none', got '$SNAPSHOT_SOURCE' — the comparison is neither case- nor whitespace-insensitive on purpose, so 'EBS' lands here, and so does 'ebs' with the trailing CR a CRLF snapshot.env leaves behind"
    ;;
esac

# It reaches an AWS CLI command line and must satisfy the ec2-snapshot-read policy's
# aws:RequestedRegion condition. Empty is the normal case: the region then comes from IMDS.
case "$SNAPSHOT_REGION" in
  '') ;;
  *[!a-z0-9-]*) die "SNAPSHOT_REGION may contain only a-z 0-9 and '-', got '$SNAPSHOT_REGION'" ;;
esac

# --- pre-flight: the tools ---------------------------------------------------
# ABOVE the lock, and specifically above it because of `flock` itself: `if ! flock -n 9`
# cannot tell flock's exit 1 ("someone else holds it") from the shell's 127 ("no such
# command"), so on a host without util-linux EVERY invocation would take the loser branch,
# exit 0 without touching the metrics, and report success for ever. hamstrack-backup.sh
# carries the same ordering for the same reason. Below the traps, so a missing tool is a
# published failure and not a silent one.
need_tool() { command -v "$1" >/dev/null 2>&1 || die "$1 is not installed or not on PATH ($2)"; }
need_tool curl  "IMDSv2 is queried over HTTP; the instance identity has no other source here"
# `-H @file` — the reason the IMDSv2 token never appears in an argv — arrived in curl 7.55.0
# (2017). An OLDER curl sends the literal `@/tmp/…` as a header of its own, IMDS answers 401,
# and the run then dies at the instance-id fetch blaming the metadata service: the right
# refusal for the wrong reason, which is the misdiagnosis this whole ticket is about. So the
# version is named HERE — and only as a WARNING, and only when it can actually be read. An
# unparseable `--version`, or a host whose `sort` has no -V, says nothing at all: a curl this
# script cannot version-check is far likelier to be newer than 7.55 than older, and a monitor
# that refuses to run on a guess is worse than one that explains itself when it breaks.
curl_ver="$(curl --version 2>/dev/null | head -n 1 | cut -d' ' -f2 || true)"
case "$curl_ver" in
  ''|*[!0-9.]*) ;;
  *)
    curl_oldest="$(printf '%s\n7.55.0\n' "$curl_ver" | LC_ALL=C sort -V 2>/dev/null | head -n 1 || true)"
    if [ -n "$curl_oldest" ] && [ "$curl_oldest" != "7.55.0" ]; then
      log "WARN curl $curl_ver is older than 7.55.0, where -H @file began meaning 'read headers from this file'. The IMDSv2 token is passed that way so it never appears in an argv; an older curl sends the filename as a header instead, IMDS answers 401, and the next failure will name the metadata service rather than curl. Upgrade curl."
    fi
    ;;
esac
need_tool flock "used to serialise runs"
need_tool "$SNAPSHOT_AWS_BIN" "the two EC2 Describe calls need the AWS CLI v2; install it, or set SNAPSHOT_SOURCE=none if this box is not on EC2"

# --- one run at a time -------------------------------------------------------
# A hand run racing the timer would write the same .prom. The loser exits 0 WITHOUT
# touching the metrics, so it cannot forge freshness, and a permanently stuck lock still
# surfaces as staleness through hamstrack_volume_snapshot_check_timestamp_seconds.
exec 9>/var/lock/hamstrack-volume-snapshot.lock
if ! flock -n 9; then
  log "another hamstrack-volume-snapshot run holds the lock; exiting without touching metrics"
  trap - EXIT
  exit 0
fi

# A run killed between the redirect and the `mv` leaves one of these behind. node-exporter
# only reads *.prom so they are never scraped, but they accumulate. UNDER the lock, because
# a concurrent run's temp file is live for the moment between its redirect and its `mv`, and
# sweeping from outside the lock is a race whose prize is that run's metrics.
rm -f -- "$SNAPSHOT_TEXTFILE_DIR"/hamstrack_volume_snapshot.prom.[0-9]* 2>/dev/null || true

# --- the AWS calls, and what a failure of one may say ------------------------
# stderr is captured and NEVER echoed: see the disclosure note in the header. What reaches
# the journal is the call and the error code, which is what an operator acts on
# (AccessDenied -> the policy or its region condition; RequestLimitExceeded -> throttling;
# InvalidVolume.NotFound -> a volume that vanished between the two calls).
AWS_ERR_FILE=""
aws_error_code() {
  local code
  code="$(sed -n 's/.*An error occurred (\([A-Za-z0-9._-]*\)).*/\1/p' "$AWS_ERR_FILE" 2>/dev/null | head -n 1)"
  # "unclassified" rather than the raw text: the CLI's stderr is the one place the account
  # id arrives by accident, so it is not printed even where it would be convenient.
  printf '%s' "${code:-unclassified (the CLI message is deliberately not echoed: an AccessDenied quotes the assumed-role ARN, which carries the account id — run the call by hand to read it)}"
}
aws_ec2() { # the arguments after `ec2`; $1 is the subcommand, and it is what the journal names
  local out rc=0
  [ -n "$AWS_ERR_FILE" ] || AWS_ERR_FILE="$(mktemp "${TMPDIR:-/tmp}/hamstrack-volume-snapshot.XXXXXX")"
  out="$("$SNAPSHOT_AWS_BIN" ec2 "$@" 2>"$AWS_ERR_FILE")" || rc=$?
  if [ "$rc" != 0 ]; then
    log "aws ec2 $1 failed (exit $rc), error code: $(aws_error_code)"
    return "$rc"
  fi
  printf '%s' "$out"
}

# --- stage "resolve" ---------------------------------------------------------
# IMDSv2 token -> instance id -> region -> root device name -> DescribeVolumes -> ONE
# volume id. A failure anywhere in here means IMDS is unreachable or refusing v2, the
# metadata hop limit was lowered, the instance identity is unavailable, or the attached
# volumes are ambiguous.
#
# AN UNREACHABLE IMDS IS A PUBLISHED FAILURE, NEVER A SILENT STAND-DOWN (ADR-0038). The
# tempting alternative is to probe IMDS and, if nothing answers, conclude "not on EC2,
# nothing to do" and exit quietly. It is rejected because it makes a PRODUCTION box whose
# IMDS broke — hop limit reset to 1, IMDS disabled, a local firewall rule — byte-for-byte
# indistinguishable from a self-hoster's bare-metal server: both publish nothing, both are
# silent, both look exactly like health. That is this ticket's own failure one layer down.
# The DC path is expressed as INSTALLATION instead: a deploy places /opt/hamstrack/ops/ and
# installs nothing, so a self-hoster on bare metal never runs the install step, no series
# ever exists, and the three rules stay dormant. Worth having for a second reason too: the
# application resolves its S3 credentials from IMDS, so a broken IMDS is a wider outage
# than this check.
IMDS_TOKEN=""
IMDS_HDR_FILE=""
imds() { # $1 = metadata path, without a leading slash
  # Short connect and total timeouts: the link-local address does not ANSWER on a box that
  # is not EC2 — it does not refuse — and a monitor must not hang on that.
  #
  # THE TOKEN IS PASSED BY FILE (-H @file, curl 7.55+), NEVER ON THE COMMAND LINE. An
  # argument is world-readable in /proc/<pid>/cmdline for the life of the process, and
  # node-exporter runs with `pid: host`, so every container on this box can see it. The
  # privilege delta is genuinely zero — anything that can read that cmdline can equally mint
  # its own token from the same IMDS — so this is hygiene rather than a hole, and it is taken
  # because it costs one mktemp: a credential that never appears in an argv is one fewer
  # place a future reader has to reason about. The file is 0600 and lives under the unit's
  # PrivateTmp=, and the EXIT trap removes it.
  #
  # WHAT BOUNDS AN UNVALIDATED BODY HERE IS --max-time 5, NOT --max-filesize. curl applies the
  # size ceiling only when the size is known in advance, so a responder that omits Content-Length
  # or chunks its reply streams straight into the command substitution until the clock runs out.
  # Both options stay: the ceiling is free and it does refuse a declared oversize body early.
  # What is being bounded is an unbounded response from whatever is answering $SNAPSHOT_IMDS_BASE
  # being read into a shell variable and then quoted into a journal message; IMDS answers are
  # tens of bytes. The unit's MemoryMax=384M is the backstop under both, and the cgroup OOM
  # killer runs no trap — so it freezes the .prom rather than publishing a failure, which is
  # the cost the "NO ExecStopPost=" paragraph above prices.
  curl -fsS --connect-timeout 2 --max-time 5 --max-filesize 65536 \
    -H @"$IMDS_HDR_FILE" "$SNAPSHOT_IMDS_BASE/$1"
}

resolve_volume() {
  local iid region root_dev basename_root rows uniq att_instance dev vol
  local matches=() attached=()

  # The instance runs --http-tokens required (docs/ops-prod-hardening.md §1), so a v1 GET
  # is refused outright: the token flow is not optional, and its failure is not a fallback
  # to v1.
  # Assigned UNQUOTED, which is safe (an assignment's right-hand side is not word-split) and
  # is also what keeps PublishedCredentials' scan honest: `IMDS_TOKEN` is a credential-shaped
  # NAME, so the guard reads the rest of the line as a published value, and `"$(curl …` opens
  # with a quote rather than with the `$(` that marks an expansion resolved elsewhere. Nothing
  # is published here — the token is minted at run time and lives 60 seconds — and the right
  # fix is this one character, never a narrowing of the guard's name pattern.
  IMDS_TOKEN=$(curl -fsS -X PUT --connect-timeout 2 --max-time 5 --max-filesize 65536 \
      -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
      "$SNAPSHOT_IMDS_BASE/latest/api/token") \
    || die "IMDSv2 token request to $SNAPSHOT_IMDS_BASE was refused or unanswered. On EC2 that is a broken metadata service, a metadata hop limit of 1, or a local firewall rule — and the app resolves its S3 credentials the same way, so read it as wider than this check. On a box that is not EC2 this unit should not be installed; set SNAPSHOT_SOURCE=none if it already is."

  # Validated BEFORE it is written into a header file, and the message deliberately does not
  # quote it: a newline in the token would forge a second HTTP header on every subsequent
  # call, and the value is a credential, so it is the one thing on this path that is refused
  # without being shown.
  case "$IMDS_TOKEN" in
    ''|*[!A-Za-z0-9+/=_.-]*)
      die "the IMDSv2 token endpoint answered with something that is not a token (the value is not echoed — it is a credential); something other than EC2's metadata service is answering $SNAPSHOT_IMDS_BASE" ;;
  esac
  IMDS_HDR_FILE="$(mktemp "${TMPDIR:-/tmp}/hamstrack-volume-snapshot-hdr.XXXXXX")"
  chmod 0600 "$IMDS_HDR_FILE"   # mktemp already does; stated because the file's whole point is the mode
  printf 'X-aws-ec2-metadata-token: %s\n' "$IMDS_TOKEN" > "$IMDS_HDR_FILE"

  iid="$(imds latest/meta-data/instance-id)" || die "IMDS answered the token request but not /latest/meta-data/instance-id"
  case "$iid" in
    i-*[!0-9a-z-]*|i-) die "IMDS returned an instance id this script will not pass to the AWS API: '$(printable "$iid")'" ;;
    i-*) ;;
    *) die "IMDS returned '$(printable "$iid")' where an instance id was expected — something other than EC2's metadata service is answering $SNAPSHOT_IMDS_BASE" ;;
  esac

  if [ -n "$SNAPSHOT_REGION" ]; then
    region="$SNAPSHOT_REGION"
  else
    region="$(imds latest/meta-data/placement/region)" \
      || die "IMDS did not answer /latest/meta-data/placement/region, and the ec2-snapshot-read policy denies any call whose region is not this instance's own — set SNAPSHOT_REGION to override"
    case "$region" in
      ''|*[!a-z0-9-]*) die "IMDS returned '$(printable "$region")' where a region name was expected" ;;
    esac
  fi
  REGION="$region"

  # NOT BlockDeviceMappings[0]. Index 0 is not guaranteed to be the root device — the same
  # class of bug as the one this whole check exists for, and docs/ops-prod-hardening.md
  # §6.2 step 4 carried a latent copy of it until HD-262 corrected it. An ABSENT answer here
  # is tolerated, because it enables the by-elimination fallback below; a WRONG one is not.
  root_dev="$(imds latest/meta-data/block-device-mapping/root 2>/dev/null)" || root_dev=""

  # THE SAME `case` GUARD ITS TWO SIBLINGS HAVE, and it was missing: this value is compared
  # against a device name and then quoted into three different refusals, so an unvalidated one
  # is echoed under exactly the conditions an operator is reading closely. EMPTY stays
  # tolerated — that is the by-elimination fallback below, and it is a different fact from
  # "malformed". Anything else is refused rather than matched: a device name that cannot be a
  # device name means something other than EC2's metadata service is answering, which is the
  # conclusion its siblings already draw.
  case "$root_dev" in
    '') ;;
    *[!A-Za-z0-9/_.-]*) die "IMDS returned '$(printable "$root_dev")' where a root device name was expected — something other than EC2's metadata service is answering $SNAPSHOT_IMDS_BASE" ;;
  esac

  # Each row is one ATTACHMENT: instance id, device, volume id. Never Attachments[0] — a
  # Multi-Attach volume has several and taking the first is a coin toss nobody would
  # notice — so the attachment's own instance id is carried out of the API and compared
  # here.
  rows="$(aws_ec2 describe-volumes --region "$REGION" \
      --filters "Name=attachment.instance-id,Values=$iid" \
      --query 'Volumes[].Attachments[].[InstanceId,Device,VolumeId]' --output text)" \
    || die "DescribeVolumes failed, so the volume for this instance could not be resolved (the error code is on the line above)"

  basename_root="${root_dev##*/}"
  while IFS=$'\t' read -r att_instance dev vol; do
    [ -n "${vol:-}" ] || continue
    [ "$vol" != "None" ] || continue
    [ "$att_instance" = "$iid" ] || continue
    attached+=("$vol")
    # Compared on the basename so /dev/xvda, xvda and /dev/sda1 versus sda1 agree.
    if [ -n "$basename_root" ] && [ "${dev##*/}" = "$basename_root" ]; then
      matches+=("$vol")
    fi
  done <<< "$rows"

  if [ "${#attached[@]}" -eq 0 ]; then
    die "DescribeVolumes returned no volume attached to $iid. There is nothing to watch, and that is not a state this instance can be in — so it is reported rather than assumed benign."
  fi

  if [ -n "$basename_root" ]; then
    # AMBIGUITY IS A FAILURE, NOT A GUESS: zero matches and more than one both stop here.
    if [ "${#matches[@]}" -eq 1 ]; then
      VOLUME="${matches[0]}"
      log "resolved root volume $VOLUME on $iid in $REGION (root device $root_dev, ${#attached[@]} attached volume(s))"
    elif [ "${#matches[@]}" -eq 0 ]; then
      die "none of the ${#attached[@]} volume(s) attached to $iid is attached at the root device '$root_dev'; refusing to guess which one holds the box"
    else
      die "${#matches[@]} volumes attached to $iid claim the root device '$root_dev'; refusing to guess which one holds the box"
    fi
  else
    # THE ONE PERMITTED FALLBACK: exactly one attached volume, and no root device name from
    # IMDS. The journal says it was chosen BY ELIMINATION, because that is a different fact
    # from "matched the root device" and an operator reading the line should not have to
    # guess which of the two happened.
    uniq="$(printf '%s\n' "${attached[@]}" | LC_ALL=C sort -u)"
    if [ "$(printf '%s\n' "$uniq" | wc -l)" -eq 1 ]; then
      VOLUME="$uniq"
      log "resolved volume $VOLUME on $iid in $REGION BY ELIMINATION — IMDS did not answer /latest/meta-data/block-device-mapping/root, and this is the only attached volume"
    else
      die "IMDS did not answer /latest/meta-data/block-device-mapping/root and $iid has more than one attached volume; refusing to guess which one holds the box"
    fi
  fi
}

resolve_volume
RESOLVE_OK=1

# --- stage "describe" --------------------------------------------------------
# A failure here means the ec2-snapshot-read policy was detached or its region condition no
# longer matches, the API is throttling, or a StartTime could not be parsed.
describe_newest() {
  local times newest ts now

  # status=completed ONLY: a `pending` snapshot is not yet restorable and may still end in
  # `error`, so counting it would report health for an artefact that never arrives. The cost
  # is that a snapshot in flight does not clear the alert until it completes, which the 30 h
  # threshold absorbs. Pagination is the CLI's default behaviour and needs nothing.
  times="$(aws_ec2 describe-snapshots --region "$REGION" --owner-ids self \
      --filters "Name=volume-id,Values=$VOLUME" "Name=status,Values=completed" \
      --query 'Snapshots[].StartTime' --output text)" \
    || { log "DescribeSnapshots failed for $VOLUME; publishing no snapshot age for this run"; return 1; }

  # Tabs to newlines, empties dropped. ISO-8601 UTC sorts lexicographically, so the last
  # line is the newest.
  times="$(printf '%s' "$times" | tr '\t' '\n' | sed '/^[[:space:]]*$/d')"
  if [ -z "$times" ]; then
    # ZERO ROWS IS AN ANSWER, AND IT IS THE 08-29 CASE: a freshly swapped volume with no
    # snapshot history publishes 0, `time() - 0` is far past any threshold, and the alert is
    # up the same evening. Omitting the series instead would be noData, which is OK, which
    # is silence. So the stage SUCCEEDED.
    NEWEST=0
    SNAP_COUNT=0
    log "no completed snapshots exist for $VOLUME — publishing 0, which is an answer and not a missing value"
    return 0
  fi

  SNAP_COUNT="$(printf '%s\n' "$times" | wc -l | tr -d '[:space:]')"
  newest="$(printf '%s\n' "$times" | LC_ALL=C sort | tail -n 1)"
  ts="$(date -u -d "$newest" +%s 2>/dev/null)" \
    || { log "the newest StartTime for $VOLUME could not be parsed as a date; publishing no snapshot age for this run"; return 1; }
  case "$ts" in
    ''|*[!0-9]*) log "the newest StartTime for $VOLUME did not convert to a Unix time; publishing no snapshot age for this run"; return 1 ;;
  esac
  # A FUTURE TIMESTAMP SWITCHES THE RULE OFF PERMANENTLY WHILE LOOKING HEALTHY: the alert
  # is `time() - ts > 108000`, so anything ahead of now makes that difference negative and
  # it can never fire again. The length test comes first, because a 20-digit number
  # overflows shell arithmetic before a comparison could reject it. hamstrack-backup.sh's
  # read_state carries the same bound for the same reason; the 300 s allowance is NTP skew,
  # and a StartTime is stamped by AWS when the snapshot STARTED, so it has no legitimate
  # reason to be ahead of this clock by more than that.
  now="$(date +%s)"
  if [ "${#ts}" -gt 11 ] || [ "$ts" -gt "$(( now + 300 ))" ]; then
    log "the newest StartTime for $VOLUME ($newest) is in the future by this box's clock; refusing it, because a future timestamp makes time()-ts negative and switches VolumeSnapshotStale off permanently while looking healthy"
    return 1
  fi
  NEWEST="$ts"
  log "newest completed snapshot of $VOLUME: $newest ($ts), out of $SNAP_COUNT completed snapshot(s)"
}

if describe_newest; then
  DESCRIBE_OK=1
fi

# The EXIT trap publishes the metrics and removes the scratch file. Exiting 0 even when the
# describe stage failed is deliberate: the FAILURE IS THE METRIC, and a non-zero exit here
# would add a systemd unit failure saying the same thing twice while adding nothing an
# operator can act on. What must never happen is the reverse — exiting 0 having published
# nothing — and the trap is what forbids that.
exit 0
