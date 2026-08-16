package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueVersionLink;
import com.hamstrack.issue.entity.StatusCategory;
import com.hamstrack.issue.entity.Version;
import com.hamstrack.issue.entity.VersionLinkType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The {@code issue_versions} link rows (HD-32) — both roles in one table,
 * discriminated by {@link VersionLinkType}. Reads are always keyed by an issue (or
 * a batch of issues) the caller has already resolved through the workspace/project
 * membership chain, or by a {@link Version} already resolved
 * {@code …AndProject} — so no method here can widen the tenant boundary.
 *
 * <p>The page loader is the "no N+1" half of §3.7: one query per page, keyed by
 * issue id, mirroring {@code IssueLabelRepository.findAllByIssueIn}.
 */
public interface IssueVersionLinkRepository extends JpaRepository<IssueVersionLink, UUID> {

    /** All version links of a single issue, version eagerly joined (single-issue GET). */
    @Query("SELECT l FROM IssueVersionLink l JOIN FETCH l.version WHERE l.issue = :issue")
    List<IssueVersionLink> findAllByIssue(@Param("issue") Issue issue);

    /**
     * Version links for a whole page of issues in ONE query (board/backlog/search
     * batch load) — BOTH roles at once, split by the service. A board page of 100
     * issues must stay a constant number of queries (§3.7).
     */
    @Query("SELECT l FROM IssueVersionLink l JOIN FETCH l.version WHERE l.issue IN :issues")
    List<IssueVersionLink> findAllByIssueIn(@Param("issues") Collection<Issue> issues);

    /** Total link count for one version, both roles (the delete-in-use guard). */
    long countByVersion(Version version);

    /**
     * <strong>Per-version progress for a whole list in ONE grouped query</strong>
     * (§6.1) — rows {@code (versionId, linkType, linkCount, doneCount)}, where
     * {@code doneCount} counts the linked issues whose status category is
     * {@code DONE}. Never call this per version.
     *
     * <p>The service reads FIX rows as {@code issueCount}/{@code doneIssueCount}
     * (the release progress bar) and AFFECTS rows as {@code affectsIssueCount};
     * {@code unresolvedFixIssueCount} is {@code fixCount - fixDoneCount}, so the
     * whole {@code VersionUsageResponse} comes from this one query too.
     *
     * <p>Takes the resolved {@link Version} entities (never bare ids) like every
     * other method here, so the tenant boundary is carried by the argument type
     * itself. <strong>Callers must chunk</strong> ({@code UsageCounts.rowsIn}): the
     * {@code IN} list binds one JDBC parameter per version, and PostgreSQL rejects a
     * statement above 65 535 parameters outright.
     */
    @Query("""
            SELECT l.version.id, l.linkType, count(l),
                   sum(case when i.status.category = :done then 1 else 0 end)
            FROM IssueVersionLink l JOIN l.issue i
            WHERE l.version IN :versions
            GROUP BY l.version.id, l.linkType
            """)
    List<Object[]> progressByVersions(@Param("versions") Collection<Version> versions,
                                      @Param("done") StatusCategory done);

    /**
     * Release step 1 of the optional {@code moveUnresolvedToVersionId} (§6.4 trap
     * T2) — collapse every source row that would collide with
     * {@code UNIQUE (issue_id, version_id, link_type)} once step 2 re-points the
     * survivors: an unresolved issue that ALREADY carries a FIX link to the target.
     *
     * <p>The caller MUST {@code flush()} between this and
     * {@link #repointUnresolvedFixLinks} (CLAUDE.md: within one flush Hibernate
     * orders INSERT/UPDATE before DELETE, so the re-point would hit the not-yet-deleted
     * old row — the trap that already bit label merge and every admin set editor).
     *
     * <p>"Unresolved" is expressed as an EXISTS subquery rather than
     * {@code l.issue.status.category}: an implicit join is not allowed in the WHERE
     * clause of a bulk statement. Plain {@code @Modifying} — nothing mutated here is
     * re-read as a managed entity.
     */
    @Modifying
    @Query("""
            DELETE FROM IssueVersionLink l
            WHERE l.version = :source AND l.linkType = :fix
              AND EXISTS (SELECT 1 FROM Issue i
                          WHERE i.id = l.issue.id AND i.status.category <> :done)
              AND EXISTS (SELECT 1 FROM IssueVersionLink t
                          WHERE t.issue.id = l.issue.id AND t.version = :target
                            AND t.linkType = :fix)
            """)
    int deleteUnresolvedFixLinksCollidingWith(@Param("source") Version source,
                                              @Param("target") Version target,
                                              @Param("fix") VersionLinkType fix,
                                              @Param("done") StatusCategory done);

    /**
     * Release step 2 — re-point the surviving unresolved FIX links at the target
     * version. Issues in a DONE-category status keep the released version, which is
     * the whole point of the move (§6.6).
     *
     * <p>A bulk UPDATE is correct here precisely because the affected
     * {@code IssueVersionLink} rows have NOT been materialized in this transaction
     * (CLAUDE.md: a bulk UPDATE does not refresh L1-cached entities).
     */
    @Modifying
    @Query("""
            UPDATE IssueVersionLink l SET l.version = :target
            WHERE l.version = :source AND l.linkType = :fix
              AND EXISTS (SELECT 1 FROM Issue i
                          WHERE i.id = l.issue.id AND i.status.category <> :done)
            """)
    int repointUnresolvedFixLinks(@Param("source") Version source,
                                  @Param("target") Version target,
                                  @Param("fix") VersionLinkType fix,
                                  @Param("done") StatusCategory done);

    /**
     * Delete-with-remap step 1 (§6.4 trap T3) — the same collision collapse as
     * {@link #deleteUnresolvedFixLinksCollidingWith}, but for <strong>both</strong>
     * roles and every issue: drop each source row whose issue already carries the
     * target in the SAME role. Roles are independent, so an issue may legitimately
     * end up with both a FIX and an AFFECTS link to the target.
     *
     * <p>The caller MUST {@code flush()} before {@link #repointToTarget}.
     */
    @Modifying
    @Query("""
            DELETE FROM IssueVersionLink l
            WHERE l.version = :source
              AND EXISTS (SELECT 1 FROM IssueVersionLink t
                          WHERE t.issue.id = l.issue.id AND t.version = :target
                            AND t.linkType = l.linkType)
            """)
    int deleteSourceRowsAlreadyOnTarget(@Param("source") Version source,
                                        @Param("target") Version target);

    /** Delete-with-remap step 2 — re-point every surviving link of both roles. */
    @Modifying
    @Query("UPDATE IssueVersionLink l SET l.version = :target WHERE l.version = :source")
    int repointToTarget(@Param("source") Version source, @Param("target") Version target);

    /** Force-delete a version: drop its links first (the composite FK cascades too). */
    @Modifying
    @Query("DELETE FROM IssueVersionLink l WHERE l.version = :version")
    int deleteAllByVersion(@Param("version") Version version);
}
