#!/usr/bin/env bash
# HD-186 — the configuration fingerprint.
# Spec: §4.9, §2 (the category rule), acceptance criterion 19.
#
#   capture/fingerprint.sh [output-dir]     (run ON THE BOX, in pre-flight)
#
# ---------------------------------------------------------------------------
# THE FINGERPRINT IS NOT PAPERWORK.
#
# It is the thing that lets a reader in six months decide whether the number still applies.
# §2 states the rule as a CATEGORY rather than an ordering of two tickets, because ticket
# numbers go stale and the property does not:
#
#   This measurement is valid only for the configuration it was taken against, and it is
#   INVALIDATED by any change to the app container's memory limit, the heap ceiling, the
#   instance type or size, the connection-pool size, the statement or lock bounds, the
#   report/search caps, or what else runs on the box. Every published figure carries the
#   fingerprint of the configuration it belongs to, and a change to any of those is a
#   reason to RE-RUN rather than to reinterpret.
#
# Everything below is COMMAND OUTPUT, pasted, not what the repository says. A `.env` can
# name a memory limit for weeks while nothing reads it and the container's actual
# HostConfig.Memory is 0. Reading the file would produce a confident and false fingerprint;
# reading the container produces the truth.
#
# ---------------------------------------------------------------------------
# WHAT IS AND IS NOT KEPT OUT OF THIS FILE, AND HOW.
#
# This output is pasted into RESULTS-<date>.md, which is COMMITTED to a source-available
# repository. Two different mechanisms keep it publishable, and only the first is a
# property of the whole file:
#
#   * the container ENVIRONMENT dump is filtered by a POSITIVE ALLOW-LIST OF NAMES, so a
#     secret added to the container later cannot pass it. That is why it is not a
#     `grep -v PASSWORD`: a deny-list is one new variable away from leaking.
#   * every OTHER command is publishable because of what it happens to print, which is a
#     claim about each command and not about the file. The IMDS identity document was the
#     counter-example: it was appended whole, and it carries accountId, instanceId and
#     privateIp. It is now PROJECTED to the four fields the measurement needs.
#
# So the rule for anyone adding a command here: say why ITS output is publishable. "Nothing
# in this file is secret-bearing" was a claim about a category that outlived a member it
# did not cover.
set -euo pipefail

HERE_CFG="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# $LOAD_CONFIG's [BOX] half — the container names and the database connection — used to be
# read by nothing on this side, so an operator with a correct configuration file still got a
# fail-fast on a container name. See capture/lib-config.sh.
# shellcheck source=./lib-config.sh
. "$HERE_CFG/lib-config.sh"


# Default OUTSIDE the harness directory: `ops/` is a synced path, and files added to one
# are what the drift check reports — with drift-reads-zero a hard precondition of the
# window (see this script's own drift section, which would then flag its own output).
: "${LOAD_RESULTS_DIR:=/var/tmp/hd186}"
OUT="${1:-$LOAD_RESULTS_DIR/fingerprint-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$OUT"
F="$OUT/fingerprint.txt"

: "${APP_CONTAINER:=hamstrack-app-1}"
: "${PG_CONTAINER:=hamstrack-postgres-1}"
# LOAD_DB_USER / LOAD_DB_NAME are the documented names; DB_USER / DB_NAME are a fallback.
# Reading only the latter worked here for the single reason that the documented values and
# the defaults are identical — an agreement between two files, not a mechanism.
: "${LOAD_DB_USER:=${DB_USER:-hamstrack}}"
: "${LOAD_DB_NAME:=${DB_NAME:-hamstrack}}"
DB_USER="$LOAD_DB_USER"
DB_NAME="$LOAD_DB_NAME"
: "${DEPLOY_DIR:=/opt/hamstrack}"

