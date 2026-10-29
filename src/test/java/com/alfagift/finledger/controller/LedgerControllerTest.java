package com.alfagift.finledger.controller;

import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.dto.CheckoutRequest;
import com.alfagift.finledger.dto.CheckoutRequest.TenderDetail;
import com.alfagift.finledger.dto.CheckoutResponse;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import com.alfagift.finledger.infrastructure.IdempotencyRejectedException;
import com.alfagift.finledger.infrastructure.exception.GlobalExceptionHandler;
import com.alfagift.finledger.service.LedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit test untuk {@link LedgerController}.
 * <p>
 * Memverifikasi:
 * <ul>
 *   <li>Endpoint /checkout mengembalikan 200 dan respons yang betul</li>
 *   <li>Endpoint /redeem mengembalikan 200</li>
 *   <li>Endpoint /accounts/{id}/mutations mengembalikan daftar mutasi</li>
 *   <li>Penanganan InsufficientBalanceException → 400</li>
 *   <li>Penanganan IdempotencyRejectedException → 409</li>
 *   <li>Validasi request gagal → 400</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LedgerControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private LedgerService ledgerService;

    @InjectMocks
    private LedgerController ledgerController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(ledgerController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /checkout harus mengembalikan 200 dan CheckoutResponse")
    void checkout_shouldReturn200AndResponse() throws Exception {
        // Given
        CheckoutResponse response = CheckoutResponse.builder()
                .transactionId(UUID.randomUUID())
                .ownerId("cust-001")
                .merchantId("merch-001")
                .currency(Currency.IDR)
                .totalAmount(new BigDecimal("50000.00"))
                .balanceRemaining(new BigDecimal("50000.00"))
                .status("SUCCESS")
                .build();

        CheckoutRequest request = CheckoutRequest.builder()
                .ownerId("cust-001")
                .merchantId("merch-001")
                .totalAmount(new BigDecimal("50000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("idem-001")
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("50000.00"))
                        .description("Pembelian")
                        .build()))
                .build();

        when(ledgerService.processMultiTenderPayment(any(CheckoutRequest.class))).thenReturn(response);

        // When & Then
        mockMvc.perform(post("/api/v1/ledger/checkout")
                        .header("X-Idempotency-Key", "idem-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.totalAmount").value(50000.00))
                .andExpect(jsonPath("$.currency").value("IDR"))
                .andExpect(jsonPath("$.transactionId").exists());
    }

    @Test
    @DisplayName("POST /redeem harus mengembalikan 200")
    void redeem_shouldReturn200() throws Exception {
        // Given
        CheckoutResponse response = CheckoutResponse.builder()
                .transactionId(UUID.randomUUID())
                .ownerId("cust-001")
                .merchantId("system")
                .currency(Currency.A_POIN)
                .totalAmount(new BigDecimal("10000.00"))
                .balanceRemaining(new BigDecimal("90000.00"))
                .status("SUCCESS")
                .build();

        when(ledgerService.redeemFlashSale(anyString(), any(java.util.UUID.class), any(Integer.class), anyString()))
                .thenReturn(response);

        // When & Then
        mockMvc.perform(post("/api/v1/ledger/redeem")
                        .param("ownerId", "cust-001")
                        .param("voucherId", UUID.randomUUID().toString())
                        .param("points", "10000")
                        .param("idempotencyKey", "idem-redeem-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.currency").value("A_POIN"));
    }

    @Test
    @DisplayName("GET /accounts/{id}/mutations harus mengembalikan daftar mutasi")
    void getMutations_shouldReturnListOfLedgerEntries() throws Exception {
        // Given
        UUID accountId = UUID.randomUUID();
        com.alfagift.finledger.domain.entities.LedgerEntry entry =
                com.alfagift.finledger.domain.entities.LedgerEntry.builder()
                        .id(UUID.randomUUID())
                        .amount(new BigDecimal("50000.00"))
                        .build();

        when(ledgerService.getAccountMutations(accountId)).thenReturn(List.of(entry));

        // When & Then
        mockMvc.perform(get("/api/v1/ledger/accounts/{accountId}/mutations", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(50000.00));
    }

    @Test
    @DisplayName("POST /checkout dengan balance tidak mencukupi harus mengembalikan 400")
    void checkout_insufficientBalance_shouldReturn400() throws Exception {
        // Given
        CheckoutRequest request = CheckoutRequest.builder()
                .ownerId("cust-001")
                .merchantId("merch-001")
                .totalAmount(new BigDecimal("50000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("idem-001")
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("50000.00"))
                        .description("Pembelian")
                        .build()))
                .build();

        when(ledgerService.processMultiTenderPayment(any(CheckoutRequest.class)))
                .thenThrow(new InsufficientBalanceException("Balans tidak mencukupi"));

        // When & Then
        mockMvc.perform(post("/api/v1/ledger/checkout")
                        .header("X-Idempotency-Key", "idem-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BALANCE_INSUFFICIENT"));
    }

    @Test
    @DisplayName("POST /checkout dengan duplicate idempotency key harus mengembalikan 409")
    void checkout_duplicateIdempotency_shouldReturn409() throws Exception {
        // Given
        CheckoutRequest request = CheckoutRequest.builder()
                .ownerId("cust-001")
                .merchantId("merch-001")
                .totalAmount(new BigDecimal("50000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("idem-duplicate")
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("50000.00"))
                        .description("Pembelian")
                        .build()))
                .build();

        when(ledgerService.processMultiTenderPayment(any(CheckoutRequest.class)))
                .thenThrow(new IdempotencyRejectedException("Permintaan duplikat"));

        // When & Then
        mockMvc.perform(post("/api/v1/ledger/checkout")
                        .header("X-Idempotency-Key", "idem-duplicate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_REJECTED"));
    }

    @Test
    @DisplayName("POST /checkout dengan request body invalid harus mengembalikan 400")
    void checkout_invalidRequest_shouldReturn400() throws Exception {
        // Given - missing required fields
        String invalidJson = "{}";

        // When & Then
        mockMvc.perform(post("/api/v1/ledger/checkout")
                        .header("X-Idempotency-Key", "idem-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}