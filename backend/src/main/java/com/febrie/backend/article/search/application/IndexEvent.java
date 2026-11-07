package com.febrie.backend.article.search.application;

/** 不携带旧文章内容，消费者始终以主库最新元数据为准。 */
public record IndexEvent(long eventId, long articleId, long metaVersion) {
    public IndexEvent {
        if (eventId <= 0 || articleId <= 0 || metaVersion <= 0) { throw new IllegalArgumentException("索引事件标识或版本无效"); }
    }
}
