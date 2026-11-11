package com.febrie.backend.article.search.application;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.febrie.backend.article.search.infrastructure.elasticsearch.*;
import com.febrie.backend.article.search.infrastructure.persistence.mapper.ArticleIndexMapper;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 显式分段导入/对账，复用在线版本保护；稳定验收需要维护窗口。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ArticleIndexMaintenance {
    private final ArticleIndexMapper mapper;
    private final ArticleIndexReader reader;
    private final ArticleIndexStore store;
    private final ArticleIndexAdmin admin;
    private final ElasticsearchClient es;
    public ArticleIndexMaintenance(ArticleIndexMapper mapper, ArticleIndexReader reader, ArticleIndexStore store,
                                   ArticleIndexAdmin admin, ElasticsearchClient es) {
        this.mapper = mapper; this.reader = reader; this.store = store; this.admin = admin; this.es = es;
    }
    public long importAll(String target) throws IOException {
        admin.verify(target);
        long upper = mapper.maxId(), after = 0, total = 0;
        while (true) {
            var ids = mapper.scanIds(after, upper, false);
            if (ids.isEmpty()) { break; }
            store.upsertBatch(target, new ArrayList<>(reader.load(ids).values()));
            after = ids.getLast(); total += ids.size(); pause();
        }
        es.indices().refresh(r -> r.index(target));
        return total;
    }
    public Audit audit(String target, boolean repair) throws IOException {
        admin.verify(target);
        long upper = mapper.maxId(), after = 0, checked = 0, differences = 0;
        List<Long> examples = new ArrayList<>();
        while (true) {
            var ids = mapper.scanIds(after, upper, false);
            if (ids.isEmpty()) { break; }
            var sql = reader.load(ids);
            var results = es.mget(m -> m.index(target).ids(ids.stream().map(Object::toString).toList()), Map.class).docs();
            List<ArticleIndexMetadata> fixes = new ArrayList<>();
            for (var item : results) {
                if (!item.isResult()) { throw new IOException("索引对账读取失败"); }
                var value = item.result();
                var expected = sql.get(Long.parseLong(value.id()));
                if (expected == null) { continue; }
                if (!value.found() || ((Number) value.source().get("metaVersion")).longValue() != expected.metaVersion()
                        // 等版本的作者缺失或损坏也必须报告，不能误判新字段已完整导入。
                        || !(value.source().get("authorId") instanceof Number author) || author.longValue() != expected.authorId()
                        || !Boolean.valueOf(expected.visible()).equals(value.source().get("visible"))) {
                    differences++;
                    if (examples.size() < 20) { examples.add(expected.articleId()); }
                    fixes.add(expected);
                }
            }
            if (repair && !fixes.isEmpty()) { store.upsertBatch(target, fixes); }
            after = ids.getLast(); checked += sql.size(); pause();
        }
        if (repair) {
            es.indices().refresh(r -> r.index(target));
            // 复核而不无条件覆盖较高版本；无法通过正常同步修复的损坏交给整索引重建。
            return audit(target, false);
        }
        return new Audit(checked, differences, List.copyOf(examples));
    }
    private static void pause() {
        try { Thread.sleep(50); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("索引维护中断", interrupted); }
    }
    public record Audit(long checked, long differences, List<Long> examples) { }
}
