package com.febrie.backend.article.application.dto;

import java.time.Instant;

/** 数据库读取结果；列表投影的正文两字段为空，不能据此发布详情缓存。 */
public record ArticleSnapshot(long id, long authorId, String title, String summary,
                              String contentJson, String contentHtml, String coverMediaId, boolean hidden,
                              Instant createdAt, Instant updatedAt, Instant publishedAt) { }
