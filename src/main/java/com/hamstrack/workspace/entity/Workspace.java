package com.hamstrack.workspace.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "workspaces")
@Getter
@Setter
public class Workspace extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    @Column(nullable = false, length = 255)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /**
     * Whether members who were never added to a project inherit its default role
     * (HD-123 §5.2). V14 sets every existing workspace to {@link ProjectAccessMode#OPEN},
     * which is exactly today's behaviour. VARCHAR(20) + Java enum, never a PG ENUM.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "project_access_mode", nullable = false, length = 20)
    private ProjectAccessMode projectAccessMode = ProjectAccessMode.OPEN;

    /**
     * The project role members inherit in projects that do not name their own default.
     * {@code null} → the built-in <strong>Contributor</strong>.
     *
     * <p>Stored as a raw id because that is all the resolver ever needs, and reading it
     * off a lazy {@code @ManyToOne} would cost one SELECT per project on
     * {@code ProjectService.list} (§9.2). {@link #defaultProjectRole} is the read-only
     * navigation view for the settings screens; <strong>write through
     * {@link #setDefaultProjectRoleId} only</strong>.
     */
    @Column(name = "default_project_role_id")
    private UUID defaultProjectRoleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_project_role_id", insertable = false, updatable = false)
    private Role defaultProjectRole;
}
