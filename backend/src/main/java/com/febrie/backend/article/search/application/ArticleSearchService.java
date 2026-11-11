package com.febrie.backend.article.search.application;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.febrie.backend.article.content.application.ArticleListHydrator;
import com.febrie.backend.article.content.application.dto.ArticleBriefData;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleSearchIndex;
import com.febrie.backend.shared.error.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 查询与缓存装配禁止外层事务；只有确定处理的候选才能推进游标。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ArticleSearchService {
    private final SearchProperties p;
    private final ArticleSearchIndex index;
    private final ArticleListHydrator cards;
    private final SearchCursorCodec cursors;
    private final MeterRegistry metrics;
    public ArticleSearchService(SearchProperties p, ArticleSearchIndex index, ArticleListHydrator cards, SearchCursorCodec cursors, MeterRegistry metrics) {
        this.p = p; this.index = index; this.cards = cards; this.cursors = cursors; this.metrics = metrics;
    }
    public Page page(ArticleSearchQuery query, String cursor) {
        if (!p.queryEnabled()) { throw SearchErrors.unavailable(); }
        SearchCursor state = cursor == null ? null : cursors.decode(cursor, query);
        String pit = state == null ? null : state.pit();
        List<SearchCursor.Value> after = state == null ? List.of() : state.sort();
        var sample = io.micrometer.core.instrument.Timer.start(metrics);
        try {
            if (pit == null) { pit = index.open(); }
            List<ArticleBriefData> items = new ArrayList<>();
            for (int round = 0; round < 3; round++) {
                var batch = index.search(query, pit, after);
                pit = batch.pit();
                var loaded = cards.publicCandidates(batch.hits().stream().map(ArticleSearchIndex.Hit::articleId).toList()).stream()
                        .collect(Collectors.toMap(v -> Long.parseLong(v.articleId()), Function.identity()));
                int processed = 0;
                for (var hit : batch.hits()) {
                    after = hit.sort(); processed++;
                    var card = loaded.get(hit.articleId());
                    // ES只负责发现；缓存或主库投影中的作者才是本页筛选依据。
                    if (card != null && query.authorId() != null && !query.authorId().toString().equals(card.authorId())) {
                        metrics.counter("article.search.candidate.rejected", "reason", "author_mismatch").increment();
                        card = null;
                    }
                    if (card != null) { items.add(card); }
                    if (items.size() == query.size()) { break; }
                }
                boolean exhausted = processed == batch.hits().size() && batch.hits().size() < query.size() + 10;
                if (exhausted) { index.close(pit); return new Page(List.copyOf(items), null); }
                if (items.size() == query.size()) { break; }
            }
            return new Page(List.copyOf(items), cursors.encode(query, pit, after));
        } catch (ElasticsearchException failure) {
            if (state == null && pit != null) { index.close(pit); }
            if (state != null && failure.status() == 404) { throw SearchErrors.expired(); }
            throw SearchErrors.unavailable();
        } catch (IOException | RuntimeException failure) {
            if (state == null && pit != null) { index.close(pit); }
            if (failure instanceof ApiException known) { throw known; }
            throw SearchErrors.unavailable();
        } finally { sample.stop(metrics.timer("article.search.duration")); }
    }
    public record Page(List<ArticleBriefData> items, String nextCursor) { }
}
