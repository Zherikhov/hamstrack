package com.hamstrack.workspace.repository;

import com.hamstrack.auth.entity.User;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {
    Optional<WorkspaceMember> findByWorkspaceAndUser(Workspace workspace, User user);
    List<WorkspaceMember> findAllByWorkspace(Workspace workspace);
    boolean existsByWorkspaceAndUser(Workspace workspace, User user);
}
