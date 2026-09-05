#!/usr/bin/env bash
# Hamstrack production configuration applier (HD-199 / HD-122).
# Spec: docs/design/config-delivery-proposal.md §7
#
#   apply-config.sh <source-dir> [target-dir] [sha] [--dry-run] [--allow-pinned|--adopt-pin]
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
#      moment of a deploy — even on a box where the hourly timer is not installed.
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
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "FATAL $*"; exit 1; }

USAGE='usage: apply-config.sh <source-dir> [target-dir] [sha] [--dry-run] [--allow-pinned|--adopt-pin]'

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
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --allow-pinned) ALLOW_PINNED=1 ;;
    --adopt-pin) ADOPT_PIN=1; ALLOW_PINNED=1 ;;
    -h|--help) printf '%s\n' "$USAGE"; exit 0 ;;
    -*) die "unknown option '$arg' — $USAGE" ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done
[ "${#POSITIONAL[@]}" -ge 1 ] || die "no source directory given — $USAGE"
[ "${#POSITIONAL[@]}" -le 3 ] || die "too many arguments — $USAGE"

SRC="${POSITIONAL[0]}"
TARGET="${POSITIONAL[1]:-/opt/hamstrack}"
SHA="${POSITIONAL[2]:-unknown}"

MANIFEST_REL='ops/deploy/synced-paths.txt'

need_tool() { command -v "$1" >/dev/null 2>&1 || die "required command not found: $1 ($2)"; }
need_tool docker "brings the stack up and validates the released compose files"
need_tool sha256sum "writes the checksum manifest that drift detection reads"
need_tool flock "serialises this script against a hand run on the same box"
[ "$DRY_RUN" = 0 ] || need_tool diff "prints the dry-run diff"

# --- step 1: refuse a source that is not a release tree ----------------------
[ -d "$SRC" ] || die "source directory does not exist: $SRC"
[ -d "$TARGET" ] || die "target directory does not exist: $TARGET"
[ -f "$SRC/$MANIFEST_REL" ] \
  || die "not a release tree: $SRC/$MANIFEST_REL is missing — refusing to sync from a source with no manifest"

SRC="$(cd "$SRC" && pwd)"
TARGET="$(cd "$TARGET" && pwd)"
[ "$SRC" != "$TARGET" ] || die "source and target are the same directory ($TARGET) — there is nothing to apply"

# The box's own file, and the reason step 2 is a real check rather than a syntax pass.
[ -f "$TARGET/.env" ] \
  || die "$TARGET/.env not found — the released compose files cannot be resolved against the box's real secrets, and this script will not replace anything it could not validate"

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
done < "$SRC/$MANIFEST_REL"
[ "${#ENTRIES[@]}" -gt 0 ] || die "the manifest $SRC/$MANIFEST_REL lists no paths"

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
log "validating the released compose files against $TARGET/.env"
if ! docker compose --project-directory "$TARGET" --env-file "$TARGET/.env" \
       "${VALIDATE_ARGS[@]}" \
       config -q; then
  die "docker compose refused the released configuration against $TARGET/.env — NOTHING has been replaced and the running stack is untouched. The compose error above names the variable: set it in $TARGET/.env and re-run the deploy."
fi
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

if [ "$PIN_STATE" != ok ]; then
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
# EVERY compose file, in pull AND in up. This used to be a warning comment in two
# documents; here it is code, so the accident it prevents — `up -d --remove-orphans` with
# only the prod file deletes loki/alloy/grafana/prometheus/the exporters as orphans —
# stops depending on whoever types the command.
run_compose() {
  ( cd "$TARGET" && docker compose "${RUN_ARGS[@]}" "$@" )
}

run_compose pull
run_compose up -d --remove-orphans

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

docker image prune -f

# --- step 9: publish the drift metrics now ------------------------------------
# So the freshest reading is always the one taken at the moment of a deploy, even on a box
# where the hourly timer has not been installed. Non-fatal: a metric that could not be
# written must never turn a good deploy into a red one.
DRIFT="$TARGET/ops/drift/hamstrack-config-drift.sh"
if [ -f "$DRIFT" ]; then
  bash "$DRIFT" "$TARGET" || log "WARN drift metrics could not be published — the deploy itself succeeded"
else
  log "WARN $DRIFT not found — no drift metrics published"
fi

log "deploy complete: $TARGET is at $SHA"
