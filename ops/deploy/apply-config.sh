#!/usr/bin/env bash
# Hamstrack production configuration applier (HD-199 / HD-122).
# Spec: docs/design/config-delivery-proposal.md §7
#
#   apply-config.sh <source-dir> [target-dir] [sha] [--dry-run] [--allow-pinned|--adopt-pin]
#   apply-config.sh <source-dir> [target-dir] [sha] --verify-only
#
# Places the repository-owned configuration paths listed in ops/deploy/synced-paths.txt
# into the compose project directory, then brings the stack up. It is deliberately
# PORTABLE: it takes a source directory and a target directory and contains no AWS, no
# SSM and no GitHub. Fetching is the caller's job — a codeload `curl` in
# .github/workflows/deploy.yml, a `git pull` for a self-hoster:
#
#   git pull && sudo ops/deploy/apply-config.sh . /opt/hamstrack
#
# ...and a pinned APP_IMAGE_TAG — which is what docs/self-hosting.md tells every
# self-hoster to run with — does NOT need a flag for that command, as long as the pin has
# not moved since the last apply here; see step 2b. The same recipe is written up for that
# reader, with --adopt-pin and COMPOSE_FILES, in docs/self-hosting.md under
# "Applying repository configuration".
#
# WHICH compose files it validates and runs is COMPOSE_FILES (space-separated, relative to
# the release tree; default `docker-compose.prod.yml docker-compose.observability.yml`).
# A listed file ABSENT from the release tree is skipped with a log line rather than fatal,
# and at least one must be present. A box that runs no observability stack narrows the set
# instead of being blocked at the validate step by a `${GF_SECURITY_ADMIN_PASSWORD:?…}`
# belonging to a stack it does not have:
#
#   COMPOSE_FILES=docker-compose.prod.yml sudo -E ops/deploy/apply-config.sh . /opt/hamstrack
#
# hamstrack-config-drift.sh takes the same variable, for the same reason: without it that
# box would report containers=1 for ever about a stack that is entirely healthy.
#
# The order below is the whole design, and each step exists because of a failure that has
# already happened:
#
#   1. refuse a source without the manifest, and refuse any manifest entry that escapes
#      the target or is on the never-sync list — the manifest is the thing a careless
#      edit would change, so it is not trusted;
#   2. VALIDATE the released compose files against the BOX'S REAL .env before touching
#      anything. This is HD-197's failure class closed by construction: a merged
#      `${VAR:?…}` guard the box cannot satisfy now fails the DEPLOY and names the
#      variable, instead of stopping the site;
#   2b. refuse to sync configuration onto an image pin that has MOVED. APP_IMAGE_TAG in
#      the box's .env pins WHAT RUNS, and nothing pins the configuration beside it, so an
#      unattended deploy would otherwise sync a newer tree onto the image an incident
#      deliberately held back — and, if that incident was caused by a configuration
#      change, re-apply the very thing that was rolled back. It is the pin MOVING that is
#      refused, not the pin existing: a self-hoster who pins by policy re-applies onto an
#      unchanged tag without a flag, while a tag that changed since `.deployed-image-tag`
#      (and a box with no stamp at all) stops until somebody overrides it — with
#      `--allow-pinned`, which proceeds THIS RUN and leaves the stamp alone so the next
#      unattended run refuses again, or `--adopt-pin`, which proceeds AND re-stamps
#      because this tag is now the intended one;
#   3. --dry-run stops there and prints a diff — and exits 0 even when the real run would
#      refuse, because a refusal is a state of the box the reader asked to be shown. The
#      exception is a bad INVOCATION rather than a state of the box — today an exported
#      APP_IMAGE_TAG that disagrees with .env — which is refused at 2b before the diff. So
#      a NON-ZERO --dry-run is always something about the command you typed, never a report;
#   4. back up what is about to be replaced;
#   5. apply, staged-then-renamed so no single path can be left half-written;
#   6. stamp the sha, the image tag it was applied beside, and a checksum per synced file —
#      drift detection reads the last of those;
#   7. pull and up -d with EVERY compose file (see the note at run_compose);
#  7b. restart the services whose configuration is BIND-MOUNTED, when a synced path they
#      mount has changed. `up -d` cannot see inside a bind mount, so without this the
#      container keeps the DELETED INODE of the file that was just replaced while every
#      check reports agreement;
#   8. prune images;
#   9. publish the drift metrics, so the freshest reading is always the one taken at the
#      moment of a deploy — even on a box where the hourly timer is not installed;
#  10. VERIFY — read the RUNNING box back and refuse to call the deploy complete when it
#      disagrees with what was just applied (HD-299). Every step before this one reports
#      what it DID; four incidents (HD-199, HD-221, HD-283, HD-287) were each visible to a
#      read-back nobody performed. Five checks, each over Compose's own service list:
#      memory ceilings (`HostConfig.Memory` vs the resolved `mem_limit`, HD-189's finding),
#      declared environment KEYS present on the running containers, the app's image
#      revision label and `/api/meta` version, Grafana's health/restarts/provisioning log
#      (only where the compose set declares it), and the drift gauges step 9 just wrote —
#      fresher than the moment step 9 started, or step 9 did not publish (HD-287). A red
#      verify exits 1 and ROLLS NOTHING BACK, by standing decision: the message names the
#      two things an operator can do. It also publishes hamstrack_deploy_verify_*.prom,
#      written pessimistically (all 0) at the first mutation and rewritten here, so a run
#      killed in between leaves a 0 for DeployVerifyFailed rather than last week's 1.
#      `--verify-only` runs steps 9 and 10 alone against an already-applied box — the way to
#      clear DeployVerifyFailed after a hand fix, and the way the real-daemon test drives it.
#
# Everything that can fail before step 5 leaves the box exactly as it was, and the image is
# pulled only at step 7 — so THE DEPLOY IS ALL-OR-NOTHING. Before this script existed a
# partly-broken deploy still pulled and started the new image; now a red deploy leaves
# production entirely on the one it was already running. That is the intended trade: a box
# running yesterday's image AND yesterday's configuration is a state somebody can reason
# about, and the two halves disagreeing is not.
set -euo pipefail

# NOT 077, and the difference is load-bearing. Only newly created PARENT directories take
# this (the payload keeps the release tree's own modes via `cp -a`), but observability/ is
# bind-mounted into Grafana and Prometheus, which run as non-root users inside their
# containers: a 0700 directory here is a provisioning tree they cannot read, and Grafana
# then starts with no dashboards and no alert rules while `up -d` still exits 0. Nothing
# this script places is a secret — .env is never synced, which is the point of §6.2.
umask 022

# WHATEVER THIS PRINTS IS PUBLIC. The repository is public, so GitHub Actions logs are
# world-readable, and this script's stdout and stderr travel back through SSM into one.
# deploy.yml prints the bodies only when the deploy FAILS, which narrows the audience and
# does not change the rule: log NAMES and COUNTS — a path, a service, how many files
# differed — never file CONTENTS, never a diff of a file that is not in this repository,
# and never a value read from .env. The image tag is the one value that crosses that line,
# deliberately, because a pin is the thing an operator most needs named back to them.
# A `cat` added here while debugging is a disclosure.
#
# TWO CHANNELS, AND WHICH ONE A LINE LANDS IN IS A DECISION. SSM returns only the FIRST
# 24 000 characters of stdout and the first 8 000 of stderr (measured 2026-09-10 from the
# bundled AWS service model, ssm/2014-11-06/service-2.json: `"StandardOutputContent":
# {"type":"string","max":24000}` with the documentation "The first 24,000 characters written
# by the plugin to stdout", and `"StandardErrorContent": {"type":"string","max":8000}`).
# Everything this script has to say arrives at the END of stdout — behind the pre-flight, a
# ten-service `compose pull`, `up -d`, the prune and the drift script — so a verbose middle
# TRUNCATES THE CONCLUSION and the reader is left with a log that stops in the middle,
# indistinguishable from a killed deploy. Two mechanisms keep that from happening: the noisy
# middle is quietened at its source, and the lines nobody may lose — every refusal and the
# final verify summary — go through log_both, which also writes them to stderr.
#
# WHICH QUIETING PROTECTS WHICH BUDGET, measured 2026-09-10 on Compose v5.1.0 rather than
# assumed, because the first version of this comment had it backwards. `docker compose pull`
# and `up -d` write NOTHING to stdout — all progress is on STDERR (0 bytes stdout / 349 bytes
# stderr for a two-service `up -d`); `docker image prune -f` writes to STDOUT (26 bytes).
# Step 7 then CAPTURES Compose's stderr and hands it to hold_foreign, which prints it on
# STDOUT behind "| ". So both quietings — `pull --quiet` and `prune -f >/dev/null` — defend
# the 24 000-character STDOUT budget, that is the channel a cold pull's layer count grows,
# and stderr carries the mirror. The production size of each channel is unmeasured since the
# capture moved the traffic: read both off the next deploy before quoting a figure here.
# EVERY LINE CARRIES THE STAMP, not only the first, and that is load-bearing rather than
# cosmetic. deploy.yml publishes a FAILED deploy's output into a world-readable Actions log by
# keeping only the lines this script stamped — an ALLOW-list, because a deny-list over text
# Docker, Compose and AWS wrote cannot be complete (measured: the deny-list it replaced withheld
# 1 of 13 crafted secret-bearing lines). The refusal is the one multi-line message this script
# emits, so an unstamped continuation line would arrive there as a refusal published as its first
# line with a pointer for the rest. One `date` for the whole message, so a multi-line refusal
# does not appear to span two seconds.
#
# ONE LOOP, SHARED BY log() AND log_both(), and that is why it is a function of its own. log()
# used to be a single printf: it stamped the FIRST line of a multi-line message and let the
# tail through unstamped, where the allow-list correctly reads it as text this script did not
# write and drops it. Three sentences — here, in deploy.yml and in the ops runbook — said both
# stamped every line, and no reachable multi-line log() call existed to make the difference
# visible: a hole behind a claim that it could not happen. Sharing the loop makes the claim
# true by construction rather than by there being no caller yet.
_stamp() { # $1 = 1 to mirror the message onto stderr as well, $2.. = the message
  local mirror="$1" line stamp
  shift
  stamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  while IFS= read -r line || [ -n "$line" ]; do
    printf '%s %s\n' "$stamp" "$line"
    [ "$mirror" = 0 ] || printf '%s %s\n' "$stamp" "$line" >&2
  done <<< "$*"
}
log() { _stamp 0 "$*"; }
# Mirrors ONLY when stdout is not a terminal. On a terminal both channels are the same screen,
# so the mirror prints every refusal twice — which reads as two failures to whoever ran it by
# hand, and is the shape of noise that gets a mechanism deleted. Under SSM, cron and CI stdout
# is a pipe and the mirror is exactly what is wanted.
log_both() {
  if [ -t 1 ]; then _stamp 0 "$*"; else _stamp 1 "$*"; fi
}
die() { log_both "FATAL $*"; exit 1; }

# --- FOREIGN text: the one door it goes through --------------------------------
# The header's rule is "never a value read from .env". Every line this script writes ITSELF
# obeys it by construction — it prints names and counts. The lines it does not write are the
# problem: Compose's own errors and Grafana's own log are third-party text, and MEASURED on
# Compose v5.1.0 (2026-09-10) an interpolation error quotes the substituted value back:
#
#   'services[app].mem_limit' invalid size: 'super-secret-looking-value'
#
# …where that string came straight out of the box's .env.
#
# THIS USED TO BE A REDACTING FILTER, AND THAT WAS THE WRONG SHAPE. withhold() replaced quoted
# spans and KEY=value pairs and handed the result to log(), which stamps — and deploy.yml's
# allow-list publishes anything stamped. So the deny-list this script had already declared
# incapable of completeness did not go away; it acquired publication rights. MEASURED
# 2026-09-10, 14 crafted secret-bearing lines fed through the pre-flight loop and then through
# the workflow's own filter: 13 were published, including a database password, a Resend key, a
# connection string with a password and an AWS key pair. `DB_PASSWORD = <value>` walked through
# it because the KEY=value rule does not fire on " = ".
#
# THE RULE NOW: a public channel gets the SHAPE, the box gets the TEXT.
#   foreign_shape  — how many lines and how long the first one is (value_shape's class). Pure;
#                    safe inside $( ), which is what lets a refusal embed it mid-sentence.
#   hold_foreign   — prints the text VERBATIM and UNSTAMPED, so it reaches the box's journal
#                    and this invocation's raw SSM output (where the operator wants it) and is
#                    dropped by deploy.yml's allow-list (where the public is). It counts the
#                    lines it held back into WITHHELD_COUNT.
# Each raw line is printed behind a "| " marker, and that marker is the reason this is not
# another deny-list: a foreign line CANNOT begin with this script's timestamp once something
# else is in front of it, so foreign text cannot impersonate the applier even if it arrives
# carrying a timestamp of its own. Positive construction, not subtraction.
#
# A redaction nobody can see is the same defect one level down, so the count is printed
# wherever a held line can reach a reader: each caller names the shape on its own stamped line
# (ApplyConfigVerifyPhaseTest#everyHoldForeignCallSiteNamesItsShapeOnAStampedLine), and the
# verify phase carries the total on the public summary line and in the refusal.
#
# hold_foreign SETS A GLOBAL RATHER THAN RETURNING, for the same reason read_service_containers
# does, and that one was a live defect rather than a precaution: `x="$(withhold …)"` ran the
# function in a SUBSHELL, so `WITHHELD_COUNT=$(( … + 1 ))` incremented a copy that died with
# it and the tally could never print. Nothing here may be called inside `$( )` or a pipeline;
# ApplyConfigVerifyPhaseTest#noFunctionThatRecordsIntoAGlobalIsCalledInASubshell enumerates every
# function of this script that records into a global and refuses that spelling for all of them.
# foreign_shape records nothing, which is exactly why it may be called in a substitution.
#
# foreign_shape AND hold_foreign ARE CARRIED BY ops/drift/hamstrack-config-drift.sh, whose log()
# also stamps and whose output also travels inside the SSM command — change both or neither;
# ApplyConfigVerifyPhaseTest#everyFunctionBothScriptsCarryIsCarriedVerbatim compares every
# function the two scripts share, so a third one copied across is compared the day it lands.
# withheld_note is NOT carried: it prints a total, and the drift script names each door's shape
# on that door's own stamped line instead.
WITHHELD_COUNT=0
foreign_shape() { # $1 = foreign text; prints "N line(s), the first M characters, <class>"
  local text="$1" n first
  n="$(printf '%s\n' "$text" | grep -c . || true)"
  first="$(printf '%s\n' "$text" | grep -m 1 . || true)"
  printf '%s line(s), the first %s' "${n:-0}" "$(value_shape "$first")"
}
hold_foreign() { # $1 = foreign text; box-only, unstamped, counted. Never inside $( ) or a pipe.
  local text="$1" n
  n="$(printf '%s\n' "$text" | grep -c . || true)"
  [ "${n:-0}" -gt 0 ] || return 0
  WITHHELD_COUNT=$(( WITHHELD_COUNT + n ))
  printf '%s\n' "$text" | sed 's/^/| /'
}
# The tally, in the one wording every caller uses. Empty when nothing was held, so a caller
# appends it unconditionally and a clean line stays clean.
withheld_note() {
  [ "$WITHHELD_COUNT" -gt 0 ] || return 0
  printf ' (%s line(s) of third-party text this run read are NOT republished here — this log is public and Compose, Docker and Grafana quote whatever they were handed. They are unstamped in this invocation'"'"'s raw output on the box, behind a "| " marker: read it in this run'"'"'s output (over SSM if the deploy ran from Actions), or re-run the command a finding names in %s.)' \
    "$WITHHELD_COUNT" "$TARGET"
}

# The SHAPE of a value, for a message that must say something about it and may not say it.
# A length and a character class locate a bad entry in a file its reader already has open;
# the text itself is the thing that may have come from .env.
value_shape() {
  local v="$1" class=other
  case "$v" in
    '') class=empty ;;
    *[!0-9]*) case "$v" in *[0-9]*) class=mixed ;; *) class=non-numeric ;; esac ;;
    *) class=all-digits ;;
  esac
  printf '%s characters, %s' "${#v}" "$class"
}

USAGE='usage: apply-config.sh <source-dir> [target-dir] [sha] [--dry-run] [--allow-pinned|--adopt-pin]  |  apply-config.sh <source-dir> [target-dir] [sha] --verify-only'

# --- arguments ---------------------------------------------------------------
# TWO overrides for step 2b, and the difference between them is the whole point of having
# two: --allow-pinned proceeds and does NOT touch .deployed-image-tag, so the disagreement
# it stepped over survives and the next unattended run refuses again; --adopt-pin proceeds
# and re-stamps, which is how a reader who has genuinely moved to a new version says so.
# One word more, typed once, in exchange for an override that cannot be turned into a
# permanent disarming by an operator who reached for it mid-incident. --adopt-pin implies
# --allow-pinned; giving both is harmless and adopting wins.
DRY_RUN=0
ALLOW_PINNED=0
ADOPT_PIN=0
# --verify-only: steps 9 and 10 against a box that is ALREADY applied — nothing is placed,
# stamped, pulled or brought up. The source may then be the target itself (the synced tree
# on the box carries the manifest), and a moved pin does not refuse it: the reader is an
# operator who has just restored a box by hand and wants DeployVerifyFailed to see that.
VERIFY_ONLY=0
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --allow-pinned) ALLOW_PINNED=1 ;;
    --adopt-pin) ADOPT_PIN=1; ALLOW_PINNED=1 ;;
    --verify-only) VERIFY_ONLY=1 ;;
    -h|--help) printf '%s\n' "$USAGE"; exit 0 ;;
    -*) die "unknown option '$arg' — $USAGE" ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done
