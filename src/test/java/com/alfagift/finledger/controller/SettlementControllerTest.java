package com.alfagift.finledger.controller;

import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.SettlementStatus;
import com.alfagift.finledger.dto.SettlementPreviewResponse;
import com.alfagift.finledger.dto.SettlementResponse;
import com.alfagift.finledger.infrastructure.ResourceConflictException;
import com.alfagift.finledger.infrastructure.exception.GlobalExceptionHandler;
import com.alfagift.finledger.service.SettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit test (MockMvc) untuk {@link SettlementController}: kontrak HTTP,
 * validasi header idempotency, dan pemetaan konflik ke 409.
 */
@ExtendWith(MockitoExtension.class)
class SettlementControllerTest {

    private static final UUID SETTLEMENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SettlementService settlementService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SettlementController(settlementService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private SettlementResponse sampleSettlement() {
        return SettlementResponse.builder()
                .id(SETTLEMENT_ID)
                .merchantId("merchant-001")
                .currency(Currency.IDR)
                .periodStart(LocalDateTime.of(2026, 1, 1, 0, 0))
                .periodEnd(LocalDateTime.of(2026, 2, 1, 0, 0))
                .grossAmount(new BigDecimal("1000.0000"))
                .refundAmount(new BigDecimal("100.0000"))
                .feePercent(new BigDecimal("1.0000"))
                .feeAmount(new BigDecimal("9.0000"))
                .netAmount(new BigDecimal("891.0000"))
                .status(SettlementStatus.PENDING)
                .itemCount(2)
                .build();
    }

    @Test
    @DisplayName("POST /settlements tanpa X-Idempotency-Key harus 400")
    void createWithoutIdempotencyHeaderShouldFail() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "merchantId", "merchant-001",
                "currency", "IDR",
                "periodStart", "2026-01-01T00:00:00",
                "periodEnd", "2026-02-01T00:00:00"));

        mockMvc.perform(post("/api/v1/settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"));
    }

    @Test
    @DisplayName("POST /settlements harus mengembalikan 201 beserta ringkasan batch")
    void createShouldReturn201() throws Exception {
        when(settlementService.create(any(), eq("IDEM-1"))).thenReturn(sampleSettlement());

        String body = objectMapper.writeValueAsString(Map.of(
                "merchantId", "merchant-001",
                "currency", "IDR",
                "periodStart", "2026-01-01T00:00:00",
                "periodEnd", "2026-02-01T00:00:00"));

        mockMvc.perform(post("/api/v1/settlements")
                        .header("X-Idempotency-Key", "IDEM-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.netAmount").value(891.0000));
    }

    @Test
    @DisplayName("Periode bertumpuk harus dipetakan ke 409 CONFLICT")
    void overlappingPeriodShouldReturn409() throws Exception {
        when(settlementService.create(any(), eq("IDEM-2")))
                .thenThrow(new ResourceConflictException("Periode bertumpuk"));

        String body = objectMapper.writeValueAsString(Map.of(
                "merchantId", "merchant-001",
                "currency", "IDR",
                "periodStart", "2026-01-01T00:00:00",
                "periodEnd", "2026-02-01T00:00:00"));

        mockMvc.perform(post("/api/v1/settlements")
                        .header("X-Idempotency-Key", "IDEM-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("POST body tidak valid harus 400 VALIDATION_ERROR")
    void invalidBodyShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/settlements")
                        .header("X-Idempotency-Key", "IDEM-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"IDR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /settlements/preview meneruskan parameter periode dan fee")
    void previewShouldForwardParameters() throws Exception {
        when(settlementService.preview(eq("merchant-001"), eq(Currency.IDR), any(), any(), any()))
                .thenReturn(SettlementPreviewResponse.builder()
                        .merchantId("merchant-001")
                        .currency(Currency.IDR)
                        .grossAmount(new BigDecimal("1000.0000"))
                        .refundAmount(new BigDecimal("0.0000"))
                        .feePercent(new BigDecimal("1.0000"))
                        .feeAmount(new BigDecimal("10.0000"))
                        .netAmount(new BigDecimal("990.0000"))
                        .paymentCount(1)
                        .payable(true)
                        .build());

        mockMvc.perform(get("/api/v1/settlements/preview")
                        .param("merchantId", "merchant-001")
                        .param("from", "2026-01-01T00:00:00")
                        .param("to", "2026-02-01T00:00:00")
                        .param("feePercent", "1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netAmount").value(990.0000))
                .andExpect(jsonPath("$.payable").value(true));
    }

    @Test
    @DisplayName("POST /settlements/{id}/pay harus mengembalikan status PAID")
    void payShouldReturnPaidStatus() throws Exception {
        SettlementResponse paid = sampleSettlement();
        paid.setStatus(SettlementStatus.PAID);
        paid.setPayoutTransactionId(UUID.randomUUID());
        when(settlementService.pay(SETTLEMENT_ID, "PAY-1")).thenReturn(paid);

        mockMvc.perform(post("/api/v1/settlements/" + SETTLEMENT_ID + "/pay")
                        .header("X-Idempotency-Key", "PAY-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payoutTransactionId").exists());
    }

    @Test
    @DisplayName("POST /settlements/{id}/cancel tanpa body harus tetap 200")
    void cancelWithoutBodyShouldSucceed() throws Exception {
        SettlementResponse cancelled = sampleSettlement();
        cancelled.setStatus(SettlementStatus.CANCELLED);
        when(settlementService.cancel(SETTLEMENT_ID, null)).thenReturn(cancelled);

        mockMvc.perform(post("/api/v1/settlements/" + SETTLEMENT_ID + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("GET /settlements/{id} harus mengembalikan detail batch")
    void getShouldReturnDetail() throws Exception {
        when(settlementService.get(SETTLEMENT_ID)).thenReturn(sampleSettlement());

        mockMvc.perform(get("/api/v1/settlements/" + SETTLEMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value("merchant-001"))
                .andExpect(jsonPath("$.itemCount").value(2));
    }
}
