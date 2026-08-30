#!/usr/bin/env bash
# HD-186 — rehearse generation and teardown on a DISPOSABLE database.
# Spec: §5.1 precondition 8, acceptance criterion 12.
#
#   LOAD_PSQL_MODE=dsn LOAD_DB_DSN=postgresql://…/hamstrack_hd186 \
#   LOAD_CONFIRM=$(date -u +%Y-%m-%d) LOAD_RUN_ID=rehearse1 \
#     ops/loadtest/fixture/rehearse.sh
#
# "A teardown first attempted on production is not a teardown." This runs the whole cycle
# against a scratch database at a small LOAD_SCALE, and — the part that matters — it proves
# the completeness assertion can FAIL. A check that has only ever returned "clean" has not
# been shown to work; it has been shown to run.
#
# Six phases:
#   1. generate at the forced rehearsal scale (the §4.2 SHAPE at 1% of the volume)
#   2. tripwire A: completeness must report a LARGE number on the populated database
#   3. tripwire B: delete ONE table's rows by hand, then assert completeness still fails
#      and NAMES a table reached only through issue_id
#   4. tripwire C: the same for a table reached only through scope_workspace_id and one
#      reached only through its parent — the two spellings the check used to be blind to
#   5. tripwire D: a table reached only at fixpoint ROUND 2 — the loop's second iteration,
#      which nothing had ever exercised
#   6. full teardown, then completeness must report ZERO
#
# The shape is held constant and only the volume scales, because the teardown is a
# function of the shape: it deletes by tenancy in foreign-key order, and 400 issues
# exercise exactly the same constraints as 40 000.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
. "$HERE/lib.sh"

# FORCED, not defaulted. `: "${LOAD_SCALE:=0.01}"` reads as a rehearsal default and is not
# one: the runbook has the operator source a configuration that sets LOAD_SCALE=1.0, and a
# default loses to it silently. A rehearsal generates AND DELETES, so the one number that
# must not be inherited from the window's configuration is how much it generates.
LOAD_SCALE="${LOAD_REHEARSAL_SCALE:-0.01}"
export LOAD_SCALE
log "rehearsal scale forced to $LOAD_SCALE (override with LOAD_REHEARSAL_SCALE, not LOAD_SCALE)"

# THE GUARD IS ABOUT THE DATABASE, NOT ABOUT THE CONNECTION.
#
# What used to be here tested the shape of LOAD_DB_DSN and let an empty DSN through
# whenever LOAD_PSQL_MODE=docker — which is the mode production's own configuration sets.
# So "the rehearsal" passed its never-against-production guard ON PRODUCTION, generated a
# full-scale fixture there, deleted it and VACUUMed, outside any window.
#
# require_disposable_database asks the database instead: a rehearsal target holds no
# account that is not this fixture's. That cannot be satisfied by exporting a variable.
require_named_target "rehearse generation and teardown"
require_disposable_database

# The target guard has now been satisfied by a human for this invocation, and the database
# has been shown to hold nobody. generate.sh and teardown.sh re-run both checks; exporting
# the answer here is what lets them, and it is safe precisely because the stricter check
# above has already run against the same connection.
export LOAD_TARGET="${LOAD_TARGET}"

# ---------------------------------------------------------------------------
# EVERY TRIPWIRE ASSERTS ON (CATEGORY, TABLE, COLUMN). A TABLE NAME PROVES NOTHING.
#
# completeness.sql reports one row per offending (category, table, column). Tripwires B and
# D used to grep for their witness's TABLE NAME — and both witnesses carry a single-column
# foreign key into users(id) (issue_comments.author_id, comment_mentions.user_id), which
# CATEGORY 4 counts from pg_constraint with no help from the fixpoint and no reference to
# any workspace. So the name appeared in the report whether or not the mechanism under test
# ran at all. Demonstrated: with the fixpoint's loop cut to a single pass,
# `reachable | comment_mentions | comment_id` vanishes and tripwire D stays green, because
# `user | comment_mentions | user_id` is still there. With EVERY reach to issue_comments
# removed — the fixpoint disabled and category 3 disabled — tripwire B stays green the same
# way, on `user | issue_comments | author_id`.
#
# A tripwire that cannot fail is worse than no tripwire: it is a claim in the run record
# that a mechanism was exercised. So the assertion names the COLUMN through which the
# witness is supposed to be reached, and the CATEGORY that reach belongs to — the two fields
# only the mechanism under test can produce.
assert_offence() {          # <report> <category-regex> <table> <column-regex> <why…>
    local report="$1" cat_re="$2" tbl="$3" col_re="$4"; shift 4
    # psql's aligned output: " reachable | comment_mentions | comment_id   |        1"
    if printf '%s\n' "$report" \
        | grep -qE "^ *(${cat_re}) +\\| +${tbl} +\\| +(${col_re}) +\\|"; then
        return 0
    fi
    printf '%s\n' "$report" >&2
    die "$*

    Looked for a row  '${cat_re} | ${tbl} | ${col_re}'  and did not find one.
    THE TABLE NAME ALONE IS NOT THE ASSERTION and must never be made into one: both of
    this file's fixpoint witnesses also carry a foreign key into users(id), which
    completeness.sql's category 4 counts unconditionally, so grepping for '${tbl}' would
    pass against an implementation in which the mechanism under test does not run."
}

