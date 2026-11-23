package com.febrie.backend.article.comment;

import com.febrie.backend.shared.cache.CacheProtocol;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Web 冷回源与后台刷新共用构建算法；只有完整快照可切换为当前版本。 */
@Service
public class CommentSnapshots {
    private static final Logger log = LoggerFactory.getLogger(CommentSnapshots.class);
    private static final int BATCH = 250;
    private final CommentStore store;
    private final CommentSnapshotCache cache;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final PlatformTransactionManager transactions;
    private final Semaphore coldBuilds = new Semaphore(2);

    public CommentSnapshots(CommentStore store, CommentSnapshotCache cache, Clock clock,
                            MeterRegistry metrics, PlatformTransactionManager transactions) {
        this.store = store;
        this.cache = cache;
        this.clock = clock;
        this.metrics = metrics;
        this.transactions = transactions;
    }

    CommentSnapshot current(CommentListKey key) {
        long deadline = System.nanoTime() + CacheProtocol.WAIT_BUDGET.toNanos();
        do {
            var snapshot = cache.current(key);
            if (cache.available(key, snapshot)) return snapshot;

            String token = cache.lock(key);
            if (token != null) {
                try {
                    snapshot = cache.current(key);
                    if (cache.available(key, snapshot)) return snapshot;
                    if (!coldBuilds.tryAcquire()) throw CommentErrors.unavailable();
                    try {
                        var published = rebuild(key, snapshot, token, false);
                        if (published != null) return published;
                    } finally {
                        coldBuilds.release();
                    }
                } finally {
                    release(key, token);
                }
            }
        } while (CacheProtocol.awaitRetry(deadline));
        event("cold_timeout");
        throw CommentErrors.unavailable();
    }

    boolean stale(CommentSnapshot snapshot) {
        return snapshot.softAt() <= clock.millis();
    }

    void refresh(CommentListKey key, String expectedId) {
        String token = cache.lock(key);
        if (token == null) return;
        try {
            var expected = cache.current(key);
            if (expected == null || !expected.id().equals(expectedId) || !stale(expected)
                    || !cache.available(key, expected)) {
                event("outdated");
                return;
            }
            if (rebuild(key, expected, token, true) == null) event("outdated");
        } finally {
            release(key, token);
        }
    }

    private CommentSnapshot rebuild(CommentListKey key, CommentSnapshot expected, String token, boolean refresh) {
        int seconds = refresh ? 45 : 5;
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(seconds);
        String id = UUID.randomUUID().toString();
        var batch = new ArrayList<String>(BATCH);
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        tx.setTimeout(seconds);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        long[] count = {0};
        try {
            tx.executeWithoutResult(status -> store.streamIndex(key, (commentId, sortValue) -> {
                checkBudget(deadline);
                batch.add(CommentSnapshot.member(sortValue, commentId));
                count[0]++;
                if (batch.size() == BATCH) {
                    cache.stage(key, id, token, batch);
                    batch.clear();
                }
            }));
            if (!batch.isEmpty()) cache.stage(key, id, token, batch);
            checkBudget(deadline);
            var result = cache.publish(key, expected, id, token, count[0], clock.millis(), refresh);
            if (result != null) metrics.summary("community.comment.rebuild.rows", "sort", key.sort().name()).record(count[0]);
            return result;
        } finally {
            metrics.timer("community.comment.rebuild", "mode", refresh ? "refresh" : "cold")
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private static void checkBudget(long deadline) {
        if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) throw CommentErrors.unavailable();
    }

    private void release(CommentListKey key, String token) {
        try {
            cache.release(key, token);
        } catch (RuntimeException failure) {
            log.warn("评论列表重建锁释放失败 key={}", key, failure);
        }
    }

    void event(String outcome) {
        metrics.counter("community.comment.cache.events", "outcome", outcome).increment();
    }
}
