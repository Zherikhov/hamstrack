package com.hamstrack.workspace.entity;

import com.hamstrack.auth.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "user_id"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class WorkspaceMember {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The caller's workspace role (HD-123 §8.2). A {@code roles} row, not an ordinal
     * enum: custom roles have no total order, so the {@code isAtLeast} ladder this
     * replaced could not express them.
     *
     * <p><strong>Always {@code JOIN FETCH}ed on the authorization path</strong>
     * ({@code WorkspaceMemberRepository.findByWorkspaceAndUser}) so it is never a lazy
     * proxy when {@code WorkspaceAccessService} reads its id — a proxy initialisation
     * there would add a SELECT to every single request.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @CreatedDate
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;
}
