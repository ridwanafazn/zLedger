package com.alfagift.finledger.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Daftar user demo yang dimuat dari konfigurasi.
 * <p>
 * Untuk portofolio, user disimpan di konfigurasi (bukan tabel) agar tidak
 * menambah kompleksitas; password tetap di-hash BCrypt saat startup.
 * Di produksi, ganti dengan {@code UserDetailsService} berbasis database.
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityUsersProperties(List<User> users) {

    public record User(String username, String password, List<String> roles) {
    }
}
