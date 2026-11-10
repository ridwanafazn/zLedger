package com.alfagift.finledger.domain.entities;

import com.alfagift.finledger.domain.enums.VoucherStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Voucher flash sale dengan kuota terbatas.
 * <p>
 * Kuota dikurangi secara atomik di bawah pessimistic lock agar tidak terjadi
 * overselling saat banyak user redeem bersamaan.
 */
@Entity
@Table(name = "vouchers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Voucher {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @EqualsAndHashCode.Include
    @Column(name = "id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "points_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal pointsCost;

    @Column(name = "total_quota", nullable = false)
    private int totalQuota;

    @Column(name = "remaining_quota", nullable = false)
    private int remainingQuota;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private VoucherStatus status = VoucherStatus.DRAFT;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Voucher dapat diredeem bila status ACTIVE, dalam periode, dan kuota tersisa.
     */
    public boolean isRedeemable(LocalDateTime now) {
        return status == VoucherStatus.ACTIVE
                && remainingQuota > 0
                && !now.isBefore(startsAt)
                && !now.isAfter(endsAt);
    }
}
