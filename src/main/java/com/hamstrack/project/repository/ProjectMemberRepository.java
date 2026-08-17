package com.hamstrack.project.repository;

import com.hamstrack.auth.entity.User;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    /**
     * The project half of the authorization resolution (HD-123 §9.2) — one indexed
     * lookup on the {@code (project_id, user_id)} unique key. Empty is a normal,
     * expected answer: a member with no explicit row inherits the project's default role
     * in an {@code OPEN} workspace, and today almost nobody has a row at all (§2.3).
     *
     * <p>{@code JOIN FETCH m.role} for the same reason as on the workspace side: the
     * resolver reads the role id immediately, and a lazy proxy would cost an extra SELECT
     * on every project-scoped request.
     */
    @Query("SELECT m FROM ProjectMember m JOIN FETCH m.role WHERE m.project = :project AND m.user = :user")
    Optional<ProjectMember> findByProjectAndUser(Project project, User user);

    List<ProjectMember> findAllByProject(Project project);

    // Listing renders m.user and the role name for every row — fetch both in one query,
    // not one per member
    @Query("SELECT m FROM ProjectMember m JOIN FETCH m.user JOIN FETCH m.role WHERE m.project = :project")
    List<ProjectMember> findAllByProjectWithUser(Project project);

    boolean existsByProjectAndUser(Project project, User user);

    /**
     * One membership query for a whole project list ({@code ProjectService.list}), which
     * is why that endpoint stays at a constant query count however many projects a
     * workspace has. {@code JOIN FETCH m.role} keeps it constant: without it the role of
     * each row would be a separate SELECT — the N+1 the batch exists to avoid.
     */
    @Query("SELECT m FROM ProjectMember m JOIN FETCH m.role WHERE m.user = :user AND m.project IN :projects")
    List<ProjectMember> findAllByUserAndProjectIn(User user, List<Project> projects);

    /**
     * Drop every explicit project membership one user holds inside ONE workspace — the
     * second half of a workspace member removal (HD-132). Removing the
     * {@code workspace_members} row alone would leave {@code project_members} rows behind
     * that grant a project role to somebody who can no longer resolve the workspace at
     * all; they would become live again the moment that person was re-invited, silently
     * restoring an access level nobody re-granted.
     *
     * <p><strong>The workspace scope is the whole point.</strong> A {@code User} is
     * global, so a {@code DELETE … WHERE m.user = :user} would evict them from every
     * tenant they belong to. The workspace is expressed as a subquery over
     * {@code Project} rather than the path {@code m.project.workspace}, because a bulk
     * JPQL DELETE cannot carry an implicit join.
     *
     * <p>Plain {@code @Modifying}: nothing in the removal transaction has materialized a
     * {@code ProjectMember}, and {@code clearAutomatically} would endanger the history
     * rows the same transaction still has to write.
     */
    @Modifying
    @Query("DELETE FROM ProjectMember m WHERE m.user = :user "
            + "AND m.project IN (SELECT p FROM Project p WHERE p.workspace = :workspace)")
    int deleteAllByUserInWorkspace(@Param("user") User user, @Param("workspace") Workspace workspace);
}
