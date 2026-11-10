package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository riwayat eksekusi rekonsiliasi.
 */
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
}
