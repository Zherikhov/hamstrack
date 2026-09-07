# ADR-0033: Flyway version numbers are not reused — after a squash the chain continues above the previous maximum

Record date: 2026-09-04
Status: Proposed
Source: `docs/design/flyway-squash-procedure.md` §5.3, §12 (HD-188);
the witness to the cost of reuse — `CLAUDE.md`, the trap about `projects.issue_seq` ("(V9)"),
and `docs/project-state.md` §"Post-squash chain (V2–V6) — number-collision caution"

## Context

The 2026-08-07 squash folded the old `V1..V12` into a new `V1` and **continued the numbering from
`V2`**. Since then a number in `V2..V12` means two different things depending on which year is being
spoken of, and this has already cost the project two artefacts:

- `docs/project-state.md` is forced to carry a whole paragraph headed "number-collision caution", which
  explains that the old `V6` is the taxonomy while the new `V6` is the search indexes over custom
  fields, and that in the sections above the numbers are historical.
- `CLAUDE.md`, in the trap about a counter clobbered by a stale entity, says: "repair already-desynced
  rows with a migration that resyncs `issue_seq` to `MAX(number)` (V9)". Today's `V9` is
  `V9__components.sql`, and there is not a word about `issue_seq` in it. The reference is not stale — it
  is **wrong**, and a reader who follows it will find a file that contradicts the sentence that sent them there.

The second squash (HD-188) would add a third generation of `V2`. The fork: continue from `V2` (as last
time), continue from the next free number, or give the baseline migration itself the next free number
instead of `V1`.

Flyway does not require version continuity: a gap costs nothing at run time.

## Decision

**A Flyway version number is bound to one migration forever.**

- The baseline migration stays `V1__init_schema.sql`. The row in production already contains
  `script = 'V1__init_schema.sql'` and `description = 'init schema'`, and Flyway checks both of them
  alongside the checksum — keeping the name turns the re-stamping into a change of **one** column
  instead of three, and a fresh install then matches the re-stamped production on every column of the row,
  not on part of it.
- **The chain continues from the next free number — `V28`**, not from `V2`.
- A future squash either takes the next free number for its baseline migration or keeps `V1` and
  continues above the previous maximum. A number may not be reused in any case.
- References to retired migrations in the documentation, javadoc and tests are **rewritten onto the
  guarantee rather than onto the number**: not "(V9)", but "the migration that resynced `issue_seq`
  with `MAX(number)`; in the baseline migration the column is already correct". This is the same rule
  already written down in `CLAUDE.md`: a claim about a **class** outlives a claim about a **member**.

## Consequences

+ A version number becomes an identifier: `V22` means invite uniqueness forever, even when
  the file is gone. Any reference in a doc, javadoc, test or ticket resolves unambiguously.
+ The "number-collision caution" paragraph stops growing: it explains one historical reuse, not
  two.
+ The retired files, stored in `docs/db/retired-chain/`, are called by their real numbers and do not
  conflict with the live chain.
− A visible hole appears in the version sequence (`1`, then `28`), which will have to be explained —
  once, in the header of the baseline migration.
− `V1` remains the only number that has meant three different files (2026-07, 2026-08-07, 2026-09). That is
  the price of not changing the name of the row in production, and it is accepted deliberately: "the first
  migration is the schema" is true for every generation, whereas `V9` means different things for two generations.
− The rule rests on convention, not on a mechanism: nothing in the build forbids naming the next file
  `V2`. The closest thing to a mechanism is the check on the contents of the migrations directory from
  `docs/design/flyway-squash-procedure.md` §11.2, which makes adding any new file a
  deliberate edit.

## Alternatives

- **Continue from `V2`, as last time** — rejected: it gives a third generation of the same numbers and
  breaks exactly those references that will have to be fixed in this very ticket.
- **Give the baseline migration the next free number (`V28__baseline_schema.sql`)** — rejected, even though
  it is the only option in which no number repeats at all. It forces the re-stamping to change three
  columns instead of one, leaves "the baseline migration" a floating number that the reader has to look up
  somewhere, and diverges from the expectation that "the first migration is the schema".
- **Leave the numbers as they are and simply write the docs carefully** — rejected: that is exactly what was
  done on 2026-08-07, and the result is a wrong reference in `CLAUDE.md` that lived for a month and was found
  only while preparing the next squash.
