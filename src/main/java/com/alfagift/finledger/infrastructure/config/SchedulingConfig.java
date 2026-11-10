package com.alfagift.finledger.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Mengaktifkan penjadwalan untuk Outbox relay.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
