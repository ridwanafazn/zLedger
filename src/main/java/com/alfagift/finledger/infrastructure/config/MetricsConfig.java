package com.alfagift.finledger.infrastructure.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mengaktifkan anotasi {@code @Timed} pada service agar latency/TPS
 * terekspos melalui Actuator ({@code /actuator/metrics/ledger.checkout}).
 */
@Configuration
public class MetricsConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