[ "${#POSITIONAL[@]}" -ge 1 ] || die "no source directory given — $USAGE"
[ "${#POSITIONAL[@]}" -le 3 ] || die "too many arguments — $USAGE"
if [ "$VERIFY_ONLY" = 1 ] && { [ "$DRY_RUN" = 1 ] || [ "$ALLOW_PINNED" = 1 ]; }; then
  die "--verify-only applies nothing, so it takes neither --dry-run nor a pin override — $USAGE"
fi

SRC="${POSITIONAL[0]}"
TARGET="${POSITIONAL[1]:-/opt/hamstrack}"
SHA="${POSITIONAL[2]:-unknown}"
# A verify-only run checks the box against the deploy that last stamped it, unless told otherwise.
if [ "$VERIFY_ONLY" = 1 ] && [ "$SHA" = unknown ] && [ -r "$TARGET/.deployed-sha" ]; then
  SHA="$(tr -d '[:space:]' < "$TARGET/.deployed-sha")"
  SHA="${SHA:-unknown}"
fi

MANIFEST_REL='ops/deploy/synced-paths.txt'

need_tool() { command -v "$1" >/dev/null 2>&1 || die "required command not found: $1 ($2)"; }
need_tool docker "brings the stack up and validates the released compose files"
need_tool sha256sum "writes the checksum manifest that drift detection reads"
need_tool flock "serialises this script against a hand run on the same box"
[ "$DRY_RUN" = 0 ] || need_tool diff "prints the dry-run diff"

# --- step 1: refuse a source that is not a release tree ----------------------
[ -d "$SRC" ] || die "source directory does not exist: $SRC"
[ -d "$TARGET" ] || die "target directory does not exist: $TARGET"
# The manifest is what makes a SYNC safe, so it is required of anything that syncs — and
# --verify-only syncs nothing. Requiring it there refused the whole read-only mode on a box
# this script has never applied, which is exactly the box docs/self-hosting.md points at
# --verify-only for: a self-hoster who set the machine up by hand has no ops/ tree on it at
# all, so the refusal arrived before any check ran and its only remedy was "run a deploy" —
# the thing that reader deliberately did not do. A verify-only run reads the compose files
# and the containers; a manifest is not among its inputs.
if [ "$VERIFY_ONLY" = 0 ] || [ -f "$SRC/$MANIFEST_REL" ]; then
  [ -f "$SRC/$MANIFEST_REL" ] \
    || die "not a release tree: $SRC/$MANIFEST_REL is missing — refusing to sync from a source with no manifest"
else
  log "verify-only: $SRC/$MANIFEST_REL is absent — this box was not set up by this script. Nothing is synced in this mode, so the read proceeds; the checks that compare against a deploy's stamp say so individually."
fi

SRC="$(cd "$SRC" && pwd)"
TARGET="$(cd "$TARGET" && pwd)"
if [ "$VERIFY_ONLY" = 0 ]; then
  [ "$SRC" != "$TARGET" ] || die "source and target are the same directory ($TARGET) — there is nothing to apply"
fi

# The box's own file, and the reason step 2 is a real check rather than a syntax pass.
#
# THE ONE INPUT --verify-only STILL HARD-REFUSES, said here rather than left for a reader to
# discover. Every other artefact a deploy leaves behind is softened for the read-only mode,
# because docs/self-hosting.md points a self-hoster at --verify-only for a box this script has
# never applied: the manifest (step 1), the source/target identity check, the empty-manifest
# refusal, the moved-pin refusal (step 2b) and the drift-fresh stamp (check 5) are all skips
# with a line in that mode. `.env` is not one of them and cannot be: EVERY read this script
# makes goes through `docker compose --env-file "$TARGET/.env"`, so without it there is no
# resolved model, no service list and no container to inspect — the refusal is the whole of the
# mode rather than one check of it, and softening it would produce a run that verified nothing
# and said PASS. A box that has a running stack has this file by construction.
[ -f "$TARGET/.env" ] \
  || die "$TARGET/.env not found — the released compose files cannot be resolved against the box's real secrets, and this script will not replace anything it could not validate. This is the ONE input --verify-only refuses too (the manifest, the pin stamp and the deploy stamps are all skipped with a line in that mode): every read here resolves the compose files with --env-file \"$TARGET/.env\", so without it there is no service list to check. Point the target at the directory that holds the stack's .env — it is the directory 'docker compose' is run from."

# --- read the manifest, then distrust it -------------------------------------
ENTRIES=()
# `|| [ -n "$line" ]` because a FINAL LINE WITH NO TRAILING NEWLINE is a path too. `read`
# returns non-zero at EOF even though it has filled the variable, so a plain `while read`
# drops it — and a manifest entry that is silently not applied is precisely HD-199's
# failure class: a file that never reaches the box while every log line says the deploy
# succeeded. An editor that trims the last newline is a one-character diff nobody reviews.
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%%#*}"                       # strip comments
  line="${line#"${line%%[![:space:]]*}"}"  # trim leading whitespace
  line="${line%"${line##*[![:space:]]}"}"  # trim trailing whitespace
  [ -n "$line" ] || continue
  # A trailing slash only means "directory" — however many of them are written. Stripping
  # ONE was a guard that held by accident: `Caddyfile///` became `Caddyfile/`, which matches
  # neither `Caddyfile` nor `*/Caddyfile` in the never-sync case below, so it PASSED the
  # never-sync guard and was stopped one line later only because a trailing slash forces
  # directory resolution and Caddyfile is a regular file. It failed closed by luck, and the
  # refusal blamed the wrong thing ("does not exist in the release tree") — a reader would
  # have added the file rather than removed the entry. Stripping them all here is also what
  # makes `.//` normalise to `.` (the target itself, refused below) rather than to `./`.
  entry="$line"
  while [ "$entry" != "${entry%/}" ]; do entry="${entry%/}"; done
  # NORMALISED ONCE, HERE, and every later comparison is against the normalised form.
  # `./observability/` and `observability/` name the same path, and once the `.` guard
  # below stopped matching the second spelling (it names the target itself; the first does
  # not) that spelling became legal — after which it applied, stamped and drift-checked
  # correctly while `case "$entry" in observability|observability/*)` at step 4 did not
  # match it, so the bind-mount restart was silently skipped. That is HD-199's own failure
  # class hiding behind a spelling: config on the box, Grafana holding a deleted inode,
  # all three drift scopes reading 0. Anything that compares against an entry STRING —
  # here, step 4's bind-mount test, and manifest_entries() in hamstrack-config-drift.sh,
  # which must agree with the stamp this script writes — depends on this line.
  while [ "$entry" != "${entry#./}" ]; do
    entry="${entry#./}"
    # `.//observability` is `./observability`, so the redundant slashes go with the `./`
    # they belong to — leaving them would spell an ABSOLUTE path and refuse the entry with
    # a message about something the reader never wrote.
    while [ "$entry" != "${entry#/}" ]; do entry="${entry#/}"; done
  done
  [ -n "$entry" ] \
    || die "manifest entry '$line' names the target directory itself rather than a path inside it"

  case "$entry" in
    /*)
      die "manifest entry '$entry' is absolute — every synced path is relative to the target directory" ;;
    ..|../*|*/../*|*/..)
      die "manifest entry '$entry' escapes the target directory" ;;
    .)
      # Only the target directory ITSELF, which both `.` and `./` name once the trailing
      # slash has been stripped. Nothing else here refuses it: the entry passes every
      # guard, `cp -a` stages the whole release tree, and the swap then fails mid-loop with
      # a bare `mv: Invalid argument` — after earlier entries have already been applied.
      # Refused up front so it costs a message instead of a half-applied box and a
      # confusing one. A path merely SPELLED with a leading `./` (`./observability/`) is an
      # ordinary entry naming something INSIDE the target: it is normalised to
      # `observability` above and never reaches this branch.
      die "manifest entry '$entry' names the target directory itself rather than a path inside it" ;;
    *"*"*|*"?"*|*"["*)
      die "manifest entry '$entry' contains a glob character — the manifest lists literal paths so that what a deploy touches can be read off it" ;;
    # `*.env` covers the shapes the arms before it missed, and it is not redundant with
    # them: those match a basename that BEGINS with `.env`, while a real secret file in this
    # repository is named `<something>.env` — `config.env` (HD-186), `backup.env` (HD-187),
    # both inside directories this repository syncs wholesale — so that shape went through.
    # A `*` in a case pattern spans `/`, so `*.env` also names it at any depth.
    # If this brace is ever "simplified": `.env` and `*/.env` are now strictly redundant with
    # `*.env` and could go, but `.env.*` and `*/.env.*` are LOAD-BEARING — they are the only
    # arms that match a name which ends in neither `.env` nor anything `*.env` sees
    # (`.env.production`, `.env.local`). Delete those two and the brace silently narrows.
    # A `<name>.env.example` template ends in `.example` and stays syncable, which it must:
    # it is the instructions for the file being refused (ops/loadtest/config.env.example,
    # ops/backup/backup.env.example).
    .env|.env.*|*/.env|*/.env.*|*.env)
      die "manifest entry '$entry' matches .env, .env.* or *.env and is NEVER synced: $TARGET/.env holds the secrets and the machine's own decisions (APP_IMAGE_TAG, APP_MEMORY_LIMIT, SITE_ADDRESS), and the single rollback story depends on it surviving every deploy; a *.env beside a synced script (config.env, backup.env) is a secret file that a deploy would copy into .config-backup/ five times over, and its <name>.env.example template travels in its place" ;;
    Caddyfile|*/Caddyfile)
      die "manifest entry '$entry' is NEVER synced: the production Caddyfile carries a hand-added Cloudflare trusted_proxies block that this repository's copy does not, so applying it would replace a hardened config with a bare one and downgrade production silently (docs/design/config-delivery-proposal.md §6.3 states the precondition for lifting this)" ;;
  esac

  [ -e "$SRC/$entry" ] || die "manifest entry '$entry' does not exist in the release tree: $SRC/$entry"
  ENTRIES+=("$entry")
done < <(cat "$SRC/$MANIFEST_REL" 2>/dev/null || true)
# An empty list is a broken RELEASE and is refused; in --verify-only on an unstamped box it
# simply means there was no manifest to read, and nothing below this point consumes ENTRIES
# in that mode (steps 4-6 are inside the `VERIFY_ONLY = 0` block).
if [ "${#ENTRIES[@]}" -eq 0 ] && [ "$VERIFY_ONLY" = 0 ]; then
  die "the manifest $SRC/$MANIFEST_REL lists no paths"
fi

# The guards above are per MANIFEST ENTRY, and a DIRECTORY entry places files the manifest
# never names. A `Caddyfile` or a `.env` committed inside a synced directory would reach the
# box through a line that reads `observability/` — the dry run showed it overwriting the
# hardened copy with the bare one. synced-paths.txt and the runbook both state the refusal
# UNCONDITIONALLY ("refuses them even if somebody adds them to the manifest"), so the
# sentence is made true here rather than narrowed there. Not reachable from today's tree,
# which is the point: a guard that holds only for the current contents of a directory is
# not a guard.
#
# The `*.env` arm is what makes "UNCONDITIONALLY" true of the shape this project actually
# writes. A secret file here is named `<something>.env`, not `.env`: HD-186's load harness
# keeps its secrets in `ops/loadtest/config.env`, and HD-187's backups in `backup.env`
# (`ops/backup/backup.env.example` is its template) — basenames matched by none of `.env`,
# `.env.*`, `*/.env`, `*/.env.*`, and both inside `ops/`, which IS synced wholesale. So the
# never-sync brace could not see the very naming convention this repository uses for the
# thing it exists to refuse. The mirror of that rule is what keeps it usable: a
# `<name>.env.example` TEMPLATE travels, the `<name>.env` it describes does not.
for entry in "${ENTRIES[@]}"; do
  [ -d "$SRC/$entry" ] || continue
  while IFS= read -r placed; do
    case "${placed##*/}" in
      .env|.env.*|*.env|Caddyfile)
        die "manifest entry '$entry' would place '$placed', and .env, .env.*, *.env and Caddyfile are NEVER synced (the reasons are in $MANIFEST_REL): a file named .env, starting with .env. or ending in .env holds secrets and a machine's own decisions, and the production Caddyfile carries a hand-added Cloudflare trusted_proxies block that this repository's copy does not. Move it out of the release tree — a load-harness config belongs at /opt/hamstrack/.loadtest.env and a backup config at /etc/hamstrack/backup.env, each described by a .example that DOES travel — or delete it, or stop syncing the directory that carries it." ;;
    esac
  done < <( cd "$SRC" && find "$entry" -print )
done

# --- which compose files ------------------------------------------------------
# Portable in its ASSUMPTIONS, not only in its arguments. Requiring both files makes every
# deployment that does not run the observability stack fail at the validate step on a
# `${GF_SECURITY_ADMIN_PASSWORD:?…}` it has no reason to set — a self-hoster blocked by a
# variable belonging to a stack they do not have. A file listed here and ABSENT from the
# release tree is skipped and said so; at least one must exist, or there is nothing to
# validate and nothing to bring up.
read -r -a COMPOSE_LIST <<< "${COMPOSE_FILES:-docker-compose.prod.yml docker-compose.observability.yml}"
VALIDATE_ARGS=()   # -f <path in the release tree> — what step 2 resolves against .env
RUN_ARGS=()        # -f <relative name>            — what step 7 runs inside the target
for f in "${COMPOSE_LIST[@]}"; do
  if [ -f "$SRC/$f" ]; then
    VALIDATE_ARGS+=(-f "$SRC/$f")
    RUN_ARGS+=(-f "$f")
  else
    log "compose file $f is not in the release tree — skipping it"
  fi
done
[ "${#RUN_ARGS[@]}" -gt 0 ] \
  || die "none of the compose files (${COMPOSE_LIST[*]}) exists in $SRC — there is nothing to validate and nothing to bring up"

# --- serialise against a hand run --------------------------------------------
# The GitHub concurrency group serialises the PIPELINE and does nothing about an operator
# running this by hand at the same moment.
exec 9>"$TARGET/.deploy.lock"
flock -n 9 || die "another apply-config run holds $TARGET/.deploy.lock — refusing to apply twice at once"

# --- step 2: validate the RELEASED files against the BOX'S .env --------------
# Compose resolves interpolation before it creates, changes or stops anything, so a
# `${VAR:?…}` the box cannot satisfy fails here — with the stack still running and not one
# file replaced. --project-directory is the target so the project NAME stays `hamstrack`
# (compose derives it from that directory's basename); a different name would treat every
# running container as somebody else's.
# The two Compose invocations this script makes, named once. `release_compose` resolves the
# RELEASED files (VALIDATE_ARGS) against the box's .env with the target as project directory,
# so the project NAME stays `hamstrack` and bind-mount paths resolve where they will run;
# `run_compose` runs the files INSIDE the target (RUN_ARGS) — see the note at step 7 for why
# it is every compose file, in pull and in up. In a --verify-only run the two sets are the
# same files.
release_compose() {
  docker compose --project-directory "$TARGET" --env-file "$TARGET/.env" "${VALIDATE_ARGS[@]}" "$@"
}
run_compose() {
  ( cd "$TARGET" && docker compose "${RUN_ARGS[@]}" "$@" )
}

log "validating the released compose files against $TARGET/.env"
# The refusal used to say "the compose error above names the variable". Under SSM that error is
# unstamped, so deploy.yml's allow-list drops it and the Actions reader was told to read a line
# that is not there — a refusal prescribing an action its reader cannot perform. Compose's text
# is still the most useful thing here, so it is kept where it can be read (the box) and
# described where it cannot (the public log).
CONFIG_ERR="$(mktemp)"
if ! release_compose config -q 2>"$CONFIG_ERR"; then
  CONFIG_ERR_TEXT="$(cat "$CONFIG_ERR" 2>/dev/null || true)"
  rm -f -- "$CONFIG_ERR"
  hold_foreign "$CONFIG_ERR_TEXT"
  die "docker compose refused the released configuration against $TARGET/.env — NOTHING has been replaced and the running stack is untouched. Compose wrote $(foreign_shape "$CONFIG_ERR_TEXT") naming the variable at fault; that text is not republished here (this log is public and a typed-decode error quotes the resolved VALUE — measured), and is unstamped ABOVE behind a \"| \" marker, in this run's raw output on the box. Read it in this run's output (over SSM if the deploy ran from Actions), or run 'docker compose ${VALIDATE_ARGS[*]} --env-file $TARGET/.env config -q' in $TARGET; then set the variable it names in $TARGET/.env and re-run the deploy."
fi
rm -f -- "$CONFIG_ERR"
log "validation passed"

