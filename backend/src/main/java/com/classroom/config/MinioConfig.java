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
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Bean("minioPresigningClient")
    public MinioClient minioPresigningClient() {
        return MinioClient.builder()
                // MinIO uses the configured region, so presigning does not need to contact this
                // browser-facing endpoint from inside the backend container.
                .endpoint(properties.getExternalEndpoint())
                .region("us-east-1")
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
