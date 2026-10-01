package com.classroom.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    private final MinioProperties properties;

    public MinioConfig(MinioProperties properties) {
        this.properties = properties;
    }

    @Bean
    public MinioClient minioClient() {
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        applyTimeouts(client);
        return client;
    }

    @Bean("minioPresigningClient")
    public MinioClient minioPresigningClient() {
        MinioClient client = MinioClient.builder()
                // MinIO uses the configured region, so presigning does not need to contact this
                // browser-facing endpoint from inside the backend container.
                .endpoint(properties.getExternalEndpoint())
                .region("us-east-1")
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        applyTimeouts(client);
        return client;
    }

    /**
     * R20-12: the SDK default is 5 MINUTES for connect, write and read, so a hung or blackholed MinIO pinned request threads for
     * minutes. Connecting to a healthy MinIO takes milliseconds (3 s by default is generous) and a read timeout is the gap between
     * two bytes of a response, not the transfer time, so 30 s still covers a slow server-side copy of a large object while a dead
     * peer is given up on quickly and answered with a retryable 503 (see ObjectStoreFailure).
     */
    private void applyTimeouts(MinioClient client) {
        client.setTimeout(properties.getConnectTimeoutMs(), properties.getWriteTimeoutMs(), properties.getReadTimeoutMs());
    }
}