# --- step 2b: refuse to sync configuration onto a MOVED image pin -------------
# APP_IMAGE_TAG pins the image; NOTHING pins the configuration beside it. A pin set during
# an incident is therefore decayed by the next merge, which syncs a newer tree onto the
# older image — and if the incident was caused by a configuration change, the documented
# single rollback story does not roll it back and re-applies it within minutes. deploy.yml
# used to claim that config and image "cannot come from different trees" because the
# fetch is by sha; the TAG is mutable, so they can. That claim is now this check.
#
# WHAT IS REFUSED IS THE PIN MOVING, NOT THE PIN EXISTING. docs/self-hosting.md tells every
# self-hoster to pin (`APP_IMAGE_TAG=0.4`), so for most of this script's audience "pinned"
# is the steady state rather than an incident, and refusing it outright would make the
# tool's default answer to them "no", for ever, behind a flag they retype every time.
# `.deployed-image-tag` is stamped on every run, so the two states are distinguishable and
# are decided differently:
#
#   the tag has NOT moved since the last apply  → a steady-state re-apply: the image is the
#                                                 one this configuration has been living
#                                                 beside all along → PROCEED;
#   the tag HAS moved since the last apply      → the rollback this check exists for:
#                                                 somebody held the image still, and the
#                                                 configuration must be held with it → REFUSE;
#   no stamp yet (first apply on this box)      → nothing to compare against, and a first
#                                                 sync deserves a human anyway → REFUSE.
#
# TWO OVERRIDES, because one cannot be both "proceed this once" and "this is the new
# intended version" without silently becoming the second:
#
#   --allow-pinned  → proceed, and DO NOT touch `.deployed-image-tag`. The disagreement
#                     survives the run, so the next unattended deploy refuses again.
#   --adopt-pin     → proceed AND re-stamp. This tag is the one configuration should be
#                     living beside from now on.
#
# The ordering that forces the split: production is pinned to 0.17.0 for an incident, CI
# goes red as designed, and six hours in — an urgent config fix, or the DeployImagePinned
# alert — sends the operator to a hand run. If that run re-stamps, the pin and the stamp
# agree again, and the NEXT MERGE's unattended run reads "unmoved", calls it a steady-state
# re-apply and places the newest configuration tree onto the deliberately held-back image,
# with no flag, no refusal and a log line saying everything is normal. That is exactly what
# this step exists to prevent, reached by fatigue rather than by decision. A version bump
# types one more word, once; an incident cannot disarm CI at all.
#
# .env is READ, never sourced: it holds every secret on this box, and sourcing a
# hand-edited file executes whatever a typo made of it. The parse must match COMPOSE'S
# env-file parser rather than a convenient subset of it: a line compose honours and this
# does not reads as UNSET, which is `latest`, which is "not pinned" — a fail-OPEN miss in
# the one check whose whole job is to refuse. So the `export ` prefix compose accepts is
# accepted here, and an unquoted ` # comment` tail is dropped the way compose drops it.
# Same parse as the drift script; keep the two identical — sealed by
# src/test/java/com/hamstrack/ops/ApplyConfigPinGuardTest.java, which compares the two
# function bodies and drives this whole step against scratch directories.
read_image_tag() {
  local v
  v="$(sed -n 's/^[[:space:]]*\(export[[:space:]]\+\)\?APP_IMAGE_TAG[[:space:]]*=[[:space:]]*//p' "$TARGET/.env" | tail -n 1)"
  case "$v" in
    \"*) v="${v#\"}"; v="${v%%\"*}" ;;
    \'*) v="${v#\'}"; v="${v%%\'*}" ;;
    *)   v="$(printf '%s' "$v" | sed 's/[[:space:]]#.*$//')" ;;
  esac
  printf '%s' "$(printf '%s' "$v" | tr -d '[:space:]')"
}
# An absent or empty value is compose's own `latest` default, and a default is not a pin.
IMAGE_TAG="$(read_image_tag)"
IMAGE_TAG="${IMAGE_TAG:-latest}"

# THE PIN CAN BE SET SOMEWHERE THIS FUNCTION CANNOT SEE. Compose gives the PROCESS
# ENVIRONMENT precedence over --env-file, so `APP_IMAGE_TAG=9.9.9 docker compose --env-file
# .env config` resolves 9.9.9 while .env says something else — and this script's own header
# tells operators to run it under `sudo -E` for COMPOSE_FILES, so `-E` is a normal habit
# here. Read only the file, and an operator who EXPORTS the pin instead of writing it gets
# the whole guard bypassed in silence: the check sees `latest` and proceeds, step 7 deploys
# the exported tag, and step 6 stamps a tag the box is not running.
#
# So the environment is folded in WITH COMPOSE'S PRECEDENCE — and when it is the thing that
# decides, the run is REFUSED rather than obeyed. An exported pin is not a pin: it lasts
# exactly as long as one shell. The next `up -d` by anybody, the hourly drift check (a
# systemd timer inherits nobody's shell, so hamstrack_deploy_image_pinned would read 0 and
# the un-pin reminder would never fire) and the next unattended deploy all see the file.
# The pin must live in .env because .env is the one file no deploy replaces; that is the
# whole reason the rollback lever was put there.
if [ "${APP_IMAGE_TAG+set}" = set ]; then
  ENV_VAR_TAG="${APP_IMAGE_TAG:-latest}"   # compose reads an empty value as unset, i.e. `latest`
  # How it RESOLVES and how it is NAMED BACK are two different things, and conflating them
  # sent the operator looking for something they never typed: an exported EMPTY value
  # resolves to `latest`, so this refusal used to read "APP_IMAGE_TAG=latest is set in this
  # run's ENVIRONMENT" — after which the reader greps their shell for `latest`, finds nothing,
  # and disbelieves a message that was right. Say what is SET, then what compose makes of it.
  ENV_PIN_SAID="APP_IMAGE_TAG=$ENV_VAR_TAG is set in this run's ENVIRONMENT and"
  if [ -z "$APP_IMAGE_TAG" ]; then
    ENV_PIN_SAID="APP_IMAGE_TAG is set in this run's ENVIRONMENT to an EMPTY value — nothing in your shell says '$ENV_VAR_TAG', but compose reads an empty value as unset and resolves it to $ENV_VAR_TAG, so exporting it UN-pins this run. It"
  fi
  if [ "$ENV_VAR_TAG" != "$IMAGE_TAG" ]; then
    die "$ENV_PIN_SAID overrides $TARGET/.env, which resolves to $IMAGE_TAG — compose gives the process environment precedence, so this deploy would run $ENV_VAR_TAG while every later one runs $IMAGE_TAG. NOTHING has been replaced and the running stack is untouched. The pin must live in $TARGET/.env to survive a deploy: an exported value lasts one shell, so the next 'docker compose up -d', the hourly drift check and the next unattended deploy would all disagree with this run — and step 2b, which is the only thing standing between an incident's held-back image and the newest configuration tree, would never see it. Set APP_IMAGE_TAG in $TARGET/.env and re-run without it in the environment."
  fi
fi

# The last tag anybody ADOPTED — written by every run that stamps, which is every run except
# an --allow-pinned one. So it is NOT, in general, the tag the configuration currently sits
# beside: after an --allow-pinned run this file deliberately still names the previous tag
# while .env names the running one, and that surviving disagreement is what refuses the next
# unattended deploy. Absent on a box this script has never run on; never trusted for anything
# but an equality test.
LAST_IMAGE_TAG=''
if [ -r "$TARGET/.deployed-image-tag" ]; then
  LAST_IMAGE_TAG="$(tr -d '[:space:]' < "$TARGET/.deployed-image-tag")"
fi

PIN_STATE=ok        # ok (unpinned, or pinned and unmoved) | moved | unstamped
PIN_REASON=''
if [ "$IMAGE_TAG" != latest ]; then
  if [ -z "$LAST_IMAGE_TAG" ]; then
    PIN_STATE=unstamped
    PIN_REASON="there is no $TARGET/.deployed-image-tag, so nothing records which image the configuration now on this box was placed beside"
  elif [ "$LAST_IMAGE_TAG" != "$IMAGE_TAG" ]; then
    PIN_STATE=moved
    PIN_REASON="the pin has MOVED since the tag this box last ADOPTED, which is $LAST_IMAGE_TAG"
  fi
fi

if [ "$PIN_STATE" != ok ] && [ "$VERIFY_ONLY" = 1 ]; then
  log "WARN $TARGET/.env pins APP_IMAGE_TAG=$IMAGE_TAG and $PIN_REASON — a --verify-only run places nothing, so it proceeds; the next deploy refuses until the pin is adopted or removed"
elif [ "$PIN_STATE" != ok ]; then
  if [ "$DRY_RUN" = 1 ]; then
    # Written for the flags actually given: a dry run must say what the real run will DO,
    # not what it would refuse without a flag the reader has already supplied.
    if [ "$ADOPT_PIN" = 1 ]; then
      log "WARN $TARGET/.env pins APP_IMAGE_TAG=$IMAGE_TAG and $PIN_REASON — with the --adopt-pin you have given, a real run places the configuration AND re-stamps the tag, after which unattended deploys proceed on this pin"
    elif [ "$ALLOW_PINNED" = 1 ]; then
      log "WARN $TARGET/.env pins APP_IMAGE_TAG=$IMAGE_TAG and $PIN_REASON — with the --allow-pinned you have given, a real run places the configuration for THIS RUN ONLY and leaves $TARGET/.deployed-image-tag alone, so the next unattended deploy refuses again"
    else
      log "WARN $TARGET/.env pins APP_IMAGE_TAG=$IMAGE_TAG and $PIN_REASON — a real run refuses this sync unless it is given --allow-pinned (proceed once) or --adopt-pin (proceed and adopt this tag)"
    fi
  elif [ "$ALLOW_PINNED" = 0 ]; then
    # Two readers reach each of these messages — the operator who moved the tag on purpose
    # and the one who moved it to hold production still — and each must find an action they
    # can perform. Prescribing only "un-pin" is advice the first of them must not take;
    # prescribing only "adopt" is advice the second must not take, and that one is worse,
    # because adopting is not undone by the incident ending.
    case "$PIN_STATE" in
      moved)
        die "$TARGET/.env pins APP_IMAGE_TAG=$IMAGE_TAG while the tag this box last ADOPTED is $LAST_IMAGE_TAG, so this run would put configuration from $SHA next to an image it did not come with. NOTHING has been replaced and the running stack is untouched. If you moved the tag ON PURPOSE — a version bump, or a pin you keep by policy — re-run this script by hand with --adopt-pin: it re-stamps the tag, so the flag is needed this once and not again while the pin stays where it is. If the tag was moved to ROLL PRODUCTION BACK, this refusal is the point: while the image is held still the configuration must be held with it, or the next merge quietly re-applies whatever the rollback was undoing — leave it refused, and un-pin (set APP_IMAGE_TAG=latest in $TARGET/.env, or delete the line) when the incident is over. If you need a configuration change applied DURING the incident, --allow-pinned does exactly that run and does not re-stamp, so the deploy after it still refuses." ;;
      *)
        die "$TARGET/.env pins APP_IMAGE_TAG=$IMAGE_TAG and $PIN_REASON, so this script cannot tell a steady-state re-apply from a rollback in progress. NOTHING has been replaced and the running stack is untouched. This is the state of EVERY box the first time this script runs on it, so read the diff with --dry-run first, and then choose by what the pin MEANS. If $IMAGE_TAG is the version this box is meant to run — a pin kept by policy, which is what docs/self-hosting.md prescribes — re-run by hand with --adopt-pin: it writes $TARGET/.deployed-image-tag and every later run on the same pin proceeds without a flag. If $IMAGE_TAG is pinned because production is CURRENTLY ROLLED BACK, do NOT adopt it: adopting makes a tag chosen during an incident the intended one permanently, and the next merge would then place the newest configuration onto the image that rollback is holding down. Leave the deploy refused, and un-pin (set APP_IMAGE_TAG=latest in $TARGET/.env, or delete the line) when the incident is over — or, if a configuration change must be applied during it, use --allow-pinned, which does this run only and does not stamp." ;;
    esac
  elif [ "$ADOPT_PIN" = 1 ]; then
    log "WARN --adopt-pin: placing configuration from $SHA onto pinned image tag $IMAGE_TAG, and $PIN_REASON — the two are from different trees, deliberately, and the tag is being adopted as the intended one"
  else
    log "WARN --allow-pinned: placing configuration from $SHA onto pinned image tag $IMAGE_TAG, and $PIN_REASON — the two are from different trees, deliberately. $TARGET/.deployed-image-tag is NOT updated, so the next run without a flag refuses again; that is what makes this override a single run rather than a permanent one"
  fi
elif [ "$IMAGE_TAG" != latest ]; then
  log "APP_IMAGE_TAG=$IMAGE_TAG is pinned and has not moved since the last apply — a steady-state re-apply, proceeding"
fi

differs() { # $1 = manifest entry; returns 0 when the box differs from the release
  [ -e "$TARGET/$1" ] || return 0
  diff -rq "$TARGET/$1" "$SRC/$1" >/dev/null 2>&1 && return 1 || return 0
}

# --- step 2c: the service list, and the tools the tail of the deploy needs ------
# COMPOSE'S OWN RESOLUTION of the released files against the box's .env — never a grep of
# the files and never a literal list — so a service added to a compose file is a member of
# every pre-flight and verify check below in the same commit, and a box whose COMPOSE_FILES
# omits the observability file has no `grafana` here and skips that check with a log line.
# Read BEFORE step 4, together with the one tool only the tail needs, so that a box lacking
# it is refused while nothing has been changed rather than at step 10 with the image pulled.
SERVICES="$(release_compose config --services 2>/dev/null | tr -d '\r')" \
  || die "docker compose validated (${COMPOSE_LIST[*]}) against $TARGET/.env and then could not list their services — NOTHING has been replaced and the running stack is untouched"
SERVICES="$(printf '%s\n' "$SERVICES" | sed '/^[[:space:]]*$/d')"
# An empty list is what the script accepted before it verified anything, and it stays a
# state rather than a refusal: nothing is declared, so steps 7 and 10 have nothing to bring
# up or read back, and each check that iterates the list says so in its own line.
[ -n "$SERVICES" ] || log "WARN the compose files (${COMPOSE_LIST[*]}) resolve to no services — nothing will be brought up, and the per-service verify checks have nothing to read"
has_service() { [ -n "$SERVICES" ] && printf '%s\n' "$SERVICES" | grep -qx -- "$1"; }
if has_service grafana; then
  need_tool curl "reads Grafana's /api/health on the host loopback at the verify step (a box whose COMPOSE_FILES declares no grafana service needs no curl)"
fi

# --- the read-back library: pre-flight (before the mutation) and verify (after it) ---
# Everything below READS. Two functions are carried IDENTICALLY by
# ops/drift/hamstrack-config-drift.sh — sanitize_label and plan_container_pairs — and
# src/test/java/com/hamstrack/ops/ApplyConfigVerifyPhaseTest.java compares the bodies, the
# way ApplyConfigPinGuardTest already does for read_image_tag: change both or neither.
#
# WHATEVER THIS PRINTS IS PUBLIC (see the header). The checks read the resolved compose
# model, which contains every secret .env folds into an `environment:` block; the reader
# below emits KEY NAMES and a set/null flag and never a value, and no inspect output is
# ever printed whole. A `docker inspect` piped to the log while debugging is a disclosure.
TEXTFILE_DIR="${CONFIG_DRIFT_TEXTFILE_DIR:-/var/lib/node_exporter/textfile_collector}"
# Poll budgets. The defaults are the production ones; the tests shorten them. None of them
# disarms a check — a shorter budget makes a slow box RED, never green.
# THEY ARE PROCESS ENVIRONMENT, NEVER .env, and that is not a detail: this script NEVER
# sources $TARGET/.env — it hands it to Compose with --env-file — so a knob written there is
# read by nobody and silently does nothing at all. Over SSM or by hand they travel as
#   VERIFY_APP_TIMEOUT_SECONDS=300 sudo -E bash …/apply-config.sh …
# and `sudo -E` is load-bearing: plain `sudo` drops them and you get the defaults with no
# line saying so.
#
# VALIDATED HERE, BEFORE STEP 4 — i.e. before anything on the box has changed. An unvalidated
# knob dies inside the verify step with a raw bash message naming the VALUE and not the
# variable ("`SECONDS + abc`: syntax error"), which arrives AFTER the mutation, leaves the
# pessimistic gauge at 0 and fires DeployVerifyFailed for what is a typo in a command line.
# Ranges rather than "a number": 0 for a poll is a busy loop, and an hour for a settle window
# is a deploy that never ends.
require_whole_number() { # $1 = variable name, $2 = min, $3 = max, $4 = what it does
  local name="$1" min="$2" max="$3" what="$4" value="${!1}"
  case "$value" in
    ''|*[!0-9]*)
      die "$name must be a whole number of seconds between $min and $max ($what) — it is not a number. It is process environment, not $TARGET/.env, which this script never sources: pass it as '$name=<seconds> sudo -E bash …/apply-config.sh …'. NOTHING has been changed." ;;
  esac
  if [ "$value" -lt "$min" ] || [ "$value" -gt "$max" ]; then
    die "$name is $value, outside $min..$max ($what). It is process environment, not $TARGET/.env, which this script never sources: pass it as '$name=<seconds> sudo -E bash …/apply-config.sh …'. NOTHING has been changed."
  fi
}
VERIFY_POLL_SECONDS="${VERIFY_POLL_SECONDS:-5}"
VERIFY_APP_TIMEOUT_SECONDS="${VERIFY_APP_TIMEOUT_SECONDS:-180}"
VERIFY_GRAFANA_TIMEOUT_SECONDS="${VERIFY_GRAFANA_TIMEOUT_SECONDS:-90}"
VERIFY_GRAFANA_SETTLE_SECONDS="${VERIFY_GRAFANA_SETTLE_SECONDS:-20}"
require_whole_number VERIFY_POLL_SECONDS 0 60 "how long each poll waits between attempts"
require_whole_number VERIFY_APP_TIMEOUT_SECONDS 1 1800 "how long /api/meta may take to answer"
require_whole_number VERIFY_GRAFANA_TIMEOUT_SECONDS 1 1800 "how long Grafana's /api/health may take"
require_whole_number VERIFY_GRAFANA_SETTLE_SECONDS 0 600 "how long Grafana must hold still to prove it is not crash-looping"
# The one that is not a number. It is fetched with curl, so a value that is not http(s) on
# the loopback is either a mistake or an attempt to make this check ask a different machine.
#
# MATCHED ON THE AUTHORITY, NOT ON A PREFIX. `http://localhost:*` looks like a loopback test
# and is not one: the USERINFO form puts anything before an `@`, so it accepted
# `http://localhost:3000@evil.example.com/leak` and `http://127.0.0.1:80@169.254.169.254/…`
# (measured 2026-09-10 — curl resolved the host after the `@`, which on this box is the
# instance metadata service). The refusal claimed "on the loopback" and nothing held the
# claim. So the authority is cut out first — everything between `://` and the first `/`,
# `?` or `#` — and it must be the whole host, with an optional numeric port and no `@`.
GRAFANA_HEALTH_URL="${GRAFANA_HEALTH_URL:-http://127.0.0.1:3000/api/health}"
is_loopback_url() { # $1 = url; 0 when its AUTHORITY is a bare loopback host with an optional numeric port
  local rest="$1" auth port
  case "$rest" in http://*|https://*) rest="${rest#*://}" ;; *) return 1 ;; esac
  auth="${rest%%/*}"; auth="${auth%%\?*}"; auth="${auth%%#*}"
  case "$auth" in
    127.0.0.1|localhost) return 0 ;;
    127.0.0.1:*|localhost:*)
      port="${auth#*:}"
      if [ -n "$port" ] && [ -z "${port//[0-9]/}" ]; then return 0; fi ;;
  esac
  return 1
}
is_loopback_url "$GRAFANA_HEALTH_URL" \
  || die "GRAFANA_HEALTH_URL must be an http(s) URL whose AUTHORITY is exactly 127.0.0.1 or localhost with an optional numeric port — no userinfo, no '@', no second host (it is what check 4 asks for Grafana's health, and Grafana publishes no public port by design). It is process environment, not $TARGET/.env. NOTHING has been changed."

