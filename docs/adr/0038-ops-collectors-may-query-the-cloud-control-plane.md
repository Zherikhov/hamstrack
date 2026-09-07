# ADR-0038: An operations collector on the host may query the cloud control plane; the application may not, and it is switched on by the fact that the unit is installed

Record date: 2026-09-06
Status: Accepted (implemented in HD-262, shipped in 0.18.0)
Source: `docs/design/root-volume-snapshot-age-proposal.md` §9 (HD-262); the inline policy
`ec2-snapshot-read` on the `hamstrack-ec2` role — verified from the instance 2026-09-06; the unit's form —
ADR-0011; the "one codebase, two modes" rule — ADR-0006

## Context

ADR-0037 requires layer 3 to be checked by artefact age. The artefact is an EBS snapshot, and
the only source of truth about it is the AWS API. That is, the deploy for the first time gains a component
that needs the **cloud provider's control plane** — while by ADR-0006 Hamstrack must
run as one codebase in two modes, and differences must be a configuration gate, not
a fork.

Three questions that cannot be settled by default:

1. **Where this should live** — in the application under the `cloud` Spring profile or outside the JAR.
2. **How it switches on** — by auto-detection (poke IMDS and, if it does not answer, quietly step aside) or
   explicitly.
3. **What to do for self-hosting** — a self-hoster on bare metal or on a VPS without EBS has neither
   volume snapshots nor an IMDS to ask.

The second question is the main one, and it has a non-obviously dangerous answer. Under auto-detection
**a production box with broken IMDS** (a hop limit reset to 1, IMDS switched off, a local
firewall rule) is indistinguishable from a self-hoster's server: both publish nothing, both are silent, both
look healthy. That is exactly the class of failure that HD-262 is — a mechanism
that failed noiselessly.

## Decision

**Cloud-specific observability dependencies live in host `systemd` units in `ops/`
(the form is ADR-0011), not in the application. The application does not call the EC2 API and gains from this
decision not a single new permission.**

Enablement is **the fact that the unit is installed**, not a profile and not an environment probe. The deploy drops
`/opt/hamstrack/ops/` and installs nothing; the operator installs. Hence:

- **The default value is "work"** (`SNAPSHOT_SOURCE=ebs`), not "off". Installing
  the unit *is* the declaration of intent. With a default of `off` the most likely field outcome is —
  the unit is installed, `snapshot.env` is forgotten, the collector silently does nothing, the layer is not observed,
  the box looks green; that is, HD-262 reproduced by the mechanism against HD-262.
- **An unreachable IMDS is a published failure, not a step aside.** Whoever installed this
  unit declared that they have snapshots; silence in response to their absence would be a lie.
- **`none` exists only as an explicit off switch** for a box that installed the unit and
  then left EC2: in that state nothing is published and one line naming the setting is written to
  the journal. Exactly as `BACKUP_TARGET=local` never emits an `upload` series.
- **The value is validated by a single `case` from which both branches are derived** — two independent
  equality checks are not complementary, and `EBS`, or `ebs` with a trailing `CR` from a
  Windows checkout, falls into neither branch while looking configured.

The grant is minimal and read-only, on the instance role: `ec2:DescribeVolumes` +
`ec2:DescribeSnapshots`, `Resource: "*"` (EC2 `Describe*` has no resource ARNs), with the condition
`aws:RequestedRegion`. `ec2:DescribeInstances` is deliberately **not** granted, and the request is built
for that: the instance id is taken from IMDS, the volume through the `attachment.instance-id` filter. Not a single
write permission: a monitor that can create a snapshot can be made to delete one.

For a self-hoster without EBS the path is **not to install the unit**: the series does not exist, and every rule in
`rules.yml` carries `noDataState: OK` and therefore sleeps. This is not "cloud-only with no
self-hosted path" in the sense of ADR-0006: observability of volume snapshots is impossible where there are no
volumes, and such a box's durability is provided by layers 1–2 and by its own
hypervisor's tooling.

## Consequences

+ The JAR pulls in neither an SDK nor AWS assumptions; the DC build loses nothing and carries nothing extra.
+ The gate is installation, that is, the same mechanism by which the backup and the drift check are already switched on;
  no new concepts are introduced, and `hamstrack_config_drift{scope="installed-ops"}` starts
  watching the new unit on the day it ships **without a single edit to the drift-check script** and
  without a new `scope` value (a closed enum, every addition being a series per box and a branch in
  the annotation).
+ The cloud permissions belong to the host unit, not to the application; the blast radius of an application
  compromise does not grow.
+ An IMDS failure is visible as a failure and not as silence, and incidentally it is a signal about the channel from which
  the application takes its S3 credentials.
− A new class of permissions on the instance role has appeared, and a new installation step that the operator must
  remember (`installed-ops` remembers it for them).
− The feature is unavailable to self-hosters outside EC2, and this is accepted openly rather than papered over.
− The collector is **not** run at the end of the deploy, unlike the drift check: otherwise the deploy would start
  depending on the control plane and on IMDS, and a fresh `check_timestamp` written by the deploy
  would reproduce the hole "looks fresh, while it is only fresh as of the last deploy", for the sake of which
  `ConfigDriftCheckStale` was invented. The price is that the collector's output does not reach the Actions log, and
  with it goes the reason to deal with redacting the account id out of `AccessDenied`
  messages; if the check is ever moved onto the deploy path, that is the first thing to
  cut out.

## Alternatives

- **The check inside the application under the `cloud` profile** — rejected: the JAR would drag in a dependency
  and assumptions that the DC build cannot make use of, and the web application would have to
  hold a permission on a cloud API for the sake of a job performed once an hour outside any request.
- **Runtime EC2 auto-detection with a quiet step aside** — rejected: it makes a broken production
  collector byte-for-byte indistinguishable from an uninterested self-hoster. A control whose absence
  is noiseless is a control that does not exist.
- **A default of `off` with mandatory configuration of the file** — rejected: an installed and
  unconfigured collector silently does nothing, which is the original failure itself.
- **A variable with the volume id instead of resolving from the instance** — rejected, see ADR-0037: a stored
  id inherits exactly the staleness for the catching of which the check exists.
- **A move to managed snapshots/RDS for the sake of "backups out of the box"** — not considered here
  for the same reasons as in ADR-0011: it is an infrastructure migration that splits DC and Cloud apart.
