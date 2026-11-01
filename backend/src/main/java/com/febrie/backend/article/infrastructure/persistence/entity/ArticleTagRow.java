package com.febrie.backend.article.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.Setter;

/** 批量联表投影，避免每篇文章单独查询标签。 */
@Getter
@Setter
public class ArticleTagRow {
    private long articleId;
    private long tagId;
    private String name;
}
