package com.febrie.backend.article.search.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.Param;

/** 元数据读取始终使用主库小字段，Tag关系属于同一快照。 */
public interface ArticleIndexMapper {
    List<MetaRow> metadata(@Param("ids") Collection<Long> ids);
    List<TagRow> tags(@Param("ids") Collection<Long> ids);
    record MetaRow(long articleId, String title, Instant publishedAt, boolean hidden, long metaVersion) { }
    record TagRow(long articleId, long tagId) { }
}
