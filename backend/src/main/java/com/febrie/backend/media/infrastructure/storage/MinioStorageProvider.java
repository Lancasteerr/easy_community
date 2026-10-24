package com.febrie.backend.media.infrastructure.storage;

import com.febrie.backend.media.application.MediaErrors;
import com.febrie.backend.media.application.ObjectStorageProvider;
import com.febrie.backend.media.infrastructure.config.MediaProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** 使用S3协议连接MinIO；条件写入头和声明类型都必须进入签名。 */
@Component
@ConditionalOnProperty(name = "app.media.provider", havingValue = "minio")
public class MinioStorageProvider implements ObjectStorageProvider {
    private final S3Client client;
    private final S3Presigner presigner;
    private final Clock clock;

    public MinioStorageProvider(MediaProperties properties, Clock clock) {
        this.clock = clock;
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        var configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        client = S3Client.builder().endpointOverride(properties.endpoint()).region(Region.of(properties.region()))
                .credentialsProvider(credentials).serviceConfiguration(configuration)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)).build()).build();
        // 浏览器端点参与签名，不能签名完成后再替换内网Host。
        presigner = S3Presigner.builder().endpointOverride(properties.uploadEndpoint()).region(Region.of(properties.region()))
                .credentialsProvider(credentials).serviceConfiguration(configuration).build();
    }

    @Override
    public String name() { return "minio"; }

    @Override
    public UploadAuthorization authorizeUpload(String bucket, String key, String contentType, Instant expiresAt) {
        try {
            var request = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).ifNoneMatch("*").build();
            var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.between(clock.instant(), expiresAt)).putObjectRequest(request).build());
            Map<String, String> headers = new LinkedHashMap<>();
            signed.signedHeaders().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("host")) { headers.put(name, String.join(",", values)); }
            });
            // 签名器若未绑定关键条件，直接拒绝授权，不能静默退回可覆盖上传。
            if (!signed.signedHeaders().containsKey("if-none-match") || !signed.signedHeaders().containsKey("content-type")) {
                throw MediaErrors.unavailable();
            }
            return new UploadAuthorization("PUT", signed.url().toString(), Map.copyOf(headers), expiresAt);
        } catch (RuntimeException error) { throw MediaErrors.unavailable(); }
    }

    @Override
    public Optional<ObjectMetadata> head(String bucket, String key) {
        try {
            var object = client.headObject(builder -> builder.bucket(bucket).key(key));
            return Optional.of(new ObjectMetadata(object.contentLength(), object.contentType(), object.eTag()));
        } catch (S3Exception error) {
            if (error.statusCode() == 404) { return Optional.empty(); }
            throw MediaErrors.unavailable();
        } catch (RuntimeException error) { throw MediaErrors.unavailable(); }
    }

    @Override
    public void copyToPublic(String bucket, String sourceKey, String targetKey, String contentType, String sourceEtag) {
        try {
            client.copyObject(CopyObjectRequest.builder().sourceBucket(bucket).sourceKey(sourceKey)
                    .destinationBucket(bucket).destinationKey(targetKey).copySourceIfMatch(sourceEtag)
                    .metadataDirective(MetadataDirective.REPLACE).contentType(contentType)
                    .cacheControl("public, max-age=3600").build());
        } catch (RuntimeException error) { throw MediaErrors.unavailable(); }
    }

    @Override
    public void delete(String bucket, String key) {
        try { client.deleteObject(builder -> builder.bucket(bucket).key(key)); }
        catch (RuntimeException error) { throw MediaErrors.unavailable(); }
    }

    @Override
    public void close() { presigner.close(); client.close(); }
}
