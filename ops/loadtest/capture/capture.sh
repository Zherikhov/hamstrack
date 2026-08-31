#!/usr/bin/env bash
# HD-186 — box-side capture (Tier 1).
# Spec: §4.7, §4.8.
#
#   capture/capture.sh start [output-dir]
#   capture/capture.sh stop
#
# ---------------------------------------------------------------------------
# TIER 1 IS SUFFICIENT FOR A VERDICT, AND IT NEEDS A WAY IN TO THE APPLICATION AND THE
# DATABASE. IT DOES NOT NEED THE OBSERVABILITY STACK — BUT IT DOES NEED ONE OF TWO MODES.
#
# The SIGNALS are portable: the application's own /actuator/prometheus, a psql, and /proc.
# Those exist on every install, in both DC and Cloud, whether or not the optional
# observability stack runs, and a harness whose verdict depended on that stack would be a
# Cloud-only tool wearing a portable name (§9).
#
# THE ACCESS IS NOT PORTABLE, AND SAYING IT WAS COST A DC INSTALL THE WHOLE OF TIER 1. This
# file reached every one of those signals through `docker exec`, and it refuses to start
# when a container name does not resolve — while its own header said Tier 1 needs "only
# the actuator, psql and /proc, which exist on EVERY install". A self-hoster running the JAR
# under systemd (this project's own dev box included) could not run Tier 1 at all, and the
# reason was a transport nobody had written down as a requirement.
#
# So the transport is a MODE, and both are real:
#
#   LOAD_CAPTURE_MODE=docker  (default)  the compose deployment. Reaches the actuator and
#                                        psql through `docker exec`, so no port has to be
#                                        published for the run — publishing one would be a
#                                        configuration change to the box under measurement.
#   LOAD_CAPTURE_MODE=host                the JAR-under-systemd deployment. Scrapes
#                                        ACTUATOR_URL directly with curl/wget and runs the
#                                        local psql against LOAD_DB_DSN. Needs no Docker.
#
# The one thing `host` mode cannot give is the per-container memory and CPU accounting
# (`docker stats`), because there are no containers. That is stated where the sampler is,
# and /proc still supplies the host-level half — which is the half §4.8's swap row is about.
#
# Tier 2 — the Prometheus range export, cAdvisor, node-exporter — is capture/export-
# prometheus.sh and is available only where that stack happens to run.
#
# ---------------------------------------------------------------------------
# WHY THE ACTUATOR IS SCRAPED BY US AND NOT BY PROMETHEUS.
#
# /actuator/prometheus is internal to the compose network, so this runs ON THE BOX as a
# small loop and its output is collected afterwards. The alternative — k6's Prometheus
# remote-write output — is explicitly rejected (§4.7): it would require enabling a
# remote-write receiver on the production Prometheus, which means editing a file under
# observability/, a SYNCED path. That raises ConfigDrift, is overwritten by the next
# deploy, and opens a write endpoint on a metrics store for the convenience of one
# afternoon.
#
# Correlate by TIMESTAMP instead. Both sides have clocks, every sample below is stamped in
# UTC, and the run is bracketed by recorded UTC timestamps. Check the skew in pre-flight
# (fingerprint.sh does) — clock skew between generator and box would silently misalign
# every correlation in the report.
#
# ---------------------------------------------------------------------------
# WHY pg_stat_activity IS SAMPLED SEPARATELY FROM postgres-exporter.
#
# Lock waits and connection states are the attribution signal for two rows of §4.8. Which
# collectors a given postgres-exporter build enables is a deployment detail, and depending
# on it is a way to discover mid-window that the number you needed was never being
# recorded. So it is sampled here directly, and the exporter is a cross-check rather than
# the source.
set -euo pipefail

HERE_CFG="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# $LOAD_CONFIG's [BOX] half — the container names and the database connection — used to be
# read by nothing on this side, so an operator with a correct configuration file still got a
# fail-fast on a container name. See capture/lib-config.sh.
# shellcheck source=./lib-config.sh
. "$HERE_CFG/lib-config.sh"


HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CMD="${1:?usage: capture.sh start [output-dir] | stop}"

# NOT inside ops/loadtest/. `ops/` is a synced path: a directory of files added to one is
# reported by the drift check, drift reading zero is a hard precondition of the window, and
# ConfigDrift (for: 30m) is one of the provisioned rules that make up half the breach bar.
# A capture directory in here would mean no stage could be recorded as passed.
: "${LOAD_RESULTS_DIR:=/var/tmp/hd186}"
OUT="${2:-$LOAD_RESULTS_DIR/capture-$(date -u +%Y%m%dT%H%M%SZ)}"
PIDFILE="/tmp/hd186-capture.pids"

