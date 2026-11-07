package com.febrie.backend.article.search.infrastructure.maintenance;

import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleIndexAdmin;
import org.springframework.boot.*;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/** 非Web显式维护入口；异常传播为非零退出码，普通启动不执行远端操作。 */
@Component
public class SearchMaintenance implements ApplicationRunner {
    private final SearchProperties p;
    private final ArticleIndexAdmin admin;
    private final ApplicationContext context;
    public SearchMaintenance(SearchProperties p, ArticleIndexAdmin admin, ApplicationContext context) {
        this.p = p; this.admin = admin; this.context = context;
    }
    @Override public void run(ApplicationArguments arguments) throws Exception {
        if (!p.maintaining()) { return; }
        if (context instanceof WebApplicationContext) { throw new IllegalStateException("索引维护必须使用非Web模式"); }
        if (p.maintenance().equals("init")) { admin.initialize(p.target(), p.targetIndex().isBlank()); }
        else { throw new IllegalArgumentException("当前维护动作尚未接入"); }
    }
}
