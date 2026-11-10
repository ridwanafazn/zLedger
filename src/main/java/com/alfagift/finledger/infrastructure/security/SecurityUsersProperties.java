package com.alfagift.finledger.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Daftar user <b>seed</b> yang dimuat dari konfigurasi.
 * <p>
 * Sejak Fase H, sumber kebenaran user adalah tabel {@code app_users}. Konfigurasi
 * ini hanya dipakai {@link UserBootstrap} untuk membuat user demo saat database
 * masih kosong; tidak lagi dipakai untuk autentikasi.
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityUsersProperties(List<User> users) {

    public record User(String username, String password, List<String> roles) {
    }
}
