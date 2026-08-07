package com.hamstrack.project.repository;

import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByWorkspace(Workspace workspace);

    List<Project> findAllByWorkspaceAndArchivedAtIsNull(Workspace workspace);

    Optional<Project> findByIdAndWorkspace(UUID id, Workspace workspace);

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
