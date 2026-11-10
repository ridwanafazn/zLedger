package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.AppUser;
import com.alfagift.finledger.domain.enums.UserRole;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.RegisterRequest;
import com.alfagift.finledger.dto.UserCreateRequest;
import com.alfagift.finledger.dto.UserResponse;
import com.alfagift.finledger.infrastructure.DuplicateUserException;
import com.alfagift.finledger.infrastructure.Idempotent;
import com.alfagift.finledger.infrastructure.security.RefreshTokenService;
import com.alfagift.finledger.infrastructure.security.TokenRevocationService;
import com.alfagift.finledger.repository.AppUserRepository;
import com.alfagift.finledger.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Implementasi {@link UserService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final TokenRevocationService tokenRevocationService;

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        return createInternal(request.getUsername(), request.getPassword(), request.getEmail(),
                request.getFullName(), Set.of(UserRole.CUSTOMER));
    }

    @Override
    @Idempotent
    @Transactional
    public UserResponse create(UserCreateRequest request, String idempotencyKey) {
        return createInternal(request.getUsername(), request.getPassword(), request.getEmail(),
                request.getFullName(), request.getRoles());
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getById(UUID userId) {
        return toResponse(appUserRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User tidak ditemukan: " + userId)));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getByUsername(String username) {
        return toResponse(appUserRepository.findByUsername(normalizeUsername(username))
                .orElseThrow(() -> new EntityNotFoundException("User tidak ditemukan: " + username)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(UserRole role, Boolean enabled, String query, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        Specification<AppUser> specification = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (role != null) {
                predicates.add(cb.equal(root.join("roles"), role));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            if (!needle.isEmpty()) {
                String pattern = "%" + needle + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("username")), pattern),
                        cb.like(cb.lower(root.get("email")), pattern),
                        cb.like(cb.lower(root.get("fullName")), pattern)));
            }
            if (cq != null) {
                cq.distinct(true);
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AppUser> users = appUserRepository.findAll(specification, pageable);

        return PageResponse.<UserResponse>builder()
                .content(users.getContent().stream().map(this::toResponse).toList())
                .page(PageResponse.PageMetadata.builder()
                        .number(users.getNumber())
                        .size(users.getSize())
                        .totalElements(users.getTotalElements())
                        .totalPages(users.getTotalPages())
                        .first(users.isFirst())
                        .last(users.isLast())
                        .numberOfElements(users.getNumberOfElements())
                        .build())
                .sort(List.of(PageResponse.SortInfo.builder()
                        .field("createdAt")
                        .direction("DESC")
                        .build()))
                .build();
    }

    @Override
    @Transactional
    public UserResponse updateStatus(UUID userId, boolean enabled, String actorUsername) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User tidak ditemukan: " + userId));

        if (!enabled && user.getUsername().equals(normalizeUsername(actorUsername))) {
            throw new IllegalArgumentException("Tidak dapat menonaktifkan akun sendiri");
        }

        user.setEnabled(enabled);
        AppUser saved = appUserRepository.save(user);

        if (enabled) {
            tokenRevocationService.clear(saved.getUsername());
            log.info("User {} diaktifkan oleh {}", saved.getUsername(), actorUsername);
        } else {
            // Cabut semua jalur akses: refresh token + access token yang masih hidup
            refreshTokenService.revokeAll(saved.getUsername());
            tokenRevocationService.revoke(saved.getUsername());
            log.info("User {} dinonaktifkan oleh {} (token dicabut)", saved.getUsername(), actorUsername);
        }

        return toResponse(saved);
    }

    private UserResponse createInternal(String rawUsername, String rawPassword, String rawEmail,
                                        String fullName, Set<UserRole> roles) {
        String username = normalizeUsername(rawUsername);
        String email = normalizeEmail(rawEmail);

        if (appUserRepository.existsByUsername(username)) {
            throw new DuplicateUserException("Username '" + username + "' sudah dipakai");
        }
        if (email != null && appUserRepository.existsByEmail(email)) {
            throw new DuplicateUserException("Email '" + email + "' sudah dipakai");
        }

        AppUser user = AppUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .email(email)
                .fullName(fullName == null || fullName.isBlank() ? null : fullName.trim())
                .enabled(true)
                .roles(new java.util.HashSet<>(roles))
                .build();

        AppUser saved = appUserRepository.save(user);
        log.info("User '{}' dibuat dengan role {}", saved.getUsername(), saved.getRoles());
        return toResponse(saved);
    }

    private String normalizeUsername(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UserResponse toResponse(AppUser user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .enabled(user.isEnabled())
                .roles(user.getRoles().stream().map(Enum::name).sorted().toList())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
