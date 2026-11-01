package com.febrie.backend.article.application;

import com.febrie.backend.article.infrastructure.cache.ArticleViewStore;
import java.time.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Redis快照→日期事务→Redis修复；事务代理成功返回前绝不修复。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ArticleViewFlush {
    private static final Logger log = LoggerFactory.getLogger(ArticleViewFlush.class);
    private final ArticleViewStore store;
    private final ArticleViewSqlService sql;
    private final Clock clock;

    public ArticleViewFlush(ArticleViewStore store, ArticleViewSqlService sql, Clock clock) {
        this.store = store; this.sql = sql; this.clock = clock;
    }

    public void flush() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ArticleViewService.BUSINESS_ZONE);
        flushDate(today.minusDays(1));
        flushDate(today);
    }

    private void flushDate(LocalDate date) {
        try {
            var snapshot = store.daily(date);
            if (snapshot.isEmpty()) { return; }
            var result = sql.flush(date, snapshot);
            store.repair(date, result);
        } catch (RuntimeException failure) {
            // 单日期失败不阻止另一日期；下一轮按累计值重试，无需回放本次事务。
            log.warn("文章浏览量刷盘或修复失败 date={} error={}", date, failure.getClass().getSimpleName());
        }
    }
}
