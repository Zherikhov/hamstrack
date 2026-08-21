package com.hamstrack.common.seed;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${seed.admin.email:}")
    private String adminEmail;

    @Value("${seed.admin.display-name:Admin}")
    private String adminDisplayName;

    @Value("${seed.admin.password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank()) {
            log.info("Admin seeding skipped — seed.admin.email not configured");
            return;
        }
        if (adminPassword.isBlank()) {
            log.warn("Admin seeding skipped — seed.admin.email is set but seed.admin.password is empty");
            return;
        }
        // Lowercase to match login, which looks the email up lowercased. Locale.ROOT for the
        // reason AuthService.register gives, and with a consequence unique to this class: the
        // lookup below is what decides between "promote the existing admin" and "create one".
        // A miss here does not fail - it MINTS a second ACTIVE SystemRole.ADMIN carrying
        // seed.admin.password, while the original stays active and orphaned, and this class
        // deliberately never logs the address, so the only trace is one extra users row. So a
        // deployment that once folded differently (a tr_TR/az/lt JVM: IT-Admin@corp.com became
        // <dotless-i>t-admin@corp.com) has a stale row this build can no longer find.
        //
        // Detection and remedy live in docs/self-hosting.md, "Duplicate accounts after an
        // upgrade" - the DC operator manual, because the person who has to run those queries
        // is an operator and not a maintainer. (It was first written into the release
        // checklist, which is a runbook about tagging that no self-hoster opens; a remedy
        // filed where its reader never looks is not a remedy.) The image pins the JVM locale
        // from 0.16.0 on, so this cannot recur there; that doc covers the rest (HD-120).
        var email = adminEmail.toLowerCase(Locale.ROOT);
        var existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null) {
            // Accounts seeded before system roles existed must still get ADMIN
            if (existing.getSystemRole() != SystemRole.ADMIN) {
                existing.setSystemRole(SystemRole.ADMIN);
                userRepository.save(existing);
                log.info("Existing seed account promoted to system ADMIN (from seed.admin.email)");
            }
            return;
        }

        var admin = new User();
        admin.setEmail(email);
        admin.setDisplayName(adminDisplayName);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setStatus(UserStatus.ACTIVE);
        admin.setSystemRole(SystemRole.ADMIN);
        userRepository.save(admin);

        log.info("Admin account created from seed.admin.email");
    }
}
