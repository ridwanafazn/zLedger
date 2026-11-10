package com.alfagift.finledger.service;

import com.alfagift.finledger.domain.enums.UserRole;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.RegisterRequest;
import com.alfagift.finledger.dto.UserCreateRequest;
import com.alfagift.finledger.dto.UserResponse;

import java.util.UUID;

/**
 * Use case manajemen user aplikasi.
 */
public interface UserService {

    /** Registrasi publik; role selalu CUSTOMER. */
    UserResponse register(RegisterRequest request);

    /** Pembuatan user oleh ADMIN dengan role bebas. */
    UserResponse create(UserCreateRequest request, String idempotencyKey);

    UserResponse getById(UUID userId);

    UserResponse getByUsername(String username);

    PageResponse<UserResponse> search(UserRole role, Boolean enabled, String query, int page, int size);

    /**
     * Mengaktifkan/menonaktifkan user.
     * <p>
     * Menonaktifkan akan mencabut refresh token dan menandai access token aktif
     * sebagai dicabut (Redis) sehingga akses berhenti seketika.
     */
    UserResponse updateStatus(UUID userId, boolean enabled, String actorUsername);
}