: "${INTERVAL:=5}"
: "${APP_CONTAINER:=hamstrack-app-1}"
: "${PG_CONTAINER:=hamstrack-postgres-1}"
# LOAD_DB_USER / LOAD_DB_NAME are what the configuration documents and what the fixture
# scripts read; DB_USER / DB_NAME are kept as a fallback for a shell that already exports
# them. They were the ONLY names read here, which worked on this box for the single reason
# that the documented values and the defaults happen to be identical — an agreement, not a
# mechanism, and it would have broken silently on any box that renamed either.
: "${LOAD_DB_USER:=${DB_USER:-hamstrack}}"
: "${LOAD_DB_NAME:=${DB_NAME:-hamstrack}}"
DB_USER="$LOAD_DB_USER"
DB_NAME="$LOAD_DB_NAME"
# 9090, not 8080: Hamstrack runs the actuator on a SEPARATE management port
# (`management.server.port=${MANAGEMENT_PORT:9090}`), so 8080 answered nothing on every
# install of this product, not just an unusual one. The default now matches the application
# it ships beside; the probe added below is what makes a future divergence loud instead of
# silent.
: "${ACTUATOR_URL:=http://localhost:9090/actuator/prometheus}"

# docker | host. See the header. Defaulted rather than detected: a mode this script GUESSED
# would silently produce a different capture on a machine that happens to have Docker
# installed for something else.
: "${LOAD_CAPTURE_MODE:=docker}"
case "$LOAD_CAPTURE_MODE" in
    docker|host) ;;
    *) echo "LOAD_CAPTURE_MODE must be 'docker' or 'host', not '$LOAD_CAPTURE_MODE'" >&2; exit 2 ;;
esac

# The two transports, behind two names, so every sampler below reads the same regardless of
# mode — the alternative is a forked script, which this project does not do (CLAUDE.md: any
# behavioural difference is profile/property-gated, never forked).
scrape_actuator() {
    if [[ "$LOAD_CAPTURE_MODE" == "docker" ]]; then
        docker exec "$APP_CONTAINER" \
            sh -c "wget -qO- '$ACTUATOR_URL' || curl -fsS '$ACTUATOR_URL'" 2>/dev/null
    else
        curl -fsS "$ACTUATOR_URL" 2>/dev/null || wget -qO- "$ACTUATOR_URL" 2>/dev/null
    fi
}
pg_query() {   # reads SQL on stdin, prints unaligned CSV
    if [[ "$LOAD_CAPTURE_MODE" == "docker" ]]; then
        docker exec -i "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -At -F, -f - 2>/dev/null
    elif [[ -n "${LOAD_DB_DSN:-}" ]]; then
        psql "$LOAD_DB_DSN" -At -F, -f - 2>/dev/null
    else
        psql -U "$DB_USER" -d "$DB_NAME" -At -F, -f - 2>/dev/null
    fi
}

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { printf '%s %s\n' "$(ts)" "$*" >&2; }

DIRFILE="/tmp/hd186-capture.dir"

stop() {
    if [[ -f "$PIDFILE" ]]; then
        while read -r p; do
            [[ -n "$p" ]] && kill "$p" 2>/dev/null || true
        done < "$PIDFILE"
        rm -f "$PIDFILE"
        # The stop timestamp brackets the run. Without it the Prometheus range export has
        # no end and the k6/box correlation has no window — and §4.7 rules out any other
        # way of aligning the two sides, so this one line is the whole alignment.
        if [[ -f "$DIRFILE" ]]; then
            echo "capture_stopped_utc=$(ts)" >> "$(cat "$DIRFILE")/window.txt"
            log "capture stopped; window recorded in $(cat "$DIRFILE")/window.txt"
            rm -f "$DIRFILE"
        else
            log "capture stopped (no directory marker — record the stop time by hand)"
        fi
    else
        log "no capture running (no $PIDFILE)"
    fi
}

if [[ "$CMD" == "stop" ]]; then stop; exit 0; fi
[[ "$CMD" == "start" ]] || { log "unknown command: $CMD"; exit 2; }

[[ -f "$PIDFILE" ]] && { log "capture already running — stop it first"; exit 2; }

