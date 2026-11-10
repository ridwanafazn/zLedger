package com.alfagift.finledger.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request menjalankan rekonsiliasi (opsional, untuk catatan audit).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationRunRequest {

    @Size(max = 500, message = "notes maksimal 500 karakter")
    private String notes;
}
