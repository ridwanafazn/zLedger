package com.alfagift.finledger.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request redeem voucher. {@code ownerId} opsional: hanya ADMIN yang boleh
 * menargetkan pemilik lain; user biasa selalu memakai identitas dari token.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherRedeemRequest {

    private String ownerId;
}
