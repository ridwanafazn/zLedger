package com.alfagift.finledger.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Mendaftarkan konfigurasi modul settlement agar bisa di-{@code @InjectMocks}
 * pada unit test tanpa memuat seluruh konteks Spring.
 */
@Configuration
@EnableConfigurationProperties(SettlementProperties.class)
public class SettlementConfig {
}
