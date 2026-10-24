package com.febrie.backend.media.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** 隔离服务商签名与对象操作；禁止把先HEAD再普通PUT当作原子防覆盖。 */
public interface ObjectStorageProvider extends AutoCloseable {
    String name();

    UploadAuthorization authorizeUpload(String bucket, String key, String contentType, Instant expiresAt);

    Optional<ObjectMetadata> head(String bucket, String key);

    void copyToPublic(String bucket, String sourceKey, String targetKey, String contentType, String sourceEtag);

    void delete(String bucket, String key);

    @Override
    default void close() { }

    /** 前端原样发送必需头，Host由HTTP客户端根据URL生成，不能手动替换。 */
    record UploadAuthorization(String method, String url, Map<String, String> headers, Instant expiresAt) { }

    /** HEAD只能提供声明元数据，不能证明文件真实格式或可解码性。 */
    record ObjectMetadata(long size, String contentType, String etag) { }
}
