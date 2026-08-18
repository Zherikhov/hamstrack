package com.hamstrack.project.repository;

import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
