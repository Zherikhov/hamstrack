# ADR-0005: Enum fields are stored as `VARCHAR`, not as a PostgreSQL ENUM

Record date: 2026-08-22 (retrospective; the decision was made earlier)
Status: Accepted
Source: `CLAUDE.md` → the "PostgreSQL ENUMs" section

## Context
Enumerated values (types, statuses and so on) have to be stored. The obvious candidate is
the native `CREATE TYPE ... AS ENUM` in PostgreSQL. But the Hibernate 7 + PG ENUM pair
produces a JDBC cast error on INSERT: `column is of type X but expression is of type
character varying`.

## Decision
Do not use `CREATE TYPE ... AS ENUM`. Store enum values as
`VARCHAR(N)`; the correctness of the values is validated at the application layer by
Java enums.

## Consequences
+ INSERTs work without cast errors.
+ Adding a new value requires no `ALTER TYPE` — only code.
− There is no admissibility check at the DB level: a garbage value is caught only by
  the application.
− The Java enum and the set of stored values have to be kept in sync.

## Alternatives
- PG `CREATE TYPE ... AS ENUM` — explicitly forbidden because of the JDBC cast error on INSERT
  in Hibernate 7.
