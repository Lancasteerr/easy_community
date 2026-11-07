package com.febrie.backend.article.search.infrastructure.maintenance;

import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleIndexAdmin;
import org.springframework.boot.*;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;
import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.messaging.IndexDeadLetterReplay;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import tools.jackson.databind.ObjectMapper;

/** 非Web显式维护入口；异常传播为非零退出码，普通启动不执行远端操作。 */
@Component
public class SearchMaintenance implements ApplicationRunner {
    private final SearchProperties p;
    private final ArticleIndexAdmin admin;
    private final ApplicationContext context;
    private final ArticleIndexMaintenance data;
    private final ArticleRankService ranks;
    private final IndexDeadLetterReplay replay;
    private final ElasticsearchClient es;
    private final ObjectMapper json;
    public SearchMaintenance(SearchProperties p, ArticleIndexAdmin admin, ApplicationContext context,
            ArticleIndexMaintenance data, ArticleRankService ranks, IndexDeadLetterReplay replay, ElasticsearchClient es, ObjectMapper json) {
        this.p = p; this.admin = admin; this.context = context; this.data = data; this.ranks = ranks; this.replay = replay; this.es = es; this.json = json;
    }
    @Override public void run(ApplicationArguments arguments) throws Exception {
        if (!p.maintaining()) { return; }
        if (context instanceof WebApplicationContext) { throw new IllegalStateException("索引维护必须使用非Web模式"); }
        Object result = switch (p.maintenance()) {
            case "init" -> { admin.initialize(p.target(), p.targetIndex().isBlank()); yield java.util.Map.of("index", p.target()); }
            case "import" -> java.util.Map.of("imported", data.importAll(p.target()));
            case "calibrate" -> {
                admin.verify(p.target());
                var written = ranks.scan(true, p.target(), false);
                es.indices().refresh(r -> r.index(p.target()));
                if (written.missing() + written.failed() > 0) { throw new IllegalStateException("全量算分未完整成功，请核对元数据与ES错误"); }
                yield written;
            }
            case "audit", "repair" -> {
                var audit = data.audit(p.target(), p.maintenance().equals("repair"));
                if (audit.differences() > 0) {
                    System.out.println(json.writeValueAsString(audit));
                    throw new IllegalStateException("索引对账仍有差异；版本保护不能修复的损坏需重建索引");
                }
                yield audit;
            }
            case "replay" -> java.util.Map.of("replayed", replay.replay(p.replayLimit()));
            case "switch" -> {
                if (p.targetIndex().isBlank()) { throw new IllegalArgumentException("切换别名必须显式指定target-index"); }
                admin.switchAliases(p.target()); yield java.util.Map.of("index", p.target());
            }
            default -> throw new IllegalArgumentException("未知维护动作");
        };
        System.out.println(json.writeValueAsString(java.util.Map.of("action", p.maintenance(), "result", result)));
    }
}
