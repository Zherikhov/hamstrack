#!/usr/bin/env bash
# Hamstrack configuration-drift check (HD-199).
# Spec: docs/design/config-delivery-proposal.md §8
#
#   hamstrack-config-drift.sh [target-dir]        (default /opt/hamstrack)
#
# Answers one question — "has this box changed since it was deployed?" — and publishes the
# answer as node-exporter textfile metrics. It is run hourly by hamstrack-config-drift.timer
# AND at the end of every apply-config.sh run, so the freshest reading is always the one
# taken at the moment of a deploy.
#
# Three comparisons, and together they are the property. Each one catches a failure the
# other two cannot see:
#
#   files         re-hash every synced file against .deployed-manifest.sha256, and detect
#                 files ADDED TO or REMOVED FROM a synced directory — somebody edited the
#                 box after the deploy, including the incident edit that must be un-done.
#   containers    each service's com.docker.compose.config-hash label on the RUNNING
#                 container versus `docker compose config --hash '*'` — the file is right
#                 and the container was never recreated.
#   installed-ops /opt/hamstrack/ops/** versus the copies installed under /usr/local/bin
#                 and /etc/systemd/system. The sync CANNOT install (§6.4), so the check has
#                 to be able to say that it hasn't.
#
# Checksums rather than a re-download: no network in the hourly path, no dependency on
# codeload being up, and it answers the question that is actually asked. WHAT the deploy
# applied is answered by .deployed-sha, which is published as a label.
#
# WHICH files differ goes to stdout and the journal, NEVER into a label. `scope` is a
# closed three-valued enum; `sha` and `tag` change on a deploy, which is a handful of new
# series a week against a 15-day retention — written down here because this project
# otherwise forbids unbounded labels.
set -euo pipefail

# WHATEVER THIS PRINTS IS PUBLIC. It goes to the journal on the box, and apply-config.sh
# runs this script at the end of every deploy — so the same lines travel back through SSM
# into a GitHub Actions log, and this repository is public. deploy.yml prints the bodies
# only when the deploy FAILS, which narrows the audience and does not change the rule: log
# NAMES and COUNTS — which file differs, which service, how many — never file CONTENTS,
# never a diff, and never a value read from .env. The image tag is the one value that
# crosses that line, deliberately and sanitised, because a pin is what an operator most
# needs named back to them.
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

TARGET="${1:-${HAMSTRACK_DIR:-/opt/hamstrack}}"
TEXTFILE_DIR="${CONFIG_DRIFT_TEXTFILE_DIR:-/var/lib/node_exporter/textfile_collector}"

# Which compose files describe this box — the same COMPOSE_FILES contract apply-config.sh
# takes, and for the same reason: a listed file that is ABSENT is skipped, so a deployment
# without the observability stack does not fail `config --hash` for ever and report
# containers=1 about a stack that is entirely healthy. Assembled once, used by scope 2.
#
# WHERE it is set matters as much as that it exists. Exporting it in a shell reaches only a
# HAND run; the hourly timer is what actually publishes the metric, and it inherits nothing
# from anybody's shell. The unit reads /etc/hamstrack/drift.env
# (`EnvironmentFile=-…`, optional) — put it there, and NOT by editing the installed unit,
# which is compared byte-for-byte with the synced copy and would then read as permanent
# `installed-ops` drift.
read -r -a COMPOSE_LIST <<< "${COMPOSE_FILES:-docker-compose.prod.yml docker-compose.observability.yml}"
COMPOSE_ARGS=()
for compose_file in "${COMPOSE_LIST[@]}"; do
  if [ -f "$TARGET/$compose_file" ]; then
    COMPOSE_ARGS+=(-f "$compose_file")
  fi
done

# Start pessimistic. A check that could not complete has not shown that the box matches
# what was deployed, so it must not publish a 0 — and the EXIT trap below is what makes an
# unexpected failure say so instead of leaving yesterday's reading in place.
DRIFT_FILES=1
DRIFT_CONTAINERS=1
DRIFT_INSTALLED=1
DEPLOYED_SHA=unknown
IMAGE_TAG=latest
IMAGE_PINNED=0

# A label value with a quote or a backslash in it makes the WHOLE textfile malformed, and
# node-exporter then drops every series in it at once — including the drift gauges this
# file exists to publish, silently, because `noDataState: OK` reads an absent series as
# health. Neither value is trusted: .deployed-sha is a file and .env is hand-edited.
sanitize_label() {
  local v="$1"
  v="${v//[^A-Za-z0-9._-]/}"
  printf '%s' "${v:-unknown}"
}

