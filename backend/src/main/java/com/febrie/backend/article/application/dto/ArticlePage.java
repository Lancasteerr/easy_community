package com.febrie.backend.article.application.dto;

import java.util.List;

/** total为页内ID选择时的快照数量，并发删除可能令items形成短页。 */
public record ArticlePage(List<ArticleListView> items, int page, int size, long total) {
    public ArticlePage { items = List.copyOf(items); }
}