log "=== REHEARSAL: generate at scale $LOAD_SCALE, then prove the teardown ==="

# ---- phase 1 ---------------------------------------------------------------
log "phase 1/6: generate"
"$HERE/generate.sh"

# ---- phase 2: tripwire A ---------------------------------------------------
log "phase 2/6: tripwire A — completeness MUST report a populated database"
if "$HERE/completeness.sh" >/dev/null 2>&1; then
    die "TRIPWIRE A FAILED: the completeness assertion reported a CLEAN database
    immediately after generating a fixture into it. The check is blind, and every
    'teardown verified' it has ever printed is worthless. Fix completeness.sql before
    trusting any teardown."
fi
log "  tripwire A ok — the check sees the fixture"

# ---- phase 3: tripwire B ---------------------------------------------------
# Delete one table's rows by hand and confirm the assertion still fails AND names another.
# This is the difference between "the check notices a whole fixture" and "the check notices
# a single table's worth of leftovers", which is the case an incomplete teardown produces.
#
# issue_comments is the witness deliberately: it carries NO workspace_id, so it is
# invisible to the proposal's own category and is seen only because this file's categories
# reach it through issues. If this phase ever starts passing, that reach has been lost.
log "phase 3/6: tripwire B — a partial teardown must still be caught, and named"
psql_run -q -c "
    DELETE FROM issue_history WHERE issue_id IN
      (SELECT i.id FROM issues i JOIN workspaces w ON w.id = i.workspace_id
        WHERE w.slug LIKE '${LOAD_WS_SLUG_PREFIX}%');" >/dev/null

REMAIN="$("$HERE/completeness.sh" 2>&1 || true)"
# category alternation, not a single value: (issue_comments, issue_id) is produced BOTH by
# the fixpoint (category 2 -> 'reachable') and by the issue_id-by-name scan (category 3 ->
# 'issue'), and the final report keeps one row per (table, column) with 'issue' winning the
# tie. Either mechanism satisfies what this tripwire claims — a reach THROUGH ISSUES — and
# neither is category 4, which is the one that made the old grep vacuous.
assert_offence "$REMAIN" 'issue|reachable' issue_comments issue_id \
    "TRIPWIRE B FAILED: after deleting only issue_history, the completeness assertion no
    longer reports issue_comments through its ISSUE reach. issue_comments carries no
    workspace_id, so it is exactly the table the proposal's own category would have missed —
    if that reach is gone, a teardown that leaves a quarter of a million comment rows behind
    will be reported as complete."
log "  tripwire B ok — a partial teardown is caught and the leftover table is named"

# ---- phase 4: tripwire C ---------------------------------------------------
# THE CATEGORY NOBODY HAD EVER SEEN FIRE.
#
# Tripwires A and B both exercise the reach through `issues`. Nothing exercised the
# workspace category itself, and that category was defined by an EXACT STRING —
# column_name = 'workspace_id' — over a schema that spells workspace tenancy two ways.
# Every taxonomy and custom-field table uses scope_workspace_id, so fifteen base tables
# were invisible to a check whose own header says a category never seen to fire has not
# been shown to work. Three of them the fixture writes.
#
# Two witnesses, because two different mechanisms are being tested:
#   field_sets       reached ONLY by the scope_workspace_id spelling
#   field_set_items  reached ONLY through its parent (it has no tenancy column at all)
log "phase 4/6: tripwire C — the workspace category and the reach through a scoped parent"

REMAIN="$("$HERE/completeness.sh" 2>&1 || true)"
# Neither witness has a foreign key into users(id) today, so unlike B and D these two were
# never vacuous. They are still asserted by column, because "has no users FK" is a property
# of the schema on the day this was written and a later `created_by` would make the table
# name start matching for a reason that has nothing to do with taxonomy tenancy.
assert_offence "$REMAIN" 'workspace' field_sets scope_workspace_id \
    "TRIPWIRE C FAILED: the completeness assertion does not report field_sets through
    scope_workspace_id on a database that holds the fixture. That is the SECOND spelling of
    workspace tenancy, and a check blind to it is blind to the whole taxonomy and
    custom-field family — fifteen base tables the teardown names and the verifier cannot
    see. The disagreement is the defect, whether or not any row is left behind today."
