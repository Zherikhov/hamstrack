#!/usr/bin/env bash
# HD-186 — Tier 2 capture: export the existing Prometheus series for the window.
# Spec: §4.7 (Tier 2).
#
#   capture/export-prometheus.sh <capture-dir> [start-utc] [end-utc]
#
# THIS BOX ONLY, because the optional observability stack happens to run here. Tier 1
# (capture.sh) is what a self-hosted install gets and is sufficient for a verdict; this is
# the extra resolution available where the stack exists. A harness whose verdict depended
# on this would be a Cloud-only tool wearing a portable name (§9).
#
# Runs ON THE BOX via `docker exec` into the Prometheus container rather than
# port-forwarding: it is one command and needs no tunnel.
#
# EXPLICITLY NOT DONE: k6's Prometheus remote-write output. It would require enabling a
# remote-write receiver on the production Prometheus — editing a file under observability/,
# a SYNCED path. That raises ConfigDrift, is overwritten by the next deploy, and opens a
# write endpoint on a metrics store for the convenience of one afternoon. The two sides are
# correlated by TIMESTAMP instead; both have clocks and the run is bracketed by recorded
# UTC timestamps (capture.sh writes them into window.txt).
set -euo pipefail

HERE_CFG="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# $LOAD_CONFIG's [BOX] half — the container names and the database connection — used to be
# read by nothing on this side, so an operator with a correct configuration file still got a
# fail-fast on a container name. See capture/lib-config.sh.
# shellcheck source=./lib-config.sh
. "$HERE_CFG/lib-config.sh"


DIR="${1:?usage: export-prometheus.sh <capture-dir> [start-utc] [end-utc]}"
[[ -d "$DIR" ]] || { echo "no such directory: $DIR" >&2; exit 2; }

# Default to the window capture.sh recorded. Passing them explicitly is for a re-export
# after the fact.
START="${2:-$(sed -n 's/^capture_started_utc=//p' "$DIR/window.txt" 2>/dev/null | head -1)}"
END="${3:-$(sed -n 's/^capture_stopped_utc=//p' "$DIR/window.txt" 2>/dev/null | head -1)}"
[[ -n "$START" && -n "$END" ]] || {
  echo "could not determine the window. Either pass start/end, or make sure" >&2
  echo "capture.sh wrote $DIR/window.txt (it writes the stop time on 'capture.sh stop')." >&2
  exit 2; }

: "${PROM_CONTAINER:=hamstrack-prometheus-1}"
: "${PROM_URL:=http://localhost:9090}"
: "${STEP:=15s}"

# FAIL FAST ON THE CONTAINER NAME, for the reason capture.sh fails fast on its two: every
# query below goes through `docker exec`, and a wrong name produced 33 files of
# {"status":"exec-failed"} plus a closing message saying how to check for empty results —
# with a grep that could not match the stub it had just written. "A clean export of nothing"
# was the reported outcome.
docker inspect "$PROM_CONTAINER" >/dev/null 2>&1 || {
    echo "FATAL no container named '$PROM_CONTAINER'. Read the name off \`docker ps\` and" >&2
    echo "      set PROM_CONTAINER (compose prefixes it with its project name, which differs" >&2
    echo "      between a checkout and /opt/hamstrack). Running now:" >&2
    docker ps --format '  {{.Names}}\t{{.Image}}' >&2 || true
    exit 2
}

OUT="$DIR/prometheus"
mkdir -p "$OUT"
echo "exporting $START .. $END at ${STEP} resolution into $OUT" >&2

