package com.febrie.backend.article.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.json.JsonData;
import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
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
        var response = es.update(u -> u.index(target).id(Long.toString(m.articleId())).requireAlias(requireAlias)
                .retryOnConflict(3).scriptedUpsert(true).upsert(Map.of())
                .script(script(m)), Map.class);
        return response.result() == Result.Created;
    }
    /** 导入整批复用相同脚本；任何分项失败使命令失败，可安全从头重跑。 */
    public void upsertBatch(String target, List<ArticleIndexMetadata> metadata) throws IOException {
        if (metadata.isEmpty()) { return; }
        var operations = metadata.stream().map(m -> BulkOperation.of(o -> o.update(u -> u.index(target)
                .id(Long.toString(m.articleId())).retryOnConflict(3)
                .action(a -> a.upsert(Map.of()).scriptedUpsert(true).script(script(m)))))).toList();
        var result = es.bulk(b -> b.operations(operations));
        if (result.errors() || result.items().size() != metadata.size()) { throw new IOException("元数据Bulk存在失败项，请修复后重跑"); }
    }
    private static Script script(ArticleIndexMetadata m) {
        var metadata = Map.of("articleId", m.articleId(), "authorId", m.authorId(), "title", m.title(), "tagIds", m.tagIds(),
                "publishedAt", m.publishedAt().toString(), "visible", m.visible(), "metaVersion", m.metaVersion());
        return Script.of(s -> s.lang("painless").source(source -> source.scriptString(META_SCRIPT))
                .params("m", JsonData.of(metadata)).params("initialScore", JsonData.of(RankFormula.score(0, m.publishedAt()))));
    }
}
