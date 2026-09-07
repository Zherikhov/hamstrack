# ADR-0001: Package-by-feature under `com.hamstrack`

Record date: 2026-08-22 (retrospective; the decision was made earlier)
Status: Accepted
Source: `CLAUDE.md` → the "Architecture" section

## Context
A package structure is needed for a growing Spring Boot application. The classic
choice is package-by-layer (project-wide `controller`, `service`, `repository`,
`entity` packages) versus package-by-feature (one package per business area, with
its own layers inside).

## Decision
Package-by-feature: one top-level package per business area under
`com.hamstrack` — `auth`, `workspace`, `project`, `issue` — plus `common` for
cross-cutting infrastructure. Inside each feature, nested layer subpackages: `entity`,
`repository`, `service`, `controller`, `dto`, `exception` (not every feature has
all six).

`common` holds the cross-cutting code: `common.entity` (`BaseEntity`/`CreatedOnlyEntity`),
`common.exception` (`AppException`, `GlobalExceptionHandler`), `common.security`
(`JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`), `common.config`
(`@ConfigurationProperties`).

## Consequences
+ The code of one business area lies together — easier navigation and clearer boundaries.
+ A natural boundary for future third-party developers and for a future
  extension model (see the project discussions of a plugin SPI).
− Requires discipline: cross-cutting code must go into `common` rather than spread across
  the features.

## Alternatives
- Package-by-layer — rejected: it blurs the boundaries of business areas and scales
  worse as the number of features grows.
