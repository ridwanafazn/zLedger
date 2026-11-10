package com.alfagift.finledger.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dilempar ketika user mencoba redeem voucher yang sama lebih dari sekali.
 */
public class VoucherAlreadyRedeemedException extends ResponseStatusException {

    public VoucherAlreadyRedeemedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
