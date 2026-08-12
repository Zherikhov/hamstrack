package com.hamstrack.search.filter.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.BaseEntity;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A named, reusable HQL query saved by a workspace member (HD-26, Advanced Search
 * proposal §8.3/§10.1). Scoped to one workspace (the tenant boundary); the
 * {@link #owner} may edit/delete it, and when {@link #shared} is {@code true} it is
 * visible read-only to every other member of the same workspace.
 *
 * <p>The {@code hql} text is validated (parse + structural registry validation) at
 * save time, but is only ever <em>executed</em> later through the HD-21 search engine
 * — which binds every literal as a parameter, so a stored filter is injection-safe by
 * construction. {@code currentUser()} and project-visibility resolve to whoever
 * <em>runs</em> the filter, never the owner.
 */
@Entity
@Table(name = "saved_filters",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_saved_filter_owner_name",
                columnNames = {"workspace_id", "owner_id", "name"}))
@Getter
@Setter
public class SavedFilter extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 2000)
    private String hql;

    @Column(nullable = false)
    private boolean shared = false;
}
