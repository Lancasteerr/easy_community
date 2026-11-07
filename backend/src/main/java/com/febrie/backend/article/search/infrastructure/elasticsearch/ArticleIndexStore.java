package com.febrie.backend.article.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.json.JsonData;
import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 条件脚本是并发安全边界，GET仅减少重复消息的主库读取。 */
@Component
public class ArticleIndexStore {
    private final ElasticsearchClient es;
    private final SearchProperties p;
    public ArticleIndexStore(ElasticsearchClient es, SearchProperties p) { this.es = es; this.p = p; }
    private static final String META_SCRIPT = """
            if (ctx.op == 'create') {
              ctx._source.metaVersion = 0L;
              ctx._source.rankScore = params.initialScore;
              ctx._source.scoreVersion = 0L;
            }
            if (params.m.metaVersion > ctx._source.metaVersion) {
              ctx._source.putAll(params.m);
            } else { ctx.op = 'noop'; }
            """;
    public long metaVersion(long id) throws IOException {
        var response = es.get(g -> g.index(p.writeAlias()).id(Long.toString(id)).realtime(true)
                .sourceIncludes("metaVersion"), Map.class);
        if (!response.found()) { return 0; }
        return ((Number) response.source().get("metaVersion")).longValue();
    }
    public boolean upsert(ArticleIndexMetadata m) throws IOException { return upsert(p.writeAlias(), m, true); }
    /** 维护导入允许显式物理目标；正常消费必须写别名，防止意外自动建索引。 */
    public boolean upsert(String target, ArticleIndexMetadata m, boolean requireAlias) throws IOException {
        var metadata = Map.of("articleId", m.articleId(), "title", m.title(), "tagIds", m.tagIds(),
                "publishedAt", m.publishedAt().toString(), "visible", m.visible(), "metaVersion", m.metaVersion());
        var response = es.update(u -> u.index(target).id(Long.toString(m.articleId())).requireAlias(requireAlias)
                .retryOnConflict(3).scriptedUpsert(true).upsert(Map.of())
                .script(s -> s.lang("painless").source(source -> source.scriptString(META_SCRIPT))
                        .params("m", JsonData.of(metadata)).params("initialScore", JsonData.of(RankFormula.score(0, m.publishedAt())))), Map.class);
        return response.result() == Result.Created;
    }
}
