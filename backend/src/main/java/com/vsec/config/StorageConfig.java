package com.vsec.config;

import com.vsec.storage.MinioStorageService;
import com.vsec.storage.StorageService;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

    @Value("${app.minio.endpoint:http://localhost:9000}")
    private String minioEndpoint;

    @Value("${app.minio.access-key:minioadmin}")
    private String minioAccessKey;

    @Value("${app.minio.secret-key:minioadmin}")
    private String minioSecretKey;

    @Value("${app.minio.bucket:vsec-videos}")
    private String minioBucket;

    @Bean
    public StorageService storageService() {
        MinioClient client = MinioClient.builder()
                .endpoint(minioEndpoint)
                .credentials(minioAccessKey, minioSecretKey)
                .build();
        return new MinioStorageService(client, minioBucket);
    }
}
