package com.alfagift.finledger.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test alur user database (Fase H):
 * registrasi -> login -> me -> nonaktifkan -> token dicabut -> aktifkan kembali.
 * <p>
 * Memakai PostgreSQL dan Redis sungguhan lewat Testcontainers agar perilaku
 * security (BCrypt, RBAC, revokasi token) teruji end-to-end.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finledger_auth")
            .withUsername("finledger")
            .withPassword("finledger_test_secret");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Alur registrasi, login, revokasi, dan aktivasi ulang harus konsisten")
    void fullUserLifecycle() throws Exception {
        String username = "phaseh" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.com";
        String password = "rahasia123";

        // 1. Registrasi publik -> 201, role CUSTOMER
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s","fullName":"Phase H"}
                                """.formatted(username, password, email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        String userId = read(registered).get("id").asText();

        // 2. Registrasi duplikat -> 409
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));

        // 3. Login user baru -> token
        JsonNode userTokens = login(username, password);
        String userAccess = userTokens.get("accessToken").asText();
        String userRefresh = userTokens.get("refreshToken").asText();

        // 4. /me membaca profil dari database
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + userAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.email").value(email));

        // 5. Customer tidak boleh mengakses manajemen user
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + userAccess))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        // 6. Admin menonaktifkan user
        String adminAccess = login("admin", "admin123").get("accessToken").asText();
        mockMvc.perform(patch("/api/v1/users/{id}/status", userId)
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // 7. Access token lama langsung ditolak (revokasi Redis)
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + userAccess))
                .andExpect(status().isUnauthorized());

        // 8. Refresh token lama juga dicabut
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + userRefresh + "\"}"))
                .andExpect(status().isUnauthorized());

        // 9. Login ulang ditolak karena akun nonaktif
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isUnauthorized());

        // 10. Admin mengaktifkan kembali -> login berhasil lagi
        mockMvc.perform(patch("/api/v1/users/{id}/status", userId)
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        assertThat(login(username, password).get("accessToken").asText()).isNotBlank();

        // 11. Admin dapat mencari user berdasarkan role
        mockMvc.perform(get("/api/v1/users")
                        .param("role", "CUSTOMER")
                        .param("q", username)
                        .header("Authorization", "Bearer " + adminAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value(username));
    }

    @Test
    @DisplayName("Admin tidak boleh menonaktifkan akun sendiri")
    void adminCannotDisableSelf() throws Exception {
        String adminAccess = login("admin", "admin123").get("accessToken").asText();

        MvcResult me = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + adminAccess))
                .andExpect(status().isOk())
                .andReturn();

        String adminId = read(me).get("id").asText();

        mockMvc.perform(patch("/api/v1/users/{id}/status", adminId)
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
