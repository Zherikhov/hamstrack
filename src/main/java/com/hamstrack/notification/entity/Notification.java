package com.hamstrack.notification.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.CreatedOnlyEntity;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The tenant whose content this notification quotes (HD-135, V20). {@code title} and
     * {@code body} are denormalised copies of workspace content — a display name and up to
     * 120 characters of a comment — so a row is readable only while its recipient is a
     * member of <em>this</em> workspace. The predicate that enforces that lives in
     * {@code NotificationRepository}, inside every query, never in a caller.
     *
     * <p><strong>{@code updatable = false}</strong>: a notification's tenant is immutable.
     * No row can legitimately move workspaces, and making that a mapping fact costs
     * nothing. (A different motivation from {@code projects.issue_seq}, which carries the
     * same annotation because native SQL writes that column behind the ORM's back; the two
     * reasons coexist without conflicting.)
     *
     * <p><strong>{@code LAZY}, and nothing here wants a {@code JOIN FETCH}</strong>: the
     * read filter is {@code n.workspace.id IN (…)}, which Hibernate answers from the FK
     * column, and {@code NotificationResponse.of} reads the id off the proxy — neither
     * initialises it, and {@code NotificationProxyQueryCountTest} measures that rather
     * than trusting it. Read any <em>other</em> field of this association on the feed path
     * and the fetch strategy stops being free; that test is what says so.
     * No {@code cascade} attribute may be added either: {@code REMOVE} or
     * {@code ALL} would try to delete the <em>workspace</em> when a notification is deleted.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, updatable = false)
    private Workspace workspace;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(columnDefinition = "TEXT")
    private String link;

    @Column(name = "read_at")
    private Instant readAt;

    public boolean isRead() {
        return readAt != null;
    }
}
