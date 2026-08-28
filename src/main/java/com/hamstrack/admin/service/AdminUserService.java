package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.*;
import com.hamstrack.auth.entity.PasswordReset;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.exception.EmailAlreadyUsedException;
import com.hamstrack.auth.repository.PasswordResetRepository;
import com.hamstrack.auth.repository.RefreshTokenRepository;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.auth.service.EmailUniqueness;
import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.dto.PageResponse;
import com.hamstrack.common.util.TokenUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Admin console user directory. Creates accounts without a password or email —
 * the caller receives a one-time setup link ({@code /reset-password?token=})
 * to hand over out-of-band. This is the primary way in on DC, where public
 * self-registration is closed. Guards protect the console from locking itself
 * out (last active ADMIN) and an admin from disabling/demoting their own account.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    // Admin-generated setup links live longer than the 1h password-reset window —
    // the admin may hand it over asynchronously.
    private static final Duration SETUP_LINK_TTL = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AppProperties appProperties;

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(Pageable pageable) {
        return PageResponse.of(userRepository.findAll(pageable).map(AdminUserResponse::of));
    }

    @Transactional
    public CreatedUserResponse create(CreateUserRequest req) {
        // Locale.ROOT, never the JVM default — the fold IS the account identity (HD-120).
        var email = req.email().toLowerCase(Locale.ROOT);
        // Folded in SQL, with the same expression users_email_lower_uk is built from (V23), for
        // the reason UserRepository.existsByFoldedEmail states: an exact check can say "free"
        // where the index says "taken", which puts an ordinary create through a doomed INSERT —
        // a 409 only because the catch below translates it, and a 500 once it stops matching.
        if (userRepository.existsByFoldedEmail(email)) {
            throw new EmailAlreadyUsedException();
        }
        var user = new User();
        user.setEmail(email);
        user.setDisplayName(req.displayName());
        // No password: the account can't be logged into until the setup link is used
        user.setPasswordHash(null);
        // Active immediately — the admin vouches for the address; email is not verified
        user.setStatus(UserStatus.ACTIVE);
        user.setSystemRole(req.roleOrDefault());
        // saveAndFlush and the catch are the same contract AuthService.register carries, for the
        // same two constraints, and the flush is what makes the catch reachable at all: a bare
        // save() defers the INSERT to commit, past this method. Translating here also keeps the
        // admin console's refusal identical to the pre-check's, so an administrator who loses a
        // race sees "Email is already registered" rather than an unexplained 500.
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (!EmailUniqueness.isDuplicateEmail(e)) throw e;
            throw new EmailAlreadyUsedException();
        }

        return new CreatedUserResponse(AdminUserResponse.of(user), generateSetupLink(user));
    }

    @Transactional
    public SetupLinkResponse regenerateSetupLink(UUID id) {
        var user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return new SetupLinkResponse(generateSetupLink(user));
    }

    @Transactional
    public AdminUserResponse update(UUID id, UpdateUserRequest req, User actor) {
        var user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (req.status() != null && req.status() != user.getStatus()) {
            if (req.status() == UserStatus.PENDING) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "PENDING is managed by email verification, not set manually");
            }
            if (req.status() == UserStatus.DISABLED) {
                if (user.getId().equals(actor.getId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot disable your own account");
                }
                guardLastAdmin(user, "disable");
                // Kill live sessions — refresh already rejects non-ACTIVE users,
                // so this just closes the short access-token window promptly
                refreshTokenRepository.deleteAllByUser(user);
            }
            user.setStatus(req.status());
        }

        if (req.systemRole() != null && req.systemRole() != user.getSystemRole()) {
            if (req.systemRole() != SystemRole.ADMIN && user.getId().equals(actor.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot remove your own admin role");
            }
            if (req.systemRole() != SystemRole.ADMIN) {
                guardLastAdmin(user, "demote");
            }
            user.setSystemRole(req.systemRole());
        }

        userRepository.save(user);
        return AdminUserResponse.of(user);
    }

    // --- helpers ---

    private void guardLastAdmin(User target, String action) {
        boolean targetCountsAsAdmin = target.getSystemRole() == SystemRole.ADMIN
                && target.getStatus() == UserStatus.ACTIVE;
        if (targetCountsAsAdmin
                && userRepository.countBySystemRoleAndStatus(SystemRole.ADMIN, UserStatus.ACTIVE) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot " + action + " the last active administrator");
        }
    }

    private String generateSetupLink(User user) {
        var raw = TokenUtils.generateRawToken();
        var reset = new PasswordReset();
        reset.setUser(user);
        reset.setTokenHash(TokenUtils.sha256(raw));
        reset.setExpiresAt(Instant.now().plus(SETUP_LINK_TTL));
        passwordResetRepository.save(reset);
        return appProperties.baseUrl() + "/reset-password?token=" + raw;
    }
}
