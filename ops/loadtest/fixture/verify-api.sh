#!/usr/bin/env bash
# HD-186 load fixture — verification through the REAL API.
# Spec: §4.2 generation guard 3, acceptance criteria 9 and 10.
#
#   ops/loadtest/fixture/verify-api.sh
#
# The fixture is written by SQL, which can produce states the application never produces.
# Flyway's version guard catches a schema this generator was not written for; it cannot
# catch a generator that drifted from the ENTITIES in a way the schema still accepts —
# a status whose category is unreadable, a JSONB custom-field value in the wrong shape, a
# sprint the planning view refuses to assemble. Only the application can say that, so this
# asks it.
#
# A fixture the API cannot read is not a fixture. Every check below asserts an HTTP status
# AND something about the shape of the body, because a 200 carrying an empty list is how a
# broken fixture passes a status-only check.
#
# Uses only the published API (§7). It adds no endpoint and needs none.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
. "$HERE/lib.sh"

# BASE_URL is what the configuration and the README both call this, and what k6 reads.
# LOAD_BASE_URL was required here and appears in no configuration file and nowhere in the
# README, so the documented sequence hard-failed at the one step whose failure postpones
# the window. Either name works; neither is invented at the call site.
: "${LOAD_BASE_URL:=${BASE_URL:-}}"
[[ -n "$LOAD_BASE_URL" ]] || die \
    "set BASE_URL (or LOAD_BASE_URL), e.g. http://localhost:8080 or http://10.0.0.5:8080.
    Point it at the ORIGIN and set HOST_HEADER to the public hostname — see
    config.env.example."
: "${LOAD_CURL_OPTS:=}"

command -v jq >/dev/null || die "jq is required (every assertion here is about a body's shape)"

# THE PLAINTEXT IS NOT KEPT ON THIS MACHINE.
#
# The box needs only LOAD_PASSWORD_HASH — the generator writes accounts, it does not log
# in. This script is the one exception, so it asks for the password for the duration of one
# command rather than reading it from a file on disk. Prompted where there is a terminal;
# taken from the environment where there is not (CI, a rehearsal), which is still not argv.
if [[ -z "${LOAD_PASSWORD:-}" ]]; then
    if [[ -t 0 ]]; then
        read -rsp "load password (not echoed): " LOAD_PASSWORD; echo >&2
    else
        die "LOAD_PASSWORD is unset and there is no terminal to ask on. Export it for this
        command only. It is deliberately not stored on the box: the generator needs the
        bcrypt hash, and this script needs the plaintext for one login."
    fi
fi
require_strong_password "$LOAD_PASSWORD"
export LOAD_PASSWORD

FAILS=0
pass() { printf '  \033[32mok\033[0m   %s\n' "$*" >&2; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2; FAILS=$((FAILS + 1)); }

# The bearer token goes in a curl CONFIG FILE at mode 0600, not in `-H` on ~30 command
# lines. An argument list is world-readable through /proc on the box this runs on, and this
# box is production. Same reason the password reaches jq through the environment below
# instead of through --arg.
AUTHRC="$(mktemp)"; chmod 600 "$AUTHRC"
HOSTRC="$(mktemp)"; chmod 600 "$HOSTRC"
trap 'rm -f "$AUTHRC" "$HOSTRC" "${TMP:-}"' EXIT

# HOST_HEADER, on every request. The configuration mandates pointing at the ORIGIN IP with
# the public hostname in the Host header; this was the one HTTP client in the harness that
# never sent it, so it exercised a routing path no measured request takes.
[[ -z "${HOST_HEADER:-}" ]] || printf 'header = "Host: %s"\n' "$HOST_HEADER" > "$HOSTRC"

# shellcheck disable=SC2086
curl_api() { curl -sS -K "$HOSTRC" $LOAD_CURL_OPTS "$@"; }
# shellcheck disable=SC2086
curl_auth() { curl -sS -K "$HOSTRC" -K "$AUTHRC" $LOAD_CURL_OPTS "$@"; }

