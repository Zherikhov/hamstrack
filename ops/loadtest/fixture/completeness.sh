#!/usr/bin/env bash
# HD-186 — run the teardown completeness assertion and turn it into an exit code.
# Spec: §5.5.3, acceptance criterion 11.
#
#   ops/loadtest/fixture/completeness.sh [--quiet-header]
#
# Exit 0  — nothing in the database is attributable to the load fixture.
# Exit 1  — something is; the offending (category, table, column, count) rows are printed.
#
# Run it on its own at any time. Before teardown it MUST report a large number: that is
# the tripwire, and a check that has never been seen to fail has not been seen to work.
#
# ---------------------------------------------------------------------------
# THE TWO RUNS ARE NOT THE SAME CHECK, AND ONLY ONE OF THEM LOOKS AT EVERYTHING.
#
# Three of the five categories resolve through the load WORKSPACE ids. A successful teardown
# deletes those workspaces, so on the run that matters most — the one after the teardown —
# the workspace handle set is EMPTY and categories 1, 2 and 3 evaluate
# `column IN (empty set)` = false for every row of every table. They report nothing because
# they cannot report anything, not because they looked and found nothing.
#
# What is actually holding then is the SCHEMA's foreign keys — but NOT in the way this
# comment used to claim. It said PostgreSQL "would have REFUSED to delete a workspace that
# still had children". It would not: nineteen of the twenty single-column foreign keys into
# workspaces(id) are ON DELETE CASCADE, and the one that is not (issues.workspace_id, NO
# ACTION) is reached first by the teardown's own ordered deletes. So the workspace delete
# CASCADES; it does not refuse.
#
# The conclusion survives, by a different route: cascade or refusal, a workspace row cannot
# outlive its children and children cannot outlive it, so "the workspaces are gone" still
# implies "their workspace-scoped rows are gone". That is the database's argument rather
# than this script's — which is the point the paragraph is making, and it is worth making
# accurately, because a reader who checks the claim and finds CASCADE has no way to tell
# which half of the sentence was wrong.
#
# The categories that still LOOK after a teardown are 4 (every column referencing users(id))
# and 5 (every recipient-shaped column at the address domain) — both keyed on handles the
# teardown deletes LAST, and the reason a post-teardown run is more than a formality.
#
# The closing message below says which is which. It used to say "no rows in any
# workspace-scoped table", which reads as a search that happened.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
. "$HERE/lib.sh"

QUIET=0
[[ "${1:-}" == "--quiet-header" ]] && QUIET=1

# psql_file, not `psql_run -f`: in docker mode psql runs INSIDE the container, where this
# repository does not exist, and `-f` would fail with a bare "No such file or directory"
# that reads like a missing script rather than a wrong execution context.
OUT="$(psql_file "$HERE/completeness.sql" \
        -v slug_prefix="$LOAD_WS_SLUG_PREFIX" \
        -v email_domain="$LOAD_EMAIL_DOMAIN")"

# The SQL prints the human table first and the single total LAST, unaligned and untitled,
# so this is exact rather than a parse.
TOTAL="$(printf '%s\n' "$OUT" | tail -n 1 | tr -d '[:space:]')"

[[ "$QUIET" == 1 ]] || printf '%s\n' "$OUT"

case "$TOTAL" in
    ''|*[!0-9]*)
        die "could not read a row total from completeness.sql (last line: '$TOTAL').
        Treat this as a FAILED assertion, not as a clean database: a completeness check
        whose result cannot be read has told you nothing." ;;
esac

if [[ "$TOTAL" -gt 0 ]]; then
    log "COMPLETENESS: $TOTAL row(s) still attributable to the load fixture"
    exit 1
fi

# Says what was looked at, in the terms the SQL defines it, so a reader can tell a clean
# database from a check that stopped looking. It is a claim about POSTGRESQL only —
# attachment blobs live in FileStorage and nothing here can see one (completeness.sql's
# header, and verify-api.sh, which deletes the objects it uploads through the API).
WS_HANDLES="$(psql_scalar "SELECT count(*) FROM workspaces
                            WHERE slug LIKE '${LOAD_WS_SLUG_PREFIX}%'" 2>/dev/null || echo '?')"
log "COMPLETENESS: clean — nothing found by any of the five categories."
if [[ "$WS_HANDLES" == "0" ]]; then
    log "              READ THIS BEFORE QUOTING IT. No workspace matches the load prefix any"
    log "              more, so categories 1-3 (workspace-scoped, foreign-key reachable, and"
    log "              issue_id) resolved against an EMPTY handle set and could not have"
    log "              reported anything. Their silence is not a search. What the two halves"
    log "              actually mean here:"
    log "                * workspace-scoped rows are gone because they CANNOT OUTLIVE the"
    log "                  workspace row: nineteen of the twenty foreign keys into"
    log "                  workspaces(id) cascade on delete and the twentieth is deleted"
    log "                  first by teardown.sql's own ordering. That is the schema's"
    log "                  guarantee, not this check's."
    log "                * categories 4 (users-referencing columns) and 5 (recipient columns"
    log "                  at @${LOAD_EMAIL_DOMAIN}) DID look: their handles are the account"
    log "                  rows and the address, which the teardown deletes last."
else
    log "              All five categories looked: $WS_HANDLES workspace handle(s) still"
    log "              exist, so the workspace-scoped, foreign-key-reachable and issue_id"
    log "              categories were evaluated against a non-empty set."
fi
log "              Database only; object storage is not visible from here."
exit 0
