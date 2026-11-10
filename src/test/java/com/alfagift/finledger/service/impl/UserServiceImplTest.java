package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.AppUser;
import com.alfagift.finledger.domain.enums.UserRole;
import com.alfagift.finledger.dto.RegisterRequest;
import com.alfagift.finledger.dto.UserCreateRequest;
import com.alfagift.finledger.infrastructure.DuplicateUserException;
import com.alfagift.finledger.infrastructure.security.RefreshTokenService;
import com.alfagift.finledger.infrastructure.security.TokenRevocationService;
import com.alfagift.finledger.repository.AppUserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test untuk {@link UserServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private TokenRevocationService tokenRevocationService;

    @InjectMocks
    private UserServiceImpl userService;

    @Nested
    @DisplayName("register")
    class Register {

        @BeforeEach
        void setUpEncoder() {
            org.mockito.Mockito.lenient().when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            org.mockito.Mockito.lenient().when(appUserRepository.save(any(AppUser.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("Registrasi publik selalu membuat CUSTOMER dan menormalisasi username/email")
        void shouldCreateCustomerWithNormalizedIdentity() {
            RegisterRequest request = RegisterRequest.builder()
                    .username("  Budi.Santoso ")
                    .password("rahasia123")
                    .email("  Budi@Example.COM ")
                    .fullName("Budi Santoso")
                    .build();

            var response = userService.register(request);

            assertThat(response.getUsername()).isEqualTo("budi.santoso");
            assertThat(response.getEmail()).isEqualTo("budi@example.com");
            assertThat(response.getRoles()).containsExactly("CUSTOMER");
            assertThat(response.isEnabled()).isTrue();

            ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
            verify(appUserRepository).save(captor.capture());
            assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$10$hashed");
            assertThat(captor.getValue().getRoles()).containsExactly(UserRole.CUSTOMER);
        }

        @Test
        @DisplayName("Username duplikat harus 409 tanpa menyimpan apa pun")
        void duplicateUsername_shouldBeRejected() {
            when(appUserRepository.existsByUsername("budi")).thenReturn(true);

            RegisterRequest request = RegisterRequest.builder()
                    .username("budi")
                    .password("rahasia123")
                    .build();

            assertThatThrownBy(() -> userService.register(request))
                    .isInstanceOf(DuplicateUserException.class)
                    .hasMessageContaining("Username");

            verify(appUserRepository, never()).save(any(AppUser.class));
        }

        @Test
        @DisplayName("Email duplikat harus 409")
        void duplicateEmail_shouldBeRejected() {
            when(appUserRepository.existsByUsername("budi")).thenReturn(false);
            when(appUserRepository.existsByEmail("budi@example.com")).thenReturn(true);

            RegisterRequest request = RegisterRequest.builder()
                    .username("budi")
                    .password("rahasia123")
                    .email("budi@example.com")
                    .build();

            assertThatThrownBy(() -> userService.register(request))
                    .isInstanceOf(DuplicateUserException.class)
                    .hasMessageContaining("Email");

            verify(appUserRepository, never()).save(any(AppUser.class));
        }

        @Test
        @DisplayName("Email kosong disimpan sebagai null, bukan string kosong")
        void blankEmail_shouldBeStoredAsNull() {
            RegisterRequest request = RegisterRequest.builder()
                    .username("budi")
                    .password("rahasia123")
                    .email("   ")
                    .build();

            var response = userService.register(request);

            assertThat(response.getEmail()).isNull();
            verify(appUserRepository, never()).existsByEmail(anyString());
        }
    }

    @Nested
    @DisplayName("create (admin)")
    class CreateByAdmin {

        @BeforeEach
        void setUpEncoder() {
            org.mockito.Mockito.lenient().when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
            org.mockito.Mockito.lenient().when(appUserRepository.save(any(AppUser.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("Admin dapat membuat user dengan banyak role")
        void shouldCreateUserWithGivenRoles() {
            UserCreateRequest request = UserCreateRequest.builder()
                    .username("merchant.baru")
                    .password("rahasia123")
                    .roles(Set.of(UserRole.MERCHANT, UserRole.CUSTOMER))
                    .build();

            var response = userService.create(request, "idem-1");

            assertThat(response.getRoles()).containsExactly("CUSTOMER", "MERCHANT");
        }
    }

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatus {

        private AppUser user;

        @BeforeEach
        void setUpUser() {
            user = AppUser.builder()
                    .id(USER_ID)
                    .username("budi")
                    .passwordHash("hash")
                    .enabled(true)
                    .roles(Set.of(UserRole.CUSTOMER))
                    .build();
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            org.mockito.Mockito.lenient().when(appUserRepository.save(any(AppUser.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("Menonaktifkan user harus mencabut refresh token dan access token")
        void disable_shouldRevokeAllTokens() {
            var response = userService.updateStatus(USER_ID, false, "admin");

            assertThat(response.isEnabled()).isFalse();
            verify(refreshTokenService).revokeAll("budi");
            verify(tokenRevocationService).revoke("budi");
        }

        @Test
        @DisplayName("Mengaktifkan user harus membersihkan daftar cabut")
        void enable_shouldClearRevocation() {
            user.setEnabled(false);

            var response = userService.updateStatus(USER_ID, true, "admin");

            assertThat(response.isEnabled()).isTrue();
            verify(tokenRevocationService).clear("budi");
            verify(refreshTokenService, never()).revokeAll(anyString());
        }

        @Test
        @DisplayName("Admin tidak boleh menonaktifkan akunnya sendiri")
        void selfDisable_shouldBeRejected() {
            assertThatThrownBy(() -> userService.updateStatus(USER_ID, false, "BUDI"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendiri");
        }

        @Test
        @DisplayName("User tidak ditemukan harus 404")
        void missingUser_shouldThrow() {
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.updateStatus(USER_ID, false, "admin"))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("Pagination dan mapping role harus benar")
        void shouldMapPage() {
            AppUser user = AppUser.builder()
                    .id(USER_ID)
                    .username("budi")
                    .enabled(true)
                    .roles(Set.of(UserRole.CUSTOMER, UserRole.ADMIN))
                    .build();

            when(appUserRepository.findAll(any(Specification.class), any(PageRequest.class)))
                    .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));

            var response = userService.search(null, null, null, 0, 20);

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().get(0).getRoles()).containsExactly("ADMIN", "CUSTOMER");
            assertThat(response.getPage().getTotalElements()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("read")
    class Read {

        @Test
        @DisplayName("getByUsername menormalisasi input")
        void shouldNormalizeUsername() {
            AppUser user = AppUser.builder().username("budi").roles(Set.of(UserRole.CUSTOMER)).build();
            when(appUserRepository.findByUsername("budi")).thenReturn(Optional.of(user));

            var response = userService.getByUsername(" BUDI ");

            assertThat(response.getUsername()).isEqualTo("budi");
        }

        @Test
        @DisplayName("getById tidak ditemukan harus 404")
        void missingId_shouldThrow() {
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.getById(USER_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }
}
