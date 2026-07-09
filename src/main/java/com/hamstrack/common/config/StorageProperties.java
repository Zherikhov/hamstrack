package com.hamstrack.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * File storage config. {@code type} selects the backend: {@code local} (DC default —
 * files on the host filesystem) or {@code s3} (Cloud default — S3 or any S3-compatible
 * store via {@code endpoint} + {@code pathStyleAccess}, e.g. MinIO for self-hosted).
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String type,
        Local local,
        S3 s3
) {
    public record Local(String baseDir) {}

    public record S3(
            String bucket,
            String region,
            String endpoint,
            boolean pathStyleAccess,
            String accessKey,
            String secretKey
    ) {}
}