# --- log in as the first load account of workspace A -------------------------
#
# THE BODY IS PIPED, NOT SUBSTITUTED, AND THAT IS THE WHOLE POINT OF THE TWENTY LINES ABOVE.
#
# jq reads the password from the ENVIRONMENT rather than from --arg, so it never appears in
# jq's argument list. But `-d "$(jq …)"` then expanded the finished JSON — plaintext and all
# — into CURL's argument list, and /proc/<pid>/cmdline is world-readable on the box this
# runs on, which is production. The threat named at line 60 was committed twenty lines
# later, by the command that was supposed to be avoiding it.
#
# --data-binary @- takes the body on stdin. Neither process ever holds the secret in argv.
log "authenticating as load-a-001@load.invalid"
AUTH="$(jq -nc '{email:"load-a-001@load.invalid", password:env.LOAD_PASSWORD}' \
        | curl_api -X POST "$LOAD_BASE_URL/api/auth/login" \
                   -H 'Content-Type: application/json' --data-binary @-)"
TOKEN="$(printf '%s' "$AUTH" | jq -r '.accessToken // empty')"
[[ -n "$TOKEN" ]] || die "login failed. Response: $AUTH
    If this is a 401, LOAD_PASSWORD does not match LOAD_PASSWORD_HASH.
    If this is a 429, the per-IP auth budget (RATE_LIMIT_AUTH_IP_PER_MINUTE, 15/min) is
    already spent from this address — wait a minute. Do NOT raise the limiter: it is part
    of the configuration under measurement (§3.2)."
printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" > "$AUTHRC"

# `req <name> <expected-status> <jq-assertion> <path>` — the whole verification vocabulary.
req() {
    local name="$1" want="$2" assert="$3" path="$4"; shift 4
    local body code
    body="$(curl_auth -w '\n%{http_code}' "$LOAD_BASE_URL$path" "$@")" \
        || { fail "$name (curl failed)"; return; }
    code="$(printf '%s' "$body" | tail -n 1)"
    body="$(printf '%s' "$body" | sed '$d')"
    if [[ "$code" != "$want" ]]; then
        fail "$name — HTTP $code (want $want): $(printf '%s' "$body" | head -c 300)"
        return
    fi
    if [[ -n "$assert" ]] && ! printf '%s' "$body" | jq -e "$assert" >/dev/null 2>&1; then
        fail "$name — HTTP $want but the body failed [$assert]: $(printf '%s' "$body" | head -c 300)"
        return
    fi
    pass "$name"
}

# --- resolve the fixture's ids through the API, not through SQL --------------
# Deliberate: if the API cannot even enumerate the fixture, everything after this is moot,
# and resolving ids by SQL here would hide exactly that failure.
# Matched on the BASE prefix and a trailing `a`, like lib/fixture.js: the fixture's slugs
# carry a random run id, and this script may be run in a shell that does not have it. Two
# matches is an error rather than a coin flip.
WS_A="$(curl_auth "$LOAD_BASE_URL/api/workspaces" \
        | jq -r --arg p "$LOAD_WS_SLUG_BASE" \
          '[.[] | select(.slug | test("^" + $p + "([a-z0-9]+-)?a$"))] | .[0].id // empty')"
[[ -n "$WS_A" ]] || die "no workspace matching ${LOAD_WS_SLUG_BASE}[<run-id>-]a is visible to
    load-a-001 through GET /api/workspaces. Either the fixture was not generated, or its
    membership rows are wrong."

PROJECTS="$(curl_auth \
            "$LOAD_BASE_URL/api/workspaces/$WS_A/projects")"
PROJ_BIG="$(printf '%s' "$PROJECTS" | jq -r '.[] | select(.key == "LDA") | .id')"
[[ -n "$PROJ_BIG" ]] || die "the large project (key LDA) is not visible through GET .../projects"

log "workspace A = $WS_A, large project = $PROJ_BIG"

B="/api/workspaces/$WS_A/projects/$PROJ_BIG"

# --- browse surface ----------------------------------------------------------
log "browse"
req "board list is capped and says so" 200 \
    '.issues | length > 0' "$B/issues"
req "board reports truncation against BOARD_MAX_ISSUES" 200 \
    '.truncated == true and .totalAvailable > .cap' "$B/issues"
req "project config resolves the bound taxonomy" 200 \
    '(.statuses | length) >= 3 and (.priorities | length) >= 3' "$B/config"
req "backlog section returns ranked issues" 200 \
    '.issues | length > 0' "$B/backlog/sections/backlog"
req "component catalog" 200 '. | length > 0' "$B/components"
req "version catalog" 200 '. | length > 0' "$B/versions"
req "sprint list carries the open sprints" 200 \
    '[.[] | select(.state == "FUTURE" or .state == "ACTIVE")] | length >= 5' "$B/sprints"