write_metrics() {
  local out tmp now
  now="$(date +%s)"
  out="$TEXTFILE_DIR/hamstrack_config.prom"
  tmp="$out.$$"
  if ! mkdir -p "$TEXTFILE_DIR" 2>/dev/null; then
    # Said plainly, and then still allowed to fail on the redirect below: a check that
    # cannot publish must not exit 0, because `noDataState: OK` turns a missing series into
    # silence rather than an alert.
    log "WARN $TEXTFILE_DIR is missing and cannot be created — no metrics will be published."
    log "WARN create it (the backup install does) or set CONFIG_DRIFT_TEXTFILE_DIR."
  fi
  {
    echo '# HELP hamstrack_config_drift Whether the box differs from the configuration that was deployed to it (1) or not (0), per comparison scope.'
    echo '# TYPE hamstrack_config_drift gauge'
    echo "hamstrack_config_drift{scope=\"files\"} $DRIFT_FILES"
    echo "hamstrack_config_drift{scope=\"containers\"} $DRIFT_CONTAINERS"
    echo "hamstrack_config_drift{scope=\"installed-ops\"} $DRIFT_INSTALLED"
    echo '# HELP hamstrack_config_deployed_info The commit whose configuration was last applied to this box.'
    echo '# TYPE hamstrack_config_deployed_info gauge'
    echo "hamstrack_config_deployed_info{sha=\"$(sanitize_label "$DEPLOYED_SHA")\"} 1"
    echo '# HELP hamstrack_config_check_timestamp_seconds Unix time this check last ran. Distinguishes a fresh 0 from a stale one when the timer is not installed.'
    echo '# TYPE hamstrack_config_check_timestamp_seconds gauge'
    echo "hamstrack_config_check_timestamp_seconds $now"
    echo '# HELP hamstrack_deploy_image_pinned Whether APP_IMAGE_TAG pins the app image to something other than latest (1) or not (0).'
    echo '# TYPE hamstrack_deploy_image_pinned gauge'
    echo "hamstrack_deploy_image_pinned{tag=\"$(sanitize_label "$IMAGE_TAG")\"} $IMAGE_PINNED"
  } > "$tmp"
  # Deliberately wider than the umask: node-exporter runs as `nobody` inside its container
  # and could not read a 0600 file, which would take every series here off the air while
  # looking installed. The file holds three flags, a timestamp, a sha and a tag — no secret.
  chmod 0644 "$tmp"
  mv -f "$tmp" "$out"   # atomic: a scrape must never see a half-written file
  log "drift: files=$DRIFT_FILES containers=$DRIFT_CONTAINERS installed-ops=$DRIFT_INSTALLED sha=$DEPLOYED_SHA tag=$IMAGE_TAG pinned=$IMAGE_PINNED"
}
trap write_metrics EXIT

# --- what was deployed -------------------------------------------------------
if [ -r "$TARGET/.deployed-sha" ]; then
  DEPLOYED_SHA="$(tr -d '[:space:]' < "$TARGET/.deployed-sha")"
fi

# --- the image pin -----------------------------------------------------------
# .env is READ, never sourced: it holds every secret on this box, and sourcing a
# hand-edited file executes whatever a typo made of it.
#
# The parse must match COMPOSE'S env-file parser rather than a convenient subset of it. A
# line compose honours and this does not reads as UNSET, which is `latest`, which is "not
# pinned" — so a box genuinely pinned by `export APP_IMAGE_TAG=v0.16.3` would publish
# hamstrack_deploy_image_pinned 0 and the un-pin reminder would never fire. Hence the
# optional `export ` prefix, and an unquoted ` # comment` tail dropped the way compose
# drops it. apply-config.sh step 2b carries the same function; keep the two identical — a
# test (ApplyConfigPinGuardTest) compares the two bodies, because one script REFUSES on this
# answer and the other PUBLISHES it, and a divergence shows up as a box whose deploys are
# blocked while the un-pin reminder says it is not pinned.
#
# The FILE and nothing else, deliberately — and this is where the two scripts differ, on
# purpose. Compose gives the process environment precedence over --env-file, so
# apply-config.sh (which is a guard, and would be bypassed by an exported pin) folds the
# environment in and refuses when it is what decides. This one is a REPORTER, and the
# question it answers is "is this box durably pinned?": the metric is published by a
# systemd timer that inherits nobody's shell, and an exported value would be gone by the
# next `up -d` anyway. Reading it here would make a hand run publish a pin the hourly run
# cannot see, which is a flapping gauge rather than an answer.
ENV_FILE="$TARGET/.env"
read_image_tag() {
  local v
  v="$(sed -n 's/^[[:space:]]*\(export[[:space:]]\+\)\?APP_IMAGE_TAG[[:space:]]*=[[:space:]]*//p' "$ENV_FILE" | tail -n 1)"
  case "$v" in
    \"*) v="${v#\"}"; v="${v%%\"*}" ;;
    \'*) v="${v#\'}"; v="${v%%\'*}" ;;
    *)   v="$(printf '%s' "$v" | sed 's/[[:space:]]#.*$//')" ;;
  esac
  printf '%s' "$(printf '%s' "$v" | tr -d '[:space:]')"
}
if [ -r "$ENV_FILE" ]; then
  # An absent or empty value is compose's `latest` default, and that is not a pin.
  IMAGE_TAG="$(read_image_tag)"
  IMAGE_TAG="${IMAGE_TAG:-latest}"
  if [ "$IMAGE_TAG" = latest ]; then
    IMAGE_PINNED=0
  else
    IMAGE_PINNED=1
  fi
