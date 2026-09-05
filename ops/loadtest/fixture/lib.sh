#!/usr/bin/env bash
# Shared helpers for the HD-186 load fixture (generate / verify / teardown / revoke /
# rehearse).
# Spec: docs/design/load-capacity-measurement-proposal.md §4.2, §5.5.
#
# Sourced, never executed. Everything here is about ONE property: this code can be
# present on production (ops/ is a synced path — §11 Q7) without being permitted to run
# there by accident. Presence is not permission, and the guards below are what say so.
#
# The guards are deliberately redundant with each other. Each one alone is a rule; four
# that fail independently are a property:
#
#   confirmation  an explicit LOAD_CONFIRM naming the window — refuses a stray invocation
#   flyway        the schema version is pinned — refuses a schema this was not written for
#   target        the operator SPELLS BACK the database being acted on, and the resolved
#                 name is read from the server rather than from the variables that chose
#                 it — refuses an invocation aimed at a box nobody named
#   tenancy       every object touched is reached through a load workspace slug or a
#                 @load.invalid address, never through an id typed by a human
#
# WHY THE TARGET GUARD EXISTS AT ALL. The other three all answer "may this run?"; none of
# them answers "WHERE". LOAD_PSQL_MODE / LOAD_PG_CONTAINER / LOAD_DB_DSN choose the
# database and nothing read them back, which is how a "rehearsal" could be pointed at
# production by an exported variable and still pass every check it had.
set -euo pipefail

# The Flyway version this fixture was written against. A generator that writes rows the
# entities cannot map is worse than one that refuses, because the refusal is loud and the
# bad rows are silent until a report 500s during the window. Bump this ONLY together with
# a re-read of the schema — see README.md "When a migration lands".
readonly LOAD_PINNED_FLYWAY_VERSION="24"

