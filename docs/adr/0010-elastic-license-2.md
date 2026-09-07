# ADR-0010: The license is Elastic License 2.0, not Apache 2.0

Date: 2026-08-26
Status: Accepted
Source: the project owner's decision (correspondence 2026-08-26); the license text —
https://www.elastic.co/licensing/elastic-license

## Context
The project started under the **Apache License 2.0** — a permissive license that allows
anyone to modify, distribute and sell the code. That is incompatible with the
commercialisation plan (subscription sales; cloud + support + paid add-on modules;
eventually — closing the repository).

The owner's requirements for the license of the open phase:
- the sources are visible, the repository is public;
- anyone, **including companies**, may clone it and deploy it for themselves for free;
- reselling / standing up a competing hosting is forbidden;
- copyright notices must not be removed.

Initially there was also the criterion "modification is forbidden", but it is incompatible with all
the ready-made source-available licenses that allow a business a free self-host
(they forbid redistribution/competition, not a private edit). Since
the business model is protected by the non-compete clause rather than by a ban on modification, the
"modification is forbidden" criterion was dropped as redundant.

## Decision
Relicense the project from Apache 2.0 to the **Elastic License 2.0 (ELv2)**.
ELv2 allows the software to be freely used, copied, modified, distributed
and deployed (including commercially), but forbids: (1) providing the software to
third parties as a hosted/managed service; (2) circumventing license keys;
(3) removing/obscuring license and copyright notices.

Updated: `LICENSE` (the full verbatim ELv2 text + a copyright notice),
`README.md` (badge + a License section), `pom.xml` (`<licenses>`),
`src/main/frontend/package.json` (`license`), `openapi.yaml` (`info.license`).

## Consequences
+ A free self-host for everybody, business included — as the owner wanted.
+ The "no hosted/managed service" clause protects the future Cloud offering from
  a competing SaaS.
+ Support for license keys fits the "immutable core + paid add-on modules" plan.
+ There is no "bomb" of an automatic conversion into open source (unlike FSL).
− ELv2 is **not** an OSI-approved open-source license (it is source-available); part of
  the community is put off by that.
− **Irreversible for what has already been published:** everything released under Apache 2.0 (the whole
  commit history before this change) stays available under Apache 2.0 forever for
  those versions. The change applies only to future versions.
− ELv2 does allow a private modification of the core — "core immutability" for
  self-hosted will have to be ensured separately (a future commercial EULA +
  technical measures), and that is out of scope for this ADR.

## Alternatives
- **PolyForm Strict 1.0.0** — rejected: it allows only non-commercial
  use, that is, a business would not be able to host it for free.
- **Functional Source License (FSL)** — rejected: after 2 years every version
  automatically becomes Apache/MIT, which contradicts the goal of monetisation.
- **A proprietary EULA of our own with a ban on modification** — deferred: there is no ready
  text, a lawyer is needed; the "modification is forbidden" criterion was found redundant for the
  business model.
- **Stay on Apache 2.0** — rejected: it allows resale and competing
  hosting, incompatible with a subscription.

## Open questions / follow-ups
- The "Hamstrack" trademark is protected separately from the code — consider registering it.
- On the move to a subscription — a private repository + a commercial EULA (with
  clauses about the immutable core and the ownership of extensions); a separate ADR.
- The rights holder in `LICENSE` is "Vladislav Zherikhov" (confirmed by the owner
  2026-08-26). On the move to a legal entity — update the copyright notice to it.
