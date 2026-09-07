# ADR-0004: Timestamps through Spring Data JPA auditing

Record date: 2026-08-22 (retrospective; the decision was made earlier)
Status: Accepted
Source: `CLAUDE.md` → the "Timestamps" / "Gotchas" sections

## Context
Entities need `createdAt` / `updatedAt`. The natural choice is Hibernate
`@CreationTimestamp` / `@UpdateTimestamp`. But in Hibernate 7 these annotations
set the value **at flush**, not at `persist()`, so after `save()` the
fields stay `null` — and that breaks code reading the timestamp right after saving.

## Decision
Use Spring Data JPA auditing: `@CreatedDate` / `@LastModifiedDate` +
`@EntityListeners(AuditingEntityListener.class)`, with `@EnableJpaAuditing` on
`HamstrackApplication`. These values are set during
`@PrePersist` / `@PreUpdate` — available immediately after `save()`. The shared fields are moved
into the mapped superclasses `common.entity.BaseEntity` / `CreatedOnlyEntity`.

The schema additionally carries `DEFAULT NOW()` + DB triggers as a safety net for
raw SQL writes outside JPA.

## Consequences
+ `createdAt` / `updatedAt` are available immediately after `save()`.
+ Writes outside JPA still get a timestamp (DB triggers).
− It must not be mixed with `@CreationTimestamp` on the same fields (the `null` bug comes back).

## Alternatives
- Hibernate `@CreationTimestamp` / `@UpdateTimestamp` — rejected: in Hibernate 7
  the value is `null` after `save()` (it is set only at flush).
