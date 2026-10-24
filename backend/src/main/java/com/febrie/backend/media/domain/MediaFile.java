package com.febrie.backend.media.domain;

import java.time.Instant;

/** 文件数据库快照；存储位置属于内部信息，禁止直接作为HTTP响应。 */
public record MediaFile(
        String id, long ownerUserId, MediaPurpose purpose, String filename,
        String declaredContentType, long declaredSize, Long actualSize, String etag,
        String storageProvider, String bucket, String tempKey, String publicKey, MediaStatus status,
        Instant uploadExpiresAt, Instant expiresAt, String operationToken, Instant leaseUntil,
        int retryCount, Instant nextRetryAt, Instant unreferencedAt) {
}