sec() { printf '\n\n========== %s ==========\n' "$1" >> "$F"; }
run() { printf '\n$ %s\n' "$*" >> "$F"; "$@" >> "$F" 2>&1 || echo "  (command failed)" >> "$F"; }
shell() { printf '\n$ %s\n' "$1" >> "$F"; sh -c "$1" >> "$F" 2>&1 || echo "  (command failed)" >> "$F"; }

{
  echo "HD-186 configuration fingerprint"
  echo "captured_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "captured_on:  $(hostname)"
} > "$F"

# --- what is deployed --------------------------------------------------------
sec "deployed commit and image"
shell "cat $DEPLOY_DIR/.deployed-sha 2>/dev/null || echo '(absent)'"
shell "cat $DEPLOY_DIR/.deployed-image-tag 2>/dev/null || echo '(absent)'"
run docker inspect --format '{{.Config.Image}} {{.Image}}' "$APP_CONTAINER"

# --- memory limits, for EVERY container --------------------------------------
# EVERY container, not only the app, and the reason is §4.8's "some OTHER container dies
# first": under host memory pressure the kernel picks the victim, and a declared ceiling on
# one container changes WHICH one it picks rather than whether it picks. A fingerprint that
# recorded only the app's limit would hide that, and which containers carry a limit is a
# per-host fact that belongs in this output rather than in this comment.
#
# VERIFY RATHER THAN ASSUME: a declared limit and an enforced one have been different here
# before, which is why this reads the container and not a compose file.
sec "container memory limits (0 = unbounded)"
shell "docker inspect \$(docker ps -q) --format '{{.Name}} {{.HostConfig.Memory}}'"

