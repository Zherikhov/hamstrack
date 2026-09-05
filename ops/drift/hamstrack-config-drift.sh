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
# THOSE TWO RUNS ARE NOT THE SAME FILE. The deploy runs the SYNCED copy under
# /opt/hamstrack/ops/; the timer's unit runs /usr/local/bin/hamstrack-config-drift, which an
# operator INSTALLED, because the sync deliberately cannot install (§6.4). So a change to
# this script reaches production's hourly path only after the install step is re-run — until
# then the deploy log shows the new behaviour and the metric keeps being published by the
# old one, with `installed-ops` reading 1 meanwhile. That re-install is a step in
# docs/release-checklist.md ("Releases that change a file the box runs from a COPY"); do not
# read a fix off a deploy log and call it deployed.
#
# Four comparisons, and together they are the property. Each one catches a failure the
# others cannot see:
#
#   files           re-hash every synced file against .deployed-manifest.sha256, and detect
#                   files ADDED TO or REMOVED FROM a synced directory — somebody edited the
#                   box after the deploy, including the incident edit that must be un-done.
#   containers      `docker compose up -d --dry-run` — Compose's own plan for the command a
#                   deploy runs, and anything it would ACT ON is drift: the file is right and
#                   the container was never recreated. It asks Compose rather than computing
#                   a second opinion about what Compose would decide (HD-221; the config-hash
#                   comparison this replaced disagreed with `up -d` on production
#                   permanently, and the check could not clear). The deploy runs
#                   `up -d --remove-orphans` and the plan here deliberately drops that flag,
#                   so it is the deploy's command MINUS the orphan sweep — and the orphans
#                   are then read out of Compose's own warning on the same output, because a
#                   service deleted from a file whose container still runs is drift that no
#                   per-service comparison can see (it is in no file and in no verb line).
#   installed-ops   /opt/hamstrack/ops/** versus the copies installed under /usr/local/bin
#                   and /etc/systemd/system. The sync CANNOT install (§6.4), so the check has
#                   to be able to say that it hasn't.
#   edge-body-limit the deployed Caddyfile carries a request_body/max_size block. The first
#                   three scopes all compare the box against what a deploy PUT there; this
#                   one is the opposite shape — the Caddyfile is one of the two paths the
#                   applier hard-refuses to sync (it carries the hand-added Cloudflare
#                   trusted_proxies block), so a repository that GAINS a body limit gives
#                   production nothing until somebody performs the merge by hand. Nothing
#                   fails when they don't: the app still refuses an over-sized upload, but
#                   only AFTER Tomcat has streamed it to a temp file, on a lane that spends
#                   no rate-limit budget at all (HD-191). A control whose absence is silent
#                   is a control nobody has. This makes it loud.
#
# Checksums rather than a re-download: no network in the hourly path, no dependency on
# codeload being up, and it answers the question that is actually asked. WHAT the deploy
# applied is answered by .deployed-sha, which is published as a label.
#
# WHICH files differ goes to stdout and the journal, NEVER into a label. `scope` is a
# closed enum whose values are the ones listed above and are added only by editing this
# file (each addition is one series per box, which is why it is a closed set at all —
# and docs/observability.md's metric table names them); `sha` and `tag` change on a deploy,
# which is a handful of new series a week against a 15-day retention — written down here
# because this project
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
# without the observability stack does not fail every Compose invocation for ever and report
# containers=1 about a stack that is entirely healthy. Assembled once, used by scope 2 —
# which passes these files to `config --services` and to `up -d --dry-run`, so the rule has
# to hold for both or the two would resolve different projects and the second would plan to
# create containers the first never declared.
#
# WHERE it is set matters as much as that it exists. Exporting it in a shell reaches only a
# HAND run; the hourly timer is what actually publishes the metric, and it inherits nothing
# from anybody's shell. The unit reads /etc/hamstrack/drift.env
# (`EnvironmentFile=-…`, optional) — put it there, and NOT by editing the installed unit,
# which is compared byte-for-byte with the synced copy and would then read as permanent
# `installed-ops` drift.
#
# AND IT HAS TO BE THE SAME VALUE THE DEPLOY USES. apply-config.sh reads COMPOSE_FILES from
# the environment of whoever runs it (`sudo -E …`); this path reads it from a file nobody
# else writes. Nothing enforces that the two agree, and narrowing one without the other is
# not caught anywhere — it surfaces as the orphan report at the bottom of scope 2 naming
# the operator's own healthy containers, hourly and for ever. Both documents that prescribe
# one now name the other: docs/self-hosting.md → Applying repository configuration, and
# docs/ops-prod-hardening.md → Installing the drift check.
read -r -a COMPOSE_LIST <<< "${COMPOSE_FILES:-docker-compose.prod.yml docker-compose.observability.yml}"
COMPOSE_ARGS=()
# The names that actually RESOLVED on this box, as distinct from the ones requested: a
# listed file absent here is skipped, so a message naming the requested list would name a
# file this check never passed to Compose. Scope 2's orphan line names this array.
COMPOSE_RESOLVED=()
for compose_file in "${COMPOSE_LIST[@]}"; do
  if [ -f "$TARGET/$compose_file" ]; then
    COMPOSE_ARGS+=(-f "$compose_file")
    COMPOSE_RESOLVED+=("$compose_file")
  fi
