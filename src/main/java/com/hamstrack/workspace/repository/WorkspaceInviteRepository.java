package com.hamstrack.workspace.repository;

import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceInvite;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <strong>Every insert into {@code workspace_invites} must run the duplicate pre-check FIRST and
 * the mail throttle SECOND</strong> (HD-133 §4.1, V22 header). This is a requirement on new code
 * rather than a description of today's callers, because it is precisely the thing a second writer
 * of this table inherits none of.
 *
 * <p>The reason is an ordering, not a query. {@code workspace_invites_pending_email_uk} makes a
 * duplicate insert fail, and {@code RecipientMailThrottle} <em>records</em> its
 * {@code mail_send_events} row inside the same transaction, before the insert. So a constraint
 * violation that surfaces after the throttle has run rolls that row back while the caller has
 * already observed the refusal — a free way to probe a stranger's mail ceilings without ever
 * spending them. Checking {@link #findPendingByWorkspaceAndFoldedEmail} above
 * {@code inviteThrottle.requireRecipientCeilings} turns the common case into a 409 that leaves the
 * recorded event alone. What makes that 409 cost the caller anything is a separate line —
 * {@code inviteThrottle.requireSenderVolume}, spent ABOVE this check — because the two halves of
 * the throttle obey opposite placement rules: the in-memory sender counter no rollback returns
 * goes above every refusal, the recorded recipient event goes below them all.
 *
 * <p>A path that skips the order fails <em>closed</em> — it cannot create the duplicate, it can
 * only report it as a 500 and leak the probe. That is the whole of what is at stake, and it is why
 * this note is on the interface rather than on one method.
 */
public interface WorkspaceInviteRepository extends JpaRepository<WorkspaceInvite, UUID> {

    /**
     * The emailed join link, <strong>locked</strong> (HD-158 §6.3).
     *
     * <p><strong>Why every accept path locks now, when none of them needed to before.</strong>
     * Until the withdrawal endpoint existed, a {@code workspace_invites} row had one writer at a
     * time in practice: the invitee, accepting or declining their own row. Withdrawal is the first
     * write by <em>another</em> actor, so accept and withdraw can now interleave — and
     * {@link WorkspaceInvite} extends {@code CreatedOnlyEntity} and carries no {@code @Version},
     * so the loser does not get an optimistic-locking 409. It gets Hibernate's unexpected
     * row-count {@code StaleStateException}: a <strong>500 for the invitee, after the transaction
     * has already decided to make them a member</strong>. Under the lock each side reads a
     * definite state and answers definitely — the accept finds no row (404), or the withdrawal
     * finds {@code accepted_at} set (409).
     *
     * <p><strong>{@code PESSIMISTIC_WRITE}, which Hibernate 7 emits against PostgreSQL as
     * {@code FOR NO KEY UPDATE} — observed in the log, not assumed from the annotation.</strong>
     * The spec asked for plain {@code FOR UPDATE}, reasoning that the <em>"{@code FOR NO KEY
     * UPDATE}, never {@code FOR UPDATE}"</em> rule is about {@code workspaces}, whose row every FK
     * child insert in the tenant would otherwise queue behind, and that <strong>no table has a
     * foreign key to {@code workspace_invites}</strong>. Both halves are true and the conclusion is
     * simply moot: the dialect does not emit {@code FOR UPDATE} for this lock mode, so the choice
     * is not one this call site gets to make. It costs nothing, and the reason is the property
     * rather than the mode's name — {@code FOR NO KEY UPDATE} conflicts with <em>itself</em>, so
     * two transactions that both take it on one row still serialise, which is the entire job of
     * this lock. The one thing it additionally permits is {@code FOR KEY SHARE}, which PostgreSQL
     * takes on a parent row for an FK child insert, and this table has no children. So do not
     * "restore" the spec's wording by dropping to a native {@code FOR UPDATE}: it would strengthen
     * nothing, and it would cost the {@code @Lock} annotation. (The same annotation on
     * {@code WorkspaceMemberRepository.lockAllByWorkspaceAndRoleId} emits the same mode, which is
     * how this was checked.)
     *
     * <p><strong>No {@code JOIN FETCH} on any locking finder in this file, and that is not an
     * oversight.</strong> PostgreSQL applies {@code FOR UPDATE} to every table the statement
     * selects from, so a fetch join here would lock the {@code workspaces}, {@code roles} and
     * {@code users} rows too — reintroducing exactly the workspace-row lock the rule above forbids,
     * by the back door. The associations are lazy and are loaded after the lock, which is free on
     * these single-row paths. The <em>list</em> is the opposite case and does fetch (see
     * {@link #findUnacceptedForWorkspace}) because it takes no lock at all.
     *
     * <p>Every caller of a locking finder in this file calls
     * {@code LockTimeout.applyToCurrentTransaction()} first — bound, then lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM WorkspaceInvite i WHERE i.tokenHash = :tokenHash")
    Optional<WorkspaceInvite> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * The invitee acting on their own row by id — <strong>accept and decline</strong> from the
     * onboarding screen, <strong>locked</strong>, same race and same reason as
     * {@link #findByTokenHashForUpdate}. Not workspace-scoped, deliberately: the invitee reaches
     * this before being a member of anything, and the row is bound to their address by the exact
     * match in {@code acceptInvite}/{@code declineInvite}, which is what actually authorizes them.
     *
     * <p><strong>Decline locks too, since HD-158 round 1.</strong> The spec waived it on the
     * ground that the invitee cannot race their own accept; withdrawal made another actor a writer
     * of this table, and unlocked the loser of that race got a 409 telling them to retry something
     * that could never succeed again. See {@code WorkspaceService.declineInvite}.
     *
     * <p><strong>Both callers take this lock BEFORE the check that authorizes them, and that is a
     * decision rather than the oversight it looks like</strong> (HD-158 round 1, item 5).
     * {@code revokeInvite} does the opposite — membership and {@code workspace.member.manage}
     * first, lock second, with a comment saying an unauthorized caller never takes one — and the
     * two orderings are the same rule, not a contradiction: <em>authorize as early as the
     * authorizing fact can be read.</em> A revoker's authority lives in {@code workspace_members},
     * a different table, so it is knowable before this row is touched. The invitee's authority is
     * the address <em>on this row</em>: it cannot be read earlier, and reading it unlocked would
     * authorize against a copy the lock is there to invalidate.
     *
     * <p>What that concedes, knowingly: somebody holding an invite id can make a transaction hold
     * a row lock on it for as long as the immediate 404 takes to roll back. Bounded by
     * {@code lock_timeout} (applied by every caller before the lock), on a UUID v7 id that is not
     * guessable and is disclosed only to the invitee and the workspace's own administrators, and
     * contending with nothing but other writers of that one invitation. Do not "fix" it by moving
     * the address check above the read — there is nothing above the read to check it against.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM WorkspaceInvite i WHERE i.id = :id")
    Optional<WorkspaceInvite> findByIdForUpdate(@Param("id") UUID id);

    /**
     * One invitation of ONE workspace, <strong>locked</strong> — the withdrawal (HD-158 §4.3).
     *
     * <p><strong>The workspace is part of the question, never of a follow-up {@code if}.</strong>
     * This repository extends {@code JpaRepository}, so a bare {@code findById} compiles and is
     * precisely the shape this project's top bug class takes: an id from another tenant would
     * resolve, and the comparison that caught it would be one refactor away from being dropped. A
     * miss is a 404 whether the id is fabricated, belongs to another tenant, or was withdrawn a
     * second ago — the id is not an existence oracle.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM WorkspaceInvite i WHERE i.id = :id AND i.workspace.id = :workspaceId")
    Optional<WorkspaceInvite> findByIdAndWorkspaceIdForUpdate(
            @Param("id") UUID id, @Param("workspaceId") UUID workspaceId);

    /**
     * <strong>Every unaccepted invitation this workspace has issued</strong> — the administrator's
     * list (HD-158 §4.1), newest first.
     *
     * <p><strong>Expired rows are included, and the predicate is deliberately no narrower than
     * {@code acceptedAt IS NULL}.</strong> Nothing in this product sweeps an expired invitation, a
     * member removal deletes one, and HD-133's uniqueness will refuse a re-invite over one — so a
     * row hidden from this list is a row no admin can clear and no future refusal can point at.
     * The general form, which survives the next predicate somebody is tempted to add: <em>the list
     * must be the complete set of rows that can still block, grant, or be cleaned up.</em> Whether
     * a row is still live is reported as a field, never as a filter.
     *
     * <p>Accepted rows are excluded: they are history, the {@code workspace_members} row is the
     * live fact, and offering a "withdraw" control beside something withdrawal cannot affect is a
     * lie the screen would tell once per accepted invitation, for ever.
     *
     * <p>{@code JOIN FETCH} on <em>both</em> associations, so that the bound on this list is
     * <em>this query's own</em> rather than an instance-wide setting it neither owns nor names.
     * Dropping them does not produce N+1 in this build, and an earlier revision of this paragraph
     * said it did: {@code hibernate.default_batch_fetch_size=100} (application.properties) collapses
     * the per-row loads into one batched select per association. Measured over ten rows with ten
     * distinct inviters — four statements with both fetches, five without, and five for a single row
     * too. So the fetches buy exactly one statement today, and the reason to keep them is that the
     * batch size degrades to one select per 100 rows on a long history, which is precisely the
     * history this list is required to return in full.
     *
     * <p>(The locking finders above must NOT fetch — see {@link #findByTokenHashForUpdate}; this one
     * takes no lock, which is what makes the fetch safe.)
     */
    @Query("""
            SELECT i FROM WorkspaceInvite i JOIN FETCH i.role JOIN FETCH i.invitedBy
             WHERE i.workspace.id = :workspaceId AND i.acceptedAt IS NULL
             ORDER BY i.createdAt DESC
            """)
    List<WorkspaceInvite> findUnacceptedForWorkspace(@Param("workspaceId") UUID workspaceId);

    /**
     * <strong>The one unaccepted invitation this workspace may hold for this address</strong> —
     * the sentence behind HD-133's {@code 409 DUPLICATE_INVITE}.
     *
     * <p><strong>The index is the invariant; this query is only the message.</strong> Two
     * concurrent requests both find nothing here and both insert;
     * {@code workspace_invites_pending_email_uk} (V22) is what makes one of them fail. So this
     * decides whether the common case gets a sentence or a stack trace, and nothing else — do not
     * read it as the enforcement, and do not delete the constraint translation in
     * {@code WorkspaceService.inviteMember} on the strength of it.
     *
     * <p><strong>{@code lower()} in JPQL, so PostgreSQL answers with the same function the index
     * was built from — and NOT {@code equals} on the already-folded Java string.</strong> That
     * shortcut is the trap this method exists to foreclose: {@code inviteMember} folds with
     * {@code toLowerCase(Locale.ROOT)}, which is a <em>different function</em> from PostgreSQL's
     * {@code lower()}, and {@code InviteMemberRequest} constrains only the <em>local part</em> to
     * ASCII — the domain may be internationalised. Where the two folds disagree on one character,
     * a Java-side check says "free" while the index says "taken": a constraint violation at flush,
     * a rollback, and precisely the free mail-ceiling probe the check order exists to prevent.
     * Ask the database the question the index answers. It is index-backed for free, because the
     * partial unique index <em>is</em> the access path for this predicate.
     *
     * <p><strong>Nor {@code MailAddresses.throttleKey}</strong>, which folds {@code +tag}, Gmail
     * dots and punycode as well. That key identifies an <em>inbox</em>, and this constraint is
     * about an <em>offer</em>: an offer is redeemed by exact match against {@code users.email}
     * (HD-120), so folding {@code bob+2@} onto {@code bob@} here would refuse an invitation to a
     * genuinely different account and make the constraint a claim the accept path does not honour.
     * Fold as far as the harm points — a ceiling folds onto the inbox, an offer onto the address.
     * The gap that leaves (one workspace, two spellings, two live offers) is covered from the
     * other side by the mail cooldown, which does fold them together.
     *
     * <p><strong>{@code Optional} rather than {@code List}, and that is safe only BECAUSE the
     * index exists</strong> — before V22 this predicate could match several rows. Flyway runs to
     * completion before the application serves traffic, so by the time this can be called the
     * invariant already holds.
     *
     * <p>Expiry is deliberately absent from the predicate: it cannot be in the index (see V22),
     * so a pre-check that ignored expired rows would hand a would-be inviter a 201 the database
     * then refuses. The caller reads {@code expiresAt} off the returned row to pick which of the
     * two wordings to use — reported as a field, never applied as a filter, which is the same rule
     * {@link #findUnacceptedForWorkspace} follows for the list this refusal points at.
     */
    @Query("""
            SELECT i FROM WorkspaceInvite i
             WHERE i.workspace.id = :workspaceId AND lower(i.email) = lower(:email)
               AND i.acceptedAt IS NULL
            """)
    Optional<WorkspaceInvite> findPendingByWorkspaceAndFoldedEmail(
            @Param("workspaceId") UUID workspaceId, @Param("email") String email);

    // Pending invites addressed to a user's email (still filtered for expiry in
    // the service). Newest first so the invites screen shows recent ones on top.
    // JOIN FETCH the role (HD-123): the response renders its key on every row, and a
    // lazy @ManyToOne would make this list N+1.
    @Query("""
            SELECT i FROM WorkspaceInvite i JOIN FETCH i.role
             WHERE lower(i.email) = lower(:email) AND i.acceptedAt IS NULL
             ORDER BY i.createdAt DESC
            """)
    List<WorkspaceInvite> findByEmailIgnoreCaseAndAcceptedAtIsNullOrderByCreatedAtDesc(String email);

    /**
     * Revoke every UNACCEPTED invite for one address in ONE workspace — part of removing a
     * member (HD-132).
     *
     * <p><strong>Without this, removal does not remove.</strong> {@code inviteMember} refuses
     * someone who is <em>already</em> a member, and since V22 it also refuses a second
     * invitation to a pending address — but neither of those helps here, because the row this
     * deletes is one that was written while the person was <em>not yet</em> a member and is
     * still perfectly valid. (Before V22 there could be several of them; the plural in this
     * query is now historical for one workspace, and is still correct because the delete is
     * not the thing that establishes it.) They are invisible while the person is a member
     * because {@code listPendingInvites} filters on {@code !existsByWorkspaceAndUser} — which
     * means the removal is exactly what makes them <em>re</em>appear, as a live join button on
     * the invitee's onboarding screen. {@code acceptInvite} likewise only blocks current
     * members, so one click puts them back in the workspace at the invite's role, with no
     * admin action and no notification. Offboarding has to close that door.
     *
     * <p>Deletes rather than expires: an invite is single-use and email-bound, an admin can
     * always re-send one, and {@code declineInvite} already establishes deletion as the way an
     * invite goes away. Accepted rows are left alone — they are the historical record of how
     * that person joined.
     *
     * <p>Matched case-insensitively because {@code workspace_invites.email} is stored
     * lower-cased by {@code inviteMember} but {@code users.email} is the authority here, and
     * {@code lower()} on both sides is the same comparison
     * {@code findByEmailIgnoreCaseAndAcceptedAtIsNullOrderByCreatedAtDesc} makes. Scoped by
     * workspace, so a removal in one tenant cannot cancel an invitation issued by another.
     *
     * <p>Plain {@code @Modifying}: the removal transaction holds no managed
     * {@code WorkspaceInvite}, and {@code clearAutomatically} would endanger the history rows
     * it still has to write.
     */
    @Modifying
    @Query("DELETE FROM WorkspaceInvite i WHERE i.workspace = :workspace "
            + "AND lower(i.email) = lower(:email) AND i.acceptedAt IS NULL")
    int deleteUnacceptedByWorkspaceAndEmail(@Param("workspace") Workspace workspace,
                                            @Param("email") String email);

    // ---------------------------------------------------- S4: role usage & reassign

    /**
     * Unaccepted invites of THIS workspace that would land on this role. Workspace-scoped
     * for the reason {@code WorkspaceMemberRepository.countByWorkspaceIdAndRoleId} gives:
     * built-in roles are shared rows.
     *
     * <p>Accepted invites are excluded — they are history, the membership row is the live
     * fact, and counting them would make a long-lived workspace's Member role permanently
     * "in use" no matter who is actually in it.
     */
    long countByWorkspaceIdAndRoleIdAndAcceptedAtIsNull(UUID workspaceId, UUID roleId);

    /** {@code roleId -> pending invites} for this workspace, in one statement. */
    @Query("""
            SELECT i.role.id, COUNT(i) FROM WorkspaceInvite i
             WHERE i.workspace.id = :workspaceId AND i.acceptedAt IS NULL
             GROUP BY i.role.id
            """)
    List<Object[]> countPendingByRole(@Param("workspaceId") UUID workspaceId);

    /**
     * Point <strong>every</strong> invite of this workspace at {@code toRole} before the old
     * role row is deleted — accepted ones included, and deliberately so.
     *
     * <p><strong>This disagrees with its sibling count on purpose, and the earlier javadoc
     * had the reason backwards</strong> (round-2 review). It used to claim accepted invites
     * were left alone because "rewriting history to name a role the invitee never got would
     * be a lie". That is a fair sentiment and an impossible one:
     * {@code workspace_invites.role_id} is a plain FK with {@code ON DELETE NO ACTION}
     * (V14), so an accepted row still pointing at the deleted role turns
     * {@code DELETE /roles/{id}?reassignToRoleId=} into a 500 the operator cannot escape
     * without SQL. The predicate was never in the query; only the claim was, and the claim
     * is what was wrong.
     *
     * <p>So the two methods answer two different questions, and both answers are right:
     * {@code countByWorkspaceIdAndRoleIdAndAcceptedAtIsNull} asks <em>what is live</em> (an
     * accepted invite is superseded by the membership row, and counting it would make a
     * long-lived workspace's Member role permanently "in use"), while this asks <em>what
     * still references the row</em>, which is every one of them. The consequence to accept
     * knowingly: an accepted invite's {@code role_id} is not a reliable record of what its
     * invitee was offered once the role it named is deleted. If that history ever needs to
     * be trustworthy it wants a denormalised role <em>key</em> column, not a live FK.
     *
     * <p>Plain {@code @Modifying}, and the rows are not materialized anywhere in this
     * transaction — see {@code WorkspaceMemberRepository.reassignRole}.
     */
    @Modifying
    @Query("""
            UPDATE WorkspaceInvite i SET i.role = :toRole
             WHERE i.workspace.id = :workspaceId AND i.role.id = :fromRoleId
            """)
    int reassignRole(@Param("workspaceId") UUID workspaceId,
                     @Param("fromRoleId") UUID fromRoleId,
                     @Param("toRole") Role toRole);
}
