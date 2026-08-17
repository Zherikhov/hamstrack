package com.hamstrack.workspace.repository;

import com.hamstrack.auth.entity.User;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    /**
     * The single hottest query in the product — every authenticated request that names a
     * workspace runs it (HD-123 §9.2).
     *
     * <p>{@code JOIN FETCH m.role} is load-bearing, not tidiness: {@code role} is a lazy
     * {@code @ManyToOne} and the resolver reads its id immediately, so without the fetch
     * every request would pay an extra SELECT to initialise the proxy. Written as an
     * explicit {@code @Query} rather than a derived method for exactly that reason —
     * a derived {@code findByWorkspaceAndUser} cannot express the fetch.
     */
    @Query("SELECT m FROM WorkspaceMember m JOIN FETCH m.role WHERE m.workspace = :workspace AND m.user = :user")
    Optional<WorkspaceMember> findByWorkspaceAndUser(Workspace workspace, User user);

    List<WorkspaceMember> findAllByWorkspace(Workspace workspace);

    boolean existsByWorkspaceAndUser(Workspace workspace, User user);

    // Member listing + @mention parsing both read m.user for every row — fetch it
    // in one query rather than a lazy load per member. m.role likewise: the listing
    // renders the role name on every row.
    @Query("SELECT m FROM WorkspaceMember m JOIN FETCH m.user JOIN FETCH m.role WHERE m.workspace = :workspace")
    List<WorkspaceMember> findAllByWorkspaceWithUser(Workspace workspace);

    @Query("SELECT m FROM WorkspaceMember m JOIN FETCH m.workspace JOIN FETCH m.role WHERE m.user.id = :userId")
    List<WorkspaceMember> findAllByUserIdWithWorkspace(UUID userId);
}
