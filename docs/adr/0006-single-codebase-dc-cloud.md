# ADR-0006: One codebase, two modes (DC/Cloud) through Spring profiles

Record date: 2026-08-22 (retrospective; the decision was made earlier)
Status: Accepted
Source: `CLAUDE.md` → the "DC vs Cloud (single codebase)" section; PLAN.md

## Context
The product has to ship in two models out of one core: a self-hosted installation
(DC) and a hosted Cloud (SaaS). The temptation is to fork the code or to breed
cloud-only branches of logic. That produces divergent behaviour and double maintenance.

## Decision
One codebase, two modes, controlled by the Spring profile `dc` or `cloud`
(`SPRING_PROFILES_ACTIVE=cloud`). Differences between the modes are implemented **only**
as behaviour gated by profile/config — **never** as a code fork.

The consequence for security (the project's highest bug class): a query/service that forgot
to scope by `workspace_id`/membership leaks one tenant's data to another in Cloud.
That is why resources are always resolved through a workspace membership check;
both a non-existent workspace and a non-member return a **404** — 403 is not used,
so as not to reveal the existence of the resource.

## Consequences
+ One behaviour and one maintenance burden for both delivery models.
+ The differences are concentrated in config/profiles rather than smeared across forks.
− Every feature has to be thought through in both modes at once (there is a `dc-cloud-guard` gate).
− Requires discipline: no cloud-only assumptions without a self-hosted equivalent.

## Alternatives
- Separate codebases/branches for DC and Cloud — rejected: divergent behaviour,
  double maintenance.
