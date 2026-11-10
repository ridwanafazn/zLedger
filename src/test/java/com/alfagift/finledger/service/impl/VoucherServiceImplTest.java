package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Voucher;
import com.alfagift.finledger.domain.entities.VoucherRedemption;
import com.alfagift.finledger.domain.enums.VoucherStatus;
import com.alfagift.finledger.dto.CheckoutResponse;
import com.alfagift.finledger.dto.VoucherCreateRequest;
import com.alfagift.finledger.dto.VoucherRedemptionResponse;
import com.alfagift.finledger.infrastructure.VoucherAlreadyRedeemedException;
import com.alfagift.finledger.repository.VoucherRedemptionRepository;
import com.alfagift.finledger.repository.VoucherRepository;
import com.alfagift.finledger.service.LedgerService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test untuk {@link VoucherServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class VoucherServiceImplTest {

    private static final UUID VOUCHER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String OWNER_ID = "cust-001";

    @Mock
    private VoucherRepository voucherRepository;

    @Mock
    private VoucherRedemptionRepository voucherRedemptionRepository;

    @Mock
    private LedgerService ledgerService;

    @InjectMocks
    private VoucherServiceImpl voucherService;

    private Voucher activeVoucher;

    @BeforeEach
    void setUp() {
        activeVoucher = Voucher.builder()
                .id(VOUCHER_ID)
                .code("PROMO10")
                .name("Diskon 10rb")
                .pointsCost(new BigDecimal("1000"))
                .totalQuota(10)
                .remainingQuota(10)
                .startsAt(LocalDateTime.now().minusDays(1))
                .endsAt(LocalDateTime.now().plusDays(1))
                .status(VoucherStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("Harus membuat voucher DRAFT dengan sisa kuota = total kuota")
        void shouldCreateDraftVoucher() {
            VoucherCreateRequest request = VoucherCreateRequest.builder()
                    .code("PROMO20")
                    .name("Promo 20")
                    .pointsCost(new BigDecimal("2000"))
                    .totalQuota(5)
                    .startsAt(LocalDateTime.now())
                    .endsAt(LocalDateTime.now().plusDays(2))
                    .build();

            when(voucherRepository.existsByCode("PROMO20")).thenReturn(false);
            when(voucherRepository.save(any(Voucher.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var response = voucherService.create(request);

            assertThat(response.getStatus()).isEqualTo(VoucherStatus.DRAFT);
            assertThat(response.getRemainingQuota()).isEqualTo(5);
            assertThat(response.getTotalQuota()).isEqualTo(5);
        }

        @Test
        @DisplayName("Kode duplikat harus ditolak")
        void duplicateCode_shouldBeRejected() {
            when(voucherRepository.existsByCode("PROMO10")).thenReturn(true);

            VoucherCreateRequest request = VoucherCreateRequest.builder()
                    .code("PROMO10")
                    .name("Duplikat")
                    .pointsCost(new BigDecimal("1000"))
                    .totalQuota(1)
                    .startsAt(LocalDateTime.now())
                    .endsAt(LocalDateTime.now().plusDays(1))
                    .build();

            assertThatThrownBy(() -> voucherService.create(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sudah ada");

            verify(voucherRepository, never()).save(any(Voucher.class));
        }

        @Test
        @DisplayName("Periode tidak valid (endsAt <= startsAt) harus ditolak")
        void invalidPeriod_shouldBeRejected() {
            when(voucherRepository.existsByCode("BAD")).thenReturn(false);
            LocalDateTime now = LocalDateTime.now();

            VoucherCreateRequest request = VoucherCreateRequest.builder()
                    .code("BAD")
                    .name("Periode salah")
                    .pointsCost(new BigDecimal("1000"))
                    .totalQuota(1)
                    .startsAt(now)
                    .endsAt(now.minusMinutes(1))
                    .build();

            assertThatThrownBy(() -> voucherService.create(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endsAt");
        }

        @Test
        @DisplayName("Harga poin desimal harus ditolak")
        void fractionalPointsCost_shouldBeRejected() {
            when(voucherRepository.existsByCode("FRAC")).thenReturn(false);
            LocalDateTime now = LocalDateTime.now();

            VoucherCreateRequest request = VoucherCreateRequest.builder()
                    .code("FRAC")
                    .name("Pecahan")
                    .pointsCost(new BigDecimal("10.5"))
                    .totalQuota(1)
                    .startsAt(now)
                    .endsAt(now.plusDays(1))
                    .build();

            assertThatThrownBy(() -> voucherService.create(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bilangan bulat");
        }
    }

    @Nested
    @DisplayName("redeem")
    class Redeem {

        @Test
        @DisplayName("Redeem sukses harus mengurangi kuota dan mencatat riwayat")
        void successfulRedeem_shouldDecrementQuota() {
            when(voucherRepository.findByIdForUpdate(VOUCHER_ID)).thenReturn(Optional.of(activeVoucher));
            when(voucherRedemptionRepository.existsByVoucherIdAndOwnerId(VOUCHER_ID, OWNER_ID)).thenReturn(false);
            when(ledgerService.redeemFlashSale(eq(OWNER_ID), eq(VOUCHER_ID), eq(1000), anyString()))
                    .thenReturn(CheckoutResponse.builder()
                            .transactionId(UUID.randomUUID())
                            .ownerId(OWNER_ID)
                            .status("SUCCESS")
                            .build());
            when(voucherRepository.save(any(Voucher.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            VoucherRedemptionResponse response = voucherService.redeem(VOUCHER_ID, OWNER_ID);

            assertThat(response.getStatus()).isEqualTo("REDEEMED");
            assertThat(response.getRemainingQuota()).isEqualTo(9);
            assertThat(response.getVoucherCode()).isEqualTo("PROMO10");
            assertThat(response.getTransactionId()).isNotNull();

            ArgumentCaptor<VoucherRedemption> captor = ArgumentCaptor.forClass(VoucherRedemption.class);
            verify(voucherRedemptionRepository).save(captor.capture());
            assertThat(captor.getValue().getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(captor.getValue().getPointsSpent()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("Voucher tidak aktif harus ditolak")
        void inactiveVoucher_shouldBeRejected() {
            activeVoucher.setStatus(VoucherStatus.INACTIVE);
            when(voucherRepository.findByIdForUpdate(VOUCHER_ID)).thenReturn(Optional.of(activeVoucher));

            assertThatThrownBy(() -> voucherService.redeem(VOUCHER_ID, OWNER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tidak dapat diredeem");

            verify(ledgerService, never()).redeemFlashSale(anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Voucher kedaluwarsa harus ditolak")
        void expiredVoucher_shouldBeRejected() {
            activeVoucher.setEndsAt(LocalDateTime.now().minusMinutes(1));
            when(voucherRepository.findByIdForUpdate(VOUCHER_ID)).thenReturn(Optional.of(activeVoucher));

            assertThatThrownBy(() -> voucherService.redeem(VOUCHER_ID, OWNER_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Kuota habis harus ditolak")
        void exhaustedQuota_shouldBeRejected() {
            activeVoucher.setRemainingQuota(0);
            when(voucherRepository.findByIdForUpdate(VOUCHER_ID)).thenReturn(Optional.of(activeVoucher));

            assertThatThrownBy(() -> voucherService.redeem(VOUCHER_ID, OWNER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sisa kuota=0");
        }

        @Test
        @DisplayName("Redeem ganda oleh user sama harus 409")
        void duplicateRedeem_shouldReturnConflict() {
            when(voucherRepository.findByIdForUpdate(VOUCHER_ID)).thenReturn(Optional.of(activeVoucher));
            when(voucherRedemptionRepository.existsByVoucherIdAndOwnerId(VOUCHER_ID, OWNER_ID)).thenReturn(true);

            assertThatThrownBy(() -> voucherService.redeem(VOUCHER_ID, OWNER_ID))
                    .isInstanceOf(VoucherAlreadyRedeemedException.class)
                    .hasMessageContaining("sudah pernah redeem");

            verify(ledgerService, never()).redeemFlashSale(anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Voucher tidak ditemukan harus 404")
        void voucherNotFound_shouldThrow() {
            when(voucherRepository.findByIdForUpdate(VOUCHER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> voucherService.redeem(VOUCHER_ID, OWNER_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }
}
