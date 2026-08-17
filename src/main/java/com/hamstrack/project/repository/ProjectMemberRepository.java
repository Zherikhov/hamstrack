package com.hamstrack.project.repository;

import com.hamstrack.auth.entity.User;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