# FAIL FAST ON THE CONTAINER NAMES.
#
# In docker mode every sampler below reaches its subject through `docker exec <name>`, inside
# a loop that swallows failures so one bad sample does not end the capture. A typo in a name therefore
# produces a whole window of `# HD186_SCRAPE_FAILED` and `SAMPLE_FAILED` — discovered while
# reading the results, when the window is over and the instance is idle. The names differ
# between a checkout directory and /opt/hamstrack because compose prefixes them with the
# project name, so getting this wrong is the expected mistake and not an exotic one.
if [[ "$LOAD_CAPTURE_MODE" == "host" ]]; then
    # The host-mode equivalent of the container-name check: prove BOTH transports work
    # before starting a window's worth of samplers that swallow their own failures.
    # NO EARLY-EXITING READER IN A PIPELINE THIS SCRIPT JUDGES BY EXIT STATUS.
    #
    # This was `scrape_actuator | head -1 | grep -q .`, which under `set -o pipefail` returns
    # 141: head exits after the first line, the still-writing curl takes SIGPIPE, and
    # pipefail reports the highest status in the pipeline. A Prometheus exposition is
    # thousands of lines, so the write always outlives the read — meaning this fail-fast
    # fired PRECISELY WHEN THE ACTUATOR WAS WORKING, and host mode could not start at all.
    # Measured: rc=141 on an 8 000-line body, rc=0 on a one-line body. The bug is a function
    # of the response being big enough to be real.
    #
    # `head -c 4096` DOES NOT FIX IT — same shape, same 141 (measured). Any reader that stops
    # before the writer does produces the same signal. So the whole body is read into a
    # variable, with no reader to close the pipe early, and the cap is applied afterwards
    # with a bash substring. It costs one full scrape, once, before the window opens.
    # `|| true`: scrape_actuator returns non-zero when BOTH transports fail, and `set -e`
    # would abort here with no message instead of reaching the FATAL below.
    actuator_probe="$(scrape_actuator || true)"
    actuator_probe="${actuator_probe:0:4096}"
    [[ -n "$actuator_probe" ]] || {
        log "FATAL cannot scrape $ACTUATOR_URL from this host. In LOAD_CAPTURE_MODE=host the"
        log "      actuator is fetched directly, so it must be reachable here — check the"
        log "      management port and that the endpoint is exposed."
        exit 2
    }
    # Same rule as the actuator probe above: no `grep -q` closing the pipe under the nose
    # of a writer whose status this line is about to read.
    pg_probe="$(echo 'SELECT 1' | pg_query || true)"
    [[ "$pg_probe" == *1* ]] || {
        log "FATAL cannot query PostgreSQL from this host. In LOAD_CAPTURE_MODE=host psql runs"
        log "      LOCALLY: set LOAD_DB_DSN, or LOAD_DB_USER/LOAD_DB_NAME plus whatever your"
        log "      local psql needs to authenticate."
        exit 2
    }
    log "host mode: actuator and psql both reachable without Docker"
fi

for c in "$APP_CONTAINER" "$PG_CONTAINER"; do
    [[ "$LOAD_CAPTURE_MODE" == "docker" ]] || continue
    docker inspect "$c" >/dev/null 2>&1 || {
        log "FATAL no container named '$c'. Read the names off \`docker ps\` and set"
        log "      APP_CONTAINER / PG_CONTAINER — compose prefixes them with its project"
        log "      name, which differs between a checkout and /opt/hamstrack. Running with"
        log "      a wrong name captures a window of scrape failures and says so only"
        log "      afterwards. Running now:"
        docker ps --format '  {{.Names}}\t{{.Image}}' >&2 || true
        exit 2
    }
done

# A NAME THAT RESOLVES IS NOT A SCRAPE THAT ANSWERS.
#
# The container-name check above and this one are the same guarantee stated twice, and only
# the first one existed: docker mode proved the name and then trusted the URL. Host mode had
# the URL probe and is the mode nothing here uses. So the fail-fast lived on the unexercised
# path while the used path captured whatever it got — which, measured on production
# 2026-08-31, was NOTHING: the default said 8080 and this product puts the actuator on 9090
# (`management.server.port=${MANAGEMENT_PORT:9090}` in application.properties). A whole
# window of empty app metrics, discovered while reading the results, is precisely the
# outcome the name check was added to prevent.
#
# Same no-early-exiting-reader rule as the host probe: read the body whole, judge it after.
if [[ "$LOAD_CAPTURE_MODE" == "docker" ]]; then
    probe="$(scrape_actuator || true)"
    [[ -n "$probe" ]] || {
        log "FATAL scraped ZERO bytes from $ACTUATOR_URL inside '$APP_CONTAINER'. The"
        log "      container exists, so this is the URL, not the name. Hamstrack serves the"
        log "      actuator on its MANAGEMENT port (default 9090), not on the application"
        log "      port — check MANAGEMENT_PORT and management.endpoints.web.exposure.include,"
        log "      then set ACTUATOR_URL. Prometheus's own target list is the fastest place"
        log "      to read the URL that actually works."
        exit 2
    }
    log "docker mode: actuator answers at $ACTUATOR_URL"
