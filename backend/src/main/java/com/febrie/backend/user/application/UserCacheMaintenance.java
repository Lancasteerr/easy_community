package com.febrie.backend.user.application;

import com.febrie.backend.shared.cache.CacheProtocol;
import com.febrie.backend.user.infrastructure.cache.UserBasicCacheStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/** 业务入口在写事务中登记，只有最外层提交后才竞争缓存锁并尽力删除。 */
@Component
public class UserCacheMaintenance {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserCacheMaintenance.class);
    private final UserBasicCacheStore cache;
    public UserCacheMaintenance(UserBasicCacheStore cache) { this.cache = cache; }

    public void invalidateAfterCommit(long id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("用户缓存失效必须在业务写事务中登记");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { invalidate(id); }
                catch (RuntimeException error) {
                    // 包住完整回调，避免维护实现变化将已提交结果误报为失败。
                    log.warn("用户已提交但缓存维护中断 userId={} error={}", id, error.getClass().getSimpleName());
                }
            }
        });
    }

    private void invalidate(long id) {
        long deadline = System.nanoTime() + CacheProtocol.WAIT_BUDGET.toNanos();
        for (int attempt = 0; attempt < 3; attempt++) {
            String token = null;
            try {
                token = cache.tryLock(id);
                if (token != null && cache.invalidate(id, token)) { return; }
            } catch (RuntimeException error) {
                log.warn("用户提交后缓存删除异常 userId={} attempt={} error={}", id, attempt + 1, error.getClass().getSimpleName());
            } finally {
                if (token != null) { cache.release(id, token); }
            }
            if (attempt == 2 || !CacheProtocol.awaitRetry(deadline)) { break; }
        }
        cache.record("invalidation_failed");
        log.warn("用户提交后缓存删除未完成 userId={}", id);
    }
}
