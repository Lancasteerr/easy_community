package com.febrie.backend.media.domain;

import java.time.Instant;

/** V2文件快照恰好映射八字段；真实MIME未知时保持空值。 */
public record FileRecord(String id, long uploaderId, MediaPurpose purpose, MediaStatus status,
                         String mimeType, Long sizeBytes, Instant createdAt, Instant nextActionAt) { }