fi

mkdir -p "$OUT"
: > "$PIDFILE"
printf '%s\n' "$OUT" > "$DIRFILE"

log "capturing every ${INTERVAL}s into $OUT"
echo "capture_started_utc=$(ts)" > "$OUT/window.txt"

# --- 1. the application's own metrics ---------------------------------------
# The whole exposition, appended with a timestamp separator. Raw rather than parsed: which
# series matter is decided while reading the results, and a sampler that pre-selected them
# would have thrown away the one nobody thought of. Everything the JVM, the pool and the
# HTTP layer expose is in here — jvm_gc_pause_seconds, jvm_gc_live_data_size_bytes,
# hikaricp_connections_{active,pending,timeout_total,acquire_seconds,usage_seconds},
# http_server_requests_seconds, hamstrack_db_statement_budget_exceeded_total and
# hamstrack_ratelimit_hit_total{kind}.
(
    while :; do
        printf '\n# HD186_SAMPLE %s\n' "$(ts)"
        # In docker mode this is scraped from INSIDE the app container so no port has to be
        # published for the run — publishing one would be a configuration change to the box
        # under measurement. In host mode there is no container to be inside and the
        # actuator is fetched directly. Both live in scrape_actuator().
        scrape_actuator || echo '# HD186_SCRAPE_FAILED'
        sleep "$INTERVAL"
    done
) >> "$OUT/actuator.prom" 2>>"$OUT/capture.err" &
echo $! >> "$PIDFILE"

# --- 2. PostgreSQL: connection states and lock waits ------------------------
(
    echo "sample_utc,state,wait_event_type,wait_event,backends"
    while :; do
        T="$(ts)"
        pg_query <<'SQL' | sed "s/^/$T,/" || echo "$T,SAMPLE_FAILED,,,"
SELECT coalesce(state,'null'),
       coalesce(wait_event_type,'null'),
       coalesce(wait_event,'null'),
       count(*)
  FROM pg_stat_activity
 WHERE datname = current_database()
 GROUP BY 1,2,3
SQL
        sleep "$INTERVAL"
    done
) >> "$OUT/pg_stat_activity.csv" 2>>"$OUT/capture.err" &
echo $! >> "$PIDFILE"

# pg_stat_database: the cache-hit ratio is the signal for §4.8's page-cache row ("the
# fixture no longer fits in RAM"), and it is a counter, so it needs sampling over time
# rather than a single reading at the end.
(
    echo "sample_utc,xact_commit,xact_rollback,blks_read,blks_hit,tup_returned,tup_fetched,deadlocks,temp_bytes"
    while :; do
        T="$(ts)"
        pg_query <<'SQL' | sed "s/^/$T,/" || echo "$T,SAMPLE_FAILED"
SELECT xact_commit, xact_rollback, blks_read, blks_hit,
       tup_returned, tup_fetched, deadlocks, temp_bytes
  FROM pg_stat_database WHERE datname = current_database()
SQL
        sleep "$INTERVAL"
    done
) >> "$OUT/pg_stat_database.csv" 2>>"$OUT/capture.err" &
echo $! >> "$PIDFILE"

