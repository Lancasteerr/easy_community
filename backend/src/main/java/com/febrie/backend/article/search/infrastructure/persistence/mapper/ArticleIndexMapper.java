package com.febrie.backend.article.search.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.Param;

/** 元数据读取始终使用主库小字段，Tag关系属于同一快照。 */
public interface ArticleIndexMapper {
    List<MetaRow> metadata(@Param("ids") Collection<Long> ids);
    List<TagRow> tags(@Param("ids") Collection<Long> ids);
    long maxId();
    List<Long> scanIds(@Param("after") long after, @Param("upper") long upper, @Param("publicOnly") boolean publicOnly);
    List<Long> candidates(@Param("date") java.time.LocalDate date, @Param("after") long after, @Param("upper") long upper);
    List<RankRow> ranking(@Param("ids") Collection<Long> ids, @Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);
    record MetaRow(long articleId, long authorId, String title, Instant publishedAt, boolean hidden, long metaVersion) { }
    record TagRow(long articleId, long tagId) { }
    record RankRow(long articleId, Instant publishedAt, long views) { }
}