req "label catalog (workspace-scoped)" 200 '. | length > 50' \
    "/api/workspaces/$WS_A/labels"

# One issue, fully — the browsing mix's heaviest single read. Number 1 exists in every
# project by construction (numbers are 1..N).
req "issue detail" 200 '.number == 1 and (.title | length) > 0' "$B/issues/1"
req "issue comments"   200 'type == "object" or type == "array"' "$B/issues/1/comments"
req "issue history"    200 'type == "object" or type == "array"' "$B/issues/1/history"
req "issue attachments" 200 'type == "array"' "$B/issues/1/attachments"

# History must be populated SOMEWHERE, or the flow report is measuring an empty table while
# returning 200. Issue 1 may legitimately have few rows; the assertion is about the class.
HIST_TOTAL=0
for n in 1 2 3 5 8 13; do
    c="$(curl_auth "$B/issues/$n/history" \
         | jq -r '(if type=="array" then length else (.content | length) end) // 0' 2>/dev/null || echo 0)"
    HIST_TOTAL=$((HIST_TOTAL + ${c:-0}))
done
if [[ "$HIST_TOTAL" -gt 0 ]]; then pass "issue history is populated ($HIST_TOTAL rows across 6 issues)"
else fail "issue history is EMPTY across six issues — the flow report will return 200 and measure nothing"; fi

# --- search surface ----------------------------------------------------------
log "search"
req "search schema resolves the whole workspace catalog" 200 \
    '(.fields | length) > 0' "/api/workspaces/$WS_A/search/schema"
req "search suggest" 200 'type == "object"' \
    "/api/workspaces/$WS_A/search/suggest?field=status"
req "search: unfiltered page" 200 '.totalElements > 1000' \
    "/api/workspaces/$WS_A/search" \
    -X POST -H 'Content-Type: application/json' -d '{"query":"","page":0,"size":50}'
req "search: a text ~ leaf matches SOMETHING" 200 '.totalElements > 0' \
    "/api/workspaces/$WS_A/search" \
    -X POST -H 'Content-Type: application/json' \
    -d '{"query":"text ~ \"checkout\"","page":0,"size":50}'

# --- reports -----------------------------------------------------------------
# Each report at its DEFAULT window, which is what the mixes request and what the sizing
# guidance will be about.
log "reports"
req "flow"          200 'type == "object"' "$B/reports/flow"
req "cycle-time"    200 'type == "object"' "$B/reports/cycle-time"
req "aging"         200 'type == "object"' "$B/reports/aging"
req "velocity"      200 'type == "object"' "$B/reports/velocity"
req "sprint-burnup" 200 'type == "object"' "$B/reports/sprint-burnup"
req "sprint-review" 200 'type == "object"' "$B/reports/sprint-review"
req "flow.csv"      200 ''                 "$B/reports/flow.csv"

# Acceptance criterion 10: the cap must actually bind. A row-level report against the
# 25 000-issue project must set meta.truncated, because the capped case is the expensive
# case and the one the heap costing is about. If this fails, the fixture is too small and
# probe P2 has nothing to measure.
CT="$(curl_auth "$B/reports/cycle-time?from=1970-01-01")"
if printf '%s' "$CT" | jq -e '.meta.truncated == true' >/dev/null 2>&1; then
    pass "a row-level report hits REPORTS_MAX_ROWS and sets meta.truncated"
else
    fail "NO report reached REPORTS_MAX_ROWS. The largest project is under the cap (or the
       window excludes its rows). Probe P2 measures nothing in this state — regenerate at
       a larger LOAD_SCALE. meta was: $(printf '%s' "$CT" | jq -c '.meta // "absent"')"
fi

