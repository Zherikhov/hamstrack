# ADR-0032: The baseline migration is taken mechanically from the live schema, and production is **relabelled**, not migrated

Record date: 2026-09-04
Status: Proposed
Source: `docs/design/flyway-squash-procedure.md` §5.2, §6.3, §7, §8 (HD-188);
the previous squash — `docs/project-state.md` §"Schema baseline — migrations squashed (2026-08-07)";
the warning about hand-authoring the baseline migration — the header of `V27__taxonomy_palette_alignment.sql`,
section "FOLDING INTO HD-188";
the rule about a backup before overwriting `flyway_schema_history` — `docs/release-checklist.md`
and `ops/backup/hamstrack-backup.sh`;
the container for verification — `docs/ops-prod-hardening.md` §6.5

## Context

The Flyway chain has grown to 27 files and 3923 lines, of which `V1__init_schema.sql` is 611. A new
installation replays four backfills, two cleanups with `DELETE` and one refusal, each of which finds no
work for itself on an empty database. The schema cannot be read other than by reading 27 files in order.

Squashing the chain means changing `V1`'s checksum and removing `V2..V27`. At startup Flyway checks
both the checksums and the presence of the applied migrations, so **any already existing database will
not start after that**. This was already done on 2026-08-07, and back then it was safe for one named
reason: the product had not a single user, any database could be torn down and created anew. **That
reason has expired**: production holds a live backlog, and after the announcement it will hold other
people's work, and a self-hoster on `ghcr.io/zherikhov/hamstrack` will get a breakage they cannot fix
themselves.

The fork is not whether to squash, but **how to obtain the baseline migration and what to do with
production**.

- **How to obtain it.** The previous `V1` was written by hand. The header of `V27` spends thirty lines
  explaining how that ends: colour literals and three `DEFAULT`s in the middle of a `CREATE TABLE` are
  exactly the lines that carry over "how it was", and then the palette alignment is silently rolled back
  **for new installations**, while every already migrated database keeps it. An error without an error:
  no log, no crash, no red test — the divergence is found by a person comparing two screenshots.
- **What to do with production.** Production is either recreated (data loss, the 2026-08-07 option,
  unacceptable today), or it stays exactly as it is and only its **bookkeeping** changes — the
  `flyway_schema_history` table.

## Decision

**The baseline migration is taken with `pg_dump` from a database the chain itself built. Production is
not migrated — one service table is rewritten for it.**

- **Generation.** `pg_dump --schema-only --no-owner --no-privileges -T flyway_schema_history` from a
  database run by the chain up to head, plus `pg_dump --data-only --column-inserts` over the fourteen
  catalogue tables from the **very same** database (never from production: there those same tables hold
  rows created by people). There is exactly one post-processing step and it is mechanical — strip the
  `public.` qualifier and the `SET` preamble, because not one migration in this repository is
  schema-qualified: that is precisely what lets the tests direct the whole Flyway run into a disposable
  **schema** instead of a separate database.
- **Hand-editing the baseline migration is forbidden.** It is not edited, it is regenerated. The
  difference between those two verbs is written in the header of the file itself.
- **Production is relabelled.** In one transaction: delete every row except version `1`, and set a new
  checksum on it. Not one DDL statement, not one user row. The checksum is read **from a database run
  by the very image that will go to production**, not from a number typed on a developer's machine.
- **Four proofs before the relabelling, and the decisive one is the fourth.** The chain's schema against
  the baseline migration's schema; the seed rows by count and by content; a clean load → `validate` →
  `contextLoads` → the whole test suite; and **a production dump restored into a separate database whose
  schema is compared with the baseline migration**. Anyone can perform the first three, the fourth
  requires access to production — and is cut so that only a `--schema-only` dump, which contains not a
  single data row, is handed outside.
- **The rollback is one file of 27 rows.** `pg_dump --data-only --column-inserts -t flyway_schema_history`,
  taken with the application stopped, plus a permanent backup in `manual/` labelled `pre-hd188-squash`.

## Consequences

+ The error class "a literal was carried over by hand and the alignment was silently rolled back" is
  closed by construction: what gets into the baseline migration is what **lies in the database**, not
  what the author retyped. The warning in `V27`'s header becomes historical.
+ Production loses not a byte: one service table changes, the schema and the data are not touched at all.
+ Every failure mode of this procedure is a refusal to **start** with a message naming the version and
  the checksum. There is no path on which the application comes up and serves the wrong schema.
+ A performable path appears for the self-hoster: the same four statements we executed ourselves.
− **Production's schema stops being reproducible from the repository.** Before the relabelling the claim
  "production is what the chain produces" can be checked; afterwards it is unprovable, because production
  is marked as the baseline migration regardless of whether that is so. Hence the fourth proof: it is
  performable exactly once and exactly before the relabelling. **If it is skipped, the relabelling is a guess.**
− Restoring any backup taken **before** the relabelling requires applying the relabelling to the restored
  copy. The `daily/` copies expire after 30 days, the `manual/pre-hd188-squash` copy never expires,
  which is why this note is permanent. The shape is the same as in ADR-0025: a restored backup requires
  a replay step.
− The identifiers of the seed rows, which used to be generated by `gen_random_uuid()` at every installation,
  freeze into literals. For new installations that is an improvement (a catalogue row can be named in
  correspondence), but production's ids are **forever different** from a fresh installation's. Not one
  diff will show it.

## Alternatives

- **Write the baseline migration by hand** — rejected: this is exactly what `V27` warns about, and the
  price of an error is a silent divergence between new and old installations that not one test catches.
- **Tear down and create production anew** — rejected: that is what was done on 2026-08-07 for the single
  named reason "there is no data". The reason has expired.
- **`flyway repair`** — rejected: it marks missing migrations as deleted rather than removing them (26
  tombstone rows where acceptance requires one), requires a CLI on the box and **computes itself** the
  very checksum the whole procedure wants to verify independently.
- **Split the baseline migration across several files** — rejected (and the same is recorded in the
  ticket): the order of such a set has to be given by hand, and a hand-written order is exactly the
  failure for whose elimination the generation is done in the first place.
- **Leave the chain as it is** — rejected: it grows monotonically, and every next release makes the
  squash more expensive. It is cheapest right now, before the announcement.
