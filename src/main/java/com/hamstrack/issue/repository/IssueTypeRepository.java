package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.IssueType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueTypeRepository extends JpaRepository<IssueType, UUID> {

    List<IssueType> findAllByScopeWorkspaceIdIsNullOrderByPosition();

    Optional<IssueType> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<IssueType> findByScopeWorkspaceIdIsNullAndName(String name);

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);

    // ---- exact-scope queries for the delegated catalog console ----

    @Query("select t from IssueType t where ((:ws is null and t.scopeWorkspaceId is null) or t.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and t.scopeProjectId is null) or t.scopeProjectId = :proj) order by t.position")
    List<IssueType> findAllAtScope(@Param("ws") UUID ws, @Param("proj") UUID proj);

    @Query("select case when count(t) > 0 then true else false end from IssueType t "
            + "where ((:ws is null and t.scopeWorkspaceId is null) or t.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and t.scopeProjectId is null) or t.scopeProjectId = :proj) and t.name = :name")
    boolean existsAtScopeAndName(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("name") String name);

    @Query("select t from IssueType t where t.id = :id "
            + "and ((:ws is null and t.scopeWorkspaceId is null) or t.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and t.scopeProjectId is null) or t.scopeProjectId = :proj)")
    Optional<IssueType> findByIdAtScope(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);

    /** By id, visible to a scope: global ∪ that workspace ∪ that project. */
    @Query("select t from IssueType t where t.id = :id and ((t.scopeWorkspaceId is null and t.scopeProjectId is null) "
            + "or t.scopeWorkspaceId = :ws or t.scopeProjectId = :proj)")
    Optional<IssueType> findByIdVisibleTo(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);

    /** All visible to a scope (delegated console list): global ∪ workspace ∪ project. */
    @Query("select t from IssueType t where (t.scopeWorkspaceId is null and t.scopeProjectId is null) "
            + "or t.scopeWorkspaceId = :ws or t.scopeProjectId = :proj order by t.position")
    List<IssueType> findAllVisibleTo(@Param("ws") UUID ws, @Param("proj") UUID proj);
}
