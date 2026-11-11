package com.febrie.backend.article.tag.infrastructure.scheduling;

import com.febrie.backend.article.tag.application.TagCatalog;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅Web实例加载目录；随机首次延迟避免各实例同时检查主库。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TagDirectoryTask implements ApplicationRunner {
    private final TagCatalog catalog;
    public TagDirectoryTask(TagCatalog catalog) { this.catalog = catalog; }
    @Override public void run(ApplicationArguments arguments) { catalog.refreshBestEffort(); }
    @Scheduled(fixedDelayString = "${app.tag.refresh-delay:60s}",
            initialDelayString = "#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(60001)}")
    public void refresh() { catalog.refreshBestEffort(); }
}
