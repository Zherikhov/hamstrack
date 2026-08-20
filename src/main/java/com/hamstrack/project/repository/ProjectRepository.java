package com.hamstrack.project.repository;

import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByWorkspace(Workspace workspace);

    List<Project> findAllByWorkspaceAndArchivedAtIsNull(Workspace workspace);

    Optional<Project> findByIdAndWorkspace(UUID id, Workspace workspace);

    /**
     * The key and name of one project, <strong>scoped by workspace</strong> — the identity a
     * downloaded report CSV has to print in its comment header (HD-141 R7), because a file that
     * has been renamed and mailed on has no URL to be read alongside.
     *
     * <p>A projection rather than {@code findById} for two reasons, and the second is the one that
     * matters. It reads two columns instead of hydrating a {@code Project} with its four taxonomy
     * associations; and it takes the <em>workspace</em> id, so it cannot answer for a project
     * outside the tenant even though every caller has already resolved one through
     * {@code WorkspaceAccessService}. That second check is redundant today and is the kind of
     * redundancy this codebase keeps: the top bug class here is a lookup that was scoped by its
     * caller until the caller changed.
     */
    Optional<ProjectHeader> findHeaderByIdAndWorkspaceId(UUID id, UUID workspaceId);

    /** Just enough of a project to name it in an exported file. */
    interface ProjectHeader {
        String getKey();

        String getName();
    }

    /**
     * The workspace-scoped batch form of {@link #findByIdAndWorkspace} — used by
     * {@code ProjectAdminGuard} to name the projects a workspace member removal would
     * strand. Scoped rather than {@code findAllById} even though the ids come from a query
     * that was itself workspace-scoped: a finder that carries its own scope cannot be
     * misused later by a caller who assumes someone else already checked.
     *
     * <p><strong>Live projects only.</strong> An archived project is frozen — there is
     * nothing left to administer in it — so it must never be the reason an offboarding is
     * refused. The filter is repeated here rather than left to the locking read alone
     * because this load is what the 409 body is built from, and a list that disagreed with
     * the decision would name projects the caller cannot act on. (Consequence, recorded
     * rather than hidden: an archived project whose last administrator leaves cannot be
     * unarchived through the API, because {@code project.archive} is project-scoped and
     * outside the curator bypass. That is the same gap HD-136's audit reports for the other
     * uncovered project permissions, and it is a seed decision, not this query's to make.)
     */
    List<Project> findAllByWorkspaceAndIdInAndArchivedAtIsNull(Workspace workspace, Collection<UUID> ids);

    boolean existsByWorkspaceAndKey(Workspace workspace, String key);

    // Admin matrix
    List<Project> findAllByOrderByCreatedAtAsc();

    // ---- "used by N projects" counts + "where is this used?" listings ----
    // Every query is scope-filtered: pass wsId/projectId = null for the global
    // (system-admin / DC) console — no predicate, whole-install counts; pass a
    // workspace or project for a delegated console so a tenant never sees usage
    // spanning other tenants. includeNull adds implicitly-bound projects (NULL
    // binding = the system-default set) but only within the same scope filter.

    @Query("select count(p) from Project p where "
            + "(p.workflow.id = :id or (:includeNull = true and p.workflow is null)) "
            + "and (:wsId is null or p.workspace.id = :wsId) "
            + "and (:projectId is null or p.id = :projectId)")
    long countProjectsUsingWorkflow(@Param("id") UUID id, @Param("includeNull") boolean includeNull,
                                    @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

    @Query("select count(p) from Project p where "
            + "(p.prioritySet.id = :id or (:includeNull = true and p.prioritySet is null)) "
            + "and (:wsId is null or p.workspace.id = :wsId) "
            + "and (:projectId is null or p.id = :projectId)")
    long countProjectsUsingPrioritySet(@Param("id") UUID id, @Param("includeNull") boolean includeNull,
                                       @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

    @Query("select count(p) from Project p where "
            + "(p.fieldSet.id = :id or (:includeNull = true and p.fieldSet is null)) "
            + "and (:wsId is null or p.workspace.id = :wsId) "
            + "and (:projectId is null or p.id = :projectId)")
    long countProjectsUsingFieldSet(@Param("id") UUID id, @Param("includeNull") boolean includeNull,
                                    @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

    @Query("select count(p) from Project p where "
            + "(p.issueTypeSet.id = :id or (:includeNull = true and p.issueTypeSet is null)) "
            + "and (:wsId is null or p.workspace.id = :wsId) "
            + "and (:projectId is null or p.id = :projectId)")
    long countProjectsUsingIssueTypeSet(@Param("id") UUID id, @Param("includeNull") boolean includeNull,
                                        @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

    @Query("select p from Project p where "
            + "(p.workflow.id = :id or (:includeNull = true and p.workflow is null)) "
            + "and (:wsId is null or p.workspace.id = :wsId) "
            + "and (:projectId is null or p.id = :projectId)")
    List<Project> findProjectsUsingWorkflow(@Param("id") UUID id, @Param("includeNull") boolean includeNull,
                                            @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

    @Query("select p from Project p where "
            + "(p.prioritySet.id = :id or (:includeNull = true and p.prioritySet is null)) "
            + "and (:wsId is null or p.workspace.id = :wsId) "
            + "and (:projectId is null or p.id = :projectId)")
    List<Project> findProjectsUsingPrioritySet(@Param("id") UUID id, @Param("includeNull") boolean includeNull,
                                               @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

    @Query("select p from Project p where "
            + "(p.fieldSet.id = :id or (:includeNull = true and p.fieldSet is null)) "
            + "and (:wsId is null or p.workspace.id = :wsId) "
            + "and (:projectId is null or p.id = :projectId)")
    List<Project> findProjectsUsingFieldSet(@Param("id") UUID id, @Param("includeNull") boolean includeNull,
                                            @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

    @Query("select p from Project p where "
            + "(p.issueTypeSet.id = :id or (:includeNull = true and p.issueTypeSet is null)) "
            + "and (:wsId is null or p.workspace.id = :wsId) "
            + "and (:projectId is null or p.id = :projectId)")
    List<Project> findProjectsUsingIssueTypeSet(@Param("id") UUID id, @Param("includeNull") boolean includeNull,
                                                @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

    // UPDATE ... RETURNING gives each concurrent transaction its own value — an
    // increment followed by a separate read lets two creates observe the same seq
    @Query(value = "UPDATE projects SET issue_seq = issue_seq + 1 WHERE id = :id RETURNING issue_seq",
           nativeQuery = true)
    long incrementAndGetIssueSeq(@Param("id") UUID id);

    // ---------------------------------------------------- S4: role usage & reassign
    //
    // `projects.default_project_role_id` has NO write path until S7, but the FK exists
    // today and `ON DELETE` is NO ACTION, so a role delete that ignored it would be a 500
    // the day the picker ships. Two statements now, instead of a production incident later
    // (§11.5).

    /** Projects of THIS workspace that name this role as their default. */
    long countByWorkspaceIdAndDefaultProjectRoleId(UUID workspaceId, UUID defaultProjectRoleId);

    /** {@code roleId -> projects defaulting to it} for this workspace, in one statement. */
    @Query("""
            SELECT p.defaultProjectRoleId, COUNT(p) FROM Project p
             WHERE p.workspace.id = :workspaceId AND p.defaultProjectRoleId IS NOT NULL
             GROUP BY p.defaultProjectRoleId
            """)
    List<Object[]> countDefaultRoleUse(@Param("workspaceId") UUID workspaceId);

    /**
     * Repoint this workspace's project defaults before the old role row is deleted.
     *
     * <p>A bulk UPDATE on a column that is also mapped on the {@code Project} entity, so
     * the standing warning applies: it is correct <em>because</em> the deleting transaction
     * does not materialize these projects. If a future caller loads them first, mutate them
     * in place and {@code saveAll} instead — a flush of stale managed copies would write
     * the deleted role id straight back.
     */
    @Modifying
    @Query("""
            UPDATE Project p SET p.defaultProjectRoleId = :toRoleId
             WHERE p.workspace.id = :workspaceId AND p.defaultProjectRoleId = :fromRoleId
            """)
    int reassignDefaultProjectRole(@Param("workspaceId") UUID workspaceId,
                                   @Param("fromRoleId") UUID fromRoleId,
                                   @Param("toRoleId") UUID toRoleId);
}
