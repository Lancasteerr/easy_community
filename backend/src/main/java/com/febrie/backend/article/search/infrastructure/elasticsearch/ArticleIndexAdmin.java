package com.febrie.backend.article.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.*;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 显式初始化和维护切换；不会删除现有索引，也不在Web启动期间调用。 */
@Component
public class ArticleIndexAdmin {
    private final ElasticsearchClient es;
    private final SearchProperties p;
    public ArticleIndexAdmin(ElasticsearchClient es, SearchProperties p) { this.es = es; this.p = p; }

    public void initialize(String index, boolean attachAliases) throws IOException {
        if (!es.indices().exists(e -> e.index(index)).value()) {
            es.indices().create(c -> c.index(index).settings(s -> s.numberOfShards("1").numberOfReplicas(Integer.toString(p.replicas())))
                    .mappings(mapping()));
        }
        verify(index);
        if (attachAliases) {
            requireAliasTarget(p.readAlias(), index);
            requireAliasTarget(p.writeAlias(), index);
            es.indices().updateAliases(a -> a
                    .actions(x -> x.add(b -> b.index(index).alias(p.readAlias())))
                    .actions(x -> x.add(b -> b.index(index).alias(p.writeAlias()).isWriteIndex(true))));
        }
    }

    public void verify(String index) throws IOException {
        var actual = es.indices().getMapping(g -> g.index(index)).get(index);
        if (actual == null || actual.mappings().dynamic() != DynamicMapping.Strict) {
            throw new IllegalStateException("目标索引不存在或Mapping不是strict");
        }
        Map<String, Property> fields = actual.mappings().properties();
        for (var field : mapping().properties().entrySet()) {
            if (!fields.containsKey(field.getKey()) || fields.get(field.getKey())._kind() != field.getValue()._kind()) {
                throw new IllegalStateException("索引字段类型不一致：" + field.getKey());
            }
        }
        if (!"cjk".equals(fields.get("title").text().analyzer())
                || (actual.mappings().source() != null && Boolean.FALSE.equals(actual.mappings().source().enabled()))) {
            throw new IllegalStateException("索引分词或_source配置不一致");
        }
    }

    private void requireAliasTarget(String alias, String index) throws IOException {
        if (es.indices().existsAlias(e -> e.name(alias)).value()) {
            var targets = es.indices().getAlias(a -> a.name(alias)).aliases().keySet();
            if (!targets.equals(java.util.Set.of(index))) { throw new IllegalStateException("别名已指向其他索引，请使用显式switch"); }
        }
    }

    public void switchAliases(String index) throws IOException {
        verify(index);
        es.indices().updateAliases(a -> a
                .actions(x -> x.remove(r -> r.index("*").alias(p.readAlias()).mustExist(false)))
                .actions(x -> x.remove(r -> r.index("*").alias(p.writeAlias()).mustExist(false)))
                .actions(x -> x.add(b -> b.index(index).alias(p.readAlias())))
                .actions(x -> x.add(b -> b.index(index).alias(p.writeAlias()).isWriteIndex(true))));
    }

    private static TypeMapping mapping() {
        return TypeMapping.of(m -> m.dynamic(DynamicMapping.Strict)
                .properties("articleId", f -> f.long_(l -> l))
                // 作者用于公开筛选，实际展示仍由主库或文章缓存确认。
                .properties("authorId", f -> f.long_(l -> l))
                .properties("title", f -> f.text(t -> t.analyzer("cjk")))
                .properties("tagIds", f -> f.keyword(k -> k))
                .properties("publishedAt", f -> f.date(d -> d))
                .properties("visible", f -> f.boolean_(b -> b))
                .properties("metaVersion", f -> f.long_(l -> l))
                .properties("rankScore", f -> f.double_(d -> d))
                .properties("scoreVersion", f -> f.long_(l -> l)));
    }
}
