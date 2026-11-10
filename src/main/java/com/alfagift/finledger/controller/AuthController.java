package com.alfagift.finledger.controller;

import com.alfagift.finledger.dto.AuthLoginRequest;
import com.alfagift.finledger.dto.AuthLoginResponse;
import com.alfagift.finledger.dto.RefreshTokenRequest;
import com.alfagift.finledger.dto.RegisterRequest;
import com.alfagift.finledger.dto.UserResponse;
import com.alfagift.finledger.infrastructure.security.JwtService;
import com.alfagift.finledger.infrastructure.security.RefreshTokenService;
import com.alfagift.finledger.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Endpoint autentikasi: registrasi, login, refresh token, logout, dan identitas pengguna.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Registrasi, login, refresh token, dan identitas pengguna")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserDetailsService userDetailsService;
    private final UserService userService;

    @PostMapping("/register")
    @Operation(summary = "Registrasi pengguna baru",
            description = "Membuat akun CUSTOMER. Role tidak dapat dipilih klien; " +
                    "akun MERCHANT/ADMIN hanya dibuat lewat endpoint admin.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Registrasi berhasil"),
            @ApiResponse(responseCode = "400", description = "Data tidak valid"),
            @ApiResponse(responseCode = "409", description = "Username/email sudah dipakai")
    })
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Menukar username/password dengan access token JWT dan refresh token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login berhasil"),
            @ApiResponse(responseCode = "401", description = "Kredensial salah atau akun nonaktif")
    })
    public ResponseEntity<AuthLoginResponse> login(@Valid @RequestBody AuthLoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

            List<String> roles = rolesOf(authentication);
            String accessToken = jwtService.generateToken(authentication.getName(), roles);
            String refreshToken = refreshTokenService.issue(authentication.getName());

            return ResponseEntity.ok(buildResponse(authentication.getName(), roles, accessToken, refreshToken));
        } catch (AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Username atau password salah");
        }
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token",
            description = "Menukar refresh token (sekali pakai) dengan access token + refresh token baru.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token diperbarui"),
            @ApiResponse(responseCode = "401", description = "Refresh token tidak valid/kedaluwarsa/akun nonaktif")
    })
    public ResponseEntity<AuthLoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        String username = refreshTokenService.consume(request.getRefreshToken());

        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Akun tidak lagi tersedia");
        }

        if (!user.isEnabled()) {
            refreshTokenService.revokeAll(username);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Akun dinonaktifkan");
        }

        List<String> roles = user.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replace("ROLE_", ""))
                .toList();

        String accessToken = jwtService.generateToken(username, roles);
        String newRefreshToken = refreshTokenService.issue(username);

        return ResponseEntity.ok(buildResponse(username, roles, accessToken, newRefreshToken));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout", description = "Mencabut refresh token aktif.")
    @ApiResponse(responseCode = "204", description = "Refresh token dicabut")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.revoke(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(summary = "Identitas saat ini",
            description = "Mengembalikan profil user (tanpa hash password) dari database.")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        return ResponseEntity.ok(userService.getByUsername(authentication.getName()));
    }

    private List<String> rolesOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replace("ROLE_", ""))
                .toList();
    }

    private AuthLoginResponse buildResponse(String username, List<String> roles,
                                            String accessToken, String refreshToken) {
        return AuthLoginResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresInSeconds(jwtService.expirySeconds())
                .refreshToken(refreshToken)
                .username(username)
                .roles(roles)
                .build();
    }
}
