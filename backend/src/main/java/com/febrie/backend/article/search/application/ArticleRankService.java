package com.febrie.backend.article.search.application;

import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleRankStore;
import com.febrie.backend.article.search.infrastructure.persistence.mapper.ArticleIndexMapper;
import com.febrie.backend.article.view.application.ArticleViewService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 候选刷新、首次算分和全量校准共用同一批量计算，不持久保存旧分数任务。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ArticleRankService implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(ArticleRankService.class);
    private final ArticleIndexMapper mapper;
    private final ArticleRankReader reader;
    private final ArticleRankStore store;
    private final SearchProperties p;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final AtomicLong lastRefresh = new AtomicLong(), lastCalibration = new AtomicLong();
    private final ThreadPoolExecutor first = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), Thread.ofPlatform().daemon(true).name("article-first-rank-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    public ArticleRankService(ArticleIndexMapper mapper, ArticleRankReader reader, ArticleRankStore store,
                              SearchProperties p, Clock clock, MeterRegistry metrics) {
        this.mapper = mapper; this.reader = reader; this.store = store; this.p = p; this.clock = clock; this.metrics = metrics;
        metrics.gauge("article.rank.refresh.last.success.seconds", lastRefresh);
        metrics.gauge("article.rank.calibration.last.success.seconds", lastCalibration);
    }
    public ArticleRankStore.WriteResult calculate(Collection<Long> ids, LocalDate day, long version, String target, boolean alias) {
        if (ids.size() > 500) { throw new IllegalArgumentException("排名批次最多500篇"); }
        var values = reader.load(ids, day).stream().map(r -> new ArticleRankStore.Score(r.articleId(), RankFormula.score(r.views(), r.publishedAt()), version)).toList();
        return store.write(target, alias, values);
    }
    public ArticleRankStore.WriteResult scan(boolean full, String target, boolean alias) {
        long version = clock.millis();
        LocalDate day = LocalDate.ofInstant(clock.instant(), ArticleViewService.BUSINESS_ZONE);
        long upper = mapper.maxId();
        int missing = 0, failed = 0;
        List<LocalDate> dates = full ? Collections.singletonList(null) : List.of(day, day.minusDays(1), day.minusDays(7));
        for (LocalDate date : dates) {
            long after = 0;
            while (true) {
                var ids = full ? mapper.scanIds(after, upper, true) : mapper.candidates(date, after, upper);
                if (ids.isEmpty()) { break; }
                var result = calculate(ids, day, version, target, alias);
                missing += result.missing(); failed += result.failed(); after = ids.getLast();
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("排名扫描中断", interrupted); }
            }
        }
        if (missing == 0 && failed == 0) { (full ? lastCalibration : lastRefresh).set(clock.instant().getEpochSecond()); }
        return new ArticleRankStore.WriteResult(missing, failed);
    }
    public void tryFirst(long id) {
        try {
            first.execute(() -> {
                try {
                    calculate(List.of(id), LocalDate.ofInstant(clock.instant(), ArticleViewService.BUSINESS_ZONE), clock.millis(), p.writeAlias(), true);
                } catch (RuntimeException failure) { metrics.counter("article.rank.first.failure").increment(); log.warn("首次算分失败 articleId={}", id); }
            });
        } catch (RejectedExecutionException rejected) { metrics.counter("article.rank.first.rejected").increment(); }
    }
    @Override public void destroy() { first.shutdownNow(); }
}
