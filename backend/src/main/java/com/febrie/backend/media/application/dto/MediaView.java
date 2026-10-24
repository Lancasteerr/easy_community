package com.febrie.backend.media.application.dto;

import com.febrie.backend.media.domain.*;
import java.time.Instant;

/** 对外仅返回文件状态与声明元数据，不公开临时Key、凭证或签名读取地址。 */
public record MediaView(String mediaId, MediaPurpose purpose, MediaStatus status, String declaredContentType,
                        long declaredSize, Long actualSize, Instant expiresAt) {
    public static MediaView from(MediaFile file) {
        return new MediaView(file.id(), file.purpose(), file.status(), file.declaredContentType(),
                file.declaredSize(), file.actualSize(), file.expiresAt());
    }
}