# --- the JVM's actual heap ceiling -------------------------------------------
# From INSIDE the container. -XX:MaxRAMPercentage=50 is taken against the CGROUP limit when
# one exists and against HOST RAM when it does not, so the same image yields a 495 MiB heap
# or a 956 MB one depending on a line in a compose file. This is the number the
# REPORTS_MAX_ROWS costing is against, so it is the number that must be recorded.
#
# ASK THE RUNNING PROCESS FIRST, AND NOTHING ELSE COUNTS AS ITS ANSWER. The 2026-08-31
# window recorded "JVM max heap 268435456 (256 MB), MaxRAMPercentage=25" and neither figure
# belonged to the application: `docker exec … java -XX:+PrintFlagsFinal -version` starts a
# SECOND JVM which does not inherit the entrypoint's flags, so it reported its own default
# ergonomics (25% of the 1 GiB cgroup limit) while the app was running the image's
# -XX:MaxRAMPercentage=50.0 — a plausible number, off by a factor of two, in the section the
# whole capacity attribution is read against. The two lines below are the honest sources and
# they are ordered by trust: the application's own startup line (HD-179, which also names the
# collector — SerialGC at a 1 GiB limit, G1 at 2 GiB), then the heap and collector flags
# PID 1 carries, allow-listed by name.
# The PrintFlagsFinal probe is kept LAST and with the percentage repeated, so
# it can only agree or disagree rather than quietly substitute.
sec "JVM heap ceiling — the application's own startup line (HD-179; authoritative)"
shell "docker logs $APP_CONTAINER 2>&1 | grep -F 'Memory: max heap' | tail -1"
# THE PROJECTION HERE IS ABOUT THIS COMMAND'S OUTPUT, NOT ABOUT ITS SOURCE. PID 1's command
# line is the honest answer to "what flags is the app running with", and it is also a string
# somebody else wrote: this harness is parameterised and aimed at self-hosters, and a compose
# file of their own may perfectly well say `command: java -Dspring.datasource.password=… -jar
# app.jar`. Dumping /proc/1/cmdline whole would then paste that into RESULTS-<date>.md, which
# is committed to a source-available repository — the same substitution the IMDS identity
# document was appended under ("the source is trustworthy" is not "the output is
# publishable").
#
# SO IT IS AN ALLOW-LIST OF THE FLAGS THE MEASUREMENT NEEDS, NAMED ONE BY ONE, and not a
# shape filter. `grep -E "^-X"` was the first attempt and it is a DENY-LIST wearing a
# prefix: -X is a namespace, not a category of harmless value. -XX:OnOutOfMemoryError= and
# -XX:OnError= take an arbitrary operator-written SHELL COMMAND (a webhook notifier with an
# inline bearer token is the ordinary case), and -Xlog:gc:file=, -XX:HeapDumpPath= and
# -XX:ErrorFile= publish host paths. "-X is where -Xmx lives and where a -D cannot" was a
# claim about which flags carry secrets, and those three refute it while containing none of
# the words a reader would grep for — which is exactly why the posture, and not the pattern,
# had to change: this file already argues twice that a deny-list is one new entry away from
# leaking (the environment dump, the IMDS projection), and it cannot hold both postures.
# Values are constrained to numbers too, so an admitted NAME cannot smuggle a string.
# Adding a flag here is a deliberate edit that says what the measurement needs it for.
#
# The documented override JAVA_TOOL_OPTIONS is an environment variable and never appears in
# cmdline at all, so nothing this section exists to record is lost. The startup line
# immediately above already carries the heap, its origin and the collector.
JVM_FLAG_ALLOWLIST='^(-Xm[sx][0-9]+[kKmMgG]?|-XX:[+-]?(MaxHeapSize|InitialHeapSize|MaxRAMPercentage|MinRAMPercentage|InitialRAMPercentage|ActiveProcessorCount|Use[A-Za-z0-9]+GC)(=[0-9]+([.][0-9]+)?[kKmMgG]?)?)$'
# READ ONCE, FAIL LOUDLY, AND SAY SO WHEN THERE IS NOTHING TO PRINT. `sh -c 'A | B; echo'`
# exits with the status of `echo`, so `shell()`'s "(command failed)" marker can never fire
# for this line — which would leave a blank line meaning either "no matching flags" or "the
# read failed quietly". That is the collision §"entitlements" forbids in so many words: an
# absent value and an agreeing value must not look the same. So /proc/1/cmdline is read once
# into a variable and a failed read exits non-zero, while the only tolerated empty result
# names itself.
sec "JVM heap ceiling — PID 1's heap/GC flags (allow-list: -Xms/-Xmx, heap size, RAM percentage, processor count, collector)"
shell "docker exec $APP_CONTAINER sh -c 'a=\$(tr \"\\0\" \"\\n\" < /proc/1/cmdline) || exit 1; printf \"%s\\n\" \"\$a\" | grep -E \"$JVM_FLAG_ALLOWLIST\" | tr \"\\n\" \" \"; printf \"%s\\n\" \"\$a\" | grep -qE \"$JVM_FLAG_ALLOWLIST\" || printf \"(no heap or GC flag on PID 1 command line)\"; echo'"
sec "JVM heap ceiling (a fresh JVM given the same flags — corroboration, not the source)"
shell "docker exec $APP_CONTAINER sh -c 'java -XX:MaxRAMPercentage=50.0 -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E \"MaxHeapSize|MaxRAMPercentage|InitialHeapSize\"'"
# 9090, not 8080: this product serves the actuator on its MANAGEMENT port
# (`management.server.port=${MANAGEMENT_PORT:9090}`). Scraping 8080 here returned nothing on
# every install of this product, silently — the same defect capture.sh carried (§9).
shell "docker exec $APP_CONTAINER sh -c 'wget -qO- http://localhost:9090/actuator/prometheus 2>/dev/null | grep -E \"^jvm_memory_max_bytes.*heap|^jvm_gc_max_data_size_bytes\"'"

