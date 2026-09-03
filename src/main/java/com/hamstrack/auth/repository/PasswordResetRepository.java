package com.hamstrack.auth.repository;

import com.hamstrack.auth.entity.PasswordReset;
import com.hamstrack.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {
    Optional<PasswordReset> findByTokenHash(String tokenHash);

    /**
     * <strong>Burns every OTHER outstanding reset for this user</strong> — run when a reset
     * completes, beside the refresh-token purge (HD-183).
     *
     * <p>Each link was already single-use; the set of them was not. At the shipped ceilings
     * (a 1-minute cooldown, five per address per 15 minutes) a user could hold five
     * simultaneously valid one-hour links, and the "Send another link" button walks them into
     * exactly that. So somebody with transient sight of the inbox copies link {@code L1}
     * without using it, the victim notices and resets with {@code L2} — killing the attacker's
     * sessions — and {@code L1} is still unused, still live, and re-takes the account for the
     * rest of its hour. Completing a reset must therefore retire the whole outstanding set, not
     * the one row that happened to be redeemed.
     *
     * <p><strong>THIS MUST NOT BE MOVED INTO THE MINT PATH.</strong> Invalidating prior links
     * when a new one is issued reads like the same fix and is a strictly worse bug: minting is
     * unauthenticated, so an attacker who POSTs {@code /api/auth/forgot-password} for a victim
     * would kill the link the victim is holding — a free denial-of-recovery against any address
     * they can name, repeatable for as long as the ceilings allow. Those ceilings exist to bound
     * the harm an anonymous caller can aim at one inbox; handing that caller the power to void
     * someone else's live link would spend the budget on damage instead of mail.
     *
     * <p><em>The bar is authentication, not the identity of this particular door.</em> A caller
     * that has proven who it is may retire an account's outstanding links — by session, as an
     * administrator, or by presenting a token that was mailed to the address, which is what
     * completing a reset does. A caller that has merely <em>asked</em> for a link has proven
     * nothing about the account, so no unauthenticated door may sweep, however much it looks like
     * the right place. Read the rule that way round rather than as a count of call sites: the
     * admin regenerate door is authenticated, so sweeping there would be safe, while a new
     * anonymous door would not become safe by copying this one.
     *
     * <p><strong>Marked used, never deleted</strong> — the row survives as the record that a link
     * existed and that it is no longer live. It does <em>not</em> record how it ended. A sibling
     * retired by this sweep and the link that was actually redeemed both carry a non-null
     * {@code used_at}, and {@code password_resets} has no discriminator column, so on the row the
     * two are indistinguishable; the {@code COMPLETED} reset metric is per reset, not per row, and
     * does not recover the distinction either. The sweep also carries no {@code expires_at}
     * predicate, so it stamps rows that had already expired: {@code used_at > expires_at} is an
     * ordinary retirement here, not corruption, and an incident reader must not read it as one.
     * Telling a redemption from a retirement after the fact would take a discriminator column,
     * hence a migration; that is deliberately not built.
     *
     * <p><strong>The sweep is deliberately blind to which door minted the row, so it DOES retire
     * an administrator's 7-day setup link</strong> ({@code AdminUserService.generateSetupLink},
     * same table, longer TTL). That is a decision, not an omission — twice over. Mechanically,
     * {@code password_resets} carries no column saying who minted a row, so sparing the admin
     * link would need a migration, a discriminator, and a rule about it; but the substantive
     * reason is that the setup link is the <em>longer-lived</em> instance of the same
     * account-takeover primitive, and a sweep that spares the seven-day capability while burning
     * the one-hour ones inverts its own risk ordering. What is being retired is the account's
     * outstanding right to set a password without knowing the current one, and once someone has
     * exercised that right the rest of the set is spent. The product cost is small and repairable
     * by the party who holds it: an administrator whose handed-over setup link stops working
     * re-issues one from the console (there is a {@code regenerateSetupLink} endpoint for
     * precisely this), and the case where it happens — the user reset their own password first —
     * is the case where they no longer need the setup link at all.
     *
     * <p>Plain {@code @Modifying}: nothing re-reads a swept row in that transaction, and
     * {@code clearAutomatically} mid-transaction would discard pending inserts (the
     * {@code WorkspaceService.create} scar). The {@code id <> :current} exclusion also keeps the
     * one managed {@code PasswordReset} in the persistence context out of the bulk statement, so
     * there is no stale-copy desync to reconcile either — the redeemed row is marked used through
     * the entity, its siblings through this.
     *
     * @return how many siblings were retired (0 is the ordinary case)
     */
    @Modifying
    @Query("UPDATE PasswordReset r SET r.usedAt = :now "
            + "WHERE r.user = :user AND r.usedAt IS NULL AND r.id <> :current")
    int invalidateOtherOutstanding(@Param("user") User user,
                                   @Param("current") UUID current,
                                   @Param("now") Instant now);
}
