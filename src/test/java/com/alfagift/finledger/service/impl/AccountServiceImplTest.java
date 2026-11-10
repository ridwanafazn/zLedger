package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.enums.AccountStatus;
import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.dto.AccountCreateRequest;
import com.alfagift.finledger.dto.AccountResponse;
import com.alfagift.finledger.dto.AccountStatusRequest;
import com.alfagift.finledger.dto.BalanceResponse;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import com.alfagift.finledger.repository.AccountRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit test untuk {@link AccountServiceImpl}.
 * <p>
 * Cakupan:
 * <ul>
 *   <li>Create akun: sukses, duplikat, saldo awal default</li>
 *   <li>Read akun: detail & saldo, termasuk kasus tidak ditemukan (404)</li>
 *   <li>State machine status: transisi legal & ilegal</li>
 *   <li>Validasi kecukupan saldo</li>
 *   <li>Pagination, clamping, dan whitelist sorting</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String OWNER_ID = "cust-001";

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private AccountServiceImpl accountService;

    private Account account;

    @BeforeEach
    void setUp() {
        account = Account.builder()
                .id(ACCOUNT_ID)
                .ownerId(OWNER_ID)
                .accountName("Dompet Utama")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .balance(new BigDecimal("250000.0000"))
                .status(AccountStatus.ACTIVE)
                .version(0L)
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* Create                                                              */
    /* ------------------------------------------------------------------ */

    @Nested
    @DisplayName("createAccount")
    class CreateAccount {

        @Test
        @DisplayName("Harus membuat akun ACTIVE dengan saldo awal dari request")
        void shouldCreateAccountWithInitialBalance() {
            // Given
            AccountCreateRequest request = AccountCreateRequest.builder()
                    .ownerId(OWNER_ID)
                    .accountName("Dompet Baru")
                    .accountType(AccountType.LIABILITY)
                    .currency(Currency.IDR)
                    .initialBalance(new BigDecimal("100000.00"))
                    .build();

            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(OWNER_ID, Currency.IDR))
                    .thenReturn(Optional.empty());
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            AccountResponse response = accountService.createAccount(request, "idem-create-1");

            // Then
            assertThat(response.getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(response.getAccountName()).isEqualTo("Dompet Baru");
            assertThat(response.getAccountType()).isEqualTo(AccountType.LIABILITY);
            assertThat(response.getCurrency()).isEqualTo(Currency.IDR);
            assertThat(response.getBalance()).isEqualByComparingTo("100000.00");
            assertThat(response.getStatus()).isEqualTo(AccountStatus.ACTIVE);

            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("Harus memakai saldo nol jika initialBalance null")
        void shouldDefaultInitialBalanceToZero() {
            // Given
            AccountCreateRequest request = AccountCreateRequest.builder()
                    .ownerId(OWNER_ID)
                    .accountName("Dompet Nol")
                    .accountType(AccountType.ASSET)
                    .currency(Currency.A_POIN)
                    .initialBalance(null)
                    .build();

            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(OWNER_ID, Currency.A_POIN))
                    .thenReturn(Optional.empty());
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            AccountResponse response = accountService.createAccount(request, "idem-create-2");

            // Then
            assertThat(response.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Harus menolak akun duplikat (ownerId + currency) tanpa menyimpan")
        void shouldRejectDuplicateAccount() {
            // Given
            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(OWNER_ID, Currency.IDR))
                    .thenReturn(Optional.of(account));

            AccountCreateRequest request = AccountCreateRequest.builder()
                    .ownerId(OWNER_ID)
                    .accountName("Dompet Duplikat")
                    .accountType(AccountType.LIABILITY)
                    .currency(Currency.IDR)
                    .build();

            // When & Then
            assertThatThrownBy(() -> accountService.createAccount(request, "idem-create-3"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sudah ada");

            verify(accountRepository, never()).save(any(Account.class));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Read                                                                */
    /* ------------------------------------------------------------------ */

    @Nested
    @DisplayName("getAccount / getBalance")
    class ReadAccount {

        @Test
        @DisplayName("Harus mengembalikan detail akun yang ditemukan")
        void shouldReturnAccountDetail() {
            // Given
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

            // When
            AccountResponse response = accountService.getAccount(ACCOUNT_ID);

            // Then
            assertThat(response.getId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(response.getVersion()).isZero();
        }

        @Test
        @DisplayName("Harus melempar EntityNotFoundException (404) jika akun tidak ditemukan")
        void shouldThrowWhenAccountNotFound() {
            // Given
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> accountService.getAccount(ACCOUNT_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Akun tidak ditemukan");
        }

        @Test
        @DisplayName("Harus mengembalikan saldo beserta status dan asOf")
        void shouldReturnBalance() {
            // Given
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

            // When
            BalanceResponse response = accountService.getBalance(ACCOUNT_ID);

            // Then
            assertThat(response.getAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(response.getBalance()).isEqualByComparingTo("250000.0000");
            assertThat(response.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(response.getAsOf()).isNotNull();
        }

        @Test
        @DisplayName("Harus melempar EntityNotFoundException saat inquiry saldo akun tak ada")
        void shouldThrowWhenBalanceAccountNotFound() {
            // Given
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> accountService.getBalance(ACCOUNT_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    /* ------------------------------------------------------------------ */
    /* State machine                                                       */
    /* ------------------------------------------------------------------ */

    @Nested
    @DisplayName("updateStatus — state machine")
    class UpdateStatus {

        private void givenStatus(AccountStatus current) {
            account.setStatus(current);
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
            lenient().when(accountRepository.save(any(Account.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("ACTIVE -> SUSPENDED harus berhasil")
        void activeToSuspended() {
            givenStatus(AccountStatus.ACTIVE);

            AccountResponse response = accountService.updateStatus(
                    ACCOUNT_ID, request(AccountStatus.SUSPENDED), "key-1");

            assertThat(response.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
            verify(accountRepository).save(account);
        }

        @Test
        @DisplayName("SUSPENDED -> ACTIVE harus berhasil")
        void suspendedToActive() {
            givenStatus(AccountStatus.SUSPENDED);

            AccountResponse response = accountService.updateStatus(
                    ACCOUNT_ID, request(AccountStatus.ACTIVE), "key-2");

            assertThat(response.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("ACTIVE -> CLOSED harus berhasil")
        void activeToClosed() {
            givenStatus(AccountStatus.ACTIVE);

            AccountResponse response = accountService.updateStatus(
                    ACCOUNT_ID, request(AccountStatus.CLOSED), "key-3");

            assertThat(response.getStatus()).isEqualTo(AccountStatus.CLOSED);
        }

        @Test
        @DisplayName("CLOSED -> ACTIVE (reopen) harus berhasil")
        void closedToActive() {
            givenStatus(AccountStatus.CLOSED);

            AccountResponse response = accountService.updateStatus(
                    ACCOUNT_ID, request(AccountStatus.ACTIVE), "key-4");

            assertThat(response.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("CLOSED -> SUSPENDED harus ditolak (400) tanpa menyimpan")
        void closedToSuspendedShouldBeRejected() {
            givenStatus(AccountStatus.CLOSED);

            assertThatThrownBy(() -> accountService.updateStatus(
                    ACCOUNT_ID, request(AccountStatus.SUSPENDED), "key-5"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tidak diizinkan");

            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Status sama dengan status saat ini harus diizinkan (idempotent)")
        void sameStatusShouldBeAllowed() {
            givenStatus(AccountStatus.ACTIVE);

            assertThatCode(() -> accountService.updateStatus(
                    ACCOUNT_ID, request(AccountStatus.ACTIVE), "key-6"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Harus melempar EntityNotFoundException (404) jika akun tak ada")
        void shouldThrowWhenAccountNotFound() {
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.updateStatus(
                    ACCOUNT_ID, request(AccountStatus.SUSPENDED), "key-7"))
                    .isInstanceOf(EntityNotFoundException.class);

            verify(accountRepository, never()).save(any(Account.class));
        }

        private AccountStatusRequest request(AccountStatus status) {
            return AccountStatusRequest.builder()
                    .status(status)
                    .reason("uji state machine")
                    .build();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Balance validation                                                  */
    /* ------------------------------------------------------------------ */

    @Nested
    @DisplayName("validateSufficientBalance")
    class ValidateBalance {

        @Test
        @DisplayName("Saldo cukup (termasuk tepat sama) tidak melempar exception")
        void sufficientBalanceShouldPass() {
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

            assertThatCode(() -> accountService.validateSufficientBalance(
                    ACCOUNT_ID, new BigDecimal("250000.0000")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Saldo kurang harus melempar InsufficientBalanceException")
        void insufficientBalanceShouldThrow() {
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.validateSufficientBalance(
                    ACCOUNT_ID, new BigDecimal("250000.0001")))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .hasMessageContaining("tidak mencukupi");
        }

        @Test
        @DisplayName("Akun tidak ditemukan harus melempar EntityNotFoundException")
        void accountNotFoundShouldThrow() {
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.validateSufficientBalance(
                    ACCOUNT_ID, BigDecimal.TEN))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Search & pagination                                                 */
    /* ------------------------------------------------------------------ */

    @Nested
    @DisplayName("searchAccounts")
    class SearchAccounts {

        @SuppressWarnings("unchecked")
        private void givenPage(Pageable pageable) {
            Page<Account> page = new PageImpl<>(List.of(account), pageable, 1);
            when(accountRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(page);
        }

        private Pageable capturePageable() {
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(accountRepository).findAll(any(Specification.class), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("Tanpa sort harus memakai default createdAt DESC")
        void shouldUseDefaultSort() {
            givenPage(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

            PageResponse<AccountResponse> response = accountService.searchAccounts(
                    null, null, null, null, 0, 20, null);

            Pageable pageable = capturePageable();
            assertThat(pageable.getSort().getOrderFor("createdAt")).isNotNull();
            assertThat(pageable.getSort().getOrderFor("createdAt").getDirection())
                    .isEqualTo(Sort.Direction.DESC);
            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getPage().getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Sort field yang di-whitelist harus diterapkan")
        void shouldApplyWhitelistedSort() {
            givenPage(PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "balance")));

            accountService.searchAccounts(null, null, null, null, 0, 20, "balance,asc");

            Pageable pageable = capturePageable();
            assertThat(pageable.getSort().getOrderFor("balance").getDirection())
                    .isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("Sort field tidak dikenal harus jatuh ke default createdAt DESC")
        void shouldFallbackForUnknownSortField() {
            givenPage(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

            accountService.searchAccounts(null, null, null, null, 0, 20, "password,asc");

            Pageable pageable = capturePageable();
            assertThat(pageable.getSort().getOrderFor("password")).isNull();
            assertThat(pageable.getSort().getOrderFor("createdAt")).isNotNull();
        }

        @Test
        @DisplayName("Size > 100 harus di-clamp menjadi 100 dan page negatif menjadi 0")
        void shouldClampPagination() {
            givenPage(PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt")));

            accountService.searchAccounts(null, null, null, null, -3, 500, null);

            Pageable pageable = capturePageable();
            assertThat(pageable.getPageNumber()).isZero();
            assertThat(pageable.getPageSize()).isEqualTo(100);
        }

        @Test
        @DisplayName("Filter harus diteruskan ke specification (accountType, currency, status)")
        void shouldBuildSpecificationWithAllFilters() {
            givenPage(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

            accountService.searchAccounts(OWNER_ID, "IDR", "LIABILITY", "ACTIVE", 0, 20, null);

            // Specification dibangun dan dipakai; nilai enum valid tidak boleh melempar exception
            verify(accountRepository).findAll(any(Specification.class), any(Pageable.class));
        }
    }
}