# The series that answer §4.8's attribution table. Named explicitly rather than exported
# wholesale because a whole-Prometheus dump of a fifteen-day retention is gigabytes and the
# box has neither the disk nor the time — and because a named list here is a LIST OF
# QUESTIONS, which is readable, where a dump is a pile.
#
# If a query below returns nothing, that is a finding about the metric, not about the run:
# record it. A series that was never being collected is exactly the thing §4.7 warns about
# discovering mid-window.
QUERIES=(
  # latency and status mix
  'sum by (uri,status) (rate(http_server_requests_seconds_count{job="hamstrack-app"}[1m]))'
  'histogram_quantile(0.95, sum by (le,uri) (rate(http_server_requests_seconds_bucket{job="hamstrack-app"}[1m])))'
  'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{job="hamstrack-app"}[1m])))'
  # the refusal taxonomy — a 429 is meaningless without its kind
  'sum by (kind) (increase(hamstrack_ratelimit_hit_total[1m]))'
  'sum by (route,method) (increase(hamstrack_db_statement_budget_exceeded_total[1m]))'
  # the connection pool — the first suspect in §4.8, and probe P1's real deliverable is
  # hikaricp_connections_usage_seconds: the MEASURED mean connection-hold time per request.
  'hikaricp_connections_active'
  'hikaricp_connections_pending'
  'hikaricp_connections_idle'
  'increase(hikaricp_connections_timeout_total[1m])'
  'rate(hikaricp_connections_acquire_seconds_sum[1m]) / rate(hikaricp_connections_acquire_seconds_count[1m])'
  'rate(hikaricp_connections_usage_seconds_sum[1m]) / rate(hikaricp_connections_usage_seconds_count[1m])'
  # heap and GC — probe P2
  'jvm_memory_used_bytes{area="heap"}'
  'jvm_memory_max_bytes{area="heap"}'
  'jvm_gc_live_data_size_bytes'
  'jvm_gc_max_data_size_bytes'
  'rate(jvm_gc_pause_seconds_sum[1m])'
  'jvm_gc_pause_seconds_max'
  'jvm_threads_live_threads'
  # container and host
  'container_memory_working_set_bytes'
  'container_spec_memory_limit_bytes'
  'rate(container_cpu_usage_seconds_total[1m])'
  'node_memory_MemAvailable_bytes'
  'node_memory_SwapFree_bytes'
  'node_memory_SwapTotal_bytes'
  'rate(node_vmstat_pswpin[1m])'
  'rate(node_vmstat_pswpout[1m])'
  'rate(node_cpu_seconds_total{mode="idle"}[1m])'
  'rate(node_disk_io_time_seconds_total[1m])'
  # postgres-exporter, as a CROSS-CHECK on the pg_stat_activity sampler — never as its
  # replacement (§4.7).
  'pg_stat_database_blks_hit'
  'pg_stat_database_blks_read'
  'pg_stat_activity_count'
  # product gauges: they jump by the fixture's size and must return afterwards (§5.5.7)
  'hamstrack_issues_total'
  'hamstrack_users_total'
)

i=0
FAILED=0
EMPTY=0
for q in "${QUERIES[@]}"; do
    i=$((i + 1))
    name="$(printf '%02d' "$i")"
    echo "  [$name] $q" >&2
    if docker exec "$PROM_CONTAINER" wget -qO- \
        "$PROM_URL/api/v1/query_range?query=$(printf '%s' "$q" \
            | sed 's/ /%20/g; s/"/%22/g; s/{/%7B/g; s/}/%7D/g; s/\[/%5B/g; s/\]/%5D/g; s/,/%2C/g; s/+/%2B/g; s/|/%7C/g; s/=/%3D/g; s/(/%28/g; s/)/%29/g; s#/#%2F#g')&start=$START&end=$END&step=$STEP" \
        > "$OUT/$name.json" 2>>"$OUT/export.err"; then
        # An empty result is a FINDING ABOUT THE METRIC and a legitimate outcome; a failed
        # exec is a finding about this script. They are counted separately because they were
        # once written to the same file in a shape neither check could tell apart.
        if grep -q '"result":\[\]' "$OUT/$name.json"; then EMPTY=$((EMPTY + 1)); fi
    else
        # Marked so the closing check can SEE it. The stub used to be
        # {"status":"exec-failed"}, and the verification this script printed grepped for
        # '"result":[]' — which does not match it. Thirty-three failed queries reported as a
        # clean export.
        printf '{"status":"HD186_EXPORT_FAILED","query":%s}\n' "$(printf '%s' "$q" | sed 's/"/\\"/g; s/^/"/; s/$/"/')" \
            > "$OUT/$name.json"
        FAILED=$((FAILED + 1))
    fi
    printf '%s\t%s\n' "$name" "$q" >> "$OUT/index.tsv"
done

echo "" >&2
echo "exported ${#QUERIES[@]} range queries; the query for each file is in $OUT/index.tsv" >&2
echo "  $EMPTY returned an EMPTY series, $FAILED FAILED to run at all." >&2
echo "" >&2
echo "  An empty series is a FINDING ABOUT THE METRIC — it was never being collected — and" >&2
echo "  it is far cheaper to notice now than while writing up:" >&2
echo "    grep -l '\"result\":\\[\\]' $OUT/*.json" >&2

if [[ "$FAILED" -gt 0 ]]; then
    echo "" >&2
    echo "  !! $FAILED of ${#QUERIES[@]} queries DID NOT RUN. These are not empty series and" >&2
    echo "  !! they are not a finding about the run — they are this script failing to reach" >&2
    echo "  !! Prometheus. See $OUT/export.err and:" >&2
    echo "  !!   grep -l HD186_EXPORT_FAILED $OUT/*.json" >&2
    echo "  !! Tier 2 is INCOMPLETE. Fix and re-export (the window is bracketed in" >&2
    echo "  !! window.txt, so a re-export after the fact covers the same range)." >&2
    exit 1
fi
