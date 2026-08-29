#!/usr/bin/env bash
# HD-186 — the capture scripts' half of $LOAD_CONFIG.
# Spec: §5.1, §9. Sourced, never executed.
#
# ---------------------------------------------------------------------------
# WHY THIS FILE EXISTS.
#
# config.env.example splits the configuration by MACHINE and labels one half [BOX] — "the
# fixture and the capture. Needs the bcrypt HASH, the database connection and the container
# names." Half of that was true. Only fixture/lib.sh ever sourced $LOAD_CONFIG, so
# APP_CONTAINER, PG_CONTAINER and PROM_CONTAINER were read by nothing that had loaded the
# file that sets them: an operator who followed the template exactly, put the right names in
# /opt/hamstrack/.loadtest.env and ran capture/capture.sh got
#
#     FATAL no container named 'hamstrack-app-1'
#
# while looking at a configuration file containing the correct name. The remedy for a
# fail-fast that fires on correct configuration is not a better message; it is reading the
# configuration.
#
# ---------------------------------------------------------------------------
# THE PRECEDENCE RULE IS THE SAME ONE fixture/lib.sh USES, AND FOR THE SAME REASON.
#
# ANYTHING ALREADY IN THE ENVIRONMENT WINS. `INTERVAL=1 capture/capture.sh start` must not
# be overridden by a stale line in a file, and one-shot values are passed exactly that way.
#
# What gets protected is an explicit list rather than a blanket `export -p` snapshot: a
# blanket snapshot re-declares SHELLOPTS and BASHOPTS, which are readonly, and under `set -e`
# that aborts the script it was meant to protect. The list is every variable the capture
# scripts read — which is why adding one there is a deliberate edit here, not an omission
# somewhere else.
: "${LOAD_CONFIG:=/opt/hamstrack/.loadtest.env}"
: "${LOAD_RESULTS_DIR:=/var/tmp/hd186}"

# ---------------------------------------------------------------------------
# `declare -gx`, AND THE `-g` IS THE WHOLE FIX.
#
# `export -p` emits `declare -x NAME=value`, and `declare` executed INSIDE A FUNCTION
# creates a LOCAL. So sourcing the snapshot from within this function set the caller's values
# on variables that ceased to exist the moment it returned, and the FILE's values were what
# survived — the exact opposite of the banner this function prints. Demonstrated:
#
#   INSIDE function: INTERVAL=1  APP_CONTAINER=from-env
#   AFTER  function: INTERVAL=99 APP_CONTAINER=from-file     <-- the file won
#
# `sed` rewrites each line to `declare -gx`, which assigns globally wherever it is run.
#
# The mktemp file holds every exported LOAD_* value — the bcrypt hash among them — and was
# removed by a plain `rm` on the success path only, so a syntax error in $LOAD_CONFIG (which
# aborts under `set -e` between the two sources) left it in /tmp. The RETURN trap removes it
# on every exit from this function, including that one.
# ---------------------------------------------------------------------------
# TWO THINGS THAT LOOK LIKE STYLE AND ARE NOT: `-g`, AND NOT USING A FILE.
#
# `export -p` emits `declare -x NAME=value`, and `declare` executed INSIDE A FUNCTION
# creates a LOCAL. So re-asserting the snapshot from within this function set the caller's
# values on variables that ceased to exist the moment it returned, and the FILE's values
# were what survived — the exact opposite of the banner this function prints. Demonstrated:
#
#   INSIDE function: INTERVAL=1  APP_CONTAINER=from-env
#   AFTER  function: INTERVAL=99 APP_CONTAINER=from-file     <-- the file won
#
# `sed` rewrites each line to `declare -gx`, which assigns globally wherever it is run.
#
# The snapshot is held in a VARIABLE and replayed with `eval`, not written to `mktemp`.
# It contains every exported LOAD_* value, the bcrypt hash among them, and the old temp file
# was removed by a plain `rm` on the success path only — so a syntax error in $LOAD_CONFIG,
# which aborts between the two sources under `set -e`, left the secret in /tmp. A `trap …
# RETURN` does NOT fix that and silently breaks the restore instead: bash runs a RETURN trap
# when a SOURCED FILE finishes as well as when a function returns, so it fires on
# `. "$LOAD_CONFIG"` and deletes the snapshot one line before it is read. No file, no
# window, no trap.
load_capture_config() {
    [[ -f "$LOAD_CONFIG" ]] || return 0
    local saved
    saved="$(export -p \
      | grep -E '^(declare -x |export )(LOAD_[A-Za-z0-9_]+|APP_CONTAINER|PG_CONTAINER|PROM_CONTAINER|PROM_URL|ACTUATOR_URL|INTERVAL|STEP|DISK_FLOOR_MB|MEM_FLOOR_MB|SWAP_CEILING_MB|DB_USER|DB_NAME)=' \
      | sed 's/^declare -x /declare -gx /' || true)"
    set -a; . "$LOAD_CONFIG"; set +a
    # Re-assert what the caller exported, so the file fills gaps and never overrides.
    eval "$saved"
    printf '%s configuration: %s (values already in the environment win)\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$LOAD_CONFIG" >&2
}