log()  { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die()  { log "FATAL $*"; exit 1; }
note() { log "  $*"; }

# --- where the operator's configuration lives --------------------------------
#
# NOT inside ops/loadtest/. `ops/` is a synced path and a synced DIRECTORY is replaced
# WHOLESALE by every deploy, which has three consequences for a file placed in here, all
# of them reachable:
#
#   * apply-config.sh copies a differing synced path into .config-backup/<ts>/ and keeps
#     the last five — so a password file dropped in this directory ends up in five
#     retained copies, at the mode `cp -a` preserved, long after the window.
#   * the drift check reports a file ADDED to a synced directory as
#     hamstrack_config_drift{scope="files"} 1, and drift reading zero is a hard
#     PRECONDITION of the window. A setup step that trips a precondition teaches its
#     reader to wave through a gate they caused.
#   * ConfigDrift (for: 30m) is one of the provisioned rules that make up half the breach
#     bar, so with a config file in here NO STAGE COULD BE RECORDED AS PASSED. The harness
#     would invalidate its own run.
#
# So the real configuration lives outside the synced tree — and since HD-222 the NAME is no
# longer a way around that either: apply-config.sh refuses to PLACE any file whose basename
# is `.env`, starts with `.env.`, or ENDS IN `.env`, per placed file (see
# config.env.example for why that refusal matters, and why renaming around it in the other
# direction — to `.env.something` — would break every deploy of the product).
: "${LOAD_CONFIG:=/opt/hamstrack/.loadtest.env}"

# Run output, for the same reason: a `results/` directory inside ops/loadtest/ is a set of
# files ADDED to a synced directory, which is exactly what the drift check reports.
: "${LOAD_RESULTS_DIR:=/var/tmp/hd186}"

# Source the operator's configuration if it is there. Anything ALREADY IN THE ENVIRONMENT
# wins: `LOAD_CONFIRM=$(date -u +%F) bash fixture/generate.sh` must not be overridden by a
# stale line in a file, and the one-shot secrets are passed exactly that way on purpose.
# Only LOAD_* is saved and re-asserted: a blanket `export -p` snapshot would try to
# re-declare SHELLOPTS and BASHOPTS, which are readonly, and under `set -e` that aborts the
# script it was meant to protect.
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
load_config() {
    [[ -f "$LOAD_CONFIG" ]] || return 0
    local saved
    saved="$(export -p | grep -E '^(declare -x |export )LOAD_[A-Za-z0-9_]+=' \
      | sed 's/^declare -x /declare -gx /' || true)"
    set -a; . "$LOAD_CONFIG"; set +a
    # Re-assert what the caller exported, so the file fills gaps and never overrides.
    eval "$saved"
    note "configuration: $LOAD_CONFIG (values already in the environment win)"
}

# Called HERE, at source time, and not by each entry script: LOAD_RUN_ID decides the slug
# prefix a few lines below, and a configuration read after that point would set a run id
# nothing reads. An ordering mistake in this file is a teardown that cannot find its own
# fixture.
load_config

# The durable handle on everything this fixture creates. Teardown finds its work by THIS,
# not by a list of ids written down at generation time: the writing mix creates rows
# nothing recorded, so an inventory is wrong by construction (§5.5.2). A slug prefix and an
# email domain survive a lost file, a crashed run and an aborted window.
#
# THE HANDLE CARRIES A RANDOM RUN ID, and that is what makes it unforgeable. `hd186-load-`
# alone is a string a user can type into "workspace name": slugify it and a real tenant
# owns a slug this harness treats as its own — after which the teardown either refuses
# (stranding 750 000 rows mid-window) or the issue_seq resync updates a real tenant's
# counter. A four-byte run id cannot be arrived at by naming a workspace.
#
# MATCHING is deliberately WIDER than creation: with no LOAD_RUN_ID the match falls back to
# the base prefix, so a teardown whose run id was lost still finds its fixture. Losing the
# ability to clean up is the worse failure, and the tenancy guard below is what makes the
# wide match safe — it excludes any matched workspace holding a real member.
readonly LOAD_WS_SLUG_BASE="hd186-load-"
readonly LOAD_EMAIL_DOMAIN="load.invalid"
: "${LOAD_RUN_ID:=}"
if [[ -n "$LOAD_RUN_ID" ]]; then
    [[ "$LOAD_RUN_ID" =~ ^[a-z0-9]{4,16}$ ]] || {
        echo "FATAL LOAD_RUN_ID becomes part of a slug: 4-16 lowercase alphanumerics, got '$LOAD_RUN_ID'" >&2
        exit 1
    }
    LOAD_WS_SLUG_PREFIX="${LOAD_WS_SLUG_BASE}${LOAD_RUN_ID}-"
else
    LOAD_WS_SLUG_PREFIX="$LOAD_WS_SLUG_BASE"
fi
readonly LOAD_WS_SLUG_PREFIX

# --- psql plumbing -----------------------------------------------------------
#
# Two connection styles, because the harness must run in both places the spec names
# (§9): a plain DSN for a local Postgres, and `docker exec` for the production box where
# 5432 is not published. LOAD_PSQL_MODE picks; nothing else in the harness knows which.
#
#   LOAD_PSQL_MODE=dsn     (default) — uses LOAD_DB_DSN, e.g.
#                          postgresql://hamstrack:hamstrack@localhost:15432/hamstrack
#   LOAD_PSQL_MODE=docker  — runs psql inside LOAD_PG_CONTAINER as LOAD_DB_USER.
#
# NEITHER OF THESE ANSWERS "WHICH DATABASE". They are how to reach one; what was actually
# reached is read back from the server by require_named_target below.
: "${LOAD_PSQL_MODE:=dsn}"
: "${LOAD_PG_CONTAINER:=hamstrack-postgres}"
: "${LOAD_DB_USER:=hamstrack}"
: "${LOAD_DB_NAME:=hamstrack}"

# psql with the flags that make a mistake loud: stop on the first error, never prompt,
# and fail on a bad variable reference rather than substituting an empty string (which is
# how a `WHERE workspace_id IN ()` becomes a `WHERE TRUE`).
psql_run() {
    case "$LOAD_PSQL_MODE" in
        dsn)
            [[ -n "${LOAD_DB_DSN:-}" ]] || die "LOAD_DB_DSN is unset and LOAD_PSQL_MODE=dsn"
            psql "$LOAD_DB_DSN" -v ON_ERROR_STOP=1 --no-psqlrc "$@"
            ;;
        docker)
            docker exec -i "$LOAD_PG_CONTAINER" \
                psql -U "$LOAD_DB_USER" -d "$LOAD_DB_NAME" -v ON_ERROR_STOP=1 --no-psqlrc "$@"
            ;;
        *) die "LOAD_PSQL_MODE must be 'dsn' or 'docker', got '$LOAD_PSQL_MODE'" ;;
    esac
}

