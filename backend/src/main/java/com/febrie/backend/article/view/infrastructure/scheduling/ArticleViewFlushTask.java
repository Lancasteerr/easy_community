package com.febrie.backend.article.view.infrastructure.scheduling;

import com.febrie.backend.article.view.application.ArticleViewFlush;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 离线恢复命令不刷统计；正常Web服务启动一分钟后每分钟运行。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ArticleViewFlushTask {
    private final ArticleViewFlush flush;

    public ArticleViewFlushTask(ArticleViewFlush flush) { this.flush = flush; }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void flush() { flush.flush(); }
}
