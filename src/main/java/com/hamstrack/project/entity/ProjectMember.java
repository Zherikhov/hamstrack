package com.hamstrack.project.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.workspace.entity.Role;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "user_id"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class ProjectMember {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The <em>explicit</em> project role (HD-123 §8.2). A member with no row here is not
     * role-less: in an {@code OPEN} workspace they inherit the project's default role
     * (§5.2), which is what makes the upgrade a no-op. Explicit membership always wins,
     * even when it grants <em>less</em> than the default (§11.3) — {@code max(explicit,
     * default)} would make Viewer meaningless.
     *
     * <p>{@code JOIN FETCH}ed by {@code ProjectMemberRepository.findByProjectAndUser} and
     * the batch finders, so it is never a proxy on the authorization path.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @CreatedDate
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;
}
