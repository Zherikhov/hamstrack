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

    Optional<ProjectMember> findByProjectAndUser(Project project, User user);

    List<ProjectMember> findAllByProject(Project project);

    // Listing renders m.user for every row — fetch it in one query, not one per member
    @Query("SELECT m FROM ProjectMember m JOIN FETCH m.user WHERE m.project = :project")
    List<ProjectMember> findAllByProjectWithUser(Project project);

    boolean existsByProjectAndUser(Project project, User user);

    List<ProjectMember> findAllByUserAndProjectIn(User user, List<Project> projects);
}
