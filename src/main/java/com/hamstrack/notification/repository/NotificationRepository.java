package com.hamstrack.notification.repository;

import com.hamstrack.auth.entity.User;
import com.hamstrack.notification.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every finder here answers one question — <em>which of this user's notifications may
 * this user still read?</em> — and the answer is the same clause in all of them
 * (HD-135 §4.4):
 *
 * <pre>{@code AND n.workspace.id IN (SELECT m.workspace.id FROM WorkspaceMember m WHERE m.user = :user)}</pre>
 *
 * <p><strong>Why the predicate is in the query and not in the service.</strong> A
 * notification's {@code title} and {@code body} are denormalised copies of workspace
 * content, written into the row at delivery time, so there is nothing left to redact at
 * render time — a row that reaches a caller has already disclosed. {@code list} also caps
 * at 30 rows, so a Java-side filter over the fetched page would show a reader with 30
 * hidden rows an empty bell while visible rows sat unread on page 2. The filter has to be
 * inside the statement that applies the limit.
 *
 * <p><strong>This deliberately does NOT extend {@code JpaRepository}</strong>, for the
 * reason {@code RoleRepository} does not: inheriting {@code findById}/{@code findAll}
 * would put an unfiltered finder on the one interface whose whole contract is that no
 * unfiltered finder exists, and {@code notificationRepository.findById(id)} would then
 * compile and read past the membership rule. Only {@link #save} is re-declared, and it
 * cannot leak — a producer holds the row it is writing. Anything added below joins
 * membership or it is a hole; {@code NotificationFinderSealTest} fails on the ones that
 * do not, with the argument in its message.
 *
 * <p>Uncorrelated {@code IN} rather than a correlated {@code EXISTS}, in all four places:
 * {@link #markAllReadForUser} is a bulk {@code @Modifying} {@code UPDATE}, where an
 * uncorrelated subquery is unambiguously supported and a correlated one referencing the
 * update alias is the shape that works until a Hibernate minor. PostgreSQL plans both as
 * the same semi-join, so nothing is paid for using one shape everywhere.
 */
public interface NotificationRepository extends Repository<Notification, UUID> {

    /** The membership predicate, quoted once so the four queries below cannot drift apart. */
    String VISIBLE = """
             AND n.workspace.id IN (
                     SELECT m.workspace.id FROM WorkspaceMember m WHERE m.user = :user)
            """;

    /**
     * The bell's feed — the caller's own rows, in the workspaces they are a member of
     * <em>now</em>. Driven by {@code idx_notifications_user (user_id, created_at DESC)};
     * the semi-join rides on {@code workspace_members}' {@code UNIQUE(workspace_id, user_id)}.
     */
    @Query("SELECT n FROM Notification n WHERE n.user = :user" + VISIBLE
           + " ORDER BY n.createdAt DESC")
    List<Notification> findVisibleForUser(@Param("user") User user, Pageable pageable);

    /** The unread badge. Same feed, same filter, counted. */
    @Query("SELECT count(n) FROM Notification n WHERE n.user = :user AND n.readAt IS NULL" + VISIBLE)
    long countVisibleUnread(@Param("user") User user);

    /**
     * The single-row read behind {@code POST /{id}/read} — which returns the full DTO,
     * {@code title} and {@code body} included, and is therefore a <em>content read</em>
     * wearing a write's clothes. An empty result is answered 404, indistinguishable from
     * an unknown id and from another user's id.
     */
    @Query("SELECT n FROM Notification n WHERE n.id = :id AND n.user = :user" + VISIBLE)
    Optional<Notification> findVisibleById(@Param("id") UUID id, @Param("user") User user);

    /**
     * "Mark all read" means everything the caller can see, so a row they cannot see stays
     * unread — hiding a row must not quietly mutate it, and a member who is re-added gets
     * their old notifications back in the state they left them.
     *
     * <p><strong>Plain {@code @Modifying}, and the missing {@code clearAutomatically} is
     * deliberate</strong> (CLAUDE.md): clearing the persistence context mid-transaction
     * discards pending inserts whose tables the bulk statement does not touch, and it buys
     * something only when the same transaction re-reads the rows the update changed.
     * Nothing re-reads them here — the caller's next read is a new request — so the clear
     * would be pure risk. Re-read that entry before adding it back.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.user = :user AND n.readAt IS NULL"
           + VISIBLE)
    int markAllReadForUser(@Param("user") User user, @Param("now") Instant now);

    Notification save(Notification notification);
}