# A single scalar, whitespace-trimmed. Used for every assertion in this harness, so it is
# worth being exact: -A -t -q gives an unaligned, untitled, quiet single value.
psql_scalar() {
    psql_run -A -t -q -c "$1" | tr -d '[:space:]'
}

# Run a .sql file. The two modes differ in a way that is easy to get wrong: in `docker`
# mode psql runs INSIDE the container, where the repository does not exist, so `-f` would
# look for the file on the container's filesystem and fail with a bare "No such file or
# directory" that reads like a missing script. The file is piped on stdin instead.
#
# `tr -d '\r'` because these files are edited on Windows: a CRLF that survives into a
# dollar-quoted body ($$ ... $$) becomes part of the function source, and a stray carriage
# return inside a PL/pgSQL block fails at a line number that does not match the file.
psql_file() {
    local f="$1"; shift
    [[ -f "$f" ]] || die "SQL file not found: $f"
    case "$LOAD_PSQL_MODE" in
        dsn)    tr -d '\r' < "$f" | psql "$LOAD_DB_DSN" -v ON_ERROR_STOP=1 --no-psqlrc "$@" ;;
        docker) tr -d '\r' < "$f" | docker exec -i "$LOAD_PG_CONTAINER" \
                    psql -U "$LOAD_DB_USER" -d "$LOAD_DB_NAME" -v ON_ERROR_STOP=1 \
                         --no-psqlrc "$@" ;;
    esac
}

# --- guards ------------------------------------------------------------------

# Guard 1 — an explicit confirmation naming the window. The value is compared, not merely
# required to be non-empty, so `LOAD_CONFIRM=yes` left in a shell from last week does not
# arm this. The operator types the date of the window they are in.
require_confirmation() {
    local action="$1"
    [[ -n "${LOAD_CONFIRM:-}" ]] || die \
        "refusing to $action: LOAD_CONFIRM is unset.
        Set LOAD_CONFIRM to today's window date in UTC, e.g. LOAD_CONFIRM=2026-09-05.
        This is the guard that makes being PRESENT on a box different from being
        PERMITTED to run on it (proposal §11 Q7)."
    local today; today="$(date -u +%Y-%m-%d)"
    [[ "$LOAD_CONFIRM" == "$today" ]] || die \
        "refusing to $action: LOAD_CONFIRM='$LOAD_CONFIRM' is not today ($today).
        A confirmation that outlives its window is not a confirmation."
}

# Guard 2 — the schema is the one this was written against. Names BOTH versions, because a
# message that says only "wrong version" sends its reader to the wrong file.
require_flyway_version() {
    local actual
    actual="$(psql_scalar "SELECT COALESCE(MAX(version::numeric)::text, '<none>')
                             FROM flyway_schema_history WHERE success")" \
        || die "could not read flyway_schema_history — is this a Hamstrack database?"
    [[ "$actual" == "$LOAD_PINNED_FLYWAY_VERSION" ]] || die \
        "schema version mismatch: this fixture is pinned to Flyway version
        '$LOAD_PINNED_FLYWAY_VERSION', the database is at '$actual'.
        Re-read src/main/resources/db/migration against ops/loadtest/fixture/10-generate.sql,
        update LOAD_PINNED_FLYWAY_VERSION in ops/loadtest/fixture/lib.sh, and re-run.
        Do NOT bypass this: the generator writes rows the entities must be able to map,
        and Flyway's version is the only thing that says which entities those are."
    note "flyway_schema_history: version $actual (pinned $LOAD_PINNED_FLYWAY_VERSION) — ok"
}

