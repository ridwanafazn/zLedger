package com.alfagift.finledger.infrastructure.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test untuk {@link JwtService}.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-with-at-least-32-bytes!!";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 60);
    }

    @Test
    @DisplayName("Token yang dibuat harus bisa diparse dan membawa subject + roles")
    void generatedTokenShouldBeParseable() {
        String token = jwtService.generateToken("admin", List.of("ADMIN"));

        Claims claims = jwtService.parse(token);

        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(jwtService.extractRoles(claims)).containsExactly("ADMIN");
        assertThat(jwtService.isValid(token)).isTrue();
    }

    @Test
    @DisplayName("expirySeconds harus mengikuti konfigurasi menit")
    void expiryShouldFollowConfiguration() {
        assertThat(jwtService.expirySeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("Token dengan signature berbeda harus dianggap tidak valid")
    void tokenFromDifferentSecretShouldBeInvalid() {
        JwtService other = new JwtService("another-secret-key-with-at-least-32-bytes-xx", 60);
        String foreignToken = other.generateToken("customer", List.of("CUSTOMER"));

        assertThat(jwtService.isValid(foreignToken)).isFalse();
    }

    @Test
    @DisplayName("String acak harus dianggap tidak valid")
    void malformedTokenShouldBeInvalid() {
        assertThat(jwtService.isValid("bukan-token-jwt")).isFalse();
    }
}
