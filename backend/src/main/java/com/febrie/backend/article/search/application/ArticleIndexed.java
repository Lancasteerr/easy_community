package com.febrie.backend.article.search.application;

/** 仅用于尽力首次算分的进程内通知，不能替代可靠元数据事件。 */
public record ArticleIndexed(long articleId) { }