# A label value with a quote or a backslash in it makes the WHOLE textfile malformed, and
# node-exporter then drops every series in it at once.
sanitize_label() {
  local v="$1"
  v="${v//[^A-Za-z0-9._-]/}"
  printf '%s' "${v:-unknown}"
}

# stdin: a `docker compose up -d --dry-run` plan (stdout+stderr merged). stdout: one
# `<container-name> <TAB> <verb>` per `Container` progress line. The word `Container` is
# LOCATED rather than assumed first — the two supported Compose generations disagree about
# what precedes it; the reasoning is at check_containers in the drift script, which is where
# this function lives first — THE SAME FUNCTION IS CARRIED BY ops/drift/hamstrack-config-drift.sh,
# the two bodies are compared by src/test/java/com/hamstrack/ops/ApplyConfigVerifyPhaseTest.java,
# so change both or neither.
plan_container_pairs() {
  tr -d '\r' | awk '{
      for (i = 1; i <= NF - 2; i++) {
        if ($i == "Container") { print $(i + 1) "\t" $(i + 2); break }
      }
    }'
}

# stdin: `docker compose config` — the RESOLVED model, which is the only comparand
# (interpolation means the file text never is). stdout: tab-separated rows
#   <service> <TAB> mem <TAB> mem_limit|deploy.resources.limits.memory <TAB> <declared value>
#   <service> <TAB> env <TAB> <KEY>                                    <TAB> set|null
# Measured on Compose v5.1.0 (2026-09-10): the model is YAML with two-space indentation,
# `mem_limit` is emitted as a STRING OF BYTES (`"2147483648"`, whatever unit the file used),
# `deploy.resources.limits.memory` stays under `deploy` in the same form, a service MAY spell
# both as long as the two agree — measured, equal values resolve and the model then carries
# BOTH rows, while distinct ones are refused with `can't set distinct values on 'mem_limit'
# and 'deploy.resources.limits.memory'`, so declared_memory taking the first row it finds is
# safe by Compose's own rule and not by ours — `env_file` keys ARE folded into `environment`, a
# list-form `environment` is normalised to a map, and a pass-through key with no value on
# the host is `null` — which Compose then does NOT hand to the container, so `null` keys are
# not required to be present. A key is read only at the EXACT depth its section lives at
# (services/<svc>/environment/<KEY> is depth 3), so a multi-line value's continuation lines,
# which are deeper, can never be mistaken for a key. Values under environment are reduced to
# set/null HERE, before anything else sees them.
compose_declarations() {
  tr -d '\r' | awk '
    function unq(s) { sub(/^["\047]/, "", s); sub(/["\047]$/, "", s); return s }
    /^[[:space:]]*$/ { next }
    /^[[:space:]]*#/ { next }
    {
      match($0, /^ */); indent = RLENGTH
      rest = substr($0, indent + 1)
      if (rest ~ /^- /) next
      if (match(rest, /^[^ ]+:( |$)/) == 0) next
      key = substr(rest, 1, RLENGTH); sub(/: ?$/, "", key)
      val = substr(rest, RLENGTH + 1)
      depth = int(indent / 2)
      keys[depth] = unq(key)
      if (keys[0] != "services") next
      if (depth == 2 && keys[2] == "mem_limit") print keys[1] "\tmem\tmem_limit\t" unq(val)
      if (depth == 5 && keys[2] == "deploy" && keys[3] == "resources" && keys[4] == "limits" && keys[5] == "memory")
        print keys[1] "\tmem\tdeploy.resources.limits.memory\t" unq(val)
      if (depth == 3 && keys[2] == "environment") print keys[1] "\tenv\t" keys[3] "\t" (val == "null" ? "null" : "set")
    }'
}

# A declared ceiling as bytes: raw bytes (what Compose emits) or a b/k/m/g suffix in
# Docker's binary units. Anything else is a value this check cannot read, and the caller
# treats that as a failure, never as a skip.
to_bytes() {
  local v="$1" n unit
  case "$v" in ''|*[!0-9bBkKmMgG]*) return 1 ;; esac
  n="${v%[bBkKmMgG]}"; unit="${v#"$n"}"
  [ -n "$n" ] && [ -z "${n//[0-9]/}" ] || return 1
  case "$unit" in
    ''|b|B) printf '%s' "$n" ;;
    k|K) printf '%s' $(( n * 1024 )) ;;
    m|M) printf '%s' $(( n * 1024 * 1024 )) ;;
    g|G) printf '%s' $(( n * 1024 * 1024 * 1024 )) ;;
    *) return 1 ;;
  esac
}
# Every RUNNING container of a service, not the first: a scaled service has several, and
# checking one is a check that silently narrows the moment somebody scales.
# "COMPOSE COULD NOT BE ASKED" AND "NOTHING IS RUNNING" ARE DIFFERENT ANSWERS, and this
# function used to return the same empty string for both: `2>/dev/null` threw away the reason
# and the pipeline's exit code was lost to `tr`. A reviewer's run turned one transient daemon
# hiccup into five hard findings and a refusal telling them production might be half-updated
# — every per-service check reads through here, so one unanswerable question becomes a finding
# per service. Now: the exit code is kept (SERVICE_CONTAINERS_RC, 0 = the daemon answered),
# the error text is kept for the caller to name, and a failed read gets ONE bounded retry
# after VERIFY_POLL_SECONDS, because the shape this saw was transient. Callers distinguish an
# empty answer from an unanswered one and say which.
# It sets GLOBALS rather than printing, and that is not a style choice: `x="$(f)"` and
# `f | head -1` both run f in a SUBSHELL, so an exit code f recorded in a variable would be
# discarded at exactly the call sites that need it. SERVICE_CIDS is the answer,
# SERVICE_CONTAINERS_RC is whether there was one.
SERVICE_CIDS=''
SERVICE_CONTAINERS_RC=0
SERVICE_CONTAINERS_ERR=''
read_service_containers() {
  local err attempt=0
  while :; do
    err="$(mktemp)"
    SERVICE_CIDS="$(run_compose ps -q "$1" 2>"$err" | tr -d '\r' | sed '/^$/d')" \
      && SERVICE_CONTAINERS_RC=0 || SERVICE_CONTAINERS_RC=$?
    SERVICE_CONTAINERS_ERR="$(head -c 200 "$err" 2>/dev/null | tr '\r\n' '  ' || true)"
    rm -f -- "$err"
    [ "$SERVICE_CONTAINERS_RC" -ne 0 ] && [ "$attempt" -eq 0 ] || break
    attempt=1
    log "verify: WARN 'docker compose ps -q $1' exited $SERVICE_CONTAINERS_RC — retrying once in ${VERIFY_POLL_SECONDS}s before treating that as an answer about the box"
    sleep "$VERIFY_POLL_SECONDS"
  done
}
# The reason text a caller appends when the daemon did not answer. Compose's stderr is
# third-party text and this note is embedded in the STAMPED refusal, which deploy.yml
# publishes — so the note carries the shape and hold_foreign puts the text itself on the box.
# That is also why this one sets a global instead of printing: `$(service_containers_unanswered …)`
# is a subshell, and the hold_foreign inside it would increment a counter that dies with the
# substitution.
SERVICE_UNANSWERED_NOTE=''
service_containers_unanswered() {
  SERVICE_UNANSWERED_NOTE="$(printf "'docker compose ps -q %s' exited %s, so this check could not be asked whether anything is running — this is NOT a finding about the box. Compose wrote %s; the text is not republished here (this log is public) and is unstamped ABOVE behind a \"| \" marker, in this run's raw output on the box — or re-run 'docker compose ps -q %s' in %s" \
    "$1" "$SERVICE_CONTAINERS_RC" "$(foreign_shape "$SERVICE_CONTAINERS_ERR")" "$1" "$TARGET")"
  hold_foreign "$SERVICE_CONTAINERS_ERR"
}
declared_memory() { printf '%s\n' "$1" | awk -F'\t' -v s="$2" '$1 == s && $2 == "mem" { print $4; exit }'; }
# The same reader over the compose files' RAW TEXT rather than Compose's resolved model, and
# the pair is the whole point: a ceiling present in one and absent from the other means .env
# interpolated it away (APP_MEMORY_LIMIT=0 — measured). Filled at step 10; empty means the
# files could not be read, and then the comparison simply does not fire and the old WARN
# stands, which is the safe direction for a check that only ever UPGRADES a warning.
FILE_DECLARATIONS=''
service_declares_ceiling_in_file() {
  printf '%s\n' "$FILE_DECLARATIONS" \
    | awk -F'\t' -v s="$1" '$1 == s && $2 == "mem" { found = 1 } END { exit !found }'
}
inspect_field() { docker inspect -f "$1" "$2" 2>/dev/null | tr -d '\r'; }
REVISION_LABEL_TEMPLATE='{{index .Config.Labels "org.opencontainers.image.revision"}}'

# --- the verify gauge ---------------------------------------------------------
# hamstrack_deploy_verify.prom: same directory, same atomic write and same mode as the drift
# script's file (a scrape must never see a half-written file; node-exporter runs as nobody).
# Written PESSIMISTICALLY — every series 0 — at the first mutation, and rewritten by verify
# with the real results, so a run killed between step 5 and step 10 leaves a 0 behind for
# DeployVerifyFailed rather than the previous deploy's 1 (the backup script's lesson). A
# check that was legitimately skipped (no such service on this box) publishes 2, which the
# alert's `< 1` does not fire on: a box that declares no grafana has nothing to page about,
# and 2 still reads differently from a 1 somebody could mistake for a reading. A write failure
# is a WARN, never fatal — a metric that cannot be written must not red a good deploy (step 9's
# rule) — and the deploy's own exit code is the witness that remains.
#
# …WHICH IS WHY A QUIET GAUGE IS NOT ENOUGH ON ITS OWN. `_check_ok` says "nothing is wrong
# here"; it cannot say "something was read". A box whose COMPOSE_FILES resolved to nothing at
# all publishes five 2s and has verified NOTHING, and the alert is quiet by design.
# hamstrack_deploy_verify_checks_ran is the second half: how many of the declared checks
# actually read the running box this run. It is on the public summary line too, so the count is
# legible without Prometheus, and the summary says PARTIAL rather than PASS whenever it is
# below the declared count.
#
# WHO READS EACH SERIES, decided rather than left silent:
#   * hamstrack_deploy_verify_check_ok{check} — DeployVerifyFailed (critical, 5 m) reads it
#     UNAGGREGATED, per check; that is the alerting reader and the only one.
#   * hamstrack_deploy_verify_ok / _checks_ran / _timestamp_seconds / _info{sha} — NO alert
#     and NO dashboard panel, deliberately. Alerting on the roll-up would double every page
#     DeployVerifyFailed already sends with a label that names nothing, and a panel for a
#     value that changes a few times a week is a tile nobody looks at. They exist for two
#     readers a human drives: Grafana Explore (the roll-up, the age of the reading and the
#     sha that produced it, in one query) and the post-deploy read-back in
#     docs/release-checklist.md. They are documented as such in docs/observability.md's
#     metric table — a metric nobody reads is a metric nobody notices breaking, so the
#     reader is written down even when it is a person rather than a rule.
OK_MEMORY=0; OK_ENV=0; OK_APP=0; OK_GRAFANA=0; OK_DRIFT=0
VERIFY_FAILURES=()
# --- what the PREVIOUS run published, read ONCE before anything here overwrites it ---------
# A CHECK THAT DID NOT READ THE BOX MAY ONLY LOWER CONFIDENCE, NEVER RAISE IT. Measured on
# 2026-09-10: a `--verify-only` run with COMPOSE_FILES narrowed to one file rewrote four checks
# that had published 0 with 2 (not applicable) and printed `verify: PASS 5/5` — four CRITICAL
# alerts went quiet and the summary said the box was fine. The rule is phrased over the category
# rather than over those four: writing 2 over an existing 0 for the same {check} is forbidden
# for every check and for every check added later, so it lives in _check_ok, which is the ONE
# place a value is written, rather than at the five call sites that set OK_*.
#
# WHY checks_ran GATES IT, and it is not a detail: 0 in this file means "failed" OR "has not run
# yet", because the pessimistic write at the first mutation publishes 0 for everything. Without
# a discriminator a deploy killed once would pin every legitimately-skipped check at 0 for ever,
# on a box whose reader cannot make grafana exist — a refusal its reader cannot act on. A
# pessimistic file publishes checks_ran 0 and a completed verify publishes at least 1, so the
# previous file's checks_ran is exactly the question "did a verify phase finish and write this".
VERIFY_PROM="$TEXTFILE_DIR/hamstrack_deploy_verify.prom"
PREVIOUS_PROM=''
[ ! -r "$VERIFY_PROM" ] || PREVIOUS_PROM="$(cat "$VERIFY_PROM" 2>/dev/null || true)"
PREVIOUS_CHECKS_RAN="$(printf '%s\n' "$PREVIOUS_PROM" | sed -n 's/^hamstrack_deploy_verify_checks_ran //p' | tail -n 1 | tr -d '[:space:]')"
case "$PREVIOUS_CHECKS_RAN" in ''|*[!0-9]*) PREVIOUS_CHECKS_RAN=0 ;; esac
previous_check_ok() { # $1 = check label; what the previous run published for it, or empty
  printf '%s\n' "$PREVIOUS_PROM" | sed -n "s/^hamstrack_deploy_verify_check_ok{check=\"$1\"} //p" | tail -n 1 | tr -d '[:space:]'
}
# Whether the previous run READ this check and found it failing — as opposed to a 0 nobody has
# overwritten since a killed deploy wrote it pessimistically.
previously_read_as_failed() { # $1 = check label
  [ "$PREVIOUS_CHECKS_RAN" -gt 0 ] || return 1
  [ "$(previous_check_ok "$1")" = 0 ] || return 1
}
# The ONE place a check's value is decided. Pure: it prints and records nothing, because it is
# called from inside the redirection that writes the file.
_check_ok() { # $1 = check label, $2 = the value THIS run reached (0 failed, 1 passed, 2 skipped)
  if [ "$2" = 2 ] && previously_read_as_failed "$1"; then
    printf '0'
    return 0
  fi
  printf '%s' "$2"
}
# Two readings the public summary line quotes back, so the counts on it are the checks' own
# rather than a second computation that could disagree with them.
ENV_SERVICES_CHECKED=0
VERIFIED_APP_VERSION=''
APP_IDENTITY_STRENGTH=none
# The check labels, once. The gauge echoes them literally below (a loop would hide them from
# the scan that keeps the published set and the run set equal), and
# ApplyConfigVerifyPhaseTest compares this list with both.
VERIFY_CHECKS=(memory-limits environment-keys app-identity grafana drift-fresh)
VERIFY_RAN=()
VERIFY_SKIPPED=()
# A check READ the box, or it declined to because nothing on this box declares what it reads.
# Every check ends in exactly one of these, and the summary line publishes both counts.
verify_ran() { VERIFY_RAN+=("$1"); }
verify_skip() { # $1 = check label, $2 = why nothing here declares it
  VERIFY_SKIPPED+=("$1")
  log "verify: $1 skipped — $2"
}
write_verify_metrics() { # $1 = overall 0|1
  local out tmp overall _co
  out="$VERIFY_PROM"
  tmp="$out.$$"
  # THE ROLL-UP CANNOT BE GREENER THAN ITS PARTS. A check held at 0 because this run did not
  # read it is a 0 in the file, so `_ok 1` beside it would be the same claim one level up.
  overall="$1"
  if [ "$overall" = 1 ]; then
    for _co in "$(_check_ok memory-limits "$OK_MEMORY")" "$(_check_ok environment-keys "$OK_ENV")" \
               "$(_check_ok app-identity "$OK_APP")" "$(_check_ok grafana "$OK_GRAFANA")" \
               "$(_check_ok drift-fresh "$OK_DRIFT")"; do
      [ "$_co" != 0 ] || overall=0
    done
  fi
  if { mkdir -p "$TEXTFILE_DIR" && {
        echo '# HELP hamstrack_deploy_verify_ok Whether the last deploy on this box read its running state back and found it matching what it applied (1) or not (0). 0 also while a deploy is between its first mutation and its verify step, and 0 whenever any check_ok below is 0.'
        echo '# TYPE hamstrack_deploy_verify_ok gauge'
        echo "hamstrack_deploy_verify_ok $overall"
        echo '# HELP hamstrack_deploy_verify_check_ok Per check of the deploy verify phase: 0 failed or not yet run, 1 passed, 2 not applicable on this box (nothing here declares what it reads). DeployVerifyFailed fires on 0, so 2 pages nobody - but it is a DIFFERENT value from 1 on purpose: COMPOSE_FILES=docker-compose.prod.yml is a documented invocation that narrows what is verified, and with 1 for both a crash-looping Grafana nobody asked about looked exactly like a Grafana that was checked and healthy. A run that SKIPPED a check the previous run read as failing republishes that 0 rather than 2: a check that did not read the box may lower confidence, never raise it.'
        echo '# TYPE hamstrack_deploy_verify_check_ok gauge'
        echo "hamstrack_deploy_verify_check_ok{check=\"memory-limits\"} $(_check_ok memory-limits "$OK_MEMORY")"
        echo "hamstrack_deploy_verify_check_ok{check=\"environment-keys\"} $(_check_ok environment-keys "$OK_ENV")"
        echo "hamstrack_deploy_verify_check_ok{check=\"app-identity\"} $(_check_ok app-identity "$OK_APP")"
        echo "hamstrack_deploy_verify_check_ok{check=\"grafana\"} $(_check_ok grafana "$OK_GRAFANA")"
        echo "hamstrack_deploy_verify_check_ok{check=\"drift-fresh\"} $(_check_ok drift-fresh "$OK_DRIFT")"
        echo '# HELP hamstrack_deploy_verify_checks_ran How many of the verify checks actually READ the running box on this run. A check that was skipped because nothing on this box declares what it reads publishes check_ok 2 and is NOT counted here, so a box that verified nothing is distinguishable from one where every check passed.'
        echo '# TYPE hamstrack_deploy_verify_checks_ran gauge'
        echo "hamstrack_deploy_verify_checks_ran ${#VERIFY_RAN[@]}"
        echo '# HELP hamstrack_deploy_verify_timestamp_seconds Unix time the verify gauge was last written.'
        echo '# TYPE hamstrack_deploy_verify_timestamp_seconds gauge'
        echo "hamstrack_deploy_verify_timestamp_seconds $(date +%s)"
        echo '# HELP hamstrack_deploy_verify_info The commit the last verified (or verifying) deploy applied.'
        echo '# TYPE hamstrack_deploy_verify_info gauge'
        echo "hamstrack_deploy_verify_info{sha=\"$(sanitize_label "$SHA")\"} 1"
      } > "$tmp" && chmod 0644 "$tmp" && mv -f "$tmp" "$out"; } 2>/dev/null; then
    return 0
  fi
  rm -f -- "$tmp" 2>/dev/null || true
  log "WARN the verify gauge could not be written to $out — DeployVerifyFailed cannot see this run; this log and the exit code are the witness, and the previous file, if any, still stands"
}