# Guard 3 — WHICH DATABASE. Every other guard in this file answers "may this run"; this one
# answers "where", and its absence is what let a rehearsal reach production.
#
# The operator spells the target back in LOAD_TARGET and it is compared against
# current_database() READ FROM THE SERVER — not against LOAD_DB_NAME, which is one of the
# variables that chose the server and therefore cannot check it. Before acting it prints
# the three facts that actually differ between a scratch database and production: the
# resolved name, how many accounts on it are NOT this fixture's, and how big it is.
#
# STATED LIMITATION: a name is not an identity. Two databases called `hamstrack` answer
# this guard identically, and it is the printed real-account count that distinguishes them.
# What this makes impossible is acting on a database nobody named — which is what happened.
require_named_target() {
    local action="$1"
    local dbname real_accounts dbsize
    dbname="$(psql_scalar "SELECT current_database()")" \
        || die "could not reach a database at all (mode=$LOAD_PSQL_MODE)"
    real_accounts="$(psql_scalar "SELECT count(*) FROM users
                                   WHERE email NOT LIKE '%@${LOAD_EMAIL_DOMAIN}'")"
    dbsize="$(psql_scalar "SELECT pg_size_pretty(pg_database_size(current_database()))")"

    log "TARGET: database '$dbname' via mode=$LOAD_PSQL_MODE, size $dbsize,"
    log "        holding $real_accounts account(s) that are NOT @${LOAD_EMAIL_DOMAIN}."

    [[ -n "${LOAD_TARGET:-}" ]] || die \
        "refusing to $action: LOAD_TARGET is unset.
        Name the database you are about to act on. It must equal the name the SERVER
        reports, resolved above as '$dbname':
          LOAD_TARGET=$dbname ...
        No variable that CHOSE the connection can check it, which is why this one is typed
        by the operator and compared against the server's own answer."
    [[ "$LOAD_TARGET" == "$dbname" ]] || die \
        "refusing to $action: LOAD_TARGET='$LOAD_TARGET' but the connection resolves to
        '$dbname' (mode=$LOAD_PSQL_MODE, container='${LOAD_PG_CONTAINER}',
        DSN ${LOAD_DB_DSN:+is set}${LOAD_DB_DSN:-is unset}).
        You are pointed at a database you did not name."
    note "target confirmed: $dbname — ok"
}

# Guard 4 — tenancy. Everything this fixture owns is reachable from the slug prefix, and
# nothing else is. Two questions before a DELETE runs:
#
#   (a) do the matched workspaces exist at all?  (an empty match means the teardown would
#       silently succeed while removing nothing, which reads identical to success)
#   (b) is every member of every matched workspace a @load.invalid account?  (a real person
#       in a matched workspace means the slug prefix has collided with a real tenant, and
#       that workspace must be left alone)
#
# A COLLISION NARROWS THE SCOPE; IT DOES NOT STOP THE TEARDOWN. The earlier version died on
# the first intruder, which is the right answer for the colliding workspace and the wrong
# one for every other: one real tenant whose name slugified into this prefix would strand
# three quarters of a million fixture rows on production until somebody edited SQL by hand,
# mid-window. So the contaminated workspaces are named, excluded (teardown.sql re-derives
# the same exclusion inside its own transaction, which is where it is a guarantee), and
# reported as a failure AFTER the safe rows are gone. Refusing everything protects nothing
# that excluding the one does not.
LOAD_CONTAMINATED=0
require_load_workspaces_are_synthetic() {
    local n
    n="$(psql_scalar "SELECT count(*) FROM workspaces
                       WHERE slug LIKE '${LOAD_WS_SLUG_PREFIX}%'")"
    [[ "$n" -gt 0 ]] || die \
        "no workspace matches slug prefix '${LOAD_WS_SLUG_PREFIX}' — nothing to tear down.
        If the fixture was generated under a different LOAD_RUN_ID, unset LOAD_RUN_ID to
        match on the base prefix '${LOAD_WS_SLUG_BASE}', or list them with
          SELECT id, slug, name FROM workspaces ORDER BY created_at DESC LIMIT 20;
        and tear down by hand. Do not widen the prefix past '${LOAD_WS_SLUG_BASE}'."

    local dirty
    dirty="$(psql_scalar "
        SELECT count(*) FROM workspaces w
         WHERE w.slug LIKE '${LOAD_WS_SLUG_PREFIX}%'
           AND EXISTS (SELECT 1 FROM workspace_members m JOIN users u ON u.id = m.user_id
                        WHERE m.workspace_id = w.id
                          AND u.email NOT LIKE '%@${LOAD_EMAIL_DOMAIN}')")"
    if [[ "$dirty" != "0" ]]; then
        LOAD_CONTAMINATED="$dirty"
        log "!!! $dirty matched workspace(s) hold a real member and will be EXCLUDED:"
        psql_run -c "
            SELECT w.slug AS workspace, u.email AS non_load_member
              FROM workspace_members m
              JOIN workspaces w ON w.id = m.workspace_id
              JOIN users u      ON u.id = m.user_id
             WHERE w.slug LIKE '${LOAD_WS_SLUG_PREFIX}%'
               AND u.email NOT LIKE '%@${LOAD_EMAIL_DOMAIN}'" >&2 || true
        [[ "$dirty" -lt "$n" ]] || die \
            "every matched workspace holds a real member. Either a real workspace has taken
            the '${LOAD_WS_SLUG_PREFIX}' prefix, or this is not the database you think it
            is. Nothing will be deleted — resolve by hand."
        log "!!! continuing with the $(( n - dirty )) synthetic workspace(s). The excluded"
        log "!!! ones are reported again at the end and make this run FAIL."
    else
        note "$n load workspace(s) matched, all members are @${LOAD_EMAIL_DOMAIN} — ok"
    fi
}

