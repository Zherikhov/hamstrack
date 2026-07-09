package com.hamstrack.common.storage;

import com.hamstrack.common.config.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.net.URI;

@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3FileStorage implements FileStorage {

    private final S3Client s3;
    private final String bucket;

    public S3FileStorage(StorageProperties props) {
        var cfg = props.s3();
        if (!StringUtils.hasText(cfg.bucket())) {
            throw new IllegalStateException("app.storage.type=s3 requires app.storage.s3.bucket");
        }
        this.bucket = cfg.bucket();

        var builder = S3Client.builder();
        if (StringUtils.hasText(cfg.region())) {
            builder.region(Region.of(cfg.region()));
        }
        // endpoint + path-style cover S3-compatible stores (MinIO etc.); without them
        // the SDK targets AWS S3 directly
        if (StringUtils.hasText(cfg.endpoint())) {
            builder.endpointOverride(URI.create(cfg.endpoint()));
        }
        if (cfg.pathStyleAccess()) {
            builder.forcePathStyle(true);
        }
        // Explicit keys win; otherwise the SDK default chain applies
        // (env vars, ~/.aws, instance/task role)
        if (StringUtils.hasText(cfg.accessKey()) && StringUtils.hasText(cfg.secretKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())));
        }
        this.s3 = builder.build();
    }

    @Override
    public void store(String key, InputStream in, long contentLength, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build(),
                RequestBody.fromInputStream(in, contentLength));
    }

    @Override
    public InputStream open(String key) {
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
