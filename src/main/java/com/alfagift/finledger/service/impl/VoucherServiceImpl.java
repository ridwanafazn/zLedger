package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Voucher;
import com.alfagift.finledger.domain.entities.VoucherRedemption;
import com.alfagift.finledger.domain.enums.VoucherStatus;
import com.alfagift.finledger.dto.CheckoutResponse;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.VoucherCreateRequest;
import com.alfagift.finledger.dto.VoucherRedemptionResponse;
import com.alfagift.finledger.dto.VoucherResponse;
import com.alfagift.finledger.infrastructure.Idempotent;
import com.alfagift.finledger.infrastructure.VoucherAlreadyRedeemedException;
import com.alfagift.finledger.repository.VoucherRedemptionRepository;
import com.alfagift.finledger.repository.VoucherRepository;
import com.alfagift.finledger.service.LedgerService;
import com.alfagift.finledger.service.VoucherService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Implementasi {@link VoucherService}.
 * <p>
 * Anti-overselling: baris voucher dikunci dengan {@code PESSIMISTIC_WRITE}
 * sebelum kuota diperiksa dan dikurangi, sehingga request paralel diproses
 * berurutan dan kuota tidak pernah negatif.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoucherServiceImpl implements VoucherService {

    private final VoucherRepository voucherRepository;
    private final VoucherRedemptionRepository voucherRedemptionRepository;
    private final LedgerService ledgerService;

    @Override
    @Transactional
    public VoucherResponse create(VoucherCreateRequest request) {
        if (voucherRepository.existsByCode(request.getCode())) {
            throw new IllegalArgumentException("Voucher dengan kode " + request.getCode() + " sudah ada");
        }
        if (!request.getEndsAt().isAfter(request.getStartsAt())) {
            throw new IllegalArgumentException("endsAt harus setelah startsAt");
        }
        try {
            request.getPointsCost().intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("pointsCost harus bilangan bulat (tanpa desimal)");
        }

        Voucher voucher = Voucher.builder()
                .code(request.getCode())
                .name(request.getName())
                .description(request.getDescription())
                .pointsCost(request.getPointsCost())
                .totalQuota(request.getTotalQuota())
                .remainingQuota(request.getTotalQuota())
                .startsAt(request.getStartsAt())
                .endsAt(request.getEndsAt())
                .status(VoucherStatus.DRAFT)
                .build();

        Voucher saved = voucherRepository.save(voucher);
        log.info("Voucher dibuat: code={}, quota={}", saved.getCode(), saved.getTotalQuota());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public VoucherResponse get(UUID voucherId) {
        return toResponse(findVoucher(voucherId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<VoucherResponse> search(String status, Boolean activeOnly, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        VoucherStatus statusFilter = parseStatus(status);
        LocalDateTime now = LocalDateTime.now();

        Specification<Voucher> specification = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (statusFilter != null) {
                predicates.add(cb.equal(root.get("status"), statusFilter));
            }
            if (Boolean.TRUE.equals(activeOnly)) {
                predicates.add(cb.equal(root.get("status"), VoucherStatus.ACTIVE));
                predicates.add(cb.greaterThan(root.get("remainingQuota"), 0));
                predicates.add(cb.lessThanOrEqualTo(root.get("startsAt"), now));
                predicates.add(cb.greaterThanOrEqualTo(root.get("endsAt"), now));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<Voucher> vouchers = voucherRepository.findAll(specification, pageable);

        return PageResponse.<VoucherResponse>builder()
                .content(vouchers.getContent().stream().map(this::toResponse).toList())
                .page(PageResponse.PageMetadata.builder()
                        .number(vouchers.getNumber())
                        .size(vouchers.getSize())
                        .totalElements(vouchers.getTotalElements())
                        .totalPages(vouchers.getTotalPages())
                        .first(vouchers.isFirst())
                        .last(vouchers.isLast())
                        .numberOfElements(vouchers.getNumberOfElements())
                        .build())
                .sort(List.of(PageResponse.SortInfo.builder()
                        .field("createdAt")
                        .direction("DESC")
                        .build()))
                .build();
    }

    @Override
    @Transactional
    public VoucherResponse updateStatus(UUID voucherId, VoucherStatus status) {
        Voucher voucher = findVoucher(voucherId);
        voucher.setStatus(status);
        return toResponse(voucherRepository.save(voucher));
    }

    @Override
    @Transactional
    @Idempotent
    public VoucherRedemptionResponse redeem(UUID voucherId, String ownerId) {
        // 1. Kunci baris voucher: serialisasi pengecekan + pengurangan kuota
        Voucher voucher = voucherRepository.findByIdForUpdate(voucherId)
                .orElseThrow(() -> new EntityNotFoundException("Voucher tidak ditemukan: " + voucherId));

        LocalDateTime now = LocalDateTime.now();
        if (!voucher.isRedeemable(now)) {
            throw new IllegalArgumentException(String.format(
                    "Voucher %s tidak dapat diredeem (status=%s, sisa kuota=%d, periode %s s/d %s)",
                    voucher.getCode(), voucher.getStatus(), voucher.getRemainingQuota(),
                    voucher.getStartsAt(), voucher.getEndsAt()));
        }

        if (voucherRedemptionRepository.existsByVoucherIdAndOwnerId(voucherId, ownerId)) {
            throw new VoucherAlreadyRedeemedException(
                    "User " + ownerId + " sudah pernah redeem voucher " + voucher.getCode());
        }

        // 2. Potong poin lewat ledger double-entry (idempotent, lock akun deterministik)
        int points = voucher.getPointsCost().intValueExact();
        String ledgerKey = "VOUCHER-" + voucherId + "-" + ownerId;
        CheckoutResponse ledgerResponse = ledgerService.redeemFlashSale(ownerId, voucherId, points, ledgerKey);

        // 3. Kurangi kuota & catat riwayat
        voucher.setRemainingQuota(voucher.getRemainingQuota() - 1);
        voucherRepository.save(voucher);

        VoucherRedemption redemption = VoucherRedemption.builder()
                .voucherId(voucherId)
                .ownerId(ownerId)
                .transactionId(ledgerResponse.getTransactionId())
                .pointsSpent(voucher.getPointsCost())
                .build();
        voucherRedemptionRepository.save(redemption);

        log.info("Voucher {} diredeem oleh {} (sisa kuota={})",
                voucher.getCode(), ownerId, voucher.getRemainingQuota());

        return VoucherRedemptionResponse.builder()
                .voucherId(voucherId)
                .voucherCode(voucher.getCode())
                .ownerId(ownerId)
                .transactionId(ledgerResponse.getTransactionId())
                .pointsSpent(voucher.getPointsCost())
                .remainingQuota(voucher.getRemainingQuota())
                .redeemedAt(LocalDateTime.now())
                .status("REDEEMED")
                .build();
    }

    private Voucher findVoucher(UUID voucherId) {
        return voucherRepository.findById(voucherId)
                .orElseThrow(() -> new EntityNotFoundException("Voucher tidak ditemukan: " + voucherId));
    }

    private VoucherStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return VoucherStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(String.format(
                    "Nilai status tidak valid: '%s'. Pilihan: %s",
                    raw, java.util.Arrays.toString(VoucherStatus.values())));
        }
    }

    private VoucherResponse toResponse(Voucher voucher) {
        return VoucherResponse.builder()
                .id(voucher.getId())
                .code(voucher.getCode())
                .name(voucher.getName())
                .description(voucher.getDescription())
                .pointsCost(voucher.getPointsCost())
                .totalQuota(voucher.getTotalQuota())
                .remainingQuota(voucher.getRemainingQuota())
                .startsAt(voucher.getStartsAt())
                .endsAt(voucher.getEndsAt())
                .status(voucher.getStatus())
                .version(voucher.getVersion())
                .createdAt(voucher.getCreatedAt())
                .updatedAt(voucher.getUpdatedAt())
                .build();
    }
}
