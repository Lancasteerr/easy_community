package com.febrie.backend.media.infrastructure.storage;

import com.febrie.backend.media.application.MediaErrors;
import com.febrie.backend.media.application.ObjectStorageProvider;
import com.febrie.backend.media.infrastructure.config.MediaProperties;
import com.qcloud.cos.*;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.endpoint.EndpointBuilder;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.*;
import com.qcloud.cos.model.*;
import com.qcloud.cos.region.Region;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 使用COS原生签名及防覆盖头；端点为完整桶域名，不在签名后替换Host。 */
@Component
@ConditionalOnProperty(name = "app.media.provider", havingValue = "cos")
public class CosStorageProvider implements ObjectStorageProvider {
    private final COSClient client;
    private final COSClient signingClient;

    public CosStorageProvider(MediaProperties properties) {
        client = client(properties, properties.endpoint());
        signingClient = client(properties, properties.uploadEndpoint());
    }

    private static COSClient client(MediaProperties properties, URI endpoint) {
        var config = new ClientConfig(new Region(properties.region()));
        config.setHttpProtocol("https".equals(endpoint.getScheme()) ? HttpProtocol.https : HttpProtocol.http);
        config.setConnectionTimeout(3000);
        config.setSocketTimeout(10000);
        config.setConnectionRequestTimeout(3000);
        config.setMaxErrorRetry(1);
        config.setMaxErrorRetryForCopyRequest(1);
        config.setEndpointBuilder(new EndpointBuilder() {
            @Override public String buildGeneralApiEndpoint(String bucket) { return endpoint.getRawAuthority(); }
            @Override public String buildGetServiceApiEndpoint() { return endpoint.getRawAuthority(); }
        });
        return new COSClient(new BasicCOSCredentials(properties.accessKey(), properties.secretKey()), config);
    }

    @Override
    public String name() { return "cos"; }

    @Override
    public UploadAuthorization authorizeUpload(String bucket, String key, String contentType, Instant expiresAt) {
        try {
            var request = new GeneratePresignedUrlRequest(bucket, key, HttpMethodName.PUT);
            request.setExpiration(Date.from(expiresAt));
            request.setContentType(contentType);
            request.putCustomRequestHeader("Content-Type", contentType);
            request.putCustomRequestHeader("x-cos-forbid-overwrite", "true");
            String url = signingClient.generatePresignedUrl(request, true).toString();
            return new UploadAuthorization("PUT", url,
                    Map.of("Content-Type", contentType, "x-cos-forbid-overwrite", "true"), expiresAt);
        } catch (RuntimeException error) { throw MediaErrors.unavailable(); }
    }

    @Override
    public Optional<ObjectStorageProvider.ObjectMetadata> head(String bucket, String key) {
        try {
            var object = client.getObjectMetadata(bucket, key);
            return Optional.of(new ObjectStorageProvider.ObjectMetadata(object.getContentLength(), object.getContentType(), object.getETag()));
        } catch (CosServiceException error) {
            if (error.getStatusCode() == 404) { return Optional.empty(); }
            throw MediaErrors.unavailable();
        } catch (RuntimeException error) { throw MediaErrors.unavailable(); }
    }

    @Override
    public void copyToPublic(String bucket, String sourceKey, String targetKey, String contentType, String sourceEtag) {
        try {
            var request = new CopyObjectRequest(bucket, sourceKey, bucket, targetKey);
            request.setMatchingETagConstraints(List.of(sourceEtag));
            var metadata = new com.qcloud.cos.model.ObjectMetadata();
            metadata.setContentType(contentType);
            metadata.setCacheControl("public, max-age=3600");
            request.setNewObjectMetadata(metadata);
            // 官方SDK解析复制响应体中的错误，不把HTTP 200一概视为复制成功。
            client.copyObject(request);
        } catch (RuntimeException error) { throw MediaErrors.unavailable(); }
    }

    @Override
    public void delete(String bucket, String key) {
        try { client.deleteObject(bucket, key); }
        catch (CosServiceException error) {
            if (error.getStatusCode() != 404) { throw MediaErrors.unavailable(); }
        } catch (RuntimeException error) { throw MediaErrors.unavailable(); }
    }

    @Override
    public void close() { signingClient.shutdown(); client.shutdown(); }
}
