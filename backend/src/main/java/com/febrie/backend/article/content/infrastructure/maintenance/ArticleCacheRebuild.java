package com.febrie.backend.article.content.infrastructure.maintenance;

import com.febrie.backend.article.content.infrastructure.cache.ArticleCache;
import com.febrie.backend.article.content.infrastructure.persistence.ArticleRepository;
import org.slf4j.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/** 维护窗口停流量并停止旧实例后运行；只重建新文章版本，不清共享Redis。 */
@Component
@ConditionalOnProperty(name = "app.article.cache-rebuild.enabled", havingValue = "true")
public class ArticleCacheRebuild implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ArticleCacheRebuild.class);
    private final ArticleRepository articles;
    private final ArticleCache cache;
    private final ApplicationContext context;
    public ArticleCacheRebuild(ArticleRepository articles, ArticleCache cache, ApplicationContext context) {
        this.articles = articles; this.cache = cache; this.context = context;
    }
    @Override public void run(ApplicationArguments arguments) {
        if (context instanceof WebApplicationContext) { throw new IllegalStateException("文章缓存恢复必须以非Web模式运行"); }
        cache.requireUnusedNamespace();
        long after = 0, count = 0;
        while (true) {
            var ids = articles.hiddenIds(after, 500);
            if (ids.isEmpty()) { break; }
            for (long id : ids) {
                String token = cache.tryLock(id);
                if (token == null) { throw new IllegalStateException("恢复版本出现并发缓存写入，停止恢复"); }
                try {
                    if (!cache.hide(id, token)) { throw new IllegalStateException("恢复墓碑失败"); }
                } finally { cache.release(id, token); }
                after = id; count++;
            }
        }
        cache.markRebuilt();
        log.info("文章缓存墓碑重建完成 count={}", count);
    }
}
