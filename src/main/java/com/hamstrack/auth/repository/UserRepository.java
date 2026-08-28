package com.hamstrack.auth.repository;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <strong>Which comparisons on {@code users.email} fold, and which must not</strong> (HD-120,
 * HD-167). The rule is the generating property rather than the list of methods, because the list
 * goes stale one caller before the rule does:
 *
 * <blockquote><strong>A check that can only REFUSE folds. A lookup that RESOLVES an identity
 * compares exactly.</strong> An extra match on a refusal declines someone who was entitled —
 * recoverable, visible, and the caller is told. An extra match on a resolution admits the wrong
 * person.</blockquote>
 *
 * <p>So {@link #existsByFoldedEmail} and {@link #findByFoldedEmail} back the write-side questions
 * ("may this address be registered?", "does the seed admin already exist?"), while
 * {@link #findByEmail} backs every identity resolution — {@code login}, {@code forgotPassword},
 * {@code resendVerification}, the already-a-member check. Those exact ones are <em>not</em> an
 * oversight and a reviewer should not "finish the job": an exact lookup on a stale or corrupted
 * key <strong>fails closed</strong> — the person cannot log in, and says so — while a folded
 * lookup on the same data can <strong>fail open</strong> and resolve a typed address to whichever
 * of two rows the planner returns.
 *
 * <p><strong>Instance-wide by design.</strong> An account exists before any membership and is
 * shared across workspaces, so this is one of the few tables where an instance-wide question is
 * the correct one — the same exception the published-password probe below already documents.
 * Anything asking "who is in this workspace" goes through {@code WorkspaceMemberRepository}.
 */
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    /**
     * Exact-match existence — a <strong>read-side</strong> question, and deliberately not a
     * write-side one. No production caller: {@code AuthService.register} and
     * {@code AdminUserService.create} both ask {@link #existsByFoldedEmail}, which asks the
     * question in the expression {@code users_email_lower_uk} answers it in.
     *
     * <p>Kept because tests assert on it, where an exact check is the correct one ("is this the
     * row we wrote?"). <strong>Do not reach for it from a writer</strong> — it sits one line above
     * the folded one with the shorter and more obvious name, which is what autocomplete offers
     * first, and the defect it reintroduces is the one V23's whole translation layer exists to
     * avoid.
     */
    boolean existsByEmail(String email);

    /**
     * <strong>May this address be registered?</strong> — the write-side check for
     * {@code AuthService.register} and {@code AdminUserService.create}, folded in SQL with the
     * <em>same expression</em> {@code users_email_lower_uk} is built from (V23).
     *
     * <p><strong>The expression has to match the index's, and that is the whole point of this
     * method.</strong> Java's {@code toLowerCase(Locale.ROOT)} and PostgreSQL's {@code lower()}
     * are different functions, and {@code users} bounds nothing — {@code RegisterRequest} carries
     * {@code @Email @NotBlank @Size(max = 255)} and no ASCII pattern, unlike
     * {@code InviteMemberRequest}. Where the two folds disagree, an exact Java-side check says
     * "free" while the index says "taken", so an ordinary signup runs a <em>doomed</em> INSERT —
     * which {@code EmailUniqueness} answers 409 today, and which is a 500 the day that catch
     * stops matching (a renamed constraint, a new writer that forgets it). The check folds so the
     * refusal never depends on the translation being right.
     *
     * <p><strong>Do not replace this with a derived {@code existsByEmailIgnoreCase}.</strong>
     * Spring Data generates {@code upper(…)} for {@code IgnoreCase}, and {@code upper} is not the
     * inverse of {@code lower} on every input — so a derived finder would ask a <em>different</em>
     * question than the index answers, which is the exact defect this method exists to prevent,
     * in a new spelling. ({@code WorkspaceInviteRepository}'s
     * {@code findByEmailIgnoreCaseAndAcceptedAtIsNull…} is <em>named</em> {@code IgnoreCase} but
     * is backed by an explicit {@code @Query} with {@code lower()}. The name lies; do not copy it
     * as a pattern.)
     *
     * <p>Precautionary rather than corrective, and it discloses nothing new: on any database V23
     * accepted, every stored value is its own fold, so this and {@link #existsByEmail} return the
     * same answer for every input the DTOs admit. It exists for the row a future writer leaves.
     */
    @Query("select case when count(u) > 0 then true else false end from User u "
            + "where lower(u.email) = lower(:email)")
    boolean existsByFoldedEmail(@Param("email") String email);

    /**
     * <strong>Seeding only</strong> — the admin find-or-create inline in
     * {@code DataSeeder.run(ApplicationArguments)} (there is no {@code seedAdmin} member to grep
     * for; older prose names one), which is a
     * <em>write-side</em> question despite being a read: a miss does not fail, it MINTS a second
     * ACTIVE system administrator carrying {@code SEED_ADMIN_PASSWORD} while the original stays
     * active and orphaned. Folding is what makes a row that differs from the configured address
     * only in case <em>found</em> rather than missed.
     *
     * <p><strong>Found is not the same as trusted, and this method cannot tell the difference.</strong>
     * What it hands back is whoever occupies the folded key, which post-V23 is exactly one row and
     * is not necessarily one the seeder wrote. Its caller therefore compares the returned address
     * <em>exactly</em> before granting {@code SystemRole.ADMIN}, and refuses the boot otherwise —
     * the rule on this interface, applied at the one call site that both resolves and grants.
     * (Any javadoc claiming the fold prevents a "silent duplicate" is describing a pre-V23 world:
     * {@code users_email_lower_uk} makes a duplicate impossible. What the fold actually displaces
     * is a loud boot failure, which is why the caller keeps one.)
     *
     * <p><strong>Not for authentication, and {@code Optional} is only safe because the index
     * exists.</strong> Before V23 this predicate could match several rows; Flyway runs to
     * completion before the application serves traffic, so by the time this can be called the
     * invariant already holds. Any caller that resolves an identity uses {@link #findByEmail}
     * instead — see the rule on this interface.
     */
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByFoldedEmail(@Param("email") String email);

    // Admin console user directory — oldest first (admin/seed accounts on top)
    List<User> findAllByOrderByCreatedAtAsc();

    // Guards the admin console against locking itself out (last active ADMIN)
    long countBySystemRoleAndStatus(SystemRole systemRole, com.hamstrack.auth.entity.UserStatus status);

    // Startup probe for the published seed password (DataSeeder).
    //
    // BOUNDED, because each row this returns costs one bcrypt verification on every boot -
    // measured at ~370 ms at the strength SecurityConfig configures (12), per (row x
    // published password). An unbounded version made a fresh test database - 1362
    // accumulated administrators - add over two minutes to EVERY Spring context, and the
    // suite never finished.
    //
    // Oldest first because that is ONE of two mechanisms, neither of which subsumes the
    // other: the ordering reaches the account an installer creates on first boot (while
    // fewer than 25 administrators predate it - the usual case, not a guarantee), and
    // DataSeeder additionally looks up whatever seed.admin.email names TODAY, which is what
    // covers an install that began seeding years in. Whatever both miss is reported by the
    // partial-coverage WARN, which is gated on the count below.
    //
    // Deliberately the whole role rather than only ACTIVE ones: a DISABLED administrator
    // whose stored password is public is one re-enable away from the same thing, and the
    // operator has to hear about it while they are already fixing the others.
    List<User> findFirst25BySystemRoleAndPasswordHashIsNotNullOrderByCreatedAtAsc(SystemRole systemRole);

    // The denominator of that WARN, and deliberately the same population as the finder
    // above: administrators that HAVE a password are the only ones the probe can examine.
    // Counting the whole role instead made the warning fire on installs with complete
    // coverage (26 admins, 22 with a password, all 22 verified) - which is how a
    // partial-coverage warning gets tuned out. Instance-wide by design, like the finder: a
    // published administrator password is a property of the deployment, not of a tenant, so
    // this is one of the few user questions that is NOT workspace-scoped. Anything asking
    // "who is in this workspace" goes through WorkspaceMemberRepository instead.
    long countBySystemRoleAndPasswordHashIsNotNull(SystemRole systemRole);

    // Backs the hamstrack.users.active gauge (see ProductMetrics) — evaluated
    // at scrape time, so it must stay a single cheap count query.
    long countByStatus(com.hamstrack.auth.entity.UserStatus status);

    // Atomic claim: only one concurrent authentication wins the right to seed
    // demo data. Returns 0 when already seeded (or claimed in parallel).
    @Modifying(clearAutomatically = true)
    @Query("update User u set u.demoSeededAt = :now where u.id = :id and u.demoSeededAt is null")
    int claimDemoSeed(@Param("id") UUID id, @Param("now") Instant now);

    // Marks first-login onboarding complete (idempotent — only sets when null).
    // Bulk update avoids attaching the detached security-principal User.
    // NOTE: deliberately NOT clearAutomatically — this is called mid-transaction
    // right after saving a workspace/member (WorkspaceService.create), and
    // clearing the persistence context would discard those still-pending INSERTs
    // (the UPDATE only touches `users`, so they aren't auto-flushed first). We
    // never re-read onboardedAt in the same transaction, so no stale-cache risk.
    @Modifying
    @Query("update User u set u.onboardedAt = :now where u.id = :id and u.onboardedAt is null")
    int markOnboarded(@Param("id") UUID id, @Param("now") Instant now);
}