verify_fail() { # $1 = check label, $2 = one-line finding
  log "verify: $1 FAILED: $2"
  VERIFY_FAILURES+=("$1: $2")
}

# --- check 1: memory-limits ----------------------------------------------------
# HostConfig.Memory of every running container == the resolved ceiling. Kept IN ADDITION to
# the drift script's `containers` scope because it is oracle-independent (HD-221 muted that
# oracle for a week) and it is HD-189's exact finding: `up -d` leaves a container whose limit
# was changed underneath it alone — measured, `docker update --memory` survives `up -d` with
# the same container id and a plan of `Running`. No declared ceiling is a WARN, not a failure
# — there is nothing to compare — and that is only defensible because the policy is held
# somewhere else: ProdComposeContractTest#everyServiceInEveryDeployedComposeFileDeclaresAMemoryCeiling
# reads BOTH deployed compose files and names the first service without one. Until HD-299's
# fix loop this sentence named a test that checked ONE service in ONE file, so the WARN was a
# fail-open excused by a citation.
check_memory_limits() { # $1 = declaration rows
  local svc declared bytes cids cid running bad=0 n=0
  verify_ran memory-limits
  while IFS= read -r svc; do
    [ -n "$svc" ] || continue
    declared="$(declared_memory "$1" "$svc")"
    if [ -z "$declared" ]; then
      # NO CEILING IN THE RESOLVED MODEL IS NOT THE SAME AS NO CEILING IN THE FILE, and the
      # gap between them is a live way to run production unbounded. MEASURED on Compose
      # v5.1.0 (2026-09-10): with `mem_limit: ${APP_MEMORY_LIMIT:-1g}` in the file and
      # APP_MEMORY_LIMIT=0 in .env, Compose DROPS mem_limit from the resolved model entirely
      # — the container runs with no limit, this check WARNed and passed, and
      # ProdComposeContractTest still saw the interpolation in the file text and passed too.
      # So the two are compared: a service whose FILE declares a ceiling and whose RESOLVED
      # model has none is a refusal, because something in .env turned it off. A service that
      # declares none anywhere stays a WARN — the file-level policy is the other test's.
      if service_declares_ceiling_in_file "$svc"; then
        verify_fail memory-limits "$svc declares a memory ceiling in the compose file text and the RESOLVED model has none, so the container runs UNBOUNDED — a value in $TARGET/.env interpolated it away (0 means unlimited to Docker, not 'use the default'; see .env.prod.example). Set it to a size or remove the override"
        bad=1
      else
        log "verify: WARN memory-limits: $svc declares no memory ceiling in the resolved configuration — nothing to compare (the policy that every service in the deployed compose set declares one is ProdComposeContractTest#everyServiceInEveryDeployedComposeFileDeclaresAMemoryCeiling)"
      fi
      continue
    fi
    if ! bytes="$(to_bytes "$declared")"; then
      # The VALUE is not printed: it is resolved from .env and this log is public. Its shape
      # locates the entry in a file the reader already has open, which is all they need.
      verify_fail memory-limits "$svc declares a memory ceiling this check cannot read as bytes ($(value_shape "$declared")) — a value it cannot read is not a value it may skip. It resolves from mem_limit / deploy.resources.limits.memory for $svc; read it with 'docker compose ${RUN_ARGS[*]} config' in $TARGET"
      bad=1; continue
    fi
    # ZERO IS UNLIMITED TO DOCKER, AND THIS BRANCH IS WHAT STOPS THE CHECK DEPENDING ON A
    # COMPOSE VERSION. Measured on Compose v5.1.0 (2026-09-10), `mem_limit: ${APP_MEMORY_LIMIT:-1g}`
    # with APP_MEMORY_LIMIT=0 is DROPPED from the resolved model, which the branch above catches
    # by comparing against the file text. A Compose that instead RESOLVES it to `0` — the shape a
    # self-hoster on v2 may well get, and not something this repository can promise about a
    # program it does not ship — walks straight past that branch: `to_bytes 0` is 0, the running
    # container's HostConfig.Memory is 0, the two agree and the check passes a container running
    # UNBOUNDED. So the finding is made about the VALUE rather than about one Compose's way of
    # expressing it, and both spellings of "somebody turned the ceiling off" are red.
    if [ "$bytes" = 0 ]; then
      verify_fail memory-limits "$svc resolves its memory ceiling to 0, which means UNLIMITED to Docker and not 'use the default', so the container runs UNBOUNDED — a value in $TARGET/.env turned it off (see .env.prod.example). Set it to a size or remove the override"
      bad=1; continue
    fi
    read_service_containers "$svc"; cids="$SERVICE_CIDS"
    if [ "$SERVICE_CONTAINERS_RC" -ne 0 ]; then
      service_containers_unanswered "$svc"
      verify_fail memory-limits "$svc: $SERVICE_UNANSWERED_NOTE"
      bad=1; continue
    fi
    if [ -z "$cids" ]; then
      verify_fail memory-limits "$svc is declared and has no running container whose ceiling could be read"
      bad=1; continue
    fi
    while IFS= read -r cid; do
      running="$(inspect_field '{{.HostConfig.Memory}}' "$cid")"
      if [ "$running" != "$bytes" ]; then
        verify_fail memory-limits "$svc runs with HostConfig.Memory=${running:-<unreadable>} while the resolved configuration declares $bytes bytes$( [ "$declared" = "$bytes" ] || printf ' (%s)' "$declared" ) — the running container was not created from this definition (HD-189's shape)"
        bad=1
      fi
    done <<< "$cids"
    n=$(( n + 1 ))
  done <<< "$SERVICES"
  if [ "$bad" = 0 ]; then
    OK_MEMORY=1
    log "verify: memory-limits ok ($n services)"
  fi
}

# --- check 2: environment-keys -------------------------------------------------
# keys(declared, resolved) ⊆ keys(running .Config.Env), for every service with an
# environment block. KEYS ONLY: a missing key is named because a name is what the operator
# needs and what .env.prod.example already publishes; a value is never read past the
# set/null flag compose_declarations reduced it to.
#
# SO A CHANGED VALUE IS OUT OF SCOPE HERE, DELIBERATELY, AND IT IS NOT UNWATCHED. Whatever
# this prints is public (see the header), and the resolved model holds every secret .env
# folds into an `environment:` block — comparing values would mean reading them, and a
# comparison that names which key disagreed is already halfway to publishing one. What
# notices a value that moved is the drift check's `containers` scope, which asks Compose's
# own `up -d --dry-run` whether it would ACT on a container: a changed environment value
# changes the service definition, so the plan says Recreate and the scope reads 1 without
# anybody looking at the value. This check is for the case that oracle cannot see — a
# container that predates the definition declaring the key at all — which is why the two
# exist side by side rather than one being the weaker half of the other.
check_environment_keys() { # $1 = declaration rows
  local svc keys key cids cid running bad=0 n=0
  verify_ran environment-keys
  while IFS= read -r svc; do
    [ -n "$svc" ] || continue
    keys="$(printf '%s\n' "$1" | awk -F'\t' -v s="$svc" '$1 == s && $2 == "env" && $4 == "set" { print $3 }')"
    [ -n "$keys" ] || continue
    read_service_containers "$svc"; cids="$SERVICE_CIDS"
    if [ "$SERVICE_CONTAINERS_RC" -ne 0 ]; then
      service_containers_unanswered "$svc"
      verify_fail environment-keys "$svc: $SERVICE_UNANSWERED_NOTE"
      bad=1; continue
    fi
    if [ -z "$cids" ]; then
      verify_fail environment-keys "$svc declares environment keys and has no running container to read them from"
      bad=1; continue
    fi
    while IFS= read -r cid; do
      running="$(inspect_field '{{range .Config.Env}}{{println .}}{{end}}' "$cid" | sed 's/=.*$//')"
      while IFS= read -r key; do
        [ -n "$key" ] || continue
        if ! printf '%s\n' "$running" | grep -qx -- "$key"; then
          verify_fail environment-keys "$svc: declared key $key is absent from the running container's environment (names only; values are never printed) — the container predates the definition that declares it"
          bad=1
        fi
      done <<< "$keys"
    done <<< "$cids"
    n=$(( n + 1 ))
  done <<< "$SERVICES"
  ENV_SERVICES_CHECKED="$n"
  if [ "$bad" = 0 ]; then
    OK_ENV=1
    log "verify: environment-keys ok ($n services)"
  fi
}

# --- check 3: app-identity -----------------------------------------------------
# (a) the app image's org.opencontainers.image.revision label is the sha this deploy placed
#     configuration for. Not compared when the sha is `unknown` (a self-hoster's `git pull`
#     run) and not compared when APP_IMAGE_TAG PINS the image — a pin by policy is a newer
#     tree beside an older image on purpose, and step 2b already decided that.
# (b) /api/meta answers from INSIDE the container (no host port is published; the healthcheck
#     already uses this wget), polled until a JSON body arrives: a refused connection, a
#     `service is not running` from exec and a 502 are all NOT YET, never a mismatch. The
#     version must be a stamped one (never dev / 0.0.0-DEV); it must equal
#     EXPECTED_APP_VERSION when the pipeline passes one (a release-tag deploy); otherwise it
#     must carry g<sha7> — or be a bare release version, which is what `git describe` yields
#     when main's tip IS the tagged commit, and (a) has then already tied the image to the sha.
check_app_identity() {
  local cid rev revnote img body version deadline expected="${EXPECTED_APP_VERSION:-}"
  if ! has_service app; then
    verify_skip app-identity "no service named app in (${COMPOSE_LIST[*]}) on this box"
    OK_APP=2
    return 0
  fi
  verify_ran app-identity
  read_service_containers app; cid="$(printf %s "$SERVICE_CIDS" | head -n 1)"
  if [ "$SERVICE_CONTAINERS_RC" -ne 0 ]; then
    service_containers_unanswered app
    verify_fail app-identity "$SERVICE_UNANSWERED_NOTE"
    return 0
  fi
  if [ -z "$cid" ]; then
    verify_fail app-identity "app is declared and has no running container"
    return 0
  fi
  # THE NEWLINE STRIP IS HERE, not inside inspect_field, which legitimately returns
  # multi-line output for the Config.Env template. A label CAN carry a newline (`docker build
  # --label` accepts one) and this value reaches a LOG LINE: a newline splits that line in
  # two, and the second half is attacker-shaped text sitting exactly where the public summary
  # anchor looks for its match.
  rev="$(inspect_field "$REVISION_LABEL_TEMPLATE" "$cid" | tr -d '\n')"
  if [ "$SHA" = unknown ]; then
    revnote="revision=unchecked(no sha given)"
  elif [ "$IMAGE_TAG" != latest ]; then
    # A PIN STILL VERIFIES SOMETHING, and it is the half that matters most during a rollback:
    # not "was this image built from this sha" (it deliberately was not), but WHICH IMAGE IS
    # RUNNING. Without this, the one deploy shape an operator reaches for under pressure —
    # APP_IMAGE_TAG=<the last good tag> — is the one shape where check 3 asserts nothing about
    # the image at all, and a pin that never took effect reads exactly like a pin that did.
    # Measured 2026-09-10 on Docker 29.2.1 / Compose v5.1.0: `.Config.Image` of a container
    # Compose created is the reference AS WRITTEN in the service definition, tag included
    # (`postgres:16-alpine`), so the pinned tag is its suffix.
    img="$(inspect_field '{{.Config.Image}}' "$cid")"
    case "$img" in
      *":$IMAGE_TAG")
        revnote="revision=unchecked(APP_IMAGE_TAG=$IMAGE_TAG pins the image, so it is not built from $SHA by design) image=$img" ;;
      *)
        verify_fail app-identity "APP_IMAGE_TAG pins the image to $IMAGE_TAG and the running app container was created from '${img:-<unreadable>}' — the pin did not take effect, so this box is not running the version somebody chose. Re-create it from the pinned tag: 'docker compose ${RUN_ARGS[*]} up -d' in $TARGET"
        revnote="revision=unchecked(pinned) image=${img:-<unreadable>}" ;;
    esac
  elif [ -z "$rev" ]; then
    verify_fail app-identity "the running app image carries no org.opencontainers.image.revision label, so it cannot be tied to $SHA"
    revnote="revision=<none>"
  elif [ "$rev" != "$SHA" ]; then
    verify_fail app-identity "the running app image was built from $rev, not from $SHA — the pulled tag did not carry this commit's build (if a newer build moved 'latest' since, the deploy queued behind this one is the one that verifies it)"
    revnote="revision=$rev"
  else
    revnote="revision=$rev"
  fi

  body=''
  deadline=$(( SECONDS + VERIFY_APP_TIMEOUT_SECONDS ))
  while :; do
    body="$(run_compose exec -T app wget -qO- http://localhost:8080/api/meta 2>/dev/null | tr -d '\r' || true)"
    case "$body" in *'"version"'*) break ;; esac
    body=''
    [ "$SECONDS" -lt "$deadline" ] || break
    sleep "$VERIFY_POLL_SECONDS"
  done
  if [ -z "$body" ]; then
    verify_fail app-identity "/api/meta did not answer with a JSON body inside ${VERIFY_APP_TIMEOUT_SECONDS}s, polled from inside the app container — the app did not come up on this definition. If this box is simply slow (a small VPS boots the app in minutes), raise VERIFY_APP_TIMEOUT_SECONDS (1..1800) — it is process environment, not $TARGET/.env, so it travels as 'VERIFY_APP_TIMEOUT_SECONDS=<seconds> sudo -E bash …/apply-config.sh …'"
    return 0
  fi
  version="$(printf '%s' "$body" | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
  # AN UNSTAMPED VERSION IS A FINDING ABOUT A PIPELINE DEPLOY AND A FACT OF LIFE FOR A
  # SELF-BUILT IMAGE. Dockerfile:9 is `ARG APP_VERSION=0.0.0-DEV`, so a source-available
  # operator who runs `docker build .` without passing the arg gets exactly this string —
  # legitimately, and for ever. Refusing it unconditionally means that reader can never
  # verify anything, which is the shape of "a refusal its reader cannot act on": their only
  # remedy would be to adopt our release pipeline. The distinguisher is already here: a run
  # with a SHA came from a build that stamps, so an unstamped version there means the pulled
  # tag is not what it claims; a run with `SHA=unknown` is the `git pull` invocation
  # docs/self-hosting.md prescribes, and it asserts what it can and says what it did not.
  # `''` stays fatal in BOTH modes: no version at all is a broken /api/meta, not a build
  # choice.
  case "$version" in
    '')
      verify_fail app-identity "/api/meta answered without a version field — the endpoint is not the app's, or the build is broken"
      return 0 ;;
    dev|0.0.0-DEV)
      if [ "$SHA" != unknown ]; then
        verify_fail app-identity "/api/meta reports version '$version' — an image with no stamped version is not a release build, and this deploy placed configuration for $SHA"
        return 0
      fi
      log "verify: WARN app-identity: /api/meta reports the unstamped default version '$version' (Dockerfile's ARG APP_VERSION default) — a locally built image. Nothing here can tie it to a source revision; pass --build-arg APP_VERSION=… to make this checkable"
      ;;
  esac
  if [ -n "$expected" ]; then
    if [ "$version" != "$expected" ]; then
      verify_fail app-identity "/api/meta reports version $version while the deployed ref expects $expected — the image that runs is not the release this deploy was for"
      return 0
    fi
  elif [ "$SHA" != unknown ] && [ "$IMAGE_TAG" = latest ]; then
    case "$version" in
      *"g${SHA:0:7}"*) : ;;
      *)
        if ! printf '%s' "$version" | grep -Eq '^[0-9]+(\.[0-9]+)+$'; then
          verify_fail app-identity "/api/meta reports version $version, which names neither g${SHA:0:7} nor a bare release version — the running build is not this commit's"
          return 0
        fi ;;
    esac
  fi
  VERIFIED_APP_VERSION="$version"
  # ONE GREEN, THREE STRENGTHS. A pass here means "the image is this commit's build and
  # /api/meta agrees" (full), or "no sha was given, so only the version was read"
  # (sha-unknown), or "a pin is deliberately holding an older image, so only WHICH image and
  # the version were read" (pinned). The gauge cannot express that and the log line buries it
  # in revnote, so the public summary names it: a reader comparing two deploys can otherwise
  # not tell a full verification from the weakest one it has.
  if [ "$SHA" = unknown ]; then
    APP_IDENTITY_STRENGTH=sha-unknown
  elif [ "$IMAGE_TAG" != latest ]; then
    APP_IDENTITY_STRENGTH=pinned
  else
    APP_IDENTITY_STRENGTH=full
  fi
  if [ "${#VERIFY_FAILURES[@]}" -eq 0 ] || ! printf '%s\n' "${VERIFY_FAILURES[@]}" | grep -q '^app-identity:'; then
    OK_APP=1
    log "verify: app-identity ok ($APP_IDENTITY_STRENGTH) $revnote version=$version"
  fi
}

