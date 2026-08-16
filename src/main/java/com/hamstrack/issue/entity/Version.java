package com.hamstrack.issue.entity;

import com.hamstrack.common.entity.BaseEntity;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A release target of one {@link Project} (HD-32,
 * labels-components-versions-proposal §6) — "2.4.0" — with a lifecycle
 * (unreleased ⇄ released, <em>reversible</em>) and an orthogonal archived flag.
 * Issues link to it in two roles through {@link IssueVersionLink}: FIX ("ships in
 * this release") and AFFECTS ("the defect exists in that release").
 *
 * <p><strong>Tenancy:</strong> {@code project} is the owner and {@code workspace}
 * is the denormalized tenant scope (as {@code Issue} and {@code Component} already
 * carry), so a tenant-scoped query never joins through {@code projects} and the
 * composite FK {@code issue_versions (version_id, workspace_id) → versions
 * (id, workspace_id)} can exist at all — a cross-tenant link is unrepresentable,
 * not merely rejected in Java (§3.8). Every repository lookup is
 * {@code …AndProject}, never a bare id (§3.1).
 *
 * <p><strong>Lifecycle invariants</strong> ({@code versions_released_ck} enforces
 * them in the DB, because both write paths are conditional bulk UPDATEs that
 * bypass any entity-level check):
 * <ul>
 *   <li>release → {@link #released} true, {@link #releasedAt} set, and
 *       {@link #releaseDate} defaulted to today when it is still null;</li>
 *   <li>un-release → {@link #released} false, {@link #releasedAt} null, and
 *       {@link #releaseDate} <strong>preserved</strong> — reversibility with no
 *       data loss is the story's acceptance criterion (§6.1).</li>
 * </ul>
 *
 * <p><strong>No {@code position} column:</strong> the list order is derived —
 * {@code released ASC} (unreleased first), then {@code release_date ASC NULLS
 * LAST}, then {@code lower(name) ASC} (§6.1, open question 6).
 *
 * <p>Versions are <em>content</em>, not bound taxonomy: nothing here enters
 * {@code ProjectConfigResponse} (§3.2).
 */
@Entity
@Table(name = "versions")
@Getter
@Setter
public class Version extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * The plan date. Survives un-release on purpose (§6.1) and is defaulted to the
     * server's UTC today when a release carries none and none is stored (§6.4).
     */
    @Column(name = "release_date")
    private LocalDate releaseDate;

    /**
     * <strong>{@code updatable = false} is load-bearing</strong> (the documented
     * {@code projects.issue_seq} pattern): this pair is written ONLY by the conditional
     * bulk UPDATEs {@code VersionRepository.markReleased}/{@code markUnreleased}, which
     * are the concurrency arbiter of the lifecycle. Left writable, every ordinary
     * {@code saveAndFlush} on a {@code Version} loaded earlier in the request (a PATCH
     * of the description, an archive/unarchive) emitted a FULL-column UPDATE from its
     * pre-race snapshot and silently wrote {@code released = false, released_at = NULL}
     * back over a release a concurrent curator had just made — and
     * {@code versions_released_ck} cannot catch it, because the stale pair is internally
     * consistent. The same applies to a {@code Version} held across
     * {@code markReleased}'s {@code clearAutomatically} and later {@code save}d: the
     * {@code merge()} would copy the stale pair back. INSERT is unaffected, so
     * {@code create()}'s {@code setReleased(false)} still persists normally.
     *
     * <p>{@code releaseDate} deliberately stays writable — {@code update()} legitimately
     * re-plans it.
     */
    @Column(nullable = false, updatable = false)
    private boolean released;

    /**
     * Set exactly when {@link #released} is true — see {@code versions_released_ck}.
     * {@code updatable = false} for the same reason as {@link #released} above.
     */
    @Column(name = "released_at", updatable = false)
    private OffsetDateTime releasedAt;

    /**
     * Archived versions leave every picker and the default Releases list but keep
     * their links (and their unique name slot). Orthogonal to {@link #released}:
     * old shipped releases are archived to keep the list short (§6.1).
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }
}
