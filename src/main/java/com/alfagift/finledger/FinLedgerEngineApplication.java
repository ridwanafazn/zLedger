package com.alfagift.finledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point untuk FinLedger Engine.
 * <p>
 * Modular Monolith dengan Clean Architecture.
 * Paket yang dipindai secara automatik oleh Spring Boot:
 * {@code com.alfagift.finledger} dan semua sub-paket.
 */
@SpringBootApplication
public class FinLedgerEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinLedgerEngineApplication.class, args);
    }
}