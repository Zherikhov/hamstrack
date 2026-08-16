package com.hamstrack.issue.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Batched {@code … IN (:entities) GROUP BY …} usage counts, shared by every
 * classification primitive of the HD-6 epic — labels (HD-30), components (HD-31)
 * and versions (HD-32).
 *
 * <p><strong>Why batching is not optional.</strong> A grouped count over an
 * {@code IN} collection binds <em>one JDBC parameter per element</em>. PostgreSQL's
 * wire protocol caps a statement at <strong>65 535</strong> bound parameters, so a
 * catalog above that made {@code ?withUsage=true} an outright failure (a driver-level
 * error surfacing as a 500), and the statement was already slow to parse and plan long
 * before that ceiling. The per-project / per-workspace catalog caps
 * ({@code app.classification.*}) keep the normal case well under it, but they are
 * operator-tunable up to 100 000 — a limit the operator raises must degrade into more
 * round-trips, never into a broken endpoint. (Versions are capped per project too, by
 * {@code app.classification.max-versions-per-project}: batching turns a hard failure
 * into unbounded work, which is an argument for a ceiling, not a substitute for one.)
 *
 * <p>Each batch is a separate query, and the grouping key is the entity's own id, so a
 * given id appears in exactly one batch — the merge below can never double-count
 * <em>provided the caller's list holds distinct entities</em> (see
 * {@link #countIn}). ({@code Integer::sum} is kept anyway: it makes the merge correct
 * by construction rather than by an invariant a future caller could break.)
 */
final class UsageCounts {

    private UsageCounts() {}

    /**
     * Elements per query. A few hundred keeps every statement two orders of magnitude
     * below the 65 535-parameter ceiling and inside the range where Postgres still
     * plans an {@code IN} list as a cheap hash probe, while keeping the round-trip
     * count negligible at the default caps (500 components / 1000 labels ⇒ 1–2
     * queries).
     */
    static final int BATCH_SIZE = 500;

    /**
     * Run {@code query} over {@code entities} in {@link #BATCH_SIZE} chunks and merge
     * the {@code (id, count)} rows into one map.
     *
     * @param entities the resolved entities (never bare ids — the tenant boundary is
     *                 carried by the argument type, as in the repositories themselves).
     *                 <strong>Must be DISTINCT.</strong> Before batching, SQL {@code IN}
     *                 absorbed a repeated entity silently; now a duplicate that straddles
     *                 a batch boundary lands in two statements and its count is summed
     *                 <em>twice</em>. Every feeder today is a repository list that
     *                 fetch-joins only ToOne associations, so it cannot contain
     *                 duplicates — but a future collection fetch-join would multiply rows
     *                 and inflate the counts with no visible error.
     * @param query    the grouped-count repository method, applied to one batch
     */
    static <T> Map<UUID, Integer> countIn(List<T> entities,
                                          Function<List<T>, List<Object[]>> query) {
        var counts = new HashMap<UUID, Integer>(entities.size());
        for (var row : rowsIn(entities, query)) {
            counts.merge((UUID) row[0], ((Number) row[1]).intValue(), Integer::sum);
        }
        return counts;
    }

    /**
     * The raw-row half of {@link #countIn}, for grouped queries whose rows carry more
     * than {@code (id, count)} — versions group by {@code (versionId, linkType)} and
     * return four columns, so the caller aggregates them itself. Same batching
     * contract, same distinctness requirement.
     *
     * @param entities the resolved entities; <strong>must be distinct</strong> (see
     *                 {@link #countIn})
     * @param query    the grouped repository method, applied to one batch
     * @return every batch's rows, concatenated in batch order
     */
    static <T> List<Object[]> rowsIn(List<T> entities,
                                     Function<List<T>, List<Object[]>> query) {
        var rows = new ArrayList<Object[]>();
        for (int from = 0; from < entities.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, entities.size());
            rows.addAll(query.apply(entities.subList(from, to)));
        }
        return rows;
    }
}