assert_offence "$REMAIN" 'reachable' field_set_items 'set_id|field_id' \
    "TRIPWIRE C FAILED: the completeness assertion does not report field_set_items through
    a foreign key on a database that holds the fixture. It carries no tenancy column at all
    and is reachable ONLY through a scoped parent, so this is the pure-child case: if it is
    gone, every table that hangs off the taxonomy is invisible to the verifier."

psql_run -q -c "
    DELETE FROM field_set_items WHERE set_id IN
      (SELECT s.id FROM field_sets s JOIN workspaces w ON w.id = s.scope_workspace_id
        WHERE w.slug LIKE '${LOAD_WS_SLUG_PREFIX}%');" >/dev/null

REMAIN="$("$HERE/completeness.sh" 2>&1 || true)"
assert_offence "$REMAIN" 'workspace' field_sets scope_workspace_id \
    "TRIPWIRE C FAILED: after deleting only field_set_items, the completeness assertion no
    longer reports field_sets through scope_workspace_id. The workspace category has stopped
    seeing that spelling, and a teardown that leaves the taxonomy behind will be reported as
    complete."
log "  tripwire C ok — both spellings of workspace tenancy are seen, and so is a pure child"

# ---- phase 5: tripwire D ---------------------------------------------------
# THE LOOP HAD NEVER BEEN SHOWN TO ITERATE.
#
# completeness.sql's category 2 is a FIXPOINT — a loop that follows foreign keys until a
# round adds nothing. Tripwire B's witness (issue_comments) is reached at round 1, and so is
# tripwire C's (field_set_items). So every existing tripwire passes against an
# implementation that runs the body ONCE and stops: a loop reduced to a single pass leaves
# all three green, and "the fixpoint works" would rest on nobody having tried.
#
# comment_mentions is the witness because it is two edges out and it is not reachable any
# other way: it carries no workspace_id, no scope_workspace_id and no issue_id, and there is
# no foreign key from it to anything reached at depth 0 or 1 except issue_comments. If this
# phase starts passing, the fixpoint has stopped iterating.
log "phase 5/6: tripwire D — the fixpoint's SECOND round"

REMAIN="$("$HERE/completeness.sh" 2>&1 || true)"
# comment_id, not comment_mentions. The table name is ALSO produced by category 4 through
# comment_mentions.user_id -> users(id), so the old grep passed against a fixpoint reduced
# to a single pass — verified by cutting the loop to one round and watching this tripwire
# stay green while 'reachable | comment_mentions | comment_id' disappeared from the report.
assert_offence "$REMAIN" 'reachable' comment_mentions comment_id \
    "TRIPWIRE D FAILED: the completeness assertion does not report comment_mentions through
    comment_id on a database that holds the fixture. That row is produced ONLY at the
    fixpoint's second round (workspaces -> issues -> issue_comments -> comment_mentions):
    the table has no workspace_id, no scope_workspace_id and no issue_id. A check that
    misses it has stopped iterating, which every other tripwire here would still call green,
    because each of their witnesses is reached on the FIRST pass."

psql_run -q -c "
    DELETE FROM issue_comments WHERE issue_id IN
      (SELECT i.id FROM issues i JOIN workspaces w ON w.id = i.workspace_id
        WHERE w.slug LIKE '${LOAD_WS_SLUG_PREFIX}%'
          AND NOT EXISTS (SELECT 1 FROM comment_mentions m
                           WHERE m.comment_id IN (SELECT id FROM issue_comments c
                                                   WHERE c.issue_id = i.id)));" >/dev/null

REMAIN="$("$HERE/completeness.sh" 2>&1 || true)"
assert_offence "$REMAIN" 'reachable' comment_mentions comment_id \
    "TRIPWIRE D FAILED: after deleting the comment rows that carry no mentions, the
    assertion no longer reports comment_mentions through comment_id. Its reach through TWO
    foreign keys has been lost, and every table that hangs two levels off a tenant root is
    now invisible to the check that is supposed to prove the teardown worked."
log "  tripwire D ok — the fixpoint iterates, and a depth-2 child is seen"

# ---- phase 6 ---------------------------------------------------------------
log "phase 6/6: full teardown, then completeness must be clean"
"$HERE/teardown.sh"

log ""
log "=== REHEARSAL PASSED ==="
log "Record this in the run record: §5.1 precondition 8 is satisfied, with the date."
log "It does NOT satisfy precondition 9 — the 1-VU dry run against production, including"
log "the operator actually stopping a run with the documented command and seeing it stop."
