package com.alfagift.finledger.controller;

import com.alfagift.finledger.dto.AuthLoginRequest;
import com.alfagift.finledger.dto.AuthLoginResponse;
import com.alfagift.finledger.infrastructure.security.JwtService;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Endpoint autentikasi: menukar kredensial dengan JWT.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Login dan identitas pengguna")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Menukar username/password dengan JWT bearer token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login berhasil"),
            @ApiResponse(responseCode = "401", description = "Kredensial salah")
    })
    public ResponseEntity<AuthLoginResponse> login(@Valid @RequestBody AuthLoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

            List<String> roles = authentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority().replace("ROLE_", ""))
                    .toList();

            String token = jwtService.generateToken(authentication.getName(), roles);

            return ResponseEntity.ok(AuthLoginResponse.builder()
                    .accessToken(token)
                    .tokenType("Bearer")
                    .expiresInSeconds(jwtService.expirySeconds())
                    .username(authentication.getName())
                    .roles(roles)
                    .build());
        } catch (AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Username atau password salah");
        }
    }

    @GetMapping("/me")
    @Operation(summary = "Identitas saat ini", description = "Mengembalikan username dan role dari token aktif.")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replace("ROLE_", ""))
                .toList();
        return ResponseEntity.ok(Map.of(
                "username", authentication.getName(),
                "roles", roles));
    }
}