# --- check 4: grafana ----------------------------------------------------------
# Only where the resolved compose set declares it; a box that runs no observability stack
# skips with a line. Where it IS declared and dead, this is RED, deliberately: HD-283 was two
# days of a healthy app behind a crash-looping watcher, and the deploy is the one moment
# somebody is looking. Three readings, each of which alone caught HD-283's shape: /api/health
# answering `"database":"ok"` (measured body on 11.5.2), RestartCount unchanged across a
# settle window with the container running, and no `level=error` line from any
# `logger=provisioning.*` since State.StartedAt — since StartedAt because alerting is
# provisioned at start, and an unchanged Grafana started weeks ago is still the one running.
# Measured on grafana:11.5.2 with this repository's tree: a sound start logs NO such line
# (the tree carries an empty provisioning/plugins/ for exactly that reason — without the
# directory Grafana logs one `provisioning.plugins` error on every start and this check
# would red a healthy box), and a uid of 41 characters logs one and exits. Template-expansion
# errors at evaluation time (`logger=ngalert.state.manager`) are a WARN, not a failure.
check_grafana() {
  local cid body deadline r1 r2 started running glog rc lines errs n first tmpl
  if ! has_service grafana; then
    verify_skip grafana "no grafana service in (${COMPOSE_LIST[*]}) on this box; nothing declares it, so nothing is asserted about it"
    OK_GRAFANA=2
    return 0
  fi
  verify_ran grafana
  read_service_containers grafana; cid="$(printf %s "$SERVICE_CIDS" | head -n 1)"
  if [ "$SERVICE_CONTAINERS_RC" -ne 0 ]; then
    service_containers_unanswered grafana
    verify_fail grafana "$SERVICE_UNANSWERED_NOTE"
    return 0
  fi
  if [ -z "$cid" ]; then
    verify_fail grafana "grafana is declared and has no running container (a crash-loop reads as an empty 'ps -q' between restarts; 'docker compose ps -a grafana' and 'docker logs' in $TARGET show it)"
    return 0
  fi
  body=''
  deadline=$(( SECONDS + VERIFY_GRAFANA_TIMEOUT_SECONDS ))
  while :; do
    # --max-time bounds HOW LONG, head -c bounds HOW MUCH. hold_foreign prints this body on
    # STDOUT behind "| ", ahead of the summary below, and SSM returns the first 24 000
    # characters of stdout (8 000 of stderr, where the mirror lands), so a large body pushes
    # this run's conclusion off the end of the log — which reads exactly like a killed deploy.
    # --max-filesize refuses a declared oversize up front; head -c holds a chunked one that
    # never declares a length. 4096, because the only thing read out of the body is
    # `"database":"ok"` and the refusal below reports a shape rather than the text.
    body="$(curl -fsS --max-time 5 --max-filesize 4096 "$GRAFANA_HEALTH_URL" 2>/dev/null | head -c 4096 | tr -d ' \n\r\t' || true)"
    case "$body" in *'"database":"ok"'*) break ;; esac
    [ "$SECONDS" -lt "$deadline" ] || break
    sleep "$VERIFY_POLL_SECONDS"
  done
  case "$body" in
    *'"database":"ok"'*) : ;;
    *)
      # THE BODY IS FOREIGN TEXT TOO. It used to be pasted verbatim, unbounded, on a stamped
      # line — whatever GRAFANA_HEALTH_URL answered with 2xx, published. Shape here, text on
      # the box, like every other door this script re-quotes. The budget names its VARIABLE:
      # a self-hoster on a small VPS whose Grafana is slow gets a refusal they can act on.
      hold_foreign "$body"
      verify_fail grafana "$GRAFANA_HEALTH_URL did not report database ok inside ${VERIFY_GRAFANA_TIMEOUT_SECONDS}s (raise VERIFY_GRAFANA_TIMEOUT_SECONDS, 1..1800, passed as process environment with 'sudo -E'). Its last answer was $(foreign_shape "$body") and is not republished here (this log is public); it is unstamped ABOVE behind a \"| \" marker, in this run's raw output on the box, or ask again with 'curl -fsS $GRAFANA_HEALTH_URL' on the box"
      return 0 ;;
  esac
  r1="$(inspect_field '{{.RestartCount}}' "$cid")"
  started="$(inspect_field '{{.State.StartedAt}}' "$cid")"
  sleep "$VERIFY_GRAFANA_SETTLE_SECONDS"
  r2="$(inspect_field '{{.RestartCount}}' "$cid")"
  running="$(inspect_field '{{.State.Running}}' "$cid")"
  if [ "$r1" != "$r2" ] || [ "$running" != true ]; then
    verify_fail grafana "grafana did not stay up across a ${VERIFY_GRAFANA_SETTLE_SECONDS}s settle window (VERIFY_GRAFANA_SETTLE_SECONDS, 0..600, process environment) — RestartCount ${r1:-?} -> ${r2:-?}, running=${running:-?}: a crash-loop under restart: unless-stopped, which 'up -d' exits 0 through"
    return 0
  fi
  # READ ONCE, into a variable. Twice was two `docker logs` over a window that is StartedAt to
  # now — weeks wide on a Grafana nobody has restarted — and the two reads could disagree.
  # `|| true` on the read would then FAIL OPEN: an unreadable log (the container gone between
  # the inspect and this line, a broken logging driver, a daemon that answers an error on
  # stderr) yields an empty string, which greps to zero provisioning errors and passes the
  # check that HD-283 exists for. A healthy Grafana ALWAYS logs its start banner, so an empty
  # window since StartedAt is not "clean", it is "not read" — and that is a finding.
  # `2>&1` IS PART OF THE READ AND NOT PART OF THE GUARD, and the difference was a second
  # fail-open hiding inside the first: Grafana writes its own log to stderr, so the redirect
  # is required — but it also folds DOCKER'S OWN ERROR into $glog, and "Error response from
  # daemon: …" is one non-empty line that greps to zero provisioning errors and publishes
  # green. So the EXIT CODE is captured separately from the text, and a non-zero read is a
  # finding whatever came back on the pipe. The empty-window case stays too: a healthy
  # Grafana always logs its start banner, so nothing since StartedAt is "not read", not
  # "clean". Read ONCE either way — the window is StartedAt to now, weeks wide on a Grafana
  # nobody has restarted, and two reads of it cost twice and may disagree.
  glog="$(docker logs --since "$started" "$cid" 2>&1 | tr -d '\r')" && rc=0 || rc=$?
  lines="$(printf '%s' "$glog" | grep -c . || true)"
  if [ "$rc" -ne 0 ] || [ "${lines:-0}" -eq 0 ]; then
    verify_fail grafana "could not read grafana's log — 'docker logs --since $started' exited $rc and returned ${lines:-0} line(s) for the running container, and a Grafana that started logs its own banner, so this check read nothing rather than finding nothing. Read it by hand in $TARGET: 'docker compose ${RUN_ARGS[*]} logs grafana'"
    return 0
  fi
  errs="$(printf '%s\n' "$glog" | grep -E 'level=error' | grep -E 'logger=provisioning' || true)"
  n="$(printf '%s' "$errs" | grep -c . || true)"
  if [ "$n" -gt 0 ]; then
    # A LENGTH BOUND IS NOT A CONTENT BOUND. `cut -c1-300` of Grafana's own line was the only
    # thing standing between a world-readable Actions log and whatever that line happens to
    # carry — a datasource URL with an inline credential, a provisioning error quoting a
    # secret it was handed. Grafana's log is logfmt, so the STRUCTURE is available: quote the
    # fields that name the fault (logger, msg) and nothing else, and tell the reader where the
    # rest is. Everything after this is still bounded, but the bound is now about WHAT rather
    # than HOW MUCH.
    # POSITIVE EXTRACTION, not subtraction: two named logfmt fields are lifted out and
    # everything else — including `error=`, which quoted a UID in the measured example and
    # could as easily quote a datasource credential — never leaves the box. That these two are
    # Grafana's own literals is a claim about third-party software, so the EXTRACTION is what
    # holds it rather than the sentence: `logger=` is bounded to an enumerated character class,
    # and `msg="…"` is ANCHORED at a field boundary and takes the FIRST match.
    # BOTH of those were bugs. `sed -nE 's/.*(msg="…").*/\1/p'` is greedy, so it published the
    # LAST msg= on the line, and it had no word boundary. Measured 2026-09-10:
    #   in : … logger=provisioning.datasources msg="failed" detail=msg="datasource url http://admin:Sekret123@db"
    #   out: msg="datasource url http://admin:Sekret123@db"
    # `grep -oE` with `(^| )` in front reports matches left to right, so `head -n 1` really is
    # the first field and `errormsg=`/`logmsg=` do not match at all — measured on the same line:
    #   out: msg="failed"
    first="$(printf '%s\n' "$errs" | head -n 1 | grep -oE '(^| )logger=[A-Za-z0-9._-]+' | head -n 1 | sed 's/^ //' || true)"
    first="${first:-logger=<unparsed>}"
    first="$first $(printf '%s\n' "$errs" | head -n 1 | grep -oE '(^| )msg="[^"]{0,120}"' | head -n 1 | sed 's/^ //' || true)"
    # THE REMEDY IS NOT "RE-RUN THE DEPLOY". This window is StartedAt to now, and step 7b
    # restarts Grafana only when a synced observability/ path CHANGED — so a re-run that
    # changes nothing leaves the same error inside the same window and this finding is
    # permanent. What clears it is restarting Grafana explicitly (which re-provisions and
    # moves StartedAt past the error), then re-reading. Both commands are ones its reader
    # can run on the box.
    verify_fail grafana "grafana logged $n provisioning error line(s) since it started at $started — first (logger and message only; the error= field is WITHHELD because it quotes whatever Grafana was handed, and this log is public — read the full line in $TARGET with 'docker compose ${RUN_ARGS[*]} logs grafana'): $first. Re-running this deploy does NOT clear it (Grafana is only restarted when observability/ changed, so the error stays inside the window): fix the rule or datasource the line names, then in $TARGET run 'docker compose ${RUN_ARGS[*]} restart grafana' and re-read with 'bash $TARGET/ops/deploy/apply-config.sh $TARGET $TARGET $SHA --verify-only'"
    return 0
  fi
  tmpl="$(printf '%s\n' "$glog" | grep -E 'logger=ngalert.state.manager' | grep -c 'Error in expanding template' || true)"
  [ "${tmpl:-0}" -eq 0 ] || log "verify: WARN grafana: $tmpl template-expansion error line(s) since $started — a rule's annotation is not a template Grafana can expand; the rule still fires, its text is lost (see the header of rules.yml)"
  OK_GRAFANA=1
  log "verify: grafana ok restarts=$r2 provisioning-errors=0 since $started"
}

