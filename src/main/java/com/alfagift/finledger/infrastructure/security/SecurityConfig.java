package com.alfagift.finledger.infrastructure.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Konfigurasi keamanan stateless berbasis JWT + RBAC.
 * <p>
 * Matriks otorisasi:
 * <ul>
 *   <li>Publik: login, register, refresh, logout, Swagger/OpenAPI, health/info</li>
 *   <li>{@code /api/v1/users/**}: ADMIN</li>
 *   <li>{@code PATCH /accounts/*&#47;status} dan {@code POST /accounts}: ADMIN</li>
 *   <li>{@code POST /checkout} dan {@code POST /redeem}: CUSTOMER/MERCHANT/ADMIN</li>
 *   <li>Endpoint lain: wajib terautentikasi</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(SecurityUsersProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   SecurityProblemResponseWriter problemWriter) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/register",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/users/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/ledger/accounts").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/ledger/accounts/*/status").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/ledger/transactions/*/reverse").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/vouchers").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/vouchers/*/status").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/vouchers/*/redeem")
                        .hasAnyRole("CUSTOMER", "MERCHANT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/ledger/checkout", "/api/v1/ledger/redeem")
                        .hasAnyRole("CUSTOMER", "MERCHANT", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemWriter)
                        .accessDeniedHandler(problemWriter))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
