package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.PaymentProofStatus;
import com.alfagift.finledger.domain.enums.UploadMethod;
import com.alfagift.finledger.dto.CheckoutRequest;
import com.alfagift.finledger.dto.CheckoutRequest.TenderDetail;
import com.alfagift.finledger.dto.CheckoutResponse;
import com.alfagift.finledger.dto.PaymentProofDownload;
import com.alfagift.finledger.dto.PaymentProofPresignRequest;
import com.alfagift.finledger.dto.PaymentProofPresignResponse;
import com.alfagift.finledger.dto.PaymentProofResponse;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.PaymentProofRepository;
import com.alfagift.finledger.service.LedgerService;
import com.alfagift.finledger.service.PaymentProofService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test presigned upload (Fase J) terhadap S3-compatible server sungguhan
 * (adobe/s3mock), membuktikan klien dapat mengunggah langsung ke storage lalu
 * server memverifikasi objek tersebut.
 */
@Testcontainers
@SpringBootTest
@Transactional
class PaymentProofPresignIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finledger_presign")
            .withUsername("finledger")
            .withPassword("finledger_test_secret");

    @Container
    static GenericContainer<?> s3mock = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.5.2"))
            .withExposedPorts(9090);

    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("app.storage.type", () -> "s3");
        registry.add("app.storage.endpoint",
                () -> "http://" + s3mock.getHost() + ":" + s3mock.getMappedPort(9090));
        registry.add("app.storage.access-key", () -> "foo");
        registry.add("app.storage.secret-key", () -> "bar");
        registry.add("app.storage.bucket", () -> "finledger-presign-it");
        registry.add("app.storage.path-style-access", () -> "true");
    }

    @Autowired
    private PaymentProofService paymentProofService;

    @Autowired
    private PaymentProofRepository paymentProofRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    private String suffix;
    private UUID transactionId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        accountRepository.save(Account.builder()
                .ownerId("presign-cust-" + suffix)
                .accountName("Presign Customer")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .balance(new BigDecimal("50000.0000"))
                .openingBalance(new BigDecimal("50000.0000"))
                .build());

        CheckoutResponse checkout = ledgerService.processMultiTenderPayment(CheckoutRequest.builder()
                .ownerId("presign-cust-" + suffix)
                .merchantId("presign-merchant-" + suffix)
                .totalAmount(new BigDecimal("10000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("presign-checkout-" + suffix)
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("10000.00"))
                        .description("presign integration")
                        .build()))
                .build());
        transactionId = checkout.getTransactionId();
    }

    @Test
    @DisplayName("Presign -> PUT langsung ke S3 -> complete memverifikasi checksum -> unduh identik")
    void presignedUploadRoundTrip() throws Exception {
        String uploader = "presign-cust-" + suffix;

        PaymentProofPresignResponse presigned = paymentProofService.presign(
                PaymentProofPresignRequest.builder()
                        .transactionId(transactionId)
                        .filename("bukti-besar.png")
                        .contentType("image/png")
                        .sizeBytes((long) PNG_BYTES.length)
                        .build(),
                uploader);

        assertThat(presigned.getUploadUrl()).startsWith("http://");
        assertThat(presigned.getMethod()).isEqualTo("PUT");

        // Klien mengunggah langsung ke object storage (tanpa lewat aplikasi).
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder put = HttpRequest.newBuilder(URI.create(presigned.getUploadUrl()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(PNG_BYTES));
        for (Map.Entry<String, String> header : presigned.getHeaders().entrySet()) {
            // Content-Length diatur otomatis oleh HttpClient
            if (!"Content-Length".equalsIgnoreCase(header.getKey())) {
                put.header(header.getKey(), header.getValue());
            }
        }
        HttpResponse<String> putResponse = client.send(put.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(putResponse.statusCode()).isBetween(200, 299);

        PaymentProofResponse completed = paymentProofService.complete(presigned.getProofId(), uploader, false);

        assertThat(completed.getStatus()).isEqualTo(PaymentProofStatus.UPLOADED);
        assertThat(completed.getUploadMethod()).isEqualTo(UploadMethod.PRESIGNED);
        assertThat(completed.getChecksumSha256()).isEqualTo(sha256Hex(PNG_BYTES));
        assertThat(completed.getSizeBytes()).isEqualTo(PNG_BYTES.length);

        PaymentProofDownload download = paymentProofService.download(completed.getId(), uploader, false);
        assertThat(download.content().readAllBytes()).isEqualTo(PNG_BYTES);
        download.content().close();

        assertThat(paymentProofRepository.findById(completed.getId())).isPresent();
    }

    @Test
    @DisplayName("Retensi menghapus bukti yang sudah lewat batas simpan beserta objeknya")
    void retentionShouldPurgeExpiredProofs() throws Exception {
        String uploader = "presign-cust-" + suffix;

        PaymentProofPresignResponse presigned = paymentProofService.presign(
                PaymentProofPresignRequest.builder()
                        .transactionId(transactionId)
                        .filename("lama.png")
                        .contentType("image/png")
                        .sizeBytes((long) PNG_BYTES.length)
                        .build(),
                uploader);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder put = HttpRequest.newBuilder(URI.create(presigned.getUploadUrl()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(PNG_BYTES))
                .header("Content-Type", "image/png");
        client.send(put.build(), HttpResponse.BodyHandlers.ofString());
        paymentProofService.complete(presigned.getProofId(), uploader, false);

        // olderThanDays = 0 -> semua bukti UPLOADED dianggap melewati batas simpan
        int purged = paymentProofService.purgeExpired(0);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(paymentProofRepository.findById(presigned.getProofId())).isEmpty();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
