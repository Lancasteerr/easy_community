package com.febrie.backend.media.infrastructure.storage;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.infrastructure.config.MediaProperties;
import jakarta.annotation.PreDestroy;
import java.time.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

/** 本地真实POST与Copy；总调用上限按当前预算设置，不要求本地防覆盖。 */
@Component
@Profile({"dev", "test"})
public class MinioUploadStorage implements UploadStorage {
    private final MediaProperties p;
    private final S3Client client;
    public MinioUploadStorage(MediaProperties p) {
        this.p = p;
        client = S3Client.builder().endpointOverride(p.endpoint()).region(Region.of(p.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(RetryPolicy.none())
                        .apiCallTimeout(Duration.ofSeconds(3)).apiCallAttemptTimeout(Duration.ofSeconds(3)).build()).build();
    }
    @Override public Descriptor authorize(String key, String mime, long size, Instant created, Instant expires) {
        String url = p.uploadEndpoint().toString().replaceAll("/+$", "") + "/" + p.bucket();
        return new Descriptor("POST", url, PostPolicies.minio(p, key, mime, size, created, expires), expires);
    }
    @Override public void copy(UploadedObject source, String target, ProcessingDeadline deadline) {
        Duration remaining = Duration.ofMillis(deadline.remainingMillis(ProcessingDeadline.COMMIT_RESERVE_MS));
        var response = client.copyObject(CopyObjectRequest.builder().sourceBucket(p.bucket()).sourceKey(source.key())
                .destinationBucket(p.bucket()).destinationKey(target).copySourceIfMatch(source.etag())
                .metadataDirective(MetadataDirective.REPLACE).contentType(source.declaredContentType())
                .cacheControl("public, max-age=3600")
                .overrideConfiguration(config -> config.apiCallTimeout(remaining).apiCallAttemptTimeout(remaining)).build());
        if (response.copyObjectResult() == null || response.copyObjectResult().eTag() == null) {
            throw new IllegalStateException("复制结果缺少对象标识");
        }
    }
    @Override public void delete(String key) { client.deleteObject(request -> request.bucket(p.bucket()).key(key)); }
    @PreDestroy public void close() { client.close(); }
}