# --- the values actually in effect ------------------------------------------
# The pool, the statement and lock bounds, every per-principal budget the run can hit, the
# occupancy share, and the board/agile caps.
# Read from the ENVIRONMENT OF THE RUNNING CONTAINER, which is what the process obeys —
# not from application.properties, which shows the default, and not from .env, which shows
# an intention.
#
# WHAT BELONGS IN THIS LIST IS A CATEGORY, NOT A ROSTER: every number a THRESHOLD in
# k6/lib/classes.js or a row in RESULTS-TEMPLATE.md is stated against. The README's rule —
# "the numbers are void the moment its configuration moves" — is only true while the
# fingerprint records the configuration the thresholds name; a class with a 429 target whose
# budget is not captured here produces a result nobody can re-derive later. That is how
# PLANNING_REQUESTS_PER_MINUTE (HD-174, the `planning` class and its hs_occupancy_429 target)
# and the EXPENSIVE_READ_* share (HD-182, §P1b's occupancy numbers) were both missing.
#
# THE EXPENSIVE_READ_* CEILINGS PRINT NOTHING ON MOST INSTALLS AND THAT IS NOT A FAILED READ:
# both ship UNSET and are then derived from DB_POOL_MAX_SIZE (60 % of it, capped at 6, the
# per-principal one clamped to fit), so an absent line here means "derived from the pool
# above" and the numbers actually in force are named in the app's startup log.
#
# The grep is an allow-list of NAMES so no secret can be printed even if one is added to
# the container's environment later. That is why it is a positive filter and not a
# `grep -v PASSWORD`: a deny-list is one new variable away from leaking.
sec "configuration actually in effect (names allow-listed; no secrets can pass this filter)"
shell "docker exec $APP_CONTAINER env | grep -E '^(DB_POOL_MAX_SIZE|DB_STATEMENT_TIMEOUT_MS|DB_LOCK_TIMEOUT_MS|REPORTS_MAX_ROWS|REPORTS_MAX_WINDOW_DAYS|REPORTS_REQUESTS_PER_MINUTE|SEARCH_REQUESTS_PER_MINUTE|PLANNING_REQUESTS_PER_MINUTE|EXPENSIVE_READ_LIMIT_ENABLED|EXPENSIVE_READ_MAX_IN_FLIGHT|EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL|EXPENSIVE_READ_ACQUIRE_WAIT_MS|BOARD_MAX_ISSUES|AGILE_SECTION_MAX_ISSUES|AGILE_MAX_OPEN_SPRINTS|MAX_LABELS_PER_WORKSPACE|RATE_LIMIT_ENABLED|RATE_LIMIT_AUTH_IP_PER_MINUTE|RATE_LIMIT_TRUST_FORWARDED_FOR|SPRING_PROFILES_ACTIVE|APP_STORAGE_TYPE)=' | sort"

# --- the entitlements the probes will assume ----------------------------------
# PRINTED EVEN WHEN UNSET, AND THAT IS THE ENTIRE POINT OF THE SECTION.
#
# probes.js drives its "fully entitled" principal at SEARCH_ENTITLEMENT / REPORT_ENTITLEMENT
# and the operator is told to cross-check them against the box's own
# SEARCH_REQUESTS_PER_MINUTE / REPORTS_REQUESTS_PER_MINUTE, read from the container
# environment above. BOTH ARE COMMENTED OUT IN THE SHIPPED TEMPLATE, so an operator who
# changed nothing was comparing the box's number against NOTHING AT ALL — an empty line,
# which reads as agreement. An absent value and an agreeing value must not look the same.
sec "entitlements the probes will assume (probes.js; cross-check against the two _PER_MINUTE values above)"
{
    printf '\nSEARCH_ENTITLEMENT=%s\n' \
        "${SEARCH_ENTITLEMENT:-<unset — probes.js default 120/min applies>}"
    printf 'REPORT_ENTITLEMENT=%s\n' \
        "${REPORT_ENTITLEMENT:-<unset — probes.js default 60/min applies>}"
} >> "$F"