# --- one real attachment, through the real storage backend -------------------
# §4.2 asks for "a handful with real bytes". The generator writes metadata only, on
# purpose: blobs are a disk experiment we do not need to run, and attachments are the one
# place DC and Cloud differ (local FS vs S3). Uploading a few HERE means the difference is
# exercised once, through FileStorage, against objects that actually exist — rather than
# turning a storage-backend difference into a difference in what the run measures (§9).
#
# AND THEY ARE DELETED AGAIN, HERE, THROUGH THE API. teardown.sql removes the
# issue_attachments ROWS and completeness.sql then reports a clean database — but neither
# can see FileStorage, so three objects would stay in the bucket (or on the disk) after a
# teardown that printed "zero rows attributable to the fixture". The row and the blob are
# only both known to the APPLICATION, so the application is what removes them: DELETE
# .../attachments/{id} goes through AttachmentService and takes both halves.
log "attachments (a handful of real bytes, through the real storage backend)"
TMP="$(mktemp)"
head -c 65536 /dev/urandom > "$TMP"
UPLOADED=()
for n in 1 2 3; do
    body="$(curl_auth -w '\n%{http_code}' -X POST \
            -F "file=@$TMP;filename=load-probe-$n.bin" "$B/issues/$n/attachments")"
    code="$(printf '%s' "$body" | tail -n 1)"
    body="$(printf '%s' "$body" | sed '$d')"
    if [[ "$code" == "200" || "$code" == "201" ]]; then
        pass "uploaded a real attachment to issue $n"
        id="$(printf '%s' "$body" | jq -r '.id // empty')"
        [[ -z "$id" ]] || UPLOADED+=("$n:$id")
    else
        fail "attachment upload to issue $n returned HTTP $code"
    fi
done

for u in "${UPLOADED[@]:-}"; do
    [[ -n "$u" ]] || continue
    n="${u%%:*}"; id="${u##*:}"
    code="$(curl_auth -o /dev/null -w '%{http_code}' -X DELETE "$B/issues/$n/attachments/$id")"
    if [[ "$code" == "200" || "$code" == "204" ]]; then pass "deleted the probe attachment on issue $n (row AND object)"
    else fail "could not delete the probe attachment on issue $n (HTTP $code) — a blob will
       survive the teardown, and completeness.sql cannot see it. Delete it by hand and
       record the storage key."; fi
done
if [[ "${#UPLOADED[@]}" -eq 0 ]]; then
    fail "no attachment id came back from an upload, so nothing could be deleted through the
       API. Three objects are now in storage that the teardown will not remove."
fi

# --- the tenancy canary's premise -------------------------------------------
# The canary asserts 404 during the run. Here we assert it BEFORE the run, because a canary
# that was always going to pass proves nothing, and one whose premise is wrong (say, both
# fixtures ended up in one workspace) would silently pass for the wrong reason.
log "tenancy"
WS_B_ID="$(psql_scalar "SELECT id FROM workspaces WHERE slug LIKE '${LOAD_WS_SLUG_BASE}%'
                          AND slug LIKE '%b' ORDER BY slug LIMIT 1")"
[[ -n "$WS_B_ID" ]] || die "no workspace ${LOAD_WS_SLUG_BASE}[<run-id>-]b exists; the canary has no target"
req "workspace A member reading workspace B gets 404, not 403" 404 '' \
    "/api/workspaces/$WS_B_ID/projects"

# The other half of the same rule, and the half a single 404 cannot establish: a workspace
# that does NOT EXIST must answer identically to one that exists and is not ours. If these
# two differ in status or in body, the difference is what tells an outsider that B exists —
# which is the leak, whatever the status code is. The canary asserts this every ten seconds
# during the run (lib/canary.js); asserting it here means a fixture that could never have
# proved it is caught the day before rather than in the results.
GHOST="00000000-0000-4000-8000-000000000000"
REAL_B="$(curl_auth -w '\n%{http_code}' "$LOAD_BASE_URL/api/workspaces/$WS_B_ID/projects")"
ABSENT="$(curl_auth -w '\n%{http_code}' "$LOAD_BASE_URL/api/workspaces/$GHOST/projects")"
if [[ "$REAL_B" == "$ABSENT" ]]; then
    pass "non-membership and non-existence are indistinguishable (identical status and body)"
else
    fail "a workspace that EXISTS but is not ours answered differently from one that does not
       exist. That difference IS the information leak, whatever the status codes are.
       foreign: $(printf '%s' "$REAL_B" | tr '\n' ' ' | head -c 200)
       absent:  $(printf '%s' "$ABSENT" | tr '\n' ' ' | head -c 200)"
fi

log ""
if [[ "$FAILS" -eq 0 ]]; then
    log "VERIFICATION PASSED — the API can read everything the generator wrote"
    exit 0
fi
die "VERIFICATION FAILED: $FAILS check(s) above.
    Do NOT open the window on this fixture. A fixture the API cannot read produces a
    measurement of error paths."