# --- check 5: drift-fresh ------------------------------------------------------
# Reads the FILE step 9 wrote — never Prometheus, so a Prometheus outage cannot red a deploy
# — gated by T9, the second taken immediately before step 9: a timestamp older than that
# means step 9 did not publish (HD-287's shape: the unit failed before its write and the
# previous file stayed in place looking fresh). `files` and `containers` are the two scopes a
# deploy controls, so a 1 there is fatal; `installed-ops` and `edge-body-limit` need a hand
# step a deploy cannot take, so they are a WARN naming that step — a refusal may only
# prescribe what its reader can do. The stamped sha must be this deploy's.
check_drift_fresh() { # $1 = T9
  local f ts files containers installed edge dsha now
  f="$TEXTFILE_DIR/hamstrack_config.prom"
  if [ ! -f "$DRIFT" ]; then
    # Same contract as a service the box does not declare: a tree that carries no drift
    # script wrote no gauge, and there is nothing here to be fresh. A script that IS present
    # and did not publish is the case below, and that one is red.
    verify_skip drift-fresh "$DRIFT is not on this box, so step 9 published nothing to read back"
    OK_DRIFT=2
    return 0
  fi
  # SAME CONTRACT, SECOND CASE: a --verify-only run on a box THIS SCRIPT HAS NEVER APPLIED.
  # Every reading this check makes is against what a deploy stamped — `files` diffs the box
  # against .deployed-manifest.sha256, and the sha comparison is against .deployed-sha — so on
  # an unstamped box the comparand does not exist and `files: … is absent` is the check
  # answering a question nobody asked, not a finding about the box. It is also a refusal whose
  # only remedy is "run a deploy", which is precisely what this reader chose not to do:
  # docs/self-hosting.md points every self-hoster at --verify-only as a safe read of a running
  # box, and a hand-built box is the common shape there. UNREACHABLE ON A DEPLOY: step 6
  # writes both stamps before step 9, so this branch exists only for the read-only mode. It is
  # a SKIP and therefore not counted in ran=, so the summary line says the box was verified
  # less thoroughly rather than implying it passed.
  if [ "$VERIFY_ONLY" = 1 ] && [ ! -r "$TARGET/.deployed-manifest.sha256" ]; then
    verify_skip drift-fresh "$TARGET has no .deployed-manifest.sha256, so this script has never applied it and there is no stamped state for the drift scopes to compare against — the other checks still read the running containers. Run a deploy against this box to make this check meaningful"
    OK_DRIFT=2
    return 0
  fi
  verify_ran drift-fresh
  if [ ! -r "$f" ]; then
    verify_fail drift-fresh "$f is absent — step 9 published nothing (the drift script's own lines above say why)"
    return 0
  fi
  ts="$(sed -n 's/^hamstrack_config_check_timestamp_seconds //p' "$f" | tail -n 1 | tr -d '[:space:]')"
  if [ -z "$ts" ] || [ -n "${ts//[0-9]/}" ] || [ "$ts" -lt "$1" ]; then
    verify_fail drift-fresh "the drift check did not publish — hamstrack_config_check_timestamp_seconds is ${ts:-absent}, older than this run's step 9 at $1, so the file is a previous run's (HD-287's shape)"
    return 0
  fi
  files="$(sed -n 's/^hamstrack_config_drift{scope="files"} //p' "$f" | tail -n 1 | tr -d '[:space:]')"
  containers="$(sed -n 's/^hamstrack_config_drift{scope="containers"} //p' "$f" | tail -n 1 | tr -d '[:space:]')"
  installed="$(sed -n 's/^hamstrack_config_drift{scope="installed-ops"} //p' "$f" | tail -n 1 | tr -d '[:space:]')"
  edge="$(sed -n 's/^hamstrack_config_drift{scope="edge-body-limit"} //p' "$f" | tail -n 1 | tr -d '[:space:]')"
  dsha="$(sed -n 's/^hamstrack_config_deployed_info{sha="\([^"]*\)"}.*/\1/p' "$f" | tail -n 1)"
  [ "$files" = 0 ] \
    || verify_fail drift-fresh "hamstrack_config_drift{scope=\"files\"} reads ${files:-absent} — a synced path differs from the release right after it was placed; the drift lines above name it"
  [ "$containers" = 0 ] \
    || verify_fail drift-fresh "hamstrack_config_drift{scope=\"containers\"} reads ${containers:-absent} — 'docker compose up -d' would still act on a container after step 7, or the plan could not be read; the drift lines above name it"
  [ "$installed" = 0 ] \
    || log "verify: WARN drift-fresh: installed-ops=${installed:-absent} — a copy installed under /usr/local/bin or /etc/systemd/system differs from the synced one. A deploy cannot install; run the step in docs/release-checklist.md ('Releases that change a file the box runs from a COPY'). ConfigDrift reports it meanwhile"
  [ "$edge" = 0 ] \
    || log "verify: WARN drift-fresh: edge-body-limit=${edge:-absent} — the deployed Caddyfile bounds no request body. A deploy never syncs that file; merge the block by hand (docs/ops-prod-hardening.md §2)"
  [ "$dsha" = "$(sanitize_label "$SHA")" ] \
    || verify_fail drift-fresh "hamstrack_config_deployed_info names sha ${dsha:-<none>} while this run stamped $SHA — the drift check read a different .deployed-sha than step 6 wrote"
  if [ "${#VERIFY_FAILURES[@]}" -eq 0 ] || ! printf '%s\n' "${VERIFY_FAILURES[@]}" | grep -q '^drift-fresh:'; then
    OK_DRIFT=1
    now="$(date +%s)"
    log "verify: drift-fresh ok files=0 containers=0 age=$(( now - ts ))s"
  fi
}

# --- the refusal ----------------------------------------------------------------
# One block, at most 25 lines with the findings, and it names ONLY actions its reader can
# perform: fix forward (push, or re-run the idempotent deploy, or --verify-only after a hand
# fix) or return by hand (the backup this run took, and the image that was running before
# it — pinnable as sha-<7> because every main build publishes that tag). NOTHING IS ROLLED
# BACK by a standing owner decision: a deploy that reverts itself turns a half-understood
# state into a second one nobody chose.
BACKUP_DIR=''
PREVIOUS_APP_REVISION=''
verify_refuse() {
  local msg backup_note prev_note f
  if [ -n "$BACKUP_DIR" ]; then
    backup_note="the synced files as they were before this run are in $BACKUP_DIR"
  else
    backup_note="no synced path changed in this run, so no backup was taken and the files need no restoring"
  fi
  if [ -n "$PREVIOUS_APP_REVISION" ]; then
    prev_note="APP_IMAGE_TAG=sha-${PREVIOUS_APP_REVISION:0:7} (the image that was running before this run, built from $PREVIOUS_APP_REVISION)"
  else
    prev_note="APP_IMAGE_TAG=<the previous release tag, or sha-<first 7 characters of the previous commit>>"
  fi
  msg="VERIFY FAILED — ${#VERIFY_FAILURES[@]} finding(s):$(withheld_note)"
  for f in "${VERIFY_FAILURES[@]}"; do msg="$msg
  - $f"; done
  # WHAT THE READER JUST DID DECIDES WHAT THEY ARE TOLD. A --verify-only run places nothing,
  # stamps nothing, pulls nothing and brings nothing up, so "the configuration IS APPLIED and
  # the containers WERE brought up … production may be half-updated" is false in every clause
  # — and it is the sentence that sends somebody hunting for a rollback after a READ. It also
  # cannot offer this run's backup directory or the image "running before this run", because
  # this run had no before. Two paragraphs, one per mode, rather than one hedged paragraph
  # that is wrong for whoever is not the deployer.
  if [ "$VERIFY_ONLY" = 1 ]; then
    msg="$msg
This was a --verify-only run: NOTHING was placed, stamped, pulled or brought up, and nothing is rolled back because nothing was changed. The findings above describe the box AS IT ALREADY WAS. Read them, then EITHER
  fix the box: correct $TARGET/.env or the file a finding names, apply the fix the finding prescribes (some name their own command), and re-read with the same line you just ran;
  OR bring it back in line with the release: run the deploy itself — 'bash <a checkout>/ops/deploy/apply-config.sh <that checkout> $TARGET <sha>' — which is what places files and recreates containers.
Until a run verifies green here, DeployVerifyFailed stays firing."
  else
    msg="$msg
The configuration from $SHA IS APPLIED and stamped and the containers WERE brought up; nothing is rolled back (by decision — a deploy never reverts itself). Production may be half-updated. Read the findings, then EITHER
  fix forward: correct $TARGET/.env or the file a finding names and push, or re-run this deploy (it is idempotent — but read a finding that says otherwise, some name their own command), or after a hand fix in $TARGET re-run only the checks: bash $TARGET/ops/deploy/apply-config.sh $TARGET $TARGET $SHA --verify-only;
  OR return to the previous state by hand: $backup_note; pin the previous image with $prev_note in $TARGET/.env; then 'docker compose ${RUN_ARGS[*]} up -d' in $TARGET. The next deploy then refuses on the moved pin until you un-pin or --adopt-pin (docs/ops-prod-hardening.md §3).
Until a run verifies green here, DeployVerifyFailed stays firing."
  fi
  die "$msg"
}

# --- pre-flight: read the box BEFORE anything is changed -----------------------
# The HD-199 read-back moved AHEAD of the change: on 2026-08-26 this would have printed
# `up -d would act on hamstrack-app-1 (Recreate)` and its two siblings, and three ceilings
# reading `running=0` against a log that claimed nothing to do. It plans the deploy's own
# command against the RELEASED files (the drift script's oracle, minus --remove-orphans, whose
# orphan warning is quoted instead) and pairs each declared ceiling with the running one.
# NEVER FATAL: a mid-outage plan aborts on an unhealthy dependency, and the deploy exists to
# fix what this reports. Names and counts only. It also remembers the revision of the app
# image that is running NOW, which is what the refusal at step 10 names as the way back.
preflight() {
  local plan rc pairs names name verbs acting=0 total=0 orphans rows svc declared bytes cids cid running note cid_app head20
  log "pre-flight: reading the box before anything is changed (names only; a finding here is what the deploy is about to fix, never a refusal)"
  plan="$(release_compose --ansi never up -d --dry-run 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    head20="$(printf '%s\n' "$plan" | head -n 20)"
    log "WARN pre-flight: 'docker compose up -d --dry-run' against the release exited $rc — the plan could not be read (an unhealthy dependency aborts it); the deploy proceeds. Compose wrote $(foreign_shape "$head20") (first 20 line(s)). That text is NOT republished here: this log is public and an interpolation error quotes the .env value it substituted (measured). It is unstamped BELOW behind a \"| \" marker, in this run's raw output on the box — read it in this run's output (over SSM if the deploy ran from Actions), or re-run 'docker compose up -d --dry-run' in $TARGET."
    hold_foreign "$head20"
  else
    pairs="$(printf '%s\n' "$plan" | plan_container_pairs)"
    names="$(printf '%s\n' "$pairs" | cut -f1 | sed '/^$/d' | LC_ALL=C sort -u)"
    while IFS= read -r name; do
      [ -n "$name" ] || continue
      total=$(( total + 1 ))
      verbs="$(printf '%s\n' "$pairs" | awk -F'\t' -v n="$name" '$1 == n { print $2 }' | LC_ALL=C sort -u | tr '\n' ',' | sed 's/,$//; s/,/, /g')"
      case ", $verbs," in
        *", Running,"*) ;;
        *) acting=$(( acting + 1 )); log "pre-flight: up -d would act on $name ($verbs)" ;;
      esac
    done <<< "$names"
    log "pre-flight: plan — $acting of $total container(s) would be acted on by 'up -d'"
    if printf '%s\n' "$plan" | grep -q 'Found orphan containers'; then
      orphans="$(printf '%s\n' "$plan" | tr -d '\r' | sed -n 's/.*Found orphan containers (\[\([^]]*\)\]).*/\1/p' | head -n 1)"
      log "pre-flight: compose reports orphan containers ${orphans:-(in a form this script could not parse)} — 'up -d --remove-orphans' at step 7 removes them"
    fi
  fi
  rows="$(release_compose config 2>/dev/null | compose_declarations || true)"
  while IFS= read -r svc; do
    [ -n "$svc" ] || continue
    declared="$(declared_memory "$rows" "$svc")"
    bytes="$(to_bytes "${declared:-x}" 2>/dev/null || true)"
    read_service_containers "$svc"; cids="$SERVICE_CIDS"
    if [ "$SERVICE_CONTAINERS_RC" -ne 0 ]; then
      log "pre-flight: ceiling $svc unread (docker compose ps -q exited $SERVICE_CONTAINERS_RC) declared=${bytes:-none}"
      continue
    fi
    if [ -z "$cids" ]; then
      log "pre-flight: ceiling $svc running=none (no running container) declared=${bytes:-none}"
      continue
    fi
    while IFS= read -r cid; do
      running="$(inspect_field '{{.HostConfig.Memory}}' "$cid")"
      note=''
      [ "$running" = "$bytes" ] || note=' (differs)'
      log "pre-flight: ceiling $svc running=${running:-<unreadable>} declared=${bytes:-none}$note"
    done <<< "$cids"
  done <<< "$SERVICES"
  if has_service app; then
    read_service_containers app; cid_app="$(printf %s "$SERVICE_CIDS" | head -n 1)"
    # THE SIXTH CALL SITE, and the only one that used to skip this branch. An unanswerable
    # `ps -q` here leaves PREVIOUS_APP_REVISION empty, which is INDISTINGUISHABLE from "no app
    # container was running" — and verify_refuse then degrades its way-back advice from a
    # concrete APP_IMAGE_TAG=sha-<7> to the generic placeholder, telling an operator to find a
    # tag by hand when one could have been named. Every read through read_service_containers
    # says which of the two it got; this one says it in the pre-flight log, because a pre-flight
    # never refuses.
    if [ "$SERVICE_CONTAINERS_RC" -ne 0 ]; then
      service_containers_unanswered app
      log "pre-flight: the revision of the app image running now is UNREAD, not absent — $SERVICE_UNANSWERED_NOTE. A refusal at step 10 will name the previous image as a placeholder rather than a tag"
    fi
    # Same reason as the rev= read in check 3: this reaches a log line. sanitize_label below
    # would strip a newline too, but only after it has been through one already.
    [ -z "$cid_app" ] || PREVIOUS_APP_REVISION="$(inspect_field "$REVISION_LABEL_TEMPLATE" "$cid_app" | tr -d '\n')"
    PREVIOUS_APP_REVISION="$(sanitize_label "${PREVIOUS_APP_REVISION:-}")"
    [ "$PREVIOUS_APP_REVISION" != unknown ] || PREVIOUS_APP_REVISION=''
    [ -z "$PREVIOUS_APP_REVISION" ] || log "pre-flight: the app image running now was built from $PREVIOUS_APP_REVISION"
  fi
}
if [ "$VERIFY_ONLY" = 0 ]; then
  preflight
fi

# --- step 3: --dry-run stops here --------------------------------------------
# What makes the FIRST sync — six weeks of changes in one step — a reviewed change rather
# than a surprise.
if [ "$DRY_RUN" = 1 ]; then
  log "DRY RUN — box ($TARGET) versus release ($SRC); nothing will be written"
  for entry in "${ENTRIES[@]}"; do
    printf '\n===== %s =====\n' "$entry"
    if [ ! -e "$TARGET/$entry" ]; then
      printf 'not present on the box — the whole path would be added\n'
      continue
    fi
    diff -ru "$TARGET/$entry" "$SRC/$entry" || true
  done
  printf '\n'
  log "DRY RUN complete — no file was replaced, no stamp was written, the stack was not touched"
  exit 0
fi

# Steps 4 to 8 are the mutation; a --verify-only run skips straight to steps 9 and 10.
if [ "$VERIFY_ONLY" = 0 ]; then

# --- residue from an interrupted earlier run ----------------------------------
# apply_path stages beside the destination, so a kill mid-apply can leave `.apply-tmp-*`
# copies that nothing names afterwards. We hold the lock, so anything found now is from a
# run that is over; every entry is re-applied below regardless. Logged rather than removed
# silently, because their existence is the only evidence that a deploy was ever
# interrupted here — and by the time anyone looks, the journal has rotated.
while IFS= read -r residue; do
  log "WARN residue from an interrupted earlier run, removing: ${residue#"$TARGET/"}"
  rm -rf -- "$residue"
done < <(find "$TARGET" -maxdepth 3 -name '.apply-tmp-*' -print 2>/dev/null || true)

# --- step 4: back up what is about to be replaced ----------------------------
CHANGED=()
for entry in "${ENTRIES[@]}"; do
  differs "$entry" && CHANGED+=("$entry")
done

