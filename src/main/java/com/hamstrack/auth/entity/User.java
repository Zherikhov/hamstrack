package com.hamstrack.auth.entity;

import com.hamstrack.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends BaseEntity implements UserDetails {

    /**
     * <strong>The account.</strong> It is what a person types to log in, what a reset link is
     * mailed to, and what {@code WorkspaceService.acceptInvite} matches an invitation against
     * with {@code equals}.
     *
     * <p><strong>Two constraints guard it, and only one of them is visible here.</strong>
     * {@code unique = true} above is {@code users_email_key}, the byte-exact one from
     * {@code V1__init_schema.sql}. The case-insensitive guarantee is
     * {@code UNIQUE (lower(email))} — {@code users_email_lower_uk}, added by
     * {@code V23__users_email_uniqueness.sql} (HD-167, ADR-0016) — and it is deliberately
     * <strong>not mirrored here</strong>: JPA cannot express a functional unique constraint, and
     * a {@code @Table(uniqueConstraints = …)} would declare a rule the schema does not have.
     * {@code ddl-auto=validate} is unaffected, because Hibernate validates columns and types
     * rather than expression indexes.
     *
     * <p><strong>What every future writer of this table owes</strong>, stated as the requirement
     * rather than as today's list of callers, because the list goes stale one importer before the
     * rule does:
     * <ol>
     *   <li><strong>Fold with {@code Locale.ROOT} before the insert.</strong> The constraint
     *       governs <em>uniqueness</em> only; the fold answers a different question — the address
     *       is stored, mailed and used as a lookup key, and those three must be one string
     *       (HD-120). Never the JVM default locale: a Turkish container folds {@code I} to a
     *       dotless {@code ı} and stores an address that exists nowhere.</li>
     *   <li><strong>Ask its existence question with {@code lower()} in SQL</strong> —
     *       {@code UserRepository.existsByFoldedEmail}, never the exact one and never a derived
     *       {@code IgnoreCase} finder, which generates {@code upper()}.</li>
     *   <li><strong>Flush, and translate {@code 23505} into the existing 409</strong>
     *       ({@code EmailUniqueness}). The case it covers is the <em>race</em> — two concurrent
     *       registrations of one address — and not a stored mixed-case row, which the folded
     *       pre-check refuses without ever reaching the INSERT. Without the translation that race
     *       is a 500, on either constraint name.</li>
     * </ol>
     *
     * <p>Reads that <em>resolve</em> an identity stay exact ({@code findByEmail}); only refusals
     * fold. {@code UserRepository} carries that rule in full.
     */
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.PENDING;

    // Null for users registered before terms acceptance existed and for
    // installs where app.legal.terms-acceptance-required=false
    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    // Null = demo workspace not yet seeded; stamped atomically on first
    // authentication (see UserRepository.claimDemoSeed / DemoDataService)
    @Column(name = "demo_seeded_at")
    private Instant demoSeededAt;

    // Null = the user hasn't completed first-login onboarding (Cloud only):
    // stamped when they create or join their first team, or skip. Gated by
    // app.onboarding.enabled — see UserRepository.markOnboarded
    @Column(name = "onboarded_at")
    private Instant onboardedAt;

    // Instance-wide role; ADMIN unlocks /api/admin/** (see SecurityConfig)
    @Enumerated(EnumType.STRING)
    @Column(name = "system_role", nullable = false, length = 20)
    private SystemRole systemRole = SystemRole.USER;

    // --- UserDetails ---

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + systemRole.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE;
    }
}
