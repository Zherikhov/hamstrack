#!/usr/bin/env bash
# HD-186 load fixture — generate.
# Spec: docs/design/load-capacity-measurement-proposal.md §4.2.
#
#   LOAD_CONFIRM=$(date -u +%Y-%m-%d) LOAD_TARGET=hamstrack \
#     ops/loadtest/fixture/generate.sh
#
# Reads its configuration from the environment and from $LOAD_CONFIG (default
# /opt/hamstrack/.loadtest.env — deliberately OUTSIDE this synced directory; see lib.sh).
# Refuses to do anything without a confirmation naming today, a named target database, a
# matching Flyway version, a bcrypt hash for the load password, and enough free disk.
#
# Everything it writes goes in ONE transaction. A failure at row 700 000 leaves the
# database exactly as it found it — which on a production box is worth the extra WAL, and
# whose cost to everybody else (a pinned xmin horizon, so autovacuum reclaims nothing
# anywhere for the duration) is printed before it starts rather than discovered after.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
. "$HERE/lib.sh"

: "${LOAD_SEED:=0.4242}"
: "${LOAD_SCALE:=1.0}"
: "${LOAD_MIN_FREE_MB:=5120}"          # §5.1 precondition 5
: "${LOAD_DISK_PATH:=/}"

# The run id is REQUIRED for generation, because it is what makes the slug handle
# unforgeable: 'hd186-load-' on its own is a string a user can produce by naming a
# workspace, and a collision means either a teardown that strands the fixture or a resync
# that writes a real tenant's issue_seq. Teardown may run without it (it falls back to the
# base prefix, and excludes any matched workspace holding a real member) — generation may
# not, because generation is where the handle is chosen.
[[ -n "${LOAD_RUN_ID:-}" ]] || die \
    "LOAD_RUN_ID is unset. It is the random suffix that makes the fixture's slug prefix
    unforgeable, and it must be RECORDED — teardown, the completeness check and the k6
    mixes all resolve the fixture through it.

      LOAD_RUN_ID=$( (command -v openssl >/dev/null && openssl rand -hex 4) || echo "$(date +%s | tail -c 5)$RANDOM" | tr -dc 'a-f0-9' | head -c 8 )

    Put it in \$LOAD_CONFIG on BOTH machines and in RESULTS-<date>.md."

SLUG_A="${LOAD_WS_SLUG_PREFIX}a"
SLUG_B="${LOAD_WS_SLUG_PREFIX}b"

log "HD-186 fixture generation starting"
note "mode=$LOAD_PSQL_MODE scale=$LOAD_SCALE seed=$LOAD_SEED run_id=$LOAD_RUN_ID"
note "workspace slugs: $SLUG_A / $SLUG_B"

require_confirmation "generate the load fixture"

# The load password never appears in this repository, and the box never needs its
# plaintext: the generator takes only the bcrypt hash, and the one script here that must
# log in (verify-api.sh) reads the plaintext interactively for a single command. A hash
# committed here would be a credential in a source-available repository with a known
# plaintext, and every account sharing a published password would be reachable by anyone
# who read the file, on production, during a window in which the instance is deliberately
# being pushed to its limits.
#
# GENERATE the password, do not choose one:
#   PW=$(openssl rand -base64 24)
#   htpasswd -bnBC 12 "" "$PW" | tr -d ':\n' | sed 's/^\$2y/\$2a/'
# Spring's BCryptPasswordEncoder is configured at strength 12 (SecurityConfig).
[[ -n "${LOAD_PASSWORD_HASH:-}" ]] || die \
    "LOAD_PASSWORD_HASH is unset. See the comment in this script, or README.md
    'The load password'. It is deliberately not committed."
[[ "$LOAD_PASSWORD_HASH" == \$2[aby]\$* ]] || die \
    "LOAD_PASSWORD_HASH does not look like a bcrypt hash (expected a \$2a\$/\$2b\$/\$2y\$ prefix).
    A wrong hash generates a whole pool of accounts that cannot log in, which is only discovered
    fifteen minutes into token minting."

# The shape check above cannot see whether the hash is the hash OF THE PASSWORD the k6
# side will send. Where the plaintext and a bcrypt implementation are both to hand, prove
# the pair here — before 750 000 rows, rather than after them.
verify_password_hash_pair

require_flyway_version
require_named_target "generate the load fixture"
require_free_disk "$LOAD_MIN_FREE_MB" "$LOAD_DISK_PATH"

# Refuse to generate on top of an existing fixture. Two fixtures in one database would
# double the row counts, break the "identical row counts from the same seed" property, and
# make the size measurement meaningless — and it is an easy mistake, because generating
# twice looks exactly like generating once until the numbers are read.
#
# Matched on the BASE prefix, not on this run's: a fixture left behind by a previous run id
# is exactly the thing that must be found here.
EXISTING="$(psql_scalar "SELECT count(*) FROM workspaces WHERE slug LIKE '${LOAD_WS_SLUG_BASE}%'")"
[[ "$EXISTING" == "0" ]] || die \
    "$EXISTING load workspace(s) already exist (matching '${LOAD_WS_SLUG_BASE}%').
    Run fixture/teardown.sh first — with the run id they were created under if you have
    it, without it if you do not. Generating on top of an existing fixture doubles every
    row count silently."

log "pre-generation database size (record this — the fixture is the difference):"
psql_run -c "SELECT pg_size_pretty(pg_database_size(current_database())) AS before;"

report_transaction_horizon

log "generating (one transaction; this is the long step)"
psql_file "$HERE/10-generate.sql" \
    -v seed="$LOAD_SEED" \
    -v scale="$LOAD_SCALE" \
    -v pwhash="$LOAD_PASSWORD_HASH" \
    -v slug_a="$SLUG_A" \
    -v slug_b="$SLUG_B"

log "resyncing issue_seq, analysing, and measuring"
psql_file "$HERE/20-resync.sql" \
    -v slug_prefix="$LOAD_WS_SLUG_PREFIX" \
    -v email_domain="$LOAD_EMAIL_DOMAIN"

log "generation complete (run id $LOAD_RUN_ID)"
cat >&2 <<EOF

  RECORD LOAD_RUN_ID=$LOAD_RUN_ID in RESULTS-<date>.md and in \$LOAD_CONFIG on BOTH
  machines. It is part of the workspace slugs, so it is how teardown, the completeness
  check and the k6 mixes find this fixture.

  NEXT, IN THIS ORDER (§4.2, §5.1):

    1. fixture/verify-api.sh   — prove the API can read what SQL just wrote. A fixture the
                                 application cannot read is not a fixture, and this is the
                                 check that catches a generator which drifted from the
                                 schema in a way Flyway's version does not reveal.
    2. Let it settle overnight — autovacuum quiet, planner statistics warm. A run against
                                 a freshly bulk-loaded table measures a cold planner, which
                                 is a real phenomenon and not the one being asked about.
    3. k6/mint-tokens.js       — on the morning of the window, not now: access tokens live
                                 30 minutes.

  IF THE WINDOW IS ABANDONED AFTER THIS POINT, the accounts still exist. Run
  fixture/revoke.sh (first) and then fixture/teardown.sh. Nothing else revokes them —
  not terminating the generator, which only destroys the client's copy of the tokens.

EOF
