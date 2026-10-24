package com.febrie.backend.media.domain;

/** 上传用途决定声明大小上限；真实文件内容识别留待后续实现。 */
public enum MediaPurpose {
    AVATAR(2L * 1024 * 1024),
    ARTICLE_COVER(5L * 1024 * 1024),
    ARTICLE_IMAGE(10L * 1024 * 1024);

    private final long maximumSize;

    MediaPurpose(long maximumSize) { this.maximumSize = maximumSize; }

    public long maximumSize() { return maximumSize; }
}
