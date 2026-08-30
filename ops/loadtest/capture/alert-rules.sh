#!/usr/bin/env bash
# HD-186 — list the provisioned alert rules that form half of the breach bar.
# Spec: §4.3 (the breach definition).
#
#   capture/alert-rules.sh [path-to-rules.yml]
#
# ---------------------------------------------------------------------------
# WHY THIS SCRIPT EXISTS AT ALL.
#
# §4.3 defines the reported capacity as:
#
#   "the highest completed stage at which every target held for the whole hold period AND
#    NO PROVISIONED ALERT RULE'S CONDITION WAS MET FOR ITS `for:` DURATION."
#
# That is expressed over the CATEGORY "every provisioned rule", not over a list — so a rule
# added later is automatically part of the bar and no list goes stale. A hand-typed roster
# of alert names in a runbook would be exactly the stale enumeration that phrasing exists
# to avoid: it would keep looking complete while the file it describes grew.
#
# So the roster is READ FROM THE FILE, every run, and printed with each rule's `for:`
# duration. The operator confirms against Grafana that none of them met its condition
# during the stage.
#
# Two rules are EXPECTED to fire on a healthy run and are not breaches — HostMemoryLow and
# HostSwapInUse are provisioned deliberately ABOVE the harness's own abort thresholds
# (200 MiB available / 128 MiB swap vs 150 MiB / 512 MiB), so they are the warning that
# precedes an abort. Note the times they fired; do not silence them, and do not count them
# as a breach.
#
# One is NOT expected and invalidates the run outright: HostKernelOOMKill. Something on the
# box was killed, and a capacity number measured across a kill measures the kill.
set -euo pipefail

HERE_CFG="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# $LOAD_CONFIG's [BOX] half — the container names and the database connection — used to be
# read by nothing on this side, so an operator with a correct configuration file still got a
# fail-fast on a container name. See capture/lib-config.sh.
# shellcheck source=./lib-config.sh
. "$HERE_CFG/lib-config.sh"


HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RULES="${1:-}"

if [[ -z "$RULES" ]]; then
    for c in "$HERE/../../../observability/grafana/provisioning/alerting/rules.yml" \
             "/opt/hamstrack/observability/grafana/provisioning/alerting/rules.yml"; do
        [[ -f "$c" ]] && { RULES="$c"; break; }
    done
fi
[[ -n "$RULES" && -f "$RULES" ]] || {
    echo "could not find rules.yml — pass its path." >&2
    echo "Looked in the repository checkout and /opt/hamstrack." >&2
    exit 2; }

echo "provisioned alert rules (source: $RULES)"
echo "Every one of these is part of §4.3's breach bar. A stage counts as PASSED only if"
echo "none of them met its condition for its 'for:' duration during the hold."
echo ""
printf '%-26s %-8s %s\n' "RULE" "FOR" "NOTE"
printf '%-26s %-8s %s\n' "----" "---" "----"

# In this file `for:` FOLLOWS its rule's `title:`, so a rule is emitted when its `for:`
# arrives — never when its title does. Getting the direction wrong shifts every duration by
# one rule, which is a wrong answer that looks exactly like a right one: the names are all
# present, the durations are all plausible, and every single pairing is false. (It was
# wrong that way once; the fix is the reason this comment names the direction instead of
# assuming it.) A title with no `for:` is emitted at EOF with "(none)" rather than dropped —
# a rule missing from this list is a rule silently removed from the breach bar.
awk '
  function note(t,   n) {
      n=""
      if (t == "HostMemoryLow" || t == "HostSwapInUse")
          n="EXPECTED during a healthy run — note the time, do not silence, NOT a breach"
      else if (t == "HostKernelOOMKill")
          n="NOT expected — if this fires the run is ALREADY INVALID"
      else if (t == "RoleScopeViolation")
          n="abort condition 6 — never masked by \"we were load testing\""
      else if (t == "ConfigDrift")
          n="must read 0 before AND after (§5.1 precondition 3, §5.5.5)"
      return n
  }
  /^[[:space:]]*title:[[:space:]]*/ {
      if (pending != "") { printf "%-26s %-8s %s\n", pending, "(none)", note(pending) }
      pending = $2; next
  }
  /^[[:space:]]*for:[[:space:]]*/ {
      if (pending != "") {
          printf "%-26s %-8s %s\n", pending, $2, note(pending)
          pending = ""
      }
  }
  END { if (pending != "") { printf "%-26s %-8s %s\n", pending, "(none)", note(pending) } }
' "$RULES"

echo ""
echo "Total: $(grep -c '^[[:space:]]*title:' "$RULES") rules."
echo ""
echo "Do NOT copy this list into the run record as a fixed roster. Record instead that"
echo "the bar was 'every rule provisioned at the time of the run' and cite the file's"
echo "commit — a count and a roster both go stale, and the property does not."