done

# Start pessimistic. A check that could not complete has not shown that the box matches
# what was deployed, so it must not publish a 0 — and the EXIT trap below is what makes an
# unexpected failure say so instead of leaving yesterday's reading in place.
DRIFT_FILES=1
DRIFT_CONTAINERS=1
DRIFT_INSTALLED=1
DRIFT_EDGE_BODY=1
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
    echo "hamstrack_config_drift{scope=\"edge-body-limit\"} $DRIFT_EDGE_BODY"
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
  log "drift: files=$DRIFT_FILES containers=$DRIFT_CONTAINERS installed-ops=$DRIFT_INSTALLED edge-body-limit=$DRIFT_EDGE_BODY sha=$DEPLOYED_SHA tag=$IMAGE_TAG pinned=$IMAGE_PINNED"
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
# was.
#
# HD-221 REPLACED THE ORACLE, not a line of it. This used to compare each service's
# com.docker.compose.config-hash label on the running container against
# `docker compose config --hash '*'`, on the reasoning that the hash is what `up` decides
# with. On production the two disagreed permanently — for the app service `running` and
# `ondisk` differed, stably, while `up -d` itself declined to recreate anything and
# `--force-recreate` reproduced the RUNNING hash rather than the on-disk one — so this scope
# reported drift for a week about a box that was in exactly the state it was deployed in.
# A detector that cannot clear gets muted, and a muted detector is worse than none: the next
# real drift (a hand-edited compose file on the box, which is what HD-199 exists to prevent)
# arrives into a channel nobody reads. The value of this check is entirely in its silence
# being meaningful.
#
# WHY THE TWO DISAGREED IS NOT KNOWN, AND THIS COMMENT NAMES NO CAUSE. What is excluded is
# the ticket's own hypothesis — that `app` is the only service whose definition interpolates
# a default (`mem_limit: ${APP_MEMORY_LIMIT:-1g}`) and that interpolation reaches one path
# and not the other. This repository's postgres and caddy carry the same shape and MATCHED,
# and a probe on Compose v5.1.0 / Docker 29.2.1 hashed `mem_limit: 128m` and
# `mem_limit: ${VAR:-128m}` byte-identically, each agreeing with its own container's label;
# that probe could not reproduce the disagreement at all. What stays open: the production
# Compose version, which nobody recorded, and a difference between the file set the deploy's
# `up` resolved and the one this check passed. Neither is answerable from off the box.
#
# So the comparison stopped being a RE-IMPLEMENTATION of Compose's decision and became
# Compose's decision. `up -d --dry-run` plans the command a deploy runs — minus its
# `--remove-orphans` sweep, see (d) — and anything it would act on is drift. The rejected
# alternative was to narrow the comparison to the fields that matter operationally (image,
# env, limits, mounts) — which is the same defect with a
# shorter list: it still computes a second opinion about what `up` would do, and its
# omissions are SILENT. A field nobody thought to include — ulimits, sysctls, cap_add, a
# healthcheck, a network alias — then drifts under a green light, which is this ticket's
# failure pointed the other way and harder to notice.
#
# WHAT MAKES THE SILENCE MEANINGFUL — and it is never "the command printed nothing". Each
# of these is a way a plan could read as health while being unread; a new one is an addition
# here, which is why this paragraph carries no count of them:
#
#   (a) Compose writes this plan to STDERR. Its stdout is EMPTY on a clean box and equally
#       empty on a drifted one — so a check that read stdout, which is the obvious way to
#       write this, would be green for ever. That is this ticket's own defect one layer
#       down, and it is why the redirection below is `2>&1` and not decoration.
#   (b) A clean service is not ABSENT from the plan, it is present as `Running`. The test is
#       therefore POSITIVE: every container this box is running must appear in the plan, and
#       must appear with that verb. An oracle that goes blind — a Compose that stops
#       printing per-container status, a flag that stops being accepted — then reports every
#       service as unplanned and is loud, instead of reading as health.
#   (c) ANY verb that is not `Running` is drift, including one this script has never seen.
#       Fail-closed on purpose: if a future Compose renames `Recreate`, an allow-list of
#       drift verbs goes quietly green and a deny-list goes loudly red naming the verb it
#       did not understand. Only one of those two mistakes is survivable here.
#   (d) AN ORPHAN IS INVISIBLE TO (b) AND (c), so it is read separately. Delete a service
#       from a compose file and leave its container running: it is not in `config
#       --services`, so no per-service comparison reaches it, and Compose prints no
#       `Container` line for it either — measured, that box publishes containers=0 while a
#       deploy's `up -d --remove-orphans` would destroy the container. Compose does say so,
#       on the same output as the plan (`Found orphan containers ([...]) for this project`),
#       and the success path below greps for exactly that. The flag is NOT added to the
#       command: `--remove-orphans` in a monitor is a delete verb in a read-only job, and
#       "a dry run cannot write" then rests on the dry-run client alone rather than on the
#       command being harmless to begin with.
#
# `--dry-run` IS A WRITE-SHAPED COMMAND IN A READ-ONLY MONITOR, run hourly by the timer and
# again at the end of every deploy, so why it cannot write is worth stating. Compose
# substitutes the whole Docker API client for a dry-run client at the CLI layer before the
# command runs, rather than consulting a flag at each mutation, so there is no
# create / start / remove path that can forget to honour it. That is the mechanism; the
# EVIDENCE is a checked property and not a reading of somebody's source —
# ConfigDriftContainerOracleTest brings a scratch project up, drifts it until the plan says
# `Recreate`, and asserts the container id is unchanged afterwards; and separately runs a
# plan against a project that is entirely DOWN and asserts that no container and no network
# came into existence.
#
# AND A FAILURE OF THE COMMAND IS NOT AN ANSWER OF "NO". A non-zero exit is reported as
# drift, with the output, exactly as an unresolvable `config` already was: a monitor that
# could not ask its question has not shown that the box is clean.
check_containers() {
  local errfile services plan plan_pairs orphans rc svc cids cid name verbs bad mismatched=0
  if [ "${#COMPOSE_ARGS[@]}" -eq 0 ]; then
    log "containers: none of (${COMPOSE_LIST[*]}) is present in $TARGET — there is no declaration to compare the running containers against"
    DRIFT_CONTAINERS=1
    return 0
  fi

  # The service list comes from Compose's own resolution rather than from a grep of the
  # files, and it is asked for separately from the plan because it answers a different
  # question: a box on which every container was destroyed still produces a perfectly good
  # plan, so the list is what says WHICH services are supposed to exist at all. stderr goes
  # to its own file, as it did before: Compose writes ordinary warnings there on a good run,
  # and a warning parsed as a service name invents a service that is "declared and not
  # running" — a drift report about a container nobody ever declared.
  errfile="$(mktemp)"
  if ! services="$( cd "$TARGET" && docker compose "${COMPOSE_ARGS[@]}" config --services 2>"$errfile" )"; then
    log "containers: docker compose could not resolve the box's configuration — that is itself a drift:"
    cat "$errfile"
    rm -f "$errfile"
    DRIFT_CONTAINERS=1
    return 0
  fi
  rm -f "$errfile"
  if [ -z "${services//[[:space:]]/}" ]; then
    log "containers: the compose files in $TARGET resolve to no services at all — there is nothing here whose definition could be compared"
    DRIFT_CONTAINERS=1
    return 0
  fi

  # THE PLAN. stderr merged in, per (a) — that is where Compose writes it, and a version of
  # this line without the redirection is a check that can never fail. `--ansi never` so the
  # parse does not depend on whether a hand run happens to have a terminal attached (the
  # capture makes it a pipe either way; the flag removes the question rather than relying on
  # that). Nothing here passes `--no-build`: no service in this repository's compose files
  # declares `build:`, and a dry run intercepts a build the way it intercepts everything.
  plan="$( cd "$TARGET" && docker compose --ansi never "${COMPOSE_ARGS[@]}" up -d --dry-run 2>&1 )" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    # The diagnostic is printed, on the same terms the `config` failure above already
    # established: WHATEVER THIS PRINTS IS PUBLIC (see the header) — it reaches the journal
    # on the box and, on a failed deploy, a GitHub Actions log through SSM. So the bound on
    # what can appear here has to be the real one, and it is NOT "Compose names files,
    # services and variables rather than values". Measured, a TYPED field's decode error
    # quotes the offending RESOLVED VALUE:
    #     'services[alpha].mem_limit' strconv.ParseFloat: parsing "s3cr3": invalid syntax
    #     'services[alpha].stop_grace_period' time: invalid duration "hunter2s"
    # The `${VAR:?}` path is the safe one — it names the variable only. What actually bounds
    # this exception is the CONTENT of the compose files: every typed interpolation in them
    # today is a tuning knob (a memory limit, a shm size, a grace period), and EVERY secret
    # that reaches a service definition lands in an untyped `environment:` string, which has
    # no parse that can fail. Stated as the category on purpose: the member-shaped version
    # of this sentence named DB_PASSWORD as "the only" one and was already false when it was
    # written (GF_SECURITY_ADMIN_PASSWORD, MAIL_PASSWORD, DB_MONITOR_PASSWORD are three
    # more), and a stale count is the last thing wanted in the comment whose job is bounding
    # what reaches a public Actions log. A new TYPED field fed by a
    # secret breaks that and would print it here — that is the condition on this exception,
    # and the thing to re-check when a compose file gains an interpolated typed value.
    # Within it, this is the one place in this scope where a body rather than a name reaches
    # the journal, and it is allowed out because a failure an operator cannot see is a
    # failure they cannot fix — but it is a deliberate exception and not a licence: nothing
    # on the SUCCESS path below prints the plan, only per-service verdicts.
    log "containers: 'docker compose up -d --dry-run' exited $rc — this check could not ask whether the box matches its files, which is not the same as an answer of no:"
    printf '%s\n' "$plan"
    # ONE FAILURE IS COMMON ENOUGH TO NAME. A `depends_on: condition: service_healthy`
    # whose target is not healthy makes Compose abandon the plan where it stands — measured:
    # ` Container x Error dependency y failed to start`, exit 1 — and every service the plan
    # had not reached yet goes unexamined.
    #
    # HOW LONG THAT TAKES IS A PROPERTY OF THE DEPENDENCY'S STATE, NOT A CONSTANT. A run
    # planned against an ALREADY-`unhealthy` dependency is refused at once (measured: 1 s).
    # A dependency that is `starting` makes the dry run WAIT for its healthcheck to settle,
    # exactly as `up -d` waits — measured on the same pair of containers, a 30 s
    # `start_period` held the plan 29 s before the same exit 1. That is the state this box
    # is in during EVERY app restart: `app`
    # declares start_period 40s / interval 10s / retries 5 and `caddy` waits on `app` being
    # healthy, so a check landing mid-restart can sit for a minute or two, plus postgres's
    # own window. It is bounded rather than open-ended, and that is what makes it acceptable
    # in an hourly job: TimeoutStartSec=300 on the unit, and SIGTERM is trapped like any
    # other exit, so even a run systemd kills publishes a truthful 1 rather than leaving
    # yesterday's reading in place.
    #
    # This scope then publishes 1 for the duration of a HEALTH incident, which is not a
    # configuration one. That is deliberate and must not be "fixed" by ignoring the exit
    # status: a plan that stopped early has shown nothing about the services after it, and
    # an operator reading a ConfigDrift alert during an outage deserves to be told which of
    # the two they are looking at rather than left to infer it from a stack trace.
    if printf '%s\n' "$plan" | grep -q 'dependency failed to start'; then
      log "containers: …because a service is UNHEALTHY and Compose abandoned the plan there. That is a health incident, not a configuration one, and this scope stays 1 until it clears — a plan that stopped early cannot show that the services after it match."
    fi
    DRIFT_CONTAINERS=1
    return 0
  fi

  # `<container-name> <TAB> <verb>`, one per progress line. `Container` lines only: Compose
  # also reports Network / Volume / Image objects, and those are shared rather than any one
  # service's definition — a network that had to be re-created is reported by the verbs of
  # the containers hanging off it anyway. `tr -d '\r'` because a CR captured into the verb
  # would fail every comparison below and read as universal drift.
  plan_pairs="$(printf '%s\n' "$plan" | tr -d '\r' | awk '$1 == "Container" && NF >= 3 { print $2 "\t" $3 }')"

  DRIFT_CONTAINERS=0

  # (d). The `Container` filter above discards this line, and the per-service loop below
  # iterates services that still exist, so without this block a deleted service whose
  # container is still running reads as a clean box. Names only, which is what Compose
  # already put in the warning; the extraction is defensive because a warning this script
  # can see but cannot parse is still drift.
  #
  # WHAT THE DETECTION PROVES AND WHAT IT DOES NOT. Compose scopes the warning to this
  # project AND to the file set COMPOSE_FILES resolved HERE, so an orphan named below is
  # really running under a service none of the files THIS CHECK was pointed at declares.
  # That much is unconditional. Whether a deploy would SWEEP it is not: it holds only if
  # the deploy resolves the same set, and nothing enforces that.
  #
  # THE TWO VALUES COME FROM DIFFERENT PLACES. apply-config.sh takes COMPOSE_FILES from the
  # environment of whoever runs it (`sudo -E …`, docs/self-hosting.md → Applying repository
  # configuration); the hourly timer inherits no shell at all and takes it from
  # /etc/hamstrack/drift.env through the unit's `EnvironmentFile=-…`
  # (docs/ops-prod-hardening.md → Installing the drift check). Two files, two people, two
  # occasions. And even EQUAL values can resolve differently: apply-config.sh skips a listed
  # file absent from the RELEASE TREE, this script skips one absent from the BOX. The single
  # path where the two sets agree by construction is apply-config.sh's own tail-end run —
  # `bash "$DRIFT" "$TARGET"`, which inherits that deploy's environment.
  #
  # So a self-hoster who runs a compose file of their own, narrows the deploy and leaves the
  # timer on the bundled default gets their own healthy containers reported as orphans every
  # hour for ever, under a sentence promising a deletion that would never happen — a monitor
  # that both fires permanently and mis-prescribes, which is this ticket's own defect wearing
  # the opposite sign. Hence: name the set actually resolved, and make the sweep CONDITIONAL.
  if printf '%s\n' "$plan" | grep -q 'Found orphan containers'; then
    orphans="$(printf '%s\n' "$plan" | tr -d '\r' \
      | sed -n 's/.*Found orphan containers (\[\([^]]*\)\]).*/\1/p' | head -n 1)"
    log "containers: compose found orphan containers for this project — ${orphans:-(compose named them in a form this check could not parse)} — running under a service none of (${COMPOSE_RESOLVED[*]:-${COMPOSE_LIST[*]}}) declares. If that is the same set your deploy resolves, its 'up -d --remove-orphans' would delete them. If you narrowed COMPOSE_FILES for the deploy and not for this check, these are your own containers: set the same value in /etc/hamstrack/drift.env"
    mismatched=1
  fi
  while IFS= read -r svc; do
    [ -n "$svc" ] || continue
    cids="$( cd "$TARGET" && docker compose "${COMPOSE_ARGS[@]}" ps -q "$svc" 2>/dev/null || true )"
    if [ -z "${cids//[[:space:]]/}" ]; then
      log "containers: service $svc is declared and not running"
      mismatched=1
      continue
    fi
    # Every container of the service, not the first: a scaled service has several, and
    # checking one is a check that silently narrows the moment somebody scales.
    while IFS= read -r cid; do
      [ -n "$cid" ] || continue
      name="$(docker inspect -f '{{.Name}}' "$cid" 2>/dev/null | tr -d '\r' || true)"
      name="${name#/}"
      if [ -z "$name" ]; then
        log "containers: the running container of service $svc could not be named, so its plan cannot be read"
        mismatched=1
        continue
      fi
      verbs="$( printf '%s\n' "$plan_pairs" | awk -F'\t' -v n="$name" '$1 == n { print $2 }' | LC_ALL=C sort -u )"
      if [ -z "$verbs" ]; then
        # (b), and NOT clean. A running container the plan does not mention means the plan
        # could not be read, and an unreadable plan must never be the thing that publishes 0.
        log "containers: the dry run planned nothing for $svc, whose container $name is running — 'up -d --dry-run' names every container it considers, so this is an oracle that cannot be read rather than a box that is clean"
        mismatched=1
        continue
      fi
      bad="$(printf '%s\n' "$verbs" | grep -vx 'Running' | head -n 1 || true)"
      if [ -n "$bad" ]; then
        # (c). The verb is printed because a verb this script does not know is exactly the
        # case where the reader needs to see what Compose actually said.
        log "containers: 'docker compose up -d' would act on $svc — compose plans '$bad' for container $name, so the definition on disk is not the one it is running"
        mismatched=1
      fi
    done <<< "$cids"
  done <<< "$services"

  [ "$mismatched" = 0 ] || DRIFT_CONTAINERS=1
  [ "$DRIFT_CONTAINERS" = 0 ] && log "containers: 'docker compose up -d --remove-orphans' would act on nothing — every declared service runs the definition on disk, and nothing runs that no file declares"
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

# --- scope 4: edge-body-limit ------------------------------------------------
# The only scope that compares the box against the REPOSITORY'S INTENT rather than against
# what a deploy placed. It has to be: apply-config.sh hard-refuses to sync a Caddyfile (the
# production one carries the hand-added Cloudflare trusted_proxies block that this
# repository's copy does not), so the body limit HD-191 added to the shipped Caddyfile
# reaches a box only through a hand merge — and until it does, the app answers 413 after
# Tomcat has already streamed and buffered the body, on the one lane no budget bounds.
#
# ABSENT IS NOT DRIFT, the rule installed-ops already uses. A box with no Caddyfile is not
# running the bundled proxy — a self-hoster fronting the stack with nginx has nothing to
# merge here and is told so once (docs/self-hosting.md tells them to set their own body
# limit; this script cannot see somebody else's proxy config).
#
# The test is deliberately for the PRESENCE OF A BOUND and not for a particular value:
# the value comes from ATTACHMENT_MAX_UPLOAD_SIZE at Caddy's start, and reading .env to
# compare numbers would put an operator's setting into a log that travels back through SSM
# into a public repository's Actions log. Presence is the whole of the question anyway —
# nobody merges this block and then sets it wrong, they simply never merge it.
check_edge_body_limit() {
  local caddyfile="$TARGET/Caddyfile" body
  if [ ! -f "$caddyfile" ]; then
    log "edge-body-limit: $caddyfile is absent — this box does not run the bundled Caddy, so there is no body limit here to merge"
    DRIFT_EDGE_BODY=0
    return 0
  fi
  # Comments stripped first, so a block that is present only inside the explanatory comment
  # (or one somebody commented OUT during an incident) reads as absent, which it is.
  body="$(sed 's/^[[:space:]]*#.*$//; s/[[:space:]]#.*$//' "$caddyfile")"
  if printf '%s\n' "$body" | grep -Eq '(^|[[:space:]])request_body([[:space:]]|\{|$)' \
     && printf '%s\n' "$body" | grep -Eq '(^|[[:space:]])max_size[[:space:]]'; then
    DRIFT_EDGE_BODY=0
    log "edge-body-limit: the deployed Caddyfile bounds the request body"
  else
    DRIFT_EDGE_BODY=1
    log "edge-body-limit: $caddyfile has no request_body/max_size block — over-sized uploads are"
    log "edge-body-limit: refused by the app only AFTER it has read the whole body, and that lane"
    log "edge-body-limit: spends no rate-limit budget. The applier never syncs this file: merge the"
    log "edge-body-limit: block by hand (docs/ops-prod-hardening.md §2) and run 'up -d caddy', not 'restart'."
  fi
  return 0
}

check_files
check_containers
check_installed_ops
check_edge_body_limit
