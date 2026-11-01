package com.febrie.backend.article.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.febrie.backend.article.application.dto.ArticleSnapshot;
import com.febrie.backend.media.domain.MediaIds;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 数据库映射仅供持久层使用，不启用全局逻辑删除或乐观锁。 */
@Getter
@Setter
@TableName("article")
public class ArticleEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long authorId;
    private String title;
    private String summary;
    private String contentJson;
    private String contentHtml;
    private byte[] coverMediaId;
    private boolean hidden;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;

    public ArticleSnapshot snapshot() {
        return new ArticleSnapshot(id, authorId, title, summary, contentJson, contentHtml,
                MediaIds.text(coverMediaId), hidden, createdAt, updatedAt, publishedAt);
    }
}