# --- 3. host memory and SWAP ------------------------------------------------
#
# SWAP COUNTERS ARE A FIRST-CLASS ATTRIBUTION SIGNAL HERE, NOT HOST TRIVIA, and this is the
# single most important thing in this file.
#
# WHETHER A HOST HAS SWAP CHANGES WHAT MEMORY EXHAUSTION LOOKS LIKE. Without it, running
# out is a kernel OOM kill: abrupt, exit 137, unmistakable, and absent from every
# application log. With it, the same pressure becomes latency while everything stays alive
# and every liveness check keeps passing — so a harness written to look for a dead
# container reports "no memory problem found" about a host that spent the window swapping.
#
# Which of those a given run is watching is a property of the host, not of this file, and
# it is recorded in the fingerprint (`free -m`, swappiness) and read from the samples
# below. Do not encode an expectation here: the whole point is that both outcomes are
# visible in the same capture.
#
# So: pswpin/pswpout from /proc/vmstat, and SwapFree from /proc/meminfo, every interval.
(
    echo "sample_utc,mem_available_kb,swap_total_kb,swap_free_kb,pswpin,pswpout,pgmajfault"
    while :; do
        T="$(ts)"
        MA=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)
        ST=$(awk '/^SwapTotal:/{print $2}' /proc/meminfo)
        SF=$(awk '/^SwapFree:/{print $2}' /proc/meminfo)
        PI=$(awk '/^pswpin /{print $2}' /proc/vmstat)
        PO=$(awk '/^pswpout /{print $2}' /proc/vmstat)
        MF=$(awk '/^pgmajfault /{print $2}' /proc/vmstat)
        echo "$T,$MA,$ST,$SF,$PI,$PO,$MF"
        sleep "$INTERVAL"
    done
) >> "$OUT/host_memory.csv" 2>>"$OUT/capture.err" &
echo $! >> "$PIDFILE"

# --- 4. container RSS against its limit -------------------------------------
# EVERY container, not only the app. §4.8's "some OTHER container dies first" row is a real
# outcome: under host memory pressure the KERNEL picks the victim, and it picks among all
# of them by its own accounting — a declared ceiling on one container changes which, and
# not whether. Which containers on a given host carry a limit is read from the fingerprint
# (`docker inspect … HostConfig.Memory`), not asserted here. Sampling only the app would
# make the kernel's actual choice invisible either way.
#
# THIS SAMPLER IS THE ONE THING host MODE CANNOT GIVE, and it is named rather than silently
# skipped: on a JAR-under-systemd install there are no containers, so there is no per-
# container accounting to sample. The host-level half — MemAvailable, swap in/out, the OOM
# counter — comes from /proc below and is unaffected, and that is the half §4.8's swap row
# is about. Record in the run record that container attribution was unavailable; do not
# record it as zero.
if [[ "$LOAD_CAPTURE_MODE" == "host" ]]; then
    log "host mode: NOT sampling docker stats (no containers). Per-container memory and CPU"
    log "           attribution is unavailable for this run; /proc still covers the host."
    echo "sample_utc,name,cpu_pct,mem_usage,mem_pct,net_io,block_io,pids" > "$OUT/docker_stats.csv"
    echo "# HD186_UNAVAILABLE_IN_HOST_MODE" >> "$OUT/docker_stats.csv"
else
(
    echo "sample_utc,name,cpu_pct,mem_usage,mem_pct,net_io,block_io,pids"
    while :; do
        T="$(ts)"
        docker stats --no-stream --format \
            '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.NetIO}},{{.BlockIO}},{{.PIDs}}' \
            2>/dev/null | sed "s/^/$T,/" || echo "$T,SAMPLE_FAILED"
        sleep "$INTERVAL"
    done
) >> "$OUT/docker_stats.csv" 2>>"$OUT/capture.err" &
echo $! >> "$PIDFILE"
fi

# --- 5. disk ----------------------------------------------------------------
# Sampled as well as watched: §5.3 condition 1 is the only failure in this plan that can
# damage real data, and a post-hoc reading cannot show how close it came.
(
    echo "sample_utc,filesystem,size,used,avail,use_pct,mounted"
    while :; do
        printf '%s,' "$(ts)"
        df -Ph / | awk 'NR==2 {print $1","$2","$3","$4","$5","$6}'
        sleep "$INTERVAL"
    done
) >> "$OUT/disk.csv" 2>>"$OUT/capture.err" &
echo $! >> "$PIDFILE"

log "started $(wc -l < "$PIDFILE") samplers"
cat >&2 <<EOF

  Capturing into: $OUT

  Stop with:  capture/capture.sh stop
  Then:       capture/export-prometheus.sh "$OUT"     (Tier 2, this box only)

  The capture directory is the run's evidence. Copy it off the box before terminating
  anything, and record capture_started_utc / capture_stopped_utc from window.txt in
  RESULTS-<date>.md — every correlation with the generator's k6 output is by timestamp,
  because §4.7 rules out shipping k6 metrics into the production Prometheus.

EOF
