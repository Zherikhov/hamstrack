package com.hamstrack.workspace.entity;

import com.hamstrack.common.entity.BaseEntity;
import com.hamstrack.common.security.RoleScope;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A named bundle of {@link com.hamstrack.common.security.Permission}s
 * (roles-permissions-proposal §8.1). Either a <strong>built-in template</strong> shared
 * by every workspace ({@code workspaceId == null}, {@code builtIn == true} — see
 * {@link BuiltInRoles}) or a <strong>custom role</strong> owned by one workspace. The
 * {@code roles_builtin_ck} CHECK keeps those two facts in sync in the database, so the
 * S4 edit/delete guards can test one column and be right.
 *
 * <p>Lives in the {@code workspace} package because a role is workspace-owned data, even
 * when its {@code scope} is {@code PROJECT}: a project role is defined by the workspace
 * and assigned in a project.
 *
 * <p><strong>FK mapping convention used across this epic:</strong> where an association
 * is only ever needed as an <em>id</em> on a hot path, the raw {@code UUID} column is the
 * writable mapping and the {@code @ManyToOne} is a read-only navigation view
 * ({@code insertable = false, updatable = false}). {@link #workspaceId} /
 * {@link #workspace} here, and {@code defaultProjectRoleId} on {@code Workspace} and
 * {@code Project}, all follow it. The reason is §9.2's constant-query claim: reading
 * {@code getWorkspace().getId()} on a lazy proxy initialises it and costs a SELECT, and
 * on {@code ProjectService.list} that is one SELECT per project. Membership roles do
 * <em>not</em> follow this convention — there the association is writable, because
 * assigning a role is exactly what those tables are for, and the resolution finders
 * {@code JOIN FETCH} it so it is never a proxy on the authorization path.
 *
 * <p><strong>Replacing the permission collection wholesale (S4):</strong>
 * {@code role.getPermissions().clear()} then {@code addAll(...)}, in one transaction. That
 * is all — do <em>not</em> reach for the {@code deleteAllBy…} + {@code flush()} +
 * re-insert dance the four admin set editors need (CLAUDE.md). That gotcha is about a
 * child <strong>@Entity</strong> with its own repository, where one flush orders the
 * INSERTs before the DELETEs and a re-inserted unique key collides. {@link #permissions}
 * is an {@code @ElementCollection}: there is no child entity and no child repository to
 * call {@code flush()} on, and Hibernate's {@code ActionQueue} executes collection
 * removals <em>before</em> collection creations, so the re-insert cannot collide with the
 * old row. Following the entity recipe here would mean first inventing an entity and a
 * repository in order to work around a problem this mapping does not have.
 */
@Entity
// The @UniqueConstraint is DOCUMENTATION ONLY: with ddl-auto=validate Hibernate neither
// creates nor verifies unique constraints, and the annotation cannot express the NULLS NOT
// DISTINCT that V13's real constraint uses to make the seven built-ins (all workspace_id IS
// NULL) collide with each other on (scope, key). Do not rely on it, and do not point a test
// context at create-drop expecting the same shape — that path would build a plain UNIQUE,
// under which two built-ins keyed OWNER could coexist. V13 is the source of truth.
@Table(name = "roles",
        uniqueConstraints = @UniqueConstraint(
                name = "roles_scope_key_uk", columnNames = {"workspace_id", "scope", "key"}))
@Getter
@Setter
public class Role extends BaseEntity {

    /**
     * Owning workspace, or {@code null} for a built-in template. Writable half of the
     * pair (see the class doc); {@link #workspace} is the read-only navigation view.
     */
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", insertable = false, updatable = false)
    private Workspace workspace;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private RoleScope scope;

    /**
     * Stable identifier within {@code (workspace, scope)}. The seven built-ins
     * deliberately use the legacy Java enum names ({@code OWNER}/{@code ADMIN}/
     * {@code MEMBER}, {@code MANAGER}/{@code MEMBER}/{@code COMMENTER}/{@code VIEWER}) so
     * {@code myRole} stays wire-compatible with the SPA and the V14 backfill is a
     * key-for-key translation. Custom roles carry a slug.
     */
    @Column(name = "key", nullable = false, length = 40)
    private String key;

    /** Display name — the new vocabulary ("Project admin", "Contributor"). */
    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /**
     * True exactly when {@link #workspaceId} is null (enforced by {@code roles_builtin_ck}).
     * Built-ins are not editable and not deletable — "Duplicate to customise" is the front
     * door (§11.4).
     */
    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    /** Display order within a scope. */
    @Column(name = "position", nullable = false)
    private short position;

    /**
     * Optimistic locking for the S4 role editor: two admins editing one role concurrently
     * must produce a 409, not a silent last-writer-wins over a permission set.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    private Set<RolePermission> permissions = new LinkedHashSet<>();
}
