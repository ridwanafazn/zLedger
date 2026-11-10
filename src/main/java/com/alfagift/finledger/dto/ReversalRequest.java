package com.alfagift.finledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request reversal/refund transaksi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReversalRequest {

    @NotBlank(message = "Alasan reversal wajib diisi")
    @Size(max = 255, message = "Alasan maksimal 255 karakter")
    private String reason;
}
