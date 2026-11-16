package com.febrie.backend.performance;

import com.febrie.backend.article.content.application.*;
import com.febrie.backend.article.content.application.dto.*;
import com.febrie.backend.article.view.application.ArticleViewService;
import com.febrie.backend.article.view.infrastructure.persistence.mapper.ArticleViewStatMapper;
import java.time.*;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** 基准路由仅存在于测试源码且需专用profile；生产制品不包含任何绕过鉴权的入口。 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("resume-perf")
public class PerfConfiguration {
    @Bean PerfSqlCounter perfSqlCounter() { return new PerfSqlCounter(); }
    @Bean @Order(0) SecurityFilterChain perfSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/perf/**").csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }

    /** A/B只替换目标步骤，保持相同HTTP序列化与作者装配；不包含登录和ES候选发现。 */
    @RestController
    @Profile("resume-perf")
    public static class PerfController {
        private final ArticleReader reader;
        private final ArticleListHydrator cards;
        private final ArticleDisplayAssembler display;
        private final ArticleViewService views;
        private final ArticleViewStatMapper stats;
        private final TransactionTemplate transaction;
        volatile List<Long> articleIds = List.of();
        PerfController(ArticleReader reader, ArticleListHydrator cards, ArticleDisplayAssembler display,
                       ArticleViewService views, ArticleViewStatMapper stats, TransactionTemplate transaction) {
            this.reader = reader; this.cards = cards; this.display = display; this.views = views;
            this.stats = stats; this.transaction = transaction;
        }
        @GetMapping("/api/perf/cards/{mode}")
        public List<ArticleListView> cards(@PathVariable String mode) {
            if (mode.equals("cached")) { return display.lists(cards.publicCandidates(articleIds)); }
            if (!mode.equals("source")) { throw new IllegalArgumentException("无效对照模式"); }
            var loaded = reader.lists(articleIds);
            return display.lists(articleIds.stream().map(id -> loaded.get(id).data()).toList());
        }
        @GetMapping("/api/perf/pv/{mode}/{id}")
        public long pv(@PathVariable String mode, @PathVariable long id) {
            if (!articleIds.contains(id)) { throw new IllegalArgumentException("只能操作本轮文章"); }
            if (mode.equals("redis")) { return views.record(id); }
            if (!mode.equals("sql")) { throw new IllegalArgumentException("无效对照模式"); }
            // 同步基线同样维护日累计与总量，行锁下执行两条写SQL；不是历史版本实现。
            return Objects.requireNonNull(transaction.execute(status -> {
                LocalDate date = LocalDate.now(ArticleViewService.BUSINESS_ZONE);
                Long previous = stats.selectPvForUpdate(id, date);
                stats.incrementTotal(id, 1);
                stats.upsertPv(id, date, (previous == null ? 0 : previous) + 1);
                return stats.selectTotal(id);
            }));
        }
    }
}
