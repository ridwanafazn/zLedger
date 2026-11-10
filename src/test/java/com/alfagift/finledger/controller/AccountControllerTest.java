package com.alfagift.finledger.controller;

import com.alfagift.finledger.domain.enums.AccountStatus;
import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.dto.AccountCreateRequest;
import com.alfagift.finledger.dto.AccountResponse;
import com.alfagift.finledger.dto.AccountStatusRequest;
import com.alfagift.finledger.dto.BalanceResponse;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.infrastructure.IdempotencyRejectedException;
import com.alfagift.finledger.infrastructure.exception.GlobalExceptionHandler;
import com.alfagift.finledger.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit test (MockMvc) untuk {@link AccountController}.
 * <p>
 * Memverifikasi kontrak HTTP:
 * <ul>
 *   <li>Create akun 201, validasi 400, header hilang 400</li>
 *   <li>Detail & saldo 200, tidak ditemukan 404</li>
 *   <li>Perubahan status 200, transisi ilegal 400, idempotency duplikat 409</li>
 *   <li>Filter & pagination diteruskan dengan benar ke service</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AccountService accountService;

    @InjectMocks
    private AccountController accountController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(accountController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AccountResponse sampleResponse() {
        return AccountResponse.builder()
                .id(ACCOUNT_ID)
                .ownerId("cust-001")
                .accountName("Dompet Utama")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .balance(new BigDecimal("100000.0000"))
                .status(AccountStatus.ACTIVE)
                .version(0L)
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* Create                                                              */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("POST /accounts harus mengembalikan 201 dan AccountResponse")
    void createAccount_shouldReturn201() throws Exception {
        AccountCreateRequest request = AccountCreateRequest.builder()
                .ownerId("cust-001")
                .accountName("Dompet Utama")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .initialBalance(new BigDecimal("100000.00"))
                .build();

        when(accountService.createAccount(any(AccountCreateRequest.class), eq("idem-001")))
                .thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/ledger/accounts")
                        .header("X-Idempotency-Key", "idem-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.ownerId").value("cust-001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /accounts tanpa X-Idempotency-Key harus mengembalikan 400")
    void createAccount_withoutIdempotencyHeader_shouldReturn400() throws Exception {
        AccountCreateRequest request = AccountCreateRequest.builder()
                .ownerId("cust-001")
                .accountName("Dompet Utama")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .build();

        mockMvc.perform(post("/api/v1/ledger/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"));

        verify(accountService, never()).createAccount(any(), any());
    }

    @Test
    @DisplayName("POST /accounts dengan body tidak valid harus mengembalikan 400")
    void createAccount_invalidBody_shouldReturn400() throws Exception {
        AccountCreateRequest request = AccountCreateRequest.builder()
                .ownerId("")
                .accountName("")
                .build();

        mockMvc.perform(post("/api/v1/ledger/accounts")
                        .header("X-Idempotency-Key", "idem-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /accounts duplikat harus mengembalikan 400 INVALID_REQUEST")
    void createAccount_duplicate_shouldReturn400() throws Exception {
        AccountCreateRequest request = AccountCreateRequest.builder()
                .ownerId("cust-001")
                .accountName("Dompet Utama")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .build();

        when(accountService.createAccount(any(AccountCreateRequest.class), eq("idem-003")))
                .thenThrow(new IllegalArgumentException("Akun untuk ownerId cust-001 sudah ada"));

        mockMvc.perform(post("/api/v1/ledger/accounts")
                        .header("X-Idempotency-Key", "idem-003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    /* ------------------------------------------------------------------ */
    /* Read                                                                */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("GET /accounts/{id} harus mengembalikan 200")
    void getAccount_shouldReturn200() throws Exception {
        when(accountService.getAccount(ACCOUNT_ID)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/ledger/accounts/{id}", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("Dompet Utama"))
                .andExpect(jsonPath("$.currency").value("IDR"));
    }

    @Test
    @DisplayName("GET /accounts/{id} tidak ditemukan harus mengembalikan 404")
    void getAccount_notFound_shouldReturn404() throws Exception {
        when(accountService.getAccount(ACCOUNT_ID))
                .thenThrow(new EntityNotFoundException("Akun tidak ditemukan: " + ACCOUNT_ID));

        mockMvc.perform(get("/api/v1/ledger/accounts/{id}", ACCOUNT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /accounts/{id}/balance harus mengembalikan 200 dan saldo")
    void getBalance_shouldReturn200() throws Exception {
        BalanceResponse balance = BalanceResponse.builder()
                .accountId(ACCOUNT_ID)
                .ownerId("cust-001")
                .currency(Currency.IDR)
                .balance(new BigDecimal("100000.0000"))
                .status(AccountStatus.ACTIVE)
                .asOf(LocalDateTime.now())
                .build();

        when(accountService.getBalance(ACCOUNT_ID)).thenReturn(balance);

        mockMvc.perform(get("/api/v1/ledger/accounts/{id}/balance", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100000.0000))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /* ------------------------------------------------------------------ */
    /* Status                                                              */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("PATCH /accounts/{id}/status harus mengembalikan 200")
    void updateStatus_shouldReturn200() throws Exception {
        AccountStatusRequest request = AccountStatusRequest.builder()
                .status(AccountStatus.SUSPENDED)
                .reason("uji")
                .build();

        AccountResponse suspended = sampleResponse();
        suspended.setStatus(AccountStatus.SUSPENDED);

        when(accountService.updateStatus(eq(ACCOUNT_ID), any(AccountStatusRequest.class), eq("idem-004")))
                .thenReturn(suspended);

        mockMvc.perform(patch("/api/v1/ledger/accounts/{id}/status", ACCOUNT_ID)
                        .header("X-Idempotency-Key", "idem-004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("PATCH status dengan transisi ilegal harus mengembalikan 400")
    void updateStatus_illegalTransition_shouldReturn400() throws Exception {
        AccountStatusRequest request = AccountStatusRequest.builder()
                .status(AccountStatus.SUSPENDED)
                .reason("uji")
                .build();

        when(accountService.updateStatus(eq(ACCOUNT_ID), any(AccountStatusRequest.class), eq("idem-005")))
                .thenThrow(new IllegalArgumentException("Transisi status CLOSED -> SUSPENDED tidak diizinkan"));

        mockMvc.perform(patch("/api/v1/ledger/accounts/{id}/status", ACCOUNT_ID)
                        .header("X-Idempotency-Key", "idem-005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("PATCH status dengan idempotency key duplikat harus mengembalikan 409")
    void updateStatus_duplicateKey_shouldReturn409() throws Exception {
        AccountStatusRequest request = AccountStatusRequest.builder()
                .status(AccountStatus.SUSPENDED)
                .build();

        when(accountService.updateStatus(eq(ACCOUNT_ID), any(AccountStatusRequest.class), eq("idem-006")))
                .thenThrow(new IdempotencyRejectedException("Permintaan duplikat dengan kunci: idem-006"));

        mockMvc.perform(patch("/api/v1/ledger/accounts/{id}/status", ACCOUNT_ID)
                        .header("X-Idempotency-Key", "idem-006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_REJECTED"));
    }

    /* ------------------------------------------------------------------ */
    /* Search                                                              */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("GET /accounts dengan accountType harus meneruskan filter ke service")
    void searchAccounts_shouldForwardFilters() throws Exception {
        PageResponse<AccountResponse> page = PageResponse.<AccountResponse>builder()
                .content(List.of(sampleResponse()))
                .page(PageResponse.PageMetadata.builder()
                        .number(0).size(20).totalElements(1).totalPages(1)
                        .first(true).last(true).numberOfElements(1)
                        .build())
                .sort(List.of(PageResponse.SortInfo.builder()
                        .field("createdAt").direction("DESC").build()))
                .build();

        when(accountService.searchAccounts(any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/ledger/accounts")
                        .param("accountType", "LIABILITY")
                        .param("currency", "IDR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ownerId").value("cust-001"))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        verify(accountService).searchAccounts(
                isNull(), eq("IDR"), eq("LIABILITY"), isNull(), eq(0), eq(20), isNull());
    }

    @Test
    @DisplayName("GET /accounts dengan page & size kustom harus diteruskan apa adanya")
    void searchAccounts_shouldForwardPaging() throws Exception {
        PageResponse<AccountResponse> page = PageResponse.<AccountResponse>builder()
                .content(List.of())
                .page(PageResponse.PageMetadata.builder()
                        .number(2).size(50).totalElements(0).totalPages(0)
                        .first(false).last(true).numberOfElements(0)
                        .build())
                .sort(List.of())
                .build();

        when(accountService.searchAccounts(any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/ledger/accounts")
                        .param("page", "2")
                        .param("size", "50")
                        .param("sort", "balance,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(2));

        verify(accountService).searchAccounts(
                isNull(), isNull(), isNull(), isNull(), eq(2), eq(50), eq("balance,asc"));
    }
}
