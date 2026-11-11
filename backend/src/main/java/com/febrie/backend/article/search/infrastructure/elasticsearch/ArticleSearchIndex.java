package com.febrie.backend.article.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;

/** PIT查询只返回ID和完整sort，拒绝超时/分片失败产生的不完整候选页。 */
@Component
public class ArticleSearchIndex {
    private final ElasticsearchClient es;
    private final SearchProperties p;
    public ArticleSearchIndex(ElasticsearchClient es, SearchProperties p) { this.es = es; this.p = p; }
    public String open() throws IOException {
        return es.openPointInTime(o -> o.index(p.readAlias()).keepAlive(t -> t.time("2m")).allowPartialSearchResults(false)).id();
    }
    public Batch search(ArticleSearchQuery query, String pit, List<SearchCursor.Value> after) throws IOException {
        var filters = new BoolQuery.Builder().filter(f -> f.term(t -> t.field("visible").value(true)));
        if (!query.q().isEmpty()) { filters.must(m -> m.match(t -> t.field("title").query(query.q()))); }
        if (query.tagId() != null) { filters.filter(f -> f.term(t -> t.field("tagIds").value(query.tagId().toString()))); }
        if (query.authorId() != null) { filters.filter(f -> f.term(t -> t.field("authorId").value(query.authorId()))); }
        var request = new SearchRequest.Builder().pit(p -> p.id(pit).keepAlive(t -> t.time("2m")))
                .query(filters.build()._toQuery()).size(query.size() + 10).source(s -> s.fetch(false))
                .trackTotalHits(t -> t.enabled(false)).allowPartialSearchResults(false).timeout("2s");
        if (!after.isEmpty()) { request.searchAfter(after.stream().map(SearchCursor.Value::field).toList()); }
        if (query.sort().equals("relevance")) { request.sort(s -> s.score(c -> c.order(SortOrder.Desc))); }
        else if (query.sort().equals("recommended")) { request.sort(s -> s.field(f -> f.field("rankScore").order(SortOrder.Desc))); }
        request.sort(s -> s.field(f -> f.field("publishedAt").order(SortOrder.Desc)))
                .sort(s -> s.field(f -> f.field("articleId").order(SortOrder.Desc)));
        var response = es.search(request.build(), Void.class);
        if (response.timedOut() || response.shards().failed().intValue() > 0) { throw new IOException("ES查询不完整"); }
        var hits = response.hits().hits().stream().map(h -> new Hit(Long.parseLong(h.id()),
                h.sort().stream().map(SearchCursor.Value::from).toList())).toList();
        return new Batch(response.pitId() == null ? pit : response.pitId(), hits);
    }
    public void close(String pit) {
        try { es.closePointInTime(c -> c.id(pit)); }
        catch (IOException | RuntimeException ignored) { /* 关闭失败由短keep_alive兜底，不覆盖已取得的业务结果。 */ }
    }
    public record Hit(long articleId, List<SearchCursor.Value> sort) { }
    public record Batch(String pit, List<Hit> hits) { }
}