fi

# --- scope 1: files ----------------------------------------------------------
# The manifest is read from the box's own synced copy, so this check describes the box it
# runs on rather than a tree it would have to fetch.
manifest_entries() {
  local manifest="$TARGET/ops/deploy/synced-paths.txt" line entry
  [ -r "$manifest" ] || return 0
  # `|| [ -n "$line" ]` for the same reason apply-config.sh has it: a final line with no
  # trailing newline is an entry, and `read` returns non-zero at EOF having already filled
  # the variable. Missing it here would leave that directory's files unchecked while the
  # applier syncs them, which is a scope that quietly stops covering a path.
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [ -n "$line" ] || continue
    # EVERY trailing slash, the way apply-config.sh strips them: `observability///` must
    # normalise to the same string in both scripts or scope 1 (b) compares `find` output
    # against a stamp written under a different spelling and reports every synced file as
    # uncovered. (In the applier the same loop is also what makes the never-sync guards
    # match `Caddyfile///`; here there is nothing to refuse, only a form to agree on.)
    entry="$line"
    while [ "$entry" != "${entry%/}" ]; do entry="${entry%/}"; done
    # Normalised exactly as apply-config.sh normalises it, because scope 1 (b) compares
    # `find "$entry"` output against the paths in the stamp THAT script wrote: if one of
    # the two strips a leading `./` and the other does not, `./observability/x` never
    # matches the stamped `observability/x` and every synced file is reported as "not
    # covered by the deploy stamp" — permanent files=1 for a box that matches perfectly.
    while [ "$entry" != "${entry#./}" ]; do
      entry="${entry#./}"
      while [ "$entry" != "${entry#/}" ]; do entry="${entry#/}"; done
    done
    [ -n "$entry" ] || continue
    case "$entry" in /*|*..*|.) continue ;; esac
    printf '%s\n' "$entry"
  done < "$manifest"
}

check_files() {
  local stamp="$TARGET/.deployed-manifest.sha256" failed added entry file stamped
  if [ ! -r "$stamp" ]; then
    log "files: $stamp is absent — this box has never been deployed by apply-config.sh"
    DRIFT_FILES=1
    return
  fi
  DRIFT_FILES=0

  # (a) every file the stamp names still hashes to what it did. `-c` also reports a file
  # that has since been DELETED, which is the half a plain re-hash of what exists misses.
  failed="$( cd "$TARGET" && sha256sum -c --quiet .deployed-manifest.sha256 2>&1 || true )"
  if [ -n "$failed" ]; then
    DRIFT_FILES=1
    log "files: changed or missing since the deploy:"
    printf '%s\n' "$failed"
  fi

  # (b) files ADDED to a synced directory. They appear in no checksum line, so (a) is blind
  # to them, and a directory is replaced wholesale at the next deploy — so an operator who
  # dropped a file in there is about to lose it and deserves to be told now.
  added=0
  # Compare whole path names, not substrings: `ops/x` is a prefix of `ops/xy`, and a
  # substring match would report a file nobody deployed as covered. The second character
  # after the hash is a space in sha256sum's text mode and a `*` in its binary mode — the
  # box writes text mode, a stamp produced anywhere else may not, and reading only one of
  # the two forms reports every synced file as unstamped.
  stamped="$(sed -n 's/^[0-9a-f]\{64\} [ *]//p' "$stamp")"
  while IFS= read -r entry; do
    [ -d "$TARGET/$entry" ] || continue
    while IFS= read -r file; do
      if ! printf '%s\n' "$stamped" | grep -Fxq -- "$file"; then
        log "files: not covered by the deploy stamp: $file"
        added=1
      fi
    done < <( cd "$TARGET" && find "$entry" -type f -print | LC_ALL=C sort )
  done < <(manifest_entries)
  [ "$added" = 0 ] || DRIFT_FILES=1

  [ "$DRIFT_FILES" = 0 ] && log "files: the synced paths match the deploy stamp"
  return 0
}

# --- scope 2: containers -----------------------------------------------------
# The file can be right while the container still runs the previous definition — the shape
# of "somebody copied the compose file and forgot `up -d`", which is half of what HD-199
# was. Compose's own config-hash is the comparison it uses internally to decide whether a
# container needs recreating, so this asks exactly the question `up -d` would answer.
check_containers() {
  local hashes svc want got cid errfile mismatched=0
  if [ "${#COMPOSE_ARGS[@]}" -eq 0 ]; then
    log "containers: none of (${COMPOSE_LIST[*]}) is present in $TARGET — there is no declaration to compare the running containers against"
    DRIFT_CONTAINERS=1
    return 0
  fi
  # stderr goes to its own file rather than into `hashes`. Compose writes ordinary warnings
  # there on a perfectly good run, and a warning parsed as a `<service> <hash>` line invents
  # a service that is "declared and not running" — a drift report about a container nobody
  # ever declared.
  errfile="$(mktemp)"
  if ! hashes="$( cd "$TARGET" && docker compose "${COMPOSE_ARGS[@]}" config --hash '*' 2>"$errfile" )"; then
    log "containers: docker compose could not resolve the box's configuration — that is itself a drift:"
    cat "$errfile"
    rm -f "$errfile"
    DRIFT_CONTAINERS=1
    return 0
  fi
  rm -f "$errfile"
  DRIFT_CONTAINERS=0
  while read -r svc want; do
    [ -n "$svc" ] || continue
    [ -n "$want" ] || continue
    cid="$( cd "$TARGET" && docker compose "${COMPOSE_ARGS[@]}" ps -q "$svc" 2>/dev/null || true )"
    if [ -z "$cid" ]; then
      log "containers: service $svc is declared and not running"
      mismatched=1
      continue
    fi
    got="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.config-hash"}}' "$cid" 2>/dev/null || true)"
    if [ "$got" != "$want" ]; then
      log "containers: $svc runs a definition that is not the one on disk"
      mismatched=1
    fi
  done <<< "$hashes"
  [ "$mismatched" = 0 ] || DRIFT_CONTAINERS=1
  [ "$DRIFT_CONTAINERS" = 0 ] && log "containers: every service runs the definition on disk"
  return 0
}

# --- scope 3: installed-ops --------------------------------------------------
# The sync places /opt/hamstrack/ops/ and installs nothing, on purpose: a deploy that
# rewrites systemd units on every merge is a blast radius nobody asked for. This is what
# keeps that gap visible instead of silent.
#
# An installed copy that is ABSENT is NOT drift — a box that never installed the backup
# timer has nothing to be out of date — while one that is PRESENT and different is. The
# remedy is always the install step (docs/ops-prod-hardening.md §6.3), never a sync.
# The two destinations of every install step in the runbook. Overridable only so that this
# comparison can be exercised somewhere other than a production box.
UNIT_DIR="${CONFIG_DRIFT_UNIT_DIR:-/etc/systemd/system}"
BIN_DIR="${CONFIG_DRIFT_BIN_DIR:-/usr/local/bin}"

installed_path_for() { # $1 = path relative to the target, under ops/
  local name; name="$(basename "$1")"
  case "$name" in
    *.service|*.timer) printf '%s/%s' "$UNIT_DIR" "$name" ;;
    *.sh)              printf '%s/%s' "$BIN_DIR" "${name%.sh}" ;;
    *)                 return 1 ;;
  esac
}

check_installed_ops() {
  local file installed stale=0
  if [ ! -d "$TARGET/ops" ]; then
    log "installed-ops: $TARGET/ops is absent — nothing has been synced here yet"
    DRIFT_INSTALLED=1
    return 0
  fi
  DRIFT_INSTALLED=0
  while IFS= read -r file; do
    installed="$(installed_path_for "$file")" || continue
    [ -f "$installed" ] || continue
    if ! cmp -s "$file" "$installed"; then
      log "installed-ops: $installed differs from $file — re-run the install step, the sync cannot do it"
      stale=1
    fi
  done < <(find "$TARGET/ops" -type f \( -name '*.sh' -o -name '*.service' -o -name '*.timer' \) -print | LC_ALL=C sort)
  [ "$stale" = 0 ] || DRIFT_INSTALLED=1
  [ "$DRIFT_INSTALLED" = 0 ] && log "installed-ops: every installed copy matches the synced one"
  return 0
}

check_files
check_containers
check_installed_ops
