package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueLabel;
import com.hamstrack.issue.entity.Label;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The {@code issue_labels} join rows (HD-30). Reads are always keyed by an issue (or a
 * batch of issues) the caller has already resolved through the workspace/project
 * membership chain, or by a {@link Label} already resolved
 * {@code …AndWorkspace} — so no method here can widen the tenant boundary.
 *
 * <p>The page loaders are the "no N+1" half of §3.7: one query per page, keyed by
 * issue id, mirroring {@code FieldValueService.valuesByIssue}.
 */
public interface IssueLabelRepository extends JpaRepository<IssueLabel, UUID> {

    /** Labels of a single issue, label eagerly joined (single-issue GET). */
    @Query("SELECT il FROM IssueLabel il JOIN FETCH il.label WHERE il.issue = :issue")
    List<IssueLabel> findAllByIssue(@Param("issue") Issue issue);

    /** Labels for a whole page of issues in ONE query (board/backlog/search batch load). */
    @Query("SELECT il FROM IssueLabel il JOIN FETCH il.label WHERE il.issue IN :issues")
    List<IssueLabel> findAllByIssueIn(@Param("issues") Collection<Issue> issues);

    /** Usage count for one label (delete-guard + {@code /usage}). */
    long countByLabel(Label label);

    /**
     * Usage counts for a batch of labels in ONE grouped query — rows
     * {@code (labelId, count)}. Takes the resolved {@link Label} entities (never bare
     * ids) like every other method here, so the tenant boundary is carried by the
     * argument type itself and no caller can widen it.
     *
     * <p><strong>Callers must chunk</strong> ({@code UsageCounts.countIn}): the
     * {@code IN} list binds one JDBC parameter per label, and PostgreSQL rejects a
     * statement above 65 535 parameters outright.
     */
    @Query("SELECT il.label.id, count(il) FROM IssueLabel il "
            + "WHERE il.label IN :labels GROUP BY il.label.id")
    List<Object[]> countsByLabels(@Param("labels") Collection<Label> labels);

    /**
     * Merge step 1 — collapse everything that would collide with
     * {@code UNIQUE (issue_id, label_id)} once step 2 re-points the survivors, namely:
     * <ol>
     *   <li>source rows whose issue ALREADY carries the target, and</li>
     *   <li>duplicate rows <em>among the sources</em> — an issue carrying two of the
     *       merged labels keeps exactly one (the lowest id), otherwise both would be
     *       rewritten to the same {@code (issue, target)} pair.</li>
     * </ol>
     * The caller MUST {@code flush()} between this and {@link #repointToTarget}
     * (CLAUDE.md: within one flush Hibernate orders INSERT/UPDATE before DELETE).
     * Plain {@code @Modifying}: nothing mutated here is re-read as a managed entity.
     */
    @Modifying
    @Query("""
            DELETE FROM IssueLabel il
            WHERE il.label IN :sources
              AND (EXISTS (SELECT 1 FROM IssueLabel t
                           WHERE t.issue = il.issue AND t.label = :target)
                OR EXISTS (SELECT 1 FROM IssueLabel o
                           WHERE o.issue = il.issue AND o.label IN :sources AND o.id < il.id))
            """)
    int deleteSourceRowsAlreadyOnTarget(@Param("sources") Collection<Label> sources,
                                        @Param("target") Label target);

    /**
     * Merge step 2 — re-point the surviving source rows at the target label. A bulk
     * UPDATE is correct here precisely because the affected {@code IssueLabel} rows
     * have NOT been materialized in this transaction (CLAUDE.md: a bulk UPDATE does
     * not refresh L1-cached entities).
     */
    @Modifying
    @Query("UPDATE IssueLabel il SET il.label = :target WHERE il.label IN :sources")
    int repointToTarget(@Param("sources") Collection<Label> sources,
                        @Param("target") Label target);

    /** Force-delete a label: drop its attachments first (the FK cascades too). */
    @Modifying
    @Query("DELETE FROM IssueLabel il WHERE il.label = :label")
    int deleteAllByLabel(@Param("label") Label label);
}