# Guard for the rehearsal only: THE DATABASE ITSELF must be disposable.
#
# The earlier version tested the SHAPE OF THE CONNECTION (does the DSN say localhost?) and
# had an empty-DSN branch that PASSED whenever LOAD_PSQL_MODE=docker — the mode the box's
# own configuration ships and the runbook tells the operator to source. A rehearsal on
# production therefore passed its own never-against-production guard, generated the full
# fixture, deleted it and VACUUMed, outside any window.
#
# A property of a connection string cannot answer a question about a database. This one
# asks the database: a rehearsal target holds NO account that is not this fixture's.
# Mode-independent, and it cannot be satisfied by exporting anything.
require_disposable_database() {
    local real
    real="$(psql_scalar "SELECT count(*) FROM users
                          WHERE email NOT LIKE '%@${LOAD_EMAIL_DOMAIN}'")"
    [[ "$real" == "0" ]] || die \
        "refusing to rehearse: this database holds $real account(s) that are not
        @${LOAD_EMAIL_DOMAIN}, so it is somebody's real database — quite possibly
        production, whose own configuration sets LOAD_PSQL_MODE=docker.

        A rehearsal GENERATES AND DELETES a fixture and then VACUUMs. Point it at a
        scratch database of its own:
          createdb hamstrack_hd186, bring the schema up to the pinned Flyway version,
          then LOAD_PSQL_MODE=dsn LOAD_DB_DSN=postgresql://.../hamstrack_hd186

        This guard is about the DATABASE and not about the connection style, because the
        previous one was about the connection style and production satisfied it."
    note "rehearsal target holds zero non-@${LOAD_EMAIL_DOMAIN} accounts — ok"
}

# --- the load password -------------------------------------------------------
#
# GENERATED, NOT CHOSEN. Every instruction here used to say "pick a password", which asks
# a human for the entropy protecting every load account on production during a window
# — and the residual is not bounded by the window, because an aborted run leaves those
# accounts behind (fixture/revoke.sh is what ends them).
#
#   openssl rand -base64 24
#
# The floor is enforced where each machine FIRST SEES the value — verify-api.sh and
# mint-tokens.js — and not only here, because the generator only ever sees the hash.
readonly LOAD_PASSWORD_MIN_LEN=24

require_strong_password() {
    local p="${1:-}"
    [[ -n "$p" ]] || die "the load password is empty"
    [[ "${#p}" -ge "$LOAD_PASSWORD_MIN_LEN" ]] || die \
        "the load password is ${#p} characters; the floor is ${LOAD_PASSWORD_MIN_LEN}.
        Do not choose one — generate it:  openssl rand -base64 24
        Every load account on production shares this string, and they outlive an aborted
        window."
}

