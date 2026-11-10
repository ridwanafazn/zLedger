package com.alfagift.finledger.controller;

import com.alfagift.finledger.dto.PaymentProofDownload;
import com.alfagift.finledger.dto.PaymentProofResponse;
import com.alfagift.finledger.infrastructure.exception.GlobalExceptionHandler;
import com.alfagift.finledger.service.PaymentProofService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit test (MockMvc) untuk {@link PaymentProofController}.
 */
@ExtendWith(MockitoExtension.class)
class PaymentProofControllerTest {

    private static final UUID TRANSACTION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PROOF_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private MockMvc mockMvc;

    @Mock
    private PaymentProofService paymentProofService;

    private Authentication customer;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentProofController(paymentProofService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        customer = new UsernamePasswordAuthenticationToken(
                "cust-001", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private PaymentProofResponse sample() {
        return PaymentProofResponse.builder()
                .id(PROOF_ID)
                .transactionId(TRANSACTION_ID)
                .uploaderId("cust-001")
                .originalFilename("bukti.png")
                .contentType("image/png")
                .sizeBytes(4)
                .checksumSha256("abc")
                .contentUrl("/api/v1/payment-proofs/" + PROOF_ID + "/content")
                .build();
    }

    @Test
    @DisplayName("POST multipart harus mengembalikan 201 dengan metadata")
    void uploadShouldReturn201() throws Exception {
        when(paymentProofService.upload(eq(TRANSACTION_ID), any(), eq("cust-001"))).thenReturn(sample());

        MockMultipartFile file = new MockMultipartFile("file", "bukti.png", "image/png", new byte[]{1, 2, 3, 4});

        mockMvc.perform(multipart("/api/v1/payment-proofs")
                        .file(file)
                        .param("transactionId", TRANSACTION_ID.toString())
                        .principal(customer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PROOF_ID.toString()))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.contentUrl").exists());
    }

    @Test
    @DisplayName("POST tanpa bagian file harus 400")
    void uploadWithoutFileShouldReturn400() throws Exception {
        mockMvc.perform(multipart("/api/v1/payment-proofs")
                        .param("transactionId", TRANSACTION_ID.toString())
                        .principal(customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET daftar bukti meneruskan flag admin=false untuk customer")
    void listShouldForwardNonAdminFlag() throws Exception {
        when(paymentProofService.findByTransaction(TRANSACTION_ID, "cust-001", false))
                .thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/v1/payment-proofs")
                        .param("transactionId", TRANSACTION_ID.toString())
                        .principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(PROOF_ID.toString()));
    }

    @Test
    @DisplayName("GET content harus streaming isi file dengan Content-Disposition inline")
    void contentShouldStreamBytes() throws Exception {
        when(paymentProofService.download(PROOF_ID, "cust-001", false))
                .thenReturn(new PaymentProofDownload(
                        new ByteArrayInputStream(new byte[]{9, 8, 7}), "bukti.png", "image/png", 3));

        mockMvc.perform(get("/api/v1/payment-proofs/" + PROOF_ID + "/content").principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"bukti.png\""))
                .andExpect(content().bytes(new byte[]{9, 8, 7}));
    }

    @Test
    @DisplayName("DELETE harus mengembalikan 204")
    void deleteShouldReturn204() throws Exception {
        mockMvc.perform(delete("/api/v1/payment-proofs/" + PROOF_ID).principal(customer))
                .andExpect(status().isNoContent());

        verify(paymentProofService).delete(PROOF_ID);
    }

    @Test
    @DisplayName("POST /presign harus mengembalikan URL unggah + header wajib")
    void presignShouldReturnUploadUrl() throws Exception {
        when(paymentProofService.presign(any(), eq("cust-001")))
                .thenReturn(com.alfagift.finledger.dto.PaymentProofPresignResponse.builder()
                        .proofId(PROOF_ID)
                        .transactionId(TRANSACTION_ID)
                        .objectKey("payment-proofs/2026/11/x.png")
                        .uploadUrl("http://localhost:9091/finledger-proofs/x.png?sig=abc")
                        .method("PUT")
                        .headers(java.util.Map.of("Content-Type", "image/png"))
                        .sizeBytes(2048)
                        .completeUrl("/api/v1/payment-proofs/" + PROOF_ID + "/complete")
                        .build());

        String body = "{\"transactionId\":\"" + TRANSACTION_ID + "\",\"filename\":\"x.png\","
                + "\"contentType\":\"image/png\",\"sizeBytes\":2048}";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/payment-proofs/presign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("PUT"))
                .andExpect(jsonPath("$.uploadUrl").exists())
                .andExpect(jsonPath("$.completeUrl").exists());
    }

    @Test
    @DisplayName("POST /retention/run harus mengembalikan jumlah yang dihapus")
    void retentionShouldReturnPurgedCount() throws Exception {
        when(paymentProofService.purgeExpired(365)).thenReturn(3);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/payment-proofs/retention/run")
                        .param("olderThanDays", "365")
                        .principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedProofs").value(3))
                .andExpect(jsonPath("$.olderThanDays").value(365));
    }
}
