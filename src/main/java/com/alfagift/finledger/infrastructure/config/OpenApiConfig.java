package com.alfagift.finledger.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata OpenAPI agar dokumentasi tidak tampil sebagai "OpenAPI definition v0".
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI finLedgerOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("FinLedger Engine API")
                .version("1.0.0")
                .description("""
                        High-concurrency double-entry ledger untuk multi-tender checkout,
                        flash sale redemption, dan audit trail immutable.

                        Semua request POST/PATCH wajib menyertakan header `X-Idempotency-Key`.
                        Semua error mengikuti RFC 7807 (`application/problem+json`).
                        Setiap response menyertakan `X-Request-ID` untuk penelusuran log.
                        """)
                .contact(new Contact()
                        .name("Ridwan")
                        .url("https://github.com/ridwan"))
                .license(new License()
                        .name("Portfolio Project")
                        .url("https://github.com/ridwan")));
    }
}