# Opportunistic pre-flight: if the plaintext happens to be present AND a bcrypt
# implementation is available, prove the pair matches BEFORE writing 750 000 rows. A
# mismatch is otherwise discovered by verify-api.sh's login — or, if that step is skipped,
# fifteen minutes into token minting.
#
# Opportunistic on purpose: the box is not supposed to hold the plaintext at all (README,
# "The load password"), so this usually prints the note and moves on. What it never does is
# stay silent — an unverifiable pair is reported as unverified rather than as fine.
verify_password_hash_pair() {
    local pw="${LOAD_PASSWORD:-}" hash="${LOAD_PASSWORD_HASH:-}"
    if [[ -z "$pw" ]]; then
        note "LOAD_PASSWORD is not set here, so the hash cannot be checked against it now."
        note "verify-api.sh logs in with it, and that login is where a mismatch surfaces."
        return 0
    fi
    require_strong_password "$pw"
    if command -v python3 >/dev/null 2>&1 && python3 -c 'import bcrypt' >/dev/null 2>&1; then
        LOAD_PW="$pw" LOAD_HASH="$hash" python3 -c 'import bcrypt,os,sys; sys.exit(0 if bcrypt.checkpw(os.environ["LOAD_PW"].encode(), os.environ["LOAD_HASH"].encode()) else 1)' \
            || die "LOAD_PASSWORD does not match LOAD_PASSWORD_HASH. Generating now would
            create a whole pool of accounts that cannot log in, which is otherwise only discovered
            fifteen minutes into token minting."
        note "LOAD_PASSWORD matches LOAD_PASSWORD_HASH (bcrypt, checked here) — ok"
    else
        note "no bcrypt available here; the pair stays UNVERIFIED until verify-api.sh logs in."
    fi
}

# --- disk ---------------------------------------------------------------------
#
# §5.1 precondition 5 and §5.3 abort condition 1. A full disk is the ONLY failure in this
# whole plan that can damage real data rather than merely producing a bad measurement, so
# it is checked before generation as well as during the run.
#
# This runs ON THE BOX, from the fixture scripts. The generator has a check of its own for
# its own filesystem (run-ladder.sh), and the two are NOT redundant with each other: they
# are two machines' disks, and only this one is near the data.
require_free_disk() {
    local need_mb="$1" path="${2:-/}"
    local free_mb
    free_mb="$(df -Pm "$path" 2>/dev/null | awk 'NR==2 {print $4}')" || free_mb=""
    [[ -n "$free_mb" ]] || { log "WARN could not read free disk on $path — skipping check"; return 0; }
    [[ "$free_mb" -ge "$need_mb" ]] || die \
        "free disk on $path is ${free_mb} MB, need at least ${need_mb} MB.
        Grow the volume before the window (§5.1 precondition 5). gp3 grows online."
    note "free disk on $path: ${free_mb} MB (need ${need_mb} MB) — ok"
}

# --- the transaction horizon ---------------------------------------------------
#
# The generator holds ONE transaction across ~750 000 inserts, which is deliberate: a
# failure at row 700 000 leaves the database exactly as it found it. The cost is charged to
# everybody, though, and it is worth saying out loud — an open transaction pins the xmin
# horizon, so for its duration autovacuum can reclaim dead tuples NOWHERE in the database,
# including in real tenants' tables. That bloat is not the fixture's own.
#
# This prints what is already open before adding to it, so generating beside somebody
# else's forgotten transaction is a decision rather than a discovery.
report_transaction_horizon() {
    log "open transactions before generating (this generator will add one long one):"
    psql_run -c "
        SELECT pid, state, now() - xact_start AS xact_age, left(query, 60) AS query
          FROM pg_stat_activity
         WHERE xact_start IS NOT NULL AND pid <> pg_backend_pid()
           AND datname = current_database()
         ORDER BY xact_start LIMIT 10;" || true
    note "an open transaction holds the xmin horizon for the WHOLE database, not only for us."
}
