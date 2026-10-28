package com.febrie.backend.media.domain;

/** 用途限制上传大小；封面属于文章图片，在绑定时额外限制5MiB。 */
public enum MediaPurpose {
    AVATAR(2L * 1024 * 1024), ARTICLE_IMAGE(10L * 1024 * 1024);
    private final long maximumSize;
    MediaPurpose(long maximumSize) { this.maximumSize = maximumSize; }
    public long maximumSize() { return maximumSize; }
}
