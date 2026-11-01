package com.febrie.backend.article.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.Setter;

/** 统计查询只读取文章ID和总量，不加载正文。 */
@Getter
@Setter
public class ArticleViewCountRow {
    private long articleId;
    private long viewCount;
}