# --- host ---------------------------------------------------------------------
sec "host: cpu, memory, SWAP, disk"
run nproc
# free -m INCLUDING swap, and swappiness. Both are new as of 2026-08-28 and both change
# what failure looks like: with swap, memory exhaustion presents as latency rather than as
# a dead container. See capture.sh for the measurement that established this.
run free -m
shell "cat /proc/sys/vm/swappiness"
run df -h
run uptime

sec "kernel OOM history (should be empty; a kill invalidates the run — §5.3 condition 4)"
shell "dmesg -T 2>/dev/null | grep -i -E 'out of memory|oom-kill|killed process' | tail -20 || echo '(dmesg unavailable to this user)'"

# --- instance ------------------------------------------------------------------
# THE CREDIT SPECIFICATION IS RECORDED BECAUSE A RUN THAT DOES NOT KNOW WHICH MODE IT IS IN
# CANNOT DISTINGUISH "the application serialises" FROM "the CPU was taken away" — which is
# the precise confusion this whole ticket exists to end.
#
# In `unlimited` mode a burstable instance does not throttle when the balance empties; it
# bills surplus instead, which makes credit exhaustion a COST note rather than an abort
# condition. In `standard` mode the opposite is true and §5.3's credit abort applies. READ
# IT, DO NOT ASSUME IT — the mode is a per-instance setting that anyone with the console
# can change, and RESULTS-TEMPLATE.md carries it as a named checkbox for that reason.
#
# THE IDENTITY DOCUMENT IS PROJECTED, NOT PASTED. It carries accountId, instanceId, imageId
# and privateIp; this file's output is committed. Four fields are what the measurement
# needs — instance type, region, availability zone, and the AMI the kernel and the docker
# version come from.
#
# DECISION, RECORDED HERE SO IT IS NOT RE-LITIGATED: instanceId STAYS OUT, and the argument
# for putting it back — "it already appears elsewhere in this repository, so it is not a
# secret" — is the argument this projection exists to refuse. The projection is an
# ALLOW-LIST: publish what the MEASUREMENT NEEDS. Re-admitting a field because somebody
# judges it non-sensitive converts it into a DENY-LIST: publish what we have judged safe.
# That is the posture the environment dump above rejects in so many words ("a deny-list is
# one new variable away from leaking"), and a file cannot hold both postures at once.
# A capacity figure does not need to name the machine; the instance TYPE is what makes it
# reproducible, and that is published.
sec "EC2 instance identity (projected: type, region, AZ, image — the document also carries an account id)"
shell "TOKEN=\$(curl -sS -X PUT 'http://169.254.169.254/latest/api/token' -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' 2>/dev/null); DOC=\$(curl -sS -H \"X-aws-ec2-metadata-token: \$TOKEN\" http://169.254.169.254/latest/dynamic/instance-identity/document 2>/dev/null); if [ -z \"\$DOC\" ]; then echo '(not on EC2, or IMDS unavailable — record the instance type by hand)'; elif command -v jq >/dev/null 2>&1; then printf '%s' \"\$DOC\" | jq -r '{instanceType,region,availabilityZone,imageId}'; else printf '%s' \"\$DOC\" | tr ',' '\n' | grep -E '\"(instanceType|region|availabilityZone|imageId)\"'; fi"
cat >> "$F" <<'EOF'

  Run this from a machine with AWS credentials (not necessarily the box) and paste it here:

    aws ec2 describe-instance-credit-specifications --instance-ids <id> \
        --query 'InstanceCreditSpecifications[0]'
    aws cloudwatch get-metric-statistics --namespace AWS/EC2 \
        --metric-name CPUCreditBalance --dimensions Name=InstanceId,Value=<id> \
        --start-time <window-start> --end-time <window-end> --period 300 --statistics Average
    aws ec2 describe-volumes --filters Name=attachment.instance-id,Values=<id> \
        --query 'Volumes[].{id:VolumeId,type:VolumeType,size:Size,iops:Iops,enc:Encrypted}'

  If the volume type is gp2, ALSO record its burst-balance state: that is a SECOND credit
  bucket that can run out and be misread as an application problem (§4.8).
