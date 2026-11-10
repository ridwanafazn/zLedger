package com.alfagift.finledger.controller;

import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.UserResponse;
import com.alfagift.finledger.infrastructure.exception.GlobalExceptionHandler;
import com.alfagift.finledger.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit test (MockMvc) untuk {@link UserController}.
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private static final UUID USER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private MockMvc mockMvc;

    @Mock
    private UserService userService;

    private UsernamePasswordAuthenticationToken admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        admin = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private UserResponse sampleUser() {
        return UserResponse.builder()
                .id(USER_ID)
                .username("merchant.baru")
                .enabled(true)
                .roles(List.of("MERCHANT"))
                .build();
    }

    @Test
    @DisplayName("POST /users harus 201")
    void create_shouldReturn201() throws Exception {
        when(userService.create(any(), eq("user-1"))).thenReturn(sampleUser());

        mockMvc.perform(post("/api/v1/users")
                        .principal(admin)
                        .header("X-Idempotency-Key", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "merchant.baru",
                                  "password": "rahasia123",
                                  "roles": ["MERCHANT"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("merchant.baru"))
                .andExpect(jsonPath("$.roles[0]").value("MERCHANT"));
    }

    @Test
    @DisplayName("POST /users tanpa header idempotency harus 400")
    void create_withoutIdempotencyKey_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .principal(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"merchant.baru\",\"password\":\"rahasia123\",\"roles\":[\"MERCHANT\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"));
    }

    @Test
    @DisplayName("POST /users dengan role kosong harus 400")
    void create_emptyRoles_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .principal(admin)
                        .header("X-Idempotency-Key", "user-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"merchant.baru\",\"password\":\"rahasia123\",\"roles\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /users harus 200 dan mendukung filter")
    void search_shouldReturn200() throws Exception {
        when(userService.search(any(), eq(true), eq("bud"), eq(0), eq(20))).thenReturn(
                PageResponse.<UserResponse>builder()
                        .content(List.of(sampleUser()))
                        .page(PageResponse.PageMetadata.builder()
                                .number(0).size(20).totalElements(1).totalPages(1)
                                .first(true).last(true).numberOfElements(1).build())
                        .sort(List.of())
                        .build());

        mockMvc.perform(get("/api/v1/users")
                        .principal(admin)
                        .param("enabled", "true")
                        .param("q", "bud"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("merchant.baru"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /users dengan role tidak valid harus 400")
    void search_invalidRole_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/users").principal(admin).param("role", "SUPERUSER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("PATCH /users/{id}/status harus meneruskan aktor dari token")
    void updateStatus_shouldPassActor() throws Exception {
        when(userService.updateStatus(USER_ID, false, "admin")).thenReturn(
                UserResponse.builder().id(USER_ID).username("merchant.baru").enabled(false)
                        .roles(List.of("MERCHANT")).build());

        mockMvc.perform(patch("/api/v1/users/{id}/status", USER_ID)
                        .principal(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(userService).updateStatus(USER_ID, false, "admin");
    }
}
