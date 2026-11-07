package com.febrie.backend.article.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.JsonData;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;

/** 分数只局部更新已有文档；Bulk逐项确认并有界重试同一payload。 */
@Component
public class ArticleRankStore {
    private static final Logger log = LoggerFactory.getLogger(ArticleRankStore.class);
    private static final String SCORE_SCRIPT = """
            if (params.version > ctx._source.scoreVersion) {
              ctx._source.rankScore = params.score;
              ctx._source.scoreVersion = params.version;
            } else { ctx.op = 'noop'; }
            """;
    private final ElasticsearchClient es;
    private final MeterRegistry metrics;
    public ArticleRankStore(ElasticsearchClient es, MeterRegistry metrics) { this.es = es; this.metrics = metrics; }
    public WriteResult write(String target, boolean requireAlias, List<Score> scores) {
        List<Score> pending = scores;
        int missing = 0, failed = 0;
        for (int attempt = 0; attempt < 3 && !pending.isEmpty(); attempt++) {
            List<Score> retry = new ArrayList<>();
            try {
                var operations = pending.stream().map(score -> BulkOperation.of(o -> o.update(u -> u.index(target)
                        .id(Long.toString(score.articleId())).requireAlias(requireAlias).retryOnConflict(3)
                        .action(a -> a.script(s -> s.lang("painless").source(source -> source.scriptString(SCORE_SCRIPT))
                                .params("score", JsonData.of(score.score())).params("version", JsonData.of(score.version()))))))).toList();
                var response = es.bulk(b -> b.operations(operations));
                if (response.items().size() != pending.size()) { throw new IOException("ES Bulk响应项数不一致"); }
                for (int i = 0; i < pending.size(); i++) {
                    var item = response.items().get(i);
                    if (item.status() < 300 && item.error() == null) { continue; }
                    if (item.status() == 404) { missing++; }
                    else if (item.status() == 409 || item.status() == 429 || item.status() >= 500) { retry.add(pending.get(i)); }
                    else {
                        failed++;
                        log.error("推荐分永久写入错误 articleId={} status={}", pending.get(i).articleId(), item.status());
                    }
                }
            } catch (IOException failure) { retry.addAll(pending); }
            catch (ElasticsearchException failure) {
                if (failure.status() == 429 || failure.status() >= 500) { retry.addAll(pending); }
                else { failed += pending.size(); log.error("推荐分Bulk请求失败 status={}", failure.status()); }
            }
            if (attempt == 2) { failed += retry.size(); }
            else if (!retry.isEmpty()) {
                try { Thread.sleep(1000L << attempt); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("推荐分写入中断", interrupted); }
            }
            pending = retry;
        }
        metrics.counter("article.rank.missing").increment(missing);
        metrics.counter("article.rank.failure").increment(failed);
        return new WriteResult(missing, failed);
    }
    public record Score(long articleId, double score, long version) { }
    public record WriteResult(int missing, int failed) { }
}