load_capture_config

# ---------------------------------------------------------------------------
# ONE NAME PER THING.
#
# The fixture scripts document LOAD_PG_CONTAINER and the capture scripts read PG_CONTAINER,
# and both defaulted to 'hamstrack-postgres-1' independently. Two defaults for one container
# agree until somebody changes one of them, and then they disagree silently on the machine
# where it matters. LOAD_PG_CONTAINER is the documented name, so it is the source and the
# bare one is the fallback — the same shape as the DB_USER/DB_NAME fallback in capture.sh.
: "${PG_CONTAINER:=${LOAD_PG_CONTAINER:-hamstrack-postgres-1}}"
: "${APP_CONTAINER:=${LOAD_APP_CONTAINER:-hamstrack-app-1}}"
: "${PROM_CONTAINER:=${LOAD_PROM_CONTAINER:-hamstrack-prometheus-1}}"
export PG_CONTAINER APP_CONTAINER PROM_CONTAINER

# ---------------------------------------------------------------------------
# A PASSWORD IN A DSN IS A PASSWORD IN argv, AND HERE IT IS A PASSWORD IN argv
# ONCE EVERY FIVE SECONDS FOR FORTY-FIVE MINUTES.
#
# LOAD_CAPTURE_MODE=host runs `psql "$LOAD_DB_DSN"` from every sampler tick, so a DSN of the
# documented shape (postgresql://user:password@host/db) publishes the database password
# through /proc/<pid>/cmdline to every account on the box, several hundred times per window.
# It is the same finding that was fixed in verify-api.sh, arriving by a different door — and
# a credential in argv is not made safer by the process being short-lived, because the
# capture's whole job is to keep restarting it.
#
# So the DSN is REFUSED if it carries one, and the password goes in a PGPASSFILE that psql
# reads and nothing else can see. Only `host` mode is affected: in `docker` mode psql runs
# inside the container under trust/peer authentication and no DSN is used at all.
require_passwordless_dsn() {
    [[ -n "${LOAD_DB_DSN:-}" ]] || return 0
    if [[ "$LOAD_DB_DSN" =~ ://[^/@]*:[^@]*@ ]]; then
        cat >&2 <<'MSG'
FATAL LOAD_DB_DSN carries an inline password, and this capture would put it in argv on
      every sampler tick — several hundred processes over the window, each readable
      through /proc/<pid>/cmdline by every local account.

      Use a passwordless DSN and let psql read the secret out of a file:

          LOAD_DB_DSN=postgresql://hamstrack@localhost:15432/hamstrack
          LOAD_DB_PASSWORD=…      # this file will write a 0600 ~/.pgpass and export PGPASSFILE

      or export PGPASSFILE yourself. See ops/loadtest/config.env.example.
MSG
        exit 2
    fi
}

# The other half: turn LOAD_DB_PASSWORD into a mode-0600 pgpass file, once, and hand psql
# PGPASSFILE instead of a command line. Skipped when the operator already exported one.
setup_pgpassfile() {
    [[ -n "${LOAD_DB_PASSWORD:-}" ]] || return 0
    [[ -z "${PGPASSFILE:-}" ]] || return 0
    install -d -m 0700 "$LOAD_RESULTS_DIR"
    local f="$LOAD_RESULTS_DIR/.pgpass"
    ( umask 077; printf '*:*:%s:%s:%s\n' \
        "${LOAD_DB_NAME:-${DB_NAME:-*}}" "${LOAD_DB_USER:-${DB_USER:-*}}" \
        "$LOAD_DB_PASSWORD" > "$f" )
    chmod 600 "$f"
    export PGPASSFILE="$f"
}

require_passwordless_dsn
setup_pgpassfile
