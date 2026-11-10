package com.alfagift.finledger.controller;

import com.alfagift.finledger.domain.enums.VoucherStatus;
import com.alfagift.finledger.dto.VoucherRedemptionResponse;
import com.alfagift.finledger.dto.VoucherResponse;
import com.alfagift.finledger.infrastructure.VoucherAlreadyRedeemedException;
import com.alfagift.finledger.infrastructure.exception.GlobalExceptionHandler;
import com.alfagift.finledger.service.VoucherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit test (MockMvc) untuk {@link VoucherController}.
 */
@ExtendWith(MockitoExtension.class)
class VoucherControllerTest {

    private static final UUID VOUCHER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private VoucherService voucherService;

    @InjectMocks
    private VoucherController voucherController;

    private Authentication admin;
    private Authentication customer;

    @BeforeEach
    void setUp() {
        voucherController = new VoucherController(voucherService);
        mockMvc = MockMvcBuilders.standaloneSetup(voucherController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        admin = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        customer = new UsernamePasswordAuthenticationToken(
                "cust-001", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private VoucherResponse sampleVoucher() {
        return VoucherResponse.builder()
                .id(VOUCHER_ID)
                .code("PROMO10")
                .name("Diskon 10rb")
                .pointsCost(new BigDecimal("1000"))
                .totalQuota(10)
                .remainingQuota(10)
                .startsAt(LocalDateTime.now())
                .endsAt(LocalDateTime.now().plusDays(1))
                .status(VoucherStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("POST /vouchers harus 201")
    void create_shouldReturn201() throws Exception {
        when(voucherService.create(any())).thenReturn(sampleVoucher());

        mockMvc.perform(post("/api/v1/vouchers")
                        .principal(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "PROMO10",
                                  "name": "Diskon 10rb",
                                  "pointsCost": 1000,
                                  "totalQuota": 10,
                                  "startsAt": "2026-11-10T00:00:00",
                                  "endsAt": "2026-12-10T00:00:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("PROMO10"))
                .andExpect(jsonPath("$.remainingQuota").value(10));
    }

    @Test
    @DisplayName("POST /vouchers dengan body tidak valid harus 400")
    void create_invalidBody_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/vouchers")
                        .principal(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /vouchers harus 200")
    void search_shouldReturn200() throws Exception {
        when(voucherService.search(any(), any(), eq(0), eq(20))).thenReturn(
                com.alfagift.finledger.dto.PageResponse.<VoucherResponse>builder()
                        .content(List.of(sampleVoucher()))
                        .page(com.alfagift.finledger.dto.PageResponse.PageMetadata.builder()
                                .number(0).size(20).totalElements(1).totalPages(1)
                                .first(true).last(true).numberOfElements(1).build())
                        .sort(List.of())
                        .build());

        mockMvc.perform(get("/api/v1/vouchers").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].code").value("PROMO10"));
    }

    @Test
    @DisplayName("POST /vouchers/{id}/redeem oleh ADMIN untuk owner lain harus 200")
    void redeem_adminTargetingOwner_shouldReturn200() throws Exception {
        when(voucherService.redeem(VOUCHER_ID, "cust-001")).thenReturn(
                VoucherRedemptionResponse.builder()
                        .voucherId(VOUCHER_ID)
                        .voucherCode("PROMO10")
                        .ownerId("cust-001")
                        .transactionId(UUID.randomUUID())
                        .pointsSpent(new BigDecimal("1000"))
                        .remainingQuota(9)
                        .status("REDEEMED")
                        .build());

        mockMvc.perform(post("/api/v1/vouchers/{id}/redeem", VOUCHER_ID)
                        .principal(admin)
                        .header("X-Idempotency-Key", "voucher-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"cust-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REDEEMED"))
                .andExpect(jsonPath("$.remainingQuota").value(9));
    }

    @Test
    @DisplayName("Redeem untuk owner lain oleh non-admin harus 403")
    void redeem_otherOwnerAsCustomer_shouldReturn403() throws Exception {
        mockMvc.perform(post("/api/v1/vouchers/{id}/redeem", VOUCHER_ID)
                        .principal(customer)
                        .header("X-Idempotency-Key", "voucher-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"cust-lain\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("Redeem ganda harus 409")
    void redeem_duplicate_shouldReturn409() throws Exception {
        when(voucherService.redeem(VOUCHER_ID, "cust-001"))
                .thenThrow(new VoucherAlreadyRedeemedException("sudah pernah redeem"));

        mockMvc.perform(post("/api/v1/vouchers/{id}/redeem", VOUCHER_ID)
                        .principal(customer)
                        .header("X-Idempotency-Key", "voucher-3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("Redeem tanpa X-Idempotency-Key harus 400")
    void redeem_withoutIdempotencyKey_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/vouchers/{id}/redeem", VOUCHER_ID)
                        .principal(customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"));

        verify(voucherService, org.mockito.Mockito.never()).redeem(any(), any());
    }
}