# Whether anything a container BIND-MOUNTS changed — read by step 7b, computed here because
# after step 5 the box and the release no longer differ and the answer would be lost.
OBS_CHANGED=0
if [ "${#CHANGED[@]}" -gt 0 ]; then
  for entry in "${CHANGED[@]}"; do
    case "$entry" in observability|observability/*) OBS_CHANGED=1 ;; esac
  done
fi

if [ "${#CHANGED[@]}" -eq 0 ]; then
  log "no synced path differs from the release — skipping the backup"
else
  BACKUP_DIR="$TARGET/.config-backup/$(date -u +%Y-%m-%dT%H%M%SZ)"
  mkdir -p "$BACKUP_DIR"
  # Count what was actually copied, not what changed: a path the release ADDS has nothing
  # on the box to preserve, and a backup line claiming otherwise is read during a rollback.
  SAVED=0
  for entry in "${CHANGED[@]}"; do
    [ -e "$TARGET/$entry" ] || continue
    mkdir -p "$BACKUP_DIR/$(dirname "$entry")"
    cp -a -- "$TARGET/$entry" "$BACKUP_DIR/$entry"
    SAVED=$(( SAVED + 1 ))
  done
  log "${#CHANGED[@]} path(s) differ; backed up the $SAVED that existed to $BACKUP_DIR"
  # Keep the last 5. The names are UTC timestamps, so lexicographic order is chronological.
  find "$TARGET/.config-backup" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' \
    | LC_ALL=C sort -r | tail -n +6 \
    | while IFS= read -r old; do rm -rf -- "${TARGET:?}/.config-backup/$old"; done
fi

# --- step 5: apply ------------------------------------------------------------
# Staged beside the destination and renamed into place, so an interrupted command cannot
# leave a half-written file. Across SEVERAL paths this is still not one transaction: the
# residual is a mixed set, the remedy is re-running the deploy (it is idempotent) or
# .config-backup/, and the `files` drift scope is what notices.
# Set by apply_path for exactly as long as a destination is mid-swap; read by the cleanup
# below. Globals rather than locals because a trap handler may run from anywhere.
APPLY_DEST=''
APPLY_STAGED=''
apply_cleanup() {
  [ -n "$APPLY_DEST" ] || return 0
  # Between the two renames of a directory swap the destination DOES NOT EXIST. A kill in
  # that window used to leave the synced path absent, two orphaned copies beside it and
  # nothing naming them. Put the old tree back — an old config is a running box, an absent
  # one is not — and clear the copies either way.
  if [ ! -e "$APPLY_DEST" ] && [ -e "$APPLY_STAGED.old" ]; then
    mv -- "$APPLY_STAGED.old" "$APPLY_DEST" \
      && log "WARN interrupted mid-swap: restored $APPLY_DEST from the copy that had stepped aside"
  fi
  rm -rf -- "$APPLY_STAGED" "$APPLY_STAGED.old"
  APPLY_DEST=''
  APPLY_STAGED=''
}

apply_path() {
  local entry="$1" dest parent staged
  dest="$TARGET/$entry"
  parent="$(dirname "$dest")"
  mkdir -p "$parent"
  staged="$parent/.apply-tmp-$(basename "$entry").$$"
  rm -rf -- "$staged" "$staged.old"
  cp -a -- "$SRC/$entry" "$staged"
  APPLY_DEST="$dest"
  APPLY_STAGED="$staged"
  if [ -d "$staged" ] && [ -d "$dest" ]; then
    # A directory cannot be renamed onto an existing one, so it is two renames rather than
    # one: the old tree steps aside, the new one takes the name, the old one is removed.
    mv -- "$dest" "$staged.old"
    mv -- "$staged" "$dest"
    rm -rf -- "$staged.old"
  else
    rm -rf -- "$dest"
    mv -- "$staged" "$dest"
  fi
  APPLY_DEST=''
  APPLY_STAGED=''
}

# THE FIRST MUTATION. From here until step 10 rewrites it, the verify gauge reads 0 for every
# check, so a run killed anywhere in between is a firing DeployVerifyFailed and not a stale 1.
write_verify_metrics 0

# Scoped deliberately: armed for the apply loop only and disarmed immediately after, so it
# can never fire for an unrelated later failure and can never surprise a reader of step 7.
trap 'apply_cleanup' EXIT
trap 'apply_cleanup; exit 130' INT
trap 'apply_cleanup; exit 143' TERM
for entry in "${ENTRIES[@]}"; do
  apply_path "$entry"
done
trap - EXIT INT TERM
log "applied ${#ENTRIES[@]} path(s) from $SRC"

# --- step 6: stamp ------------------------------------------------------------
# Relative names, produced from inside the target, so `sha256sum -c` works there unchanged.
checksum_manifest() {
  local entry file
  for entry in "${ENTRIES[@]}"; do
    if [ -d "$entry" ]; then
      find "$entry" -type f -print | LC_ALL=C sort | while IFS= read -r file; do
        sha256sum "$file"
      done
    else
      sha256sum "$entry"
    fi
  done
}

printf '%s\n' "$SHA" > "$TARGET/.deployed-sha"
date -u +%Y-%m-%dT%H:%M:%SZ > "$TARGET/.deployed-at"
# The image tag this configuration was applied BESIDE, stamped next to the sha because the
# pair is the deployed state and the sha alone is half of it: the tag is mutable, so
# `.deployed-sha` says which tree the files came from and says nothing about what runs.
# Read it during an incident before believing that a rollback rolled everything back.
#
# WITHHELD after a bare --allow-pinned, and that is the flag's entire meaning. Re-stamping
# here would make the .env pin and the stamp agree again, so the next unattended run would
# read "unmoved", call it a steady-state re-apply and place the newest configuration tree
# onto an image an incident is deliberately holding back — the exact case step 2b exists to
# refuse, re-opened by an override taken under pressure. --adopt-pin is how a reader who
# has genuinely moved version says so, and it is the only thing that moves this file when
# the pin disagreed. The sha, the timestamp and the checksums ARE written either way: those
# describe what is now on disk, which the override really did change.
STAMPED_IMAGE_TAG=1
if [ "$PIN_STATE" != ok ] && [ "$ADOPT_PIN" = 0 ]; then
  STAMPED_IMAGE_TAG=0
  log "WARN --allow-pinned: leaving $TARGET/.deployed-image-tag at ${LAST_IMAGE_TAG:-<absent>} rather than $IMAGE_TAG — the pin and the stamp still disagree, so the next run without a flag refuses again. Adopt the tag with --adopt-pin when it is the intended version."
else
  printf '%s\n' "$IMAGE_TAG" > "$TARGET/.deployed-image-tag"
fi
(
  cd "$TARGET" || exit 1
  checksum_manifest > ".deployed-manifest.sha256.$$"
  mv -f ".deployed-manifest.sha256.$$" .deployed-manifest.sha256
)
chmod 0644 "$TARGET/.deployed-sha" "$TARGET/.deployed-at" "$TARGET/.deployed-manifest.sha256"
# Conditional because a bare --allow-pinned deliberately does not create it, and on a box
# with no stamp yet there is then nothing to chmod — an unconditional one would turn a
# successful deploy red at the last line.
[ ! -e "$TARGET/.deployed-image-tag" ] || chmod 0644 "$TARGET/.deployed-image-tag"
if [ "$STAMPED_IMAGE_TAG" = 1 ]; then
  log "stamped .deployed-sha=$SHA .deployed-image-tag=$IMAGE_TAG and $(wc -l < "$TARGET/.deployed-manifest.sha256") checksums"
else
  log "stamped .deployed-sha=$SHA, left .deployed-image-tag unchanged (see the --allow-pinned warning above) and wrote $(wc -l < "$TARGET/.deployed-manifest.sha256") checksums"
fi

# --- step 7/8: bring the stack up ---------------------------------------------
# EVERY compose file, in pull AND in up (run_compose, defined at step 2). This used to be a
# warning comment in two documents; here it is code, so the accident it prevents — `up -d
# --remove-orphans` with only the prod file deletes loki/alloy/grafana/prometheus/the
# exporters as orphans — stops depending on whoever types the command.
# --quiet, AND WHICH BUDGET IT DEFENDS. MEASURED 2026-09-10 on Compose v5.1.0: `pull` and
# `up -d` write NOTHING to stdout — every progress line goes to STDERR (a two-service `up -d`
# produced 0 bytes of stdout and 349 of stderr). Step 7 CAPTURES that stderr below and
# hold_foreign re-emits it on STDOUT behind "| ", so per-layer progress on a cold pull is now
# spent from the 24 000-character STDOUT budget — the channel this script's conclusion is at
# the end of. It suppresses PROGRESS only; an error still prints and still fails the run.
#
# EACH ONE ENDS IN A STAMPED REFUSAL, AND NEITHER USED TO. These two lines had no `|| die`,
# and the EXIT/INT/TERM traps are cleared just above at the end of the apply loop — so `set -e`
# aborted here with no handler and nothing stamped. Under SSM that is a red deploy whose whole
# public log reads "N line(s) withheld": Compose's own progress and error text is unstamped
# and correctly dropped by deploy.yml's allow-list, and there was no line of ours left to say
# what failed. Every way this script can end now ends in a line its reader can act on, and the
# two commands are named apart because they leave the box in different states.
#
# THESE TWO WERE THE LAST DOORS ON THE OLD ARGUMENT. Compose's stderr streamed straight out
# here, unmarked, because it is unstamped and deploy.yml's allow-list therefore drops it — which
# is the deny-list-by-absence the "| " marker replaced at every other door: it holds only for as
# long as no line Compose writes begins with this script's timestamp. MEASURED 2026-09-10 by
# feeding these two doors one line of exactly that shape (ApplyConfigVerifyPhaseTest's
# CRAFTED_STAMP_FORGERIES): it was published into the world-readable Actions log from both.
# Captured, marked and counted like every other door now — on success as well as on failure, so
# the text still reaches the box's journal and this invocation's raw output.
#
# ONE HELPER FOR BOTH COMMANDS, so the capture, the hold and the stamped shape line cannot drift
# apart. The shape is named on a stamped line and not only inside the refusal because a GREEN
# deploy holds lines too, and the legend for "| " is in text that run never prints.
step7_run() { # $1.. = compose arguments; sets STEP7_RC/STEP7_TEXT. Never inside $( ) or a pipe.
  local err
  err="$(mktemp)"
  run_compose "$@" 2>"$err" && STEP7_RC=0 || STEP7_RC=$?
  STEP7_TEXT="$(cat "$err" 2>/dev/null || true)"
  rm -f "$err"
  hold_foreign "$STEP7_TEXT"
  [ -n "$STEP7_TEXT" ] || return 0
  log "step 7: 'docker compose ${RUN_ARGS[*]} $*' wrote $(foreign_shape "$STEP7_TEXT"); it is not republished here (this log is public) and is unstamped ABOVE behind a \"| \" marker, in this run's raw output on the box."
}
step7_run pull --quiet
[ "$STEP7_RC" -eq 0 ] \
  || die "step 7: 'docker compose ${RUN_ARGS[*]} pull --quiet' failed in $TARGET. The configuration from $SHA IS APPLIED and stamped, and NOTHING was recreated — the box is still running the images it was running before, beside the new configuration. Compose wrote $(foreign_shape "$STEP7_TEXT") and it is not republished here (this log is public); it is unstamped ABOVE behind a \"| \" marker, in this run's raw output on the box. Read it in this run's output (over SSM if the deploy ran from Actions), or re-run the same pull in $TARGET: a registry the box cannot reach or an APP_IMAGE_TAG that does not exist are the two shapes this takes. Until a run verifies green, DeployVerifyFailed stays firing."
step7_run up -d --remove-orphans
[ "$STEP7_RC" -eq 0 ] \
  || die "step 7: 'docker compose ${RUN_ARGS[*]} up -d --remove-orphans' failed in $TARGET. The configuration from $SHA IS APPLIED and stamped and the images WERE pulled, so production may be half-updated — some containers may have been recreated before Compose stopped. Compose wrote $(foreign_shape "$STEP7_TEXT") and it is not republished here (this log is public); it is unstamped ABOVE behind a \"| \" marker, in this run's raw output on the box. Read it in this run's output (over SSM if the deploy ran from Actions), or re-run the same command in $TARGET (it is idempotent), then re-read with 'bash $TARGET/ops/deploy/apply-config.sh $TARGET $TARGET $SHA --verify-only'. Until a run verifies green, DeployVerifyFailed stays firing."

# --- step 7b: restart the services whose configuration is BIND-MOUNTED --------
# `up -d` compares the SERVICE DEFINITION, and a bind mount's spec does not change when the
# file behind it does. Replacing observability/ wholesale therefore leaves each container
# holding the DELETED INODE of its old config while compose correctly does nothing — and
# BOTH drift scopes read 0, because the definition really does match and the file on disk
# really is the released one. Every check agrees and the merged alert rule is not running:
# HD-199's own failure class, one layer down.
#
# This was documented in prose, for Grafana only. Prometheus, Loki and Alloy are mounted
# exactly the same way, so all four are here. A service that gains a `./observability/…`
# bind mount belongs in this list in the same commit; one that has none must NOT, because a
# restart it does not need is downtime it does not need either.
BIND_MOUNT_SERVICES=(grafana prometheus loki alloy)
restart_bind_mounted() {
  local svc cid restarted=0
  for svc in "${BIND_MOUNT_SERVICES[@]}"; do
    # Absent from this deployment (no observability compose file) or simply not running:
    # either way there is nothing to restart and nothing to warn about.
    cid="$(run_compose ps -q "$svc" 2>/dev/null || true)"
    [ -n "$cid" ] || continue
    run_compose restart "$svc" \
      || die "the configuration WAS applied and $svc could not be restarted, so it is still running the file that was replaced — its bind-mounted config is now a deleted inode and no drift scope can see it. Re-run 'docker compose restart $svc' in $TARGET."
    restarted=$(( restarted + 1 ))
  done
  log "restarted $restarted service(s) whose configuration is bind-mounted"
}
if [ "$OBS_CHANGED" = 1 ]; then
  log "a bind-mounted configuration path changed — restarting the services that mount it"
  restart_bind_mounted
fi

# This one really is on STDOUT — measured, 26 bytes for a no-op prune and dozens of lines of
# deleted image ids on a box with a long history — so it is the 24 000-character budget this
# redirect defends, not the 8 000 one `pull --quiet` defends. No reader either way. An error
# still reaches stderr.
docker image prune -f >/dev/null

fi # end of the mutation (steps 4-8); a --verify-only run resumes here
if [ "$VERIFY_ONLY" = 1 ]; then
  log "verify-only: steps 9 and 10 against $TARGET as it stands (nothing is placed, stamped, pulled or brought up)"
  write_verify_metrics 0
fi

# --- step 9: publish the drift metrics now ------------------------------------
# So the freshest reading is always the one taken at the moment of a deploy, even on a box
# where the hourly timer has not been installed. Non-fatal HERE: a metric that could not be
# written must never turn a good deploy into a red one — but step 10 reads the file this
# writes and refuses when it is older than T9, so a step 9 that silently did not publish is
# still a red deploy (HD-287's shape), by a check that is not this step's own opinion of itself.
#
# THIS IS THE SYNCED SCRIPT, and the timer runs /usr/local/bin/hamstrack-config-drift — a
# COPY, because the sync deliberately cannot install (§6.4). After a release that changed
# this file the two differ until somebody re-installs, so behaviour visible in THIS run is
# not yet the behaviour of the hourly path, and `installed-ops` says so meanwhile. The
# re-install is a step in docs/release-checklist.md ("Releases that change a file the box
# runs from a COPY").
DRIFT="$TARGET/ops/drift/hamstrack-config-drift.sh"
T9="$(date +%s)"   # taken IMMEDIATELY before step 9; check 5 refuses a gauge older than this
if [ -f "$DRIFT" ]; then
  bash "$DRIFT" "$TARGET" || log "WARN drift metrics could not be published — the deploy itself succeeded"
else
  log "WARN $DRIFT not found — no drift metrics published"
fi

# --- step 10: verify — read the running box back --------------------------------
# After step 9 (check 5 reads what it wrote) and after 7b (check 4 reads the restarted
# Grafana); nothing sits after this. Every check runs even once one has failed, so the
# refusal carries the whole picture rather than the first finding, and the gauge is rewritten
# BEFORE the refusal so DeployVerifyFailed names each failed check. The declarations are
# read once, from the files now IN the target (which after step 5 are the release's).
log "verify: reading the running state back against what was applied"
DECLARATIONS=''
[ -z "$SERVICES" ] || DECLARATIONS="$(run_compose config 2>/dev/null | compose_declarations || true)"
# …and the same rows from the FILES' OWN TEXT, uninterpolated, so check 1 can tell "no
# ceiling declared anywhere" from "a ceiling the box's .env turned off".
FILE_DECLARATIONS="$(cd "$TARGET" && cat "${COMPOSE_LIST[@]}" 2>/dev/null | compose_declarations || true)"
if [ -z "$SERVICES" ]; then
  verify_skip memory-limits "the compose files (${COMPOSE_LIST[*]}) declare no service on this box"
  verify_skip environment-keys "the compose files (${COMPOSE_LIST[*]}) declare no service on this box"
  OK_MEMORY=2; OK_ENV=2
elif [ -z "$DECLARATIONS" ]; then
  verify_ran memory-limits
  verify_ran environment-keys
  verify_fail memory-limits "'docker compose config' in $TARGET yielded no declarations to compare — the resolved model could not be read"
  verify_fail environment-keys "(same cause: the resolved model could not be read)"
else
  check_memory_limits "$DECLARATIONS"
  check_environment_keys "$DECLARATIONS"
fi
check_app_identity
check_grafana
check_drift_fresh "$T9"

if [ "$(( ${#VERIFY_RAN[@]} + ${#VERIFY_SKIPPED[@]} ))" -ne "${#VERIFY_CHECKS[@]}" ]; then
  log "WARN verify: ${#VERIFY_RAN[@]} ran + ${#VERIFY_SKIPPED[@]} skipped is not the ${#VERIFY_CHECKS[@]} declared check(s) — one ended without saying which it was, so the summary below undercounts what happened"
fi
# A SKIP MAY NOT CLEAR A FAILURE SOMEBODY ELSE FOUND. _check_ok already refuses to write 2 over
# a 0 the previous run READ, so the alert keeps firing on its own; this loop is the witness for
# the same event — without it the run would exit 0 with a green summary while DeployVerifyFailed
# stayed up, which is the shape that gets an alert muted. The remedy it names is one its reader
# can perform: run the same checks with the compose set that declares what was skipped.
for _held in ${VERIFY_SKIPPED[@]+"${VERIFY_SKIPPED[@]}"}; do
  previously_read_as_failed "$_held" || continue
  verify_fail "$_held" "this run did NOT read $_held (the 'verify: $_held skipped' line above says why) and the last run that did read it published 0. A check that did not read the box may lower confidence, never raise it, so that 0 stands and DeployVerifyFailed keeps firing on it. Read it: in $TARGET run 'COMPOSE_FILES=\"docker-compose.prod.yml docker-compose.observability.yml\" bash $TARGET/ops/deploy/apply-config.sh $TARGET $TARGET $SHA --verify-only' with the compose set that declares $_held — or fix what the earlier run found, whichever the earlier finding named"
done
if [ "${#VERIFY_FAILURES[@]}" -eq 0 ]; then
  write_verify_metrics 1
  # THE ONE LINE THAT IS DESIGNED TO BE REPUBLISHED. deploy.yml prints it into a
  # world-readable Actions log on success, matched by ANCHOR — `verify: PASS|PARTIAL
  # ran=<n>/<n> skipped=` — and never by substring: a `verify: WARN drift-fresh:
  # edge-body-limit=1` line names a live weakness of the production edge, and a filter written
  # as "lines containing verify:" would publish it. So everything on this line is chosen to be
  # public: counts, service counts, the sha (already public — it is the commit that built the
  # image) and the app version (/api/meta serves it to anyone). Nothing read out of .env, no
  # path, no finding.
  # It goes through log_both because it is also the line whose absence means "truncated" —
  # stdout may be cut at 24 000 characters, stderr carries almost nothing.
  #
  # PASS OR PARTIAL, AND `ran=<read>/<declared>` RATHER THAN `<n>/<n>`. The fraction used to be
  # the declared count over itself — 5/5 on every run that reached this line, including a run
  # that read nothing at all — so it carried no information and read as a stronger claim than
  # the box had earned. The word now answers the same question at a glance that checks_ran
  # answers in Prometheus, and the two are the same array so they cannot disagree.
  SKIPPED_LIST=none
  VERIFY_VERDICT=PASS
  if [ "${#VERIFY_SKIPPED[@]}" -gt 0 ]; then
    SKIPPED_LIST="$(IFS=,; printf '%s' "${VERIFY_SKIPPED[*]}")"
    VERIFY_VERDICT=PARTIAL
  fi
  SERVICE_COUNT="$(printf '%s\n' "$SERVICES" | grep -c . || true)"
  log_both "verify: $VERIFY_VERDICT ran=${#VERIFY_RAN[@]}/${#VERIFY_CHECKS[@]} skipped=$SKIPPED_LIST services=$SERVICE_COUNT env-services=$ENV_SERVICES_CHECKED app-identity=$APP_IDENTITY_STRENGTH withheld=$WITHHELD_COUNT sha=$(sanitize_label "$SHA") version=$(sanitize_label "${VERIFIED_APP_VERSION:-none}")"
else
  write_verify_metrics 0
  verify_refuse
fi

if [ "$VERIFY_ONLY" = 1 ]; then
  log "verify complete: $TARGET is at $SHA"
else
  log "deploy complete: $TARGET is at $SHA"
fi
