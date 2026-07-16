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
        // Lowercase to match login, which looks the email up lowercased
        var email = adminEmail.toLowerCase();
        var existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null) {
            // Accounts seeded before system roles existed must still get ADMIN
            if (existing.getSystemRole() != SystemRole.ADMIN) {
                existing.setSystemRole(SystemRole.ADMIN);
                userRepository.save(existing);
                log.info("Existing seed account promoted to system ADMIN: {}", email);
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

        log.info("Admin account created: {}", email);
    }
}
