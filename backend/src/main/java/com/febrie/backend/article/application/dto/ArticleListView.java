package com.febrie.backend.article.application.dto;

import java.time.Instant;
import java.util.List;

/** 可直接展示的公开列表投影，禁止夹带正文或访问者权限。 */
public record ArticleListView(String articleId, String title, String authorId, String authorName,
                              String summary, String coverUrl, List<TagView> tags, Instant publishedAt, Instant updatedAt,
                              Long viewCount) {
    public ArticleListView { tags = List.copyOf(tags); }

    /** 管理列表与公共列表共用字段，展示取舍由前端决定。 */
    public ArticleListView withViewCount(long count) {
        return new ArticleListView(articleId, title, authorId, authorName, summary, coverUrl, tags,
                publishedAt, updatedAt, count);
    }
}
