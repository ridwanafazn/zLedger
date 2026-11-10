package com.alfagift.finledger.infrastructure.security;

import com.alfagift.finledger.domain.entities.AppUser;
import com.alfagift.finledger.domain.enums.UserRole;
import com.alfagift.finledger.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Seed user demo dari konfigurasi {@code app.security.users}.
 * <p>
 * Hanya membuat user yang belum ada; tidak pernah menimpa password/role user
 * yang sudah tersimpan di database. Dengan begitu data produksi aman dan
 * kredensial demo tetap tersedia untuk reviewer/CI.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserBootstrap implements ApplicationRunner {

    private final AppUserRepository appUserRepository;
    private final SecurityUsersProperties securityUsersProperties;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (securityUsersProperties.users() == null || securityUsersProperties.users().isEmpty()) {
            return;
        }

        for (SecurityUsersProperties.User seed : securityUsersProperties.users()) {
            String username = seed.username().trim().toLowerCase(Locale.ROOT);
            if (appUserRepository.existsByUsername(username)) {
                continue;
            }

            Set<UserRole> roles = seed.roles().stream()
                    .map(role -> UserRole.valueOf(role.trim().toUpperCase(Locale.ROOT)))
                    .collect(Collectors.toSet());

            appUserRepository.save(AppUser.builder()
                    .username(username)
                    .passwordHash(passwordEncoder.encode(seed.password()))
                    .fullName(seed.username())
                    .enabled(true)
                    .roles(roles)
                    .build());

            log.info("Seed user '{}' dibuat dengan role {}", username, roles);
        }
    }
}
