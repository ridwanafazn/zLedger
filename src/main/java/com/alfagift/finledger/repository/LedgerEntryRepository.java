package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.LedgerEntry;
import com.alfagift.finledger.domain.enums.ReferenceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository untuk entiti {@link LedgerEntry}.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /**
     * Semua entri ledger bagi suatu transaksi.
     */
    List<LedgerEntry> findByJournalTransactionId(UUID transactionId);

    /**
     * Semua entri ledger bagi suatu akaun (mutasi akaun), terbaru dulu.
     */
    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    /**
     * Mutasi akaun dengan pagination.
     */
    org.springframework.data.domain.Page<LedgerEntry> findByAccountId(
            UUID accountId, org.springframework.data.domain.Pageable pageable);

    /**
     * Entri pada akun merchant yang relevan untuk settlement dalam rentang waktu tertentu.
     * <p>
     * Yang dihitung hanya:
     * <ul>
     *   <li>jurnal {@code PAYMENT} (credit ke merchant), dan</li>
     *   <li>jurnal {@code REFUND} yang merupakan reversal dari jurnal {@code PAYMENT}.</li>
     * </ul>
     * Reversal payout {@code SETTLEMENT} sengaja dikecualikan agar payout yang
     * dibatalkan tidak dianggap sebagai refund penjualan.
     *
     * @param from    batas awal (inklusif)
     * @param to      batas akhir (eksklusif)
     * @param payment konstanta {@link ReferenceType#PAYMENT}
     * @param refund  konstanta {@link ReferenceType#REFUND}
     */
    @Query("""
            select new com.alfagift.finledger.repository.SettlementContribution(
                       le.journalTransaction.id,
                       le.journalTransaction.referenceType,
                       le.entryType,
                       le.amount,
                       le.journalTransaction.createdAt)
              from LedgerEntry le
             where le.account.id = :accountId
               and le.journalTransaction.createdAt >= :from
               and le.journalTransaction.createdAt < :to
               and (le.journalTransaction.referenceType = :payment
                    or (le.journalTransaction.referenceType = :refund
                        and le.journalTransaction.reversalOfId in (
                            select jt.id from JournalTransaction jt where jt.referenceType = :payment)))
             order by le.journalTransaction.createdAt asc, le.id asc
            """)
    List<SettlementContribution> findSettlementContributions(@Param("accountId") UUID accountId,
                                                             @Param("from") LocalDateTime from,
                                                             @Param("to") LocalDateTime to,
                                                             @Param("payment") ReferenceType payment,
                                                             @Param("refund") ReferenceType refund);
}
