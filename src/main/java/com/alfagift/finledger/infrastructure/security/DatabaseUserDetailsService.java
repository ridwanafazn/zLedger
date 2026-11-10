package com.alfagift.finledger.infrastructure.security;

import com.alfagift.finledger.domain.entities.AppUser;
import com.alfagift.finledger.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * {@link UserDetailsService} berbasis database.
 * <p>
 * Menggantikan user in-memory: kredensial, role, dan status aktif dibaca dari
 * tabel {@code app_users}. Username dinormalisasi ke huruf kecil.
 */
@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);

        AppUser user = appUserRepository.findByUsername(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("User tidak ditemukan: " + username));

        var authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();

        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(authorities)
                .disabled(!user.isEnabled())
                .build();
    }
}
