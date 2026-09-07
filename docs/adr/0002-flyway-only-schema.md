# ADR-0002: The DB schema is managed by Flyway alone; Hibernate is `validate`

Record date: 2026-08-22 (retrospective; the decision was made earlier)
Status: Accepted
Source: `CLAUDE.md` → the "Architecture" section

## Context
The PostgreSQL schema has to be managed. Hibernate can generate/update the schema
itself (`ddl-auto=update/create`), but that is unpredictable in production and gives no
reproducible history of changes.

## Decision
The schema is managed **exclusively** by Flyway migrations
(`src/main/resources/db/migration/V*.sql`). Hibernate runs in
`spring.jpa.hibernate.ddl-auto=validate` mode — it only checks the entity mapping against
the schema, but never changes it.

Additionally: `spring.jpa.open-in-view=false`, so service methods that
touch lazy associations must be `@Transactional`.

## Consequences
+ The schema is reproducible and versioned; the history of changes is clear.
+ `validate` catches an "entity ⇄ schema" drift at startup, before runtime.
− Any schema change requires a hand-written migration — Hibernate will not "fill in" the column.
− An already applied migration must not be edited (the Flyway rule) — only a new one.

## Alternatives
- `ddl-auto=update` — rejected: unpredictable schema changes, no
  reproducible history, dangerous in production.
