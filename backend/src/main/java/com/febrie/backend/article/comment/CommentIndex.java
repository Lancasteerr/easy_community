package com.febrie.backend.article.comment;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.febrie.backend.article.search.application.SearchCursor;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 生命周期、点赞与回填写入相同版本空间，公开查询只读取候选 ID 与排序值。 */
@Component
class CommentIndex {
    private static final Logger log = LoggerFactory.getLogger(CommentIndex.class);
    private final ElasticsearchClient es;
    private final int replicas;
    private final String name;

    CommentIndex(ElasticsearchClient es, SearchProperties properties,
                 @Value("${app.comment.index-name:community-comment-root}") String name) {
        this.es = es;
        this.replicas = properties.replicas();
        this.name = name;
    }

    void initialize() throws IOException {
        String index = name + "-v1";
        if (!es.indices().exists(request -> request.index(index)).value()) {
            es.indices().create(request -> request.index(index)
                    .settings(s -> s.numberOfShards("1").numberOfReplicas(Integer.toString(replicas)))
                    .mappings(m -> m.dynamic(DynamicMapping.Strict)
                            .properties("commentId", p -> p.long_(l -> l))
                            .properties("articleId", p -> p.long_(l -> l))
                            .properties("createdAt", p -> p.long_(l -> l))
                            .properties("likeCount", p -> p.long_(l -> l))
                            .properties("indexVersion", p -> p.long_(l -> l))
                            .properties("visible", p -> p.boolean_(b -> b))));
        }
        // 维护命令可重复运行，避免把已有别名隐式扩展到多个索引。
        for (String alias : List.of(readAlias(), writeAlias())) {
            if (es.indices().existsAlias(a -> a.name(alias)).value()
                    && !es.indices().getAlias(a -> a.name(alias)).aliases().keySet().equals(Set.of(index))) {
                throw new IllegalStateException("评论索引别名已经指向其他索引");
            }
        }
        es.indices().updateAliases(request -> request
                .actions(a -> a.add(v -> v.index(index).alias(readAlias())))
                .actions(a -> a.add(v -> v.index(index).alias(writeAlias()).isWriteIndex(true))));
    }

    Set<Long> write(List<CommentIndexTasks.Document> documents) throws IOException {
        if (documents.isEmpty()) return Set.of();
        var operations = documents.stream().map(doc -> BulkOperation.of(op -> op.index(i -> i.index(writeAlias())
                .id(Long.toString(doc.commentId())).requireAlias(true).document(doc)
                .version(doc.indexVersion()).versionType(VersionType.External)))).toList();
        var result = es.bulk(b -> b.operations(operations));
        var successful = new HashSet<Long>();
        for (int i = 0; i < result.items().size(); i++) {
            var item = result.items().get(i);
            if (item.error() == null || (item.status() == 409 && "version_conflict_engine_exception".equals(item.error().type()))) {
                successful.add(documents.get(i).commentId());
            } else {
                log.warn("评论索引项写入失败 commentId={} status={} type={}",
                        documents.get(i).commentId(), item.status(), item.error().type());
            }
        }
        return successful;
    }

    void refresh() throws IOException {
        es.indices().refresh(r -> r.index(readAlias()));
    }

    long differences(List<CommentIndexTasks.Document> documents) throws IOException {
        var response = es.mget(m -> m.index(readAlias())
                .ids(documents.stream().map(doc -> Long.toString(doc.commentId())).toList()), CommentIndexTasks.Document.class);
        long differences = 0;
        for (int i = 0; i < documents.size(); i++) {
            var reply = response.docs().get(i);
            var expected = documents.get(i);
            if (reply.isFailure() || !reply.result().found() || reply.result().source() == null) {
                differences++;
                continue;
            }
            var actual = reply.result().source();
            if (actual.indexVersion() < expected.indexVersion()
                    || (actual.indexVersion() == expected.indexVersion() && !actual.equals(expected))) differences++;
        }
        return differences;
    }

    String open() throws IOException {
        return es.openPointInTime(p -> p.index(readAlias()).keepAlive(t -> t.time("2m")).allowPartialSearchResults(false)).id();
    }

    Batch page(CommentListKey key, String pit, List<SearchCursor.Value> after, int size) throws IOException {
        var search = new SearchRequest.Builder().pit(p -> p.id(pit).keepAlive(t -> t.time("2m")))
                .query(q -> q.bool(b -> b.filter(f -> f.term(t -> t.field("articleId").value(key.articleId())))
                        .filter(f -> f.term(t -> t.field("visible").value(true)))))
                .size(size).source(s -> s.fetch(false)).trackTotalHits(t -> t.enabled(false))
                .allowPartialSearchResults(false).timeout("2s")
                .sort(s -> s.field(f -> f.field(key.sort() == CommentListKey.Sort.HOT ? "likeCount" : "createdAt")
                        .order(SortOrder.Desc)))
                .sort(s -> s.field(f -> f.field("commentId").order(SortOrder.Desc)));
        if (!after.isEmpty()) search.searchAfter(after.stream().map(SearchCursor.Value::field).toList());
        var result = es.search(search.build(), Void.class);
        if (result.timedOut() || result.shards().failed().intValue() > 0) throw new IOException("评论 ES 查询不完整");
        return new Batch(result.pitId() == null ? pit : result.pitId(), result.hits().hits().stream()
                .map(hit -> new Hit(Long.parseLong(hit.id()), hit.sort().stream().map(SearchCursor.Value::from).toList()))
                .toList());
    }

    void close(String pit) {
        try {
            es.closePointInTime(c -> c.id(pit));
        } catch (IOException | RuntimeException ignored) {
            // 关闭失败由 PIT 短租期回收，不覆盖已经取得的结果。
        }
    }

    private String readAlias() { return name + "-read"; }
    private String writeAlias() { return name + "-write"; }

    record Hit(long id, List<SearchCursor.Value> sort) { }
    record Batch(String pit, List<Hit> hits) { }
}
