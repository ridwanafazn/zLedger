package com.alfagift.finledger.domain.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Catatan satu kali redeem voucher oleh seorang user.
 * <p>
 * Unique constraint {@code (voucher_id, owner_id)} menjamin user tidak bisa
 * redeem voucher yang sama dua kali, bahkan di bawah request paralel.
 */
@Entity
@Table(name = "voucher_redemptions",
        uniqueConstraints = @UniqueConstraint(name = "uk_voucher_redemption_owner",
                columnNames = {"voucher_id", "owner_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class VoucherRedemption {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @EqualsAndHashCode.Include
    @Column(name = "id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "voucher_id", nullable = false, columnDefinition = "UUID")
    private UUID voucherId;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "transaction_id", nullable = false, columnDefinition = "UUID")
    private UUID transactionId;

    @Column(name = "points_spent", nullable = false, precision = 19, scale = 4)
    private BigDecimal pointsSpent;

    @CreationTimestamp
    @Column(name = "redeemed_at", updatable = false)
    private LocalDateTime redeemedAt;
}
