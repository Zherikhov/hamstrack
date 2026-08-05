package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    List<Workflow> findAllByScopeWorkspaceIdIsNullOrderByName();

    Optional<Workflow> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<Workflow> findBySystemDefaultTrue();

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);

    /**
     * Workflows a project may bind: global (both scopes null) ∪ its workspace's
     * ∪ its own project-private. Pass {@code proj = null} to get just global ∪
     * workspace (the workspace-level menu) — a null project id matches no rows.
     */
    @Query("select w from Workflow w where (w.scopeWorkspaceId is null and w.scopeProjectId is null) "
            + "or w.scopeWorkspaceId = :ws or w.scopeProjectId = :proj order by w.name")
    List<Workflow> findAllBindableForProject(@Param("ws") UUID ws, @Param("proj") UUID proj);

    // ---- exact-scope queries for the delegated set console ----

    @Query("select w from Workflow w where ((:ws is null and w.scopeWorkspaceId is null) or w.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and w.scopeProjectId is null) or w.scopeProjectId = :proj) order by w.name")
    List<Workflow> findAllAtScope(@Param("ws") UUID ws, @Param("proj") UUID proj);

    @Query("select case when count(w) > 0 then true else false end from Workflow w "
            + "where ((:ws is null and w.scopeWorkspaceId is null) or w.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and w.scopeProjectId is null) or w.scopeProjectId = :proj) and w.name = :name")
    boolean existsAtScopeAndName(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("name") String name);

    @Query("select w from Workflow w where w.id = :id "
            + "and ((:ws is null and w.scopeWorkspaceId is null) or w.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and w.scopeProjectId is null) or w.scopeProjectId = :proj)")
    Optional<Workflow> findByIdAtScope(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);
}
