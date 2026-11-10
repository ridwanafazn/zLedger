package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Request pembuatan user oleh ADMIN (boleh menentukan role).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreateRequest {

    @NotBlank(message = "Username wajib diisi")
    @Pattern(regexp = "^[A-Za-z0-9._-]{3,32}$",
            message = "Username 3-32 karakter, hanya huruf, angka, titik, garis bawah, dan strip")
    private String username;

    @NotBlank(message = "Password wajib diisi")
    @Size(min = 8, max = 72, message = "Password 8-72 karakter")
    private String password;

    @Email(message = "Format email tidak valid")
    @Size(max = 255, message = "Email maksimal 255 karakter")
    private String email;

    @Size(max = 128, message = "Nama lengkap maksimal 128 karakter")
    private String fullName;

    @NotEmpty(message = "Minimal satu role wajib diisi")
    private Set<UserRole> roles;
}
