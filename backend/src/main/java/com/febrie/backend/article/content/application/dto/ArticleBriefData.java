package com.febrie.backend.article.content.application.dto;

import com.febrie.backend.article.tag.application.dto.TagView;
import java.time.Instant;
import java.util.List;

/** 文章简略缓存只包含文章自身字段，作者资料在解锁后单独组装。 */
public record ArticleBriefData(String articleId, String title, String authorId, String summary,
                               String coverMediaId, List<TagView> tags, Instant publishedAt, Instant updatedAt) {
    public ArticleBriefData { tags = List.copyOf(tags); }
}
