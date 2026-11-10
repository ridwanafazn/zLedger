package com.alfagift.finledger.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request mengaktifkan / menonaktifkan user (ADMIN).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStatusRequest {

    @NotNull(message = "Status enabled wajib diisi")
    private Boolean enabled;
}
