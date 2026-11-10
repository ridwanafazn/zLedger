package com.alfagift.finledger.controller;

import com.alfagift.finledger.dto.AuthLoginRequest;
import com.alfagift.finledger.infrastructure.exception.GlobalExceptionHandler;
import com.alfagift.finledger.infrastructure.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit test (MockMvc) untuk {@link AuthController}.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private com.alfagift.finledger.infrastructure.security.RefreshTokenService refreshTokenService;

    @Mock
    private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

    private AuthController authController;

    @BeforeEach
    void setUp() {
        authController = new AuthController(authenticationManager, jwtService, refreshTokenService, userDetailsService);
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Login valid harus mengembalikan access token dan refresh token")
    void login_shouldReturnToken() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(jwtService.generateToken("admin", List.of("ADMIN"))).thenReturn("jwt-token");
        when(jwtService.expirySeconds()).thenReturn(3600L);
        when(refreshTokenService.issue("admin")).thenReturn("refresh-token");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                AuthLoginRequest.builder().username("admin").password("admin123").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    @DisplayName("Login salah harus mengembalikan 401 problem+json")
    void login_badCredentials_shouldReturn401() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                AuthLoginRequest.builder().username("admin").password("salah").build())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Login tanpa field wajib harus 400")
    void login_invalidBody_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Login dengan JSON rusak harus 400, bukan 500")
    void login_malformedJson_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{username:admin}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("Refresh token valid harus mengembalikan token baru (rotasi)")
    void refresh_shouldReturnNewTokens() throws Exception {
        var user = org.springframework.security.core.userdetails.User
                .withUsername("customer")
                .password("x")
                .roles("CUSTOMER")
                .build();

        when(refreshTokenService.consume("refresh-1")).thenReturn("customer");
        when(userDetailsService.loadUserByUsername("customer")).thenReturn(user);
        when(jwtService.generateToken("customer", List.of("CUSTOMER"))).thenReturn("jwt-new");
        when(jwtService.expirySeconds()).thenReturn(3600L);
        when(refreshTokenService.issue("customer")).thenReturn("refresh-2");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                com.alfagift.finledger.dto.RefreshTokenRequest.builder()
                                        .refreshToken("refresh-1").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-new"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-2"));
    }

    @Test
    @DisplayName("Refresh token tidak valid harus 401")
    void refresh_invalidToken_shouldReturn401() throws Exception {
        when(refreshTokenService.consume("refresh-lama"))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Refresh token tidak valid atau kedaluwarsa"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-lama\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Logout harus mencabut refresh token (204)")
    void logout_shouldRevokeToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-1\"}"))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(refreshTokenService).revoke("refresh-1");
    }
}
