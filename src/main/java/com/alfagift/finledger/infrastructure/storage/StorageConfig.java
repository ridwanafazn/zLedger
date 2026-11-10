package com.alfagift.finledger.infrastructure.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.nio.file.Path;

/**
 * Memilih implementasi {@link BlobStorage} berdasarkan {@code app.storage.type}:
 * <ul>
 *   <li>{@code local} (default) — filesystem, tanpa Docker;</li>
 *   <li>{@code s3} — MinIO/S3-compatible.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
    public BlobStorage localBlobStorage(StorageProperties properties) {
        return new LocalFileSystemBlobStorage(Path.of(properties.localBasePath()));
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
    public BlobStorage s3BlobStorage(StorageProperties properties) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        Region region = Region.of(properties.region());
        S3Configuration serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyleAccess())
                .build();

        var clientBuilder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfiguration);
        var presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfiguration);

        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            URI endpoint = URI.create(properties.endpoint());
            clientBuilder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
        }

        return new S3BlobStorage(clientBuilder.build(), presignerBuilder.build(), properties.bucket());
    }
}