EOF

# --- database -------------------------------------------------------------------
# IT FOLLOWS LOAD_CAPTURE_MODE. Hardcoding `docker exec` here meant that in
# LOAD_CAPTURE_MODE=host — where there is deliberately no Docker — every line of this
# section failed and the fingerprint came out with NO POSTGRESQL IN IT: no version, no size,
# no schema version, no pg_settings. A fingerprint is the document that says what was
# measured, so a whole missing subject is worse than a missing run.
#
# The DSN is passed BY NAME, never expanded into the recorded command line: this file's
# output is pasted into RESULTS-<date>.md and committed. capture/lib-config.sh already
# refuses a DSN carrying an inline password, and this keeps the second copy from existing at
# all.
: "${LOAD_CAPTURE_MODE:=docker}"
psql_cmd() {        # <psql args…> -> the command string for the configured mode
    if [[ "$LOAD_CAPTURE_MODE" == "docker" ]]; then
        printf 'docker exec -i %s psql -U %s -d %s %s' "$PG_CONTAINER" "$DB_USER" "$DB_NAME" "$1"
    elif [[ -n "${LOAD_DB_DSN:-}" ]]; then
        printf 'psql "$LOAD_DB_DSN" %s' "$1"
    else
        printf 'psql -U %s -d %s %s' "$DB_USER" "$DB_NAME" "$1"
    fi
}

sec "database: version, size, and the fixture's row counts (LOAD_CAPTURE_MODE=$LOAD_CAPTURE_MODE)"
shell "$(psql_cmd "-Atc 'SELECT version()'")"
shell "$(psql_cmd "-Atc \"SELECT pg_size_pretty(pg_database_size(current_database()))\"")"
shell "$(psql_cmd "-Atc \"SELECT max(version) FROM flyway_schema_history WHERE success\"")"
shell "$(psql_cmd "-c \"SELECT name, setting FROM pg_settings WHERE name IN ('shared_buffers','effective_cache_size','work_mem','max_connections','statement_timeout','lock_timeout')\"")"

# --- what else is running --------------------------------------------------------
# "What else runs on the box" is one of the things §2's category rule names as
# invalidating. The observability stack is seven containers competing for the same 1909 MB.
sec "what else is running on this box"
shell "docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'"
shell "docker ps -q | wc -l"

sec "observability retention (the Prometheus export's resolution depends on it)"
shell "docker inspect \$(docker ps -qf name=prometheus) --format '{{range .Args}}{{println .}}{{end}}' 2>/dev/null | grep -i retention || echo '(no prometheus container, or no retention flag)'"

# --- drift ------------------------------------------------------------------------
# §5.1 precondition 3: measuring a box that DIFFERS from the repository produces a number
# that describes nothing. Recorded before AND after (§5.5.5), and it must be unchanged.
sec "configuration drift (must read 0 for every scope — §5.1 precondition 3)"
shell "cat /var/lib/node_exporter/textfile_collector/*drift* 2>/dev/null | grep -E '^hamstrack_config_drift' || echo '(drift metrics not found — run ops/drift/hamstrack-config-drift.sh)'"

sec "clock (skew between box and generator would silently misalign every correlation)"
run date -u
shell "timedatectl 2>/dev/null | grep -Ei 'ntp|synchron' || echo '(timedatectl unavailable)'"

echo "" >> "$F"
echo "=== END OF FINGERPRINT ===" >> "$F"

cat >&2 <<EOF

  Fingerprint written to $F

  PASTE IT INTO RESULTS-<date>.md WHOLE. Summarising it defeats the purpose: the reader in
  six months needs to check a specific value against their own box, and a summary is a
  claim about the configuration rather than the configuration.

  Two things this script CANNOT read and you must add by hand:
    * the AWS block above (credit specification, credit balance, volume type/IOPS)
    * the fixture's generator SEED and its verification row counts (from generate.sh)

EOF
