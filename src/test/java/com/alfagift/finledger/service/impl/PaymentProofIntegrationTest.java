package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.dto.CheckoutRequest;
import com.alfagift.finledger.dto.CheckoutRequest.TenderDetail;
import com.alfagift.finledger.dto.CheckoutResponse;
import com.alfagift.finledger.dto.PaymentProofDownload;
import com.alfagift.finledger.dto.PaymentProofResponse;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.PaymentProofRepository;
import com.alfagift.finledger.service.LedgerService;
import com.alfagift.finledger.service.PaymentProofService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test Fase J (bukti bayar) + Fase K (endpoint Prometheus).
 * <p>
 * Storage memakai filesystem pada direktori sementara sehingga tidak butuh MinIO,
 * namun jalur kode yang diuji identik dengan {@code StorageConfig}.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PaymentProofIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finledger_proof")
            .withUsername("finledger")
            .withPassword("finledger_test_secret");

    private static final Path BLOB_DIR = Path.of(System.getProperty("java.io.tmpdir"), "finledger-proof-it");

    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("app.storage.type", () -> "local");
        registry.add("app.storage.local-base-path", BLOB_DIR::toString);
        registry.add("app.storage.max-file-size-bytes", () -> "1024");
    }

    @Autowired
    private PaymentProofService paymentProofService;

    @Autowired
    private PaymentProofRepository paymentProofRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MockMvc mockMvc;

    private String suffix;
    private UUID transactionId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        accountRepository.save(Account.builder()
                .ownerId("proof-cust-" + suffix)
                .accountName("Proof Customer")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .balance(new BigDecimal("50000.0000"))
                .openingBalance(new BigDecimal("50000.0000"))
                .build());

        CheckoutResponse checkout = ledgerService.processMultiTenderPayment(CheckoutRequest.builder()
                .ownerId("proof-cust-" + suffix)
                .merchantId("proof-merchant-" + suffix)
                .totalAmount(new BigDecimal("10000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("proof-checkout-" + suffix)
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("10000.00"))
                        .description("proof integration")
                        .build()))
                .build());
        transactionId = checkout.getTransactionId();
    }

    @Test
    @DisplayName("Unggah → metadata di DB → unduh → hapus")
    void uploadDownloadDeleteRoundTrip() throws IOException {
        String uploader = "proof-cust-" + suffix;
        MockMultipartFile file = new MockMultipartFile("file", "bukti.png", "image/png", PNG_BYTES);

        PaymentProofResponse uploaded = paymentProofService.upload(transactionId, file, uploader);

        assertThat(uploaded.getId()).isNotNull();
        assertThat(paymentProofRepository.findById(uploaded.getId())).isPresent();
        assertThat(paymentProofRepository.findByTransactionIdOrderByCreatedAtAsc(transactionId)).hasSize(1);
        assertThat(uploaded.getChecksumSha256()).hasSize(64);

        PaymentProofDownload payload = paymentProofService.download(uploaded.getId(), uploader, false);
        assertThat(payload.content().readAllBytes()).isEqualTo(PNG_BYTES);
        payload.content().close();

        paymentProofService.delete(uploaded.getId());        assertThat(paymentProofRepository.findById(uploaded.getId())).isEmpty();
    }

    @Test
    @DisplayName("Endpoint /actuator/prometheus mengekspos metrik bisnis FinLedger")
    void prometheusEndpointShouldExposeBusinessMetrics() throws Exception {
        String uploader = "proof-cust-" + suffix;
        paymentProofService.upload(transactionId,
                new MockMultipartFile("file", "bukti.png", "image/png", PNG_BYTES), uploader);

        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("finledger_payment_proof_uploaded_total");
        assertThat(body).contains("jvm_memory_used_bytes");
    }
}
