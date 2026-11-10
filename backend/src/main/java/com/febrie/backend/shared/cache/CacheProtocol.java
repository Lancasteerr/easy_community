package com.febrie.backend.shared.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/** 仅共享租期和等待规则，领域缓存仍自行定义数据与Lua操作。 */
public final class CacheProtocol {
    public static final Duration LOCK_TTL = Duration.ofSeconds(5);
    public static final Duration WAIT_BUDGET = Duration.ofMillis(300);
    private CacheProtocol() { }
    public static long dataTtlMillis() { return ThreadLocalRandom.current().nextLong(270_000, 300_001); }

    /** 调用前必须已释放全部缓存锁；等待不延长当前批次的共同预算。 */
    public static boolean awaitRetry(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) { return false; }
        LockSupport.parkNanos(Math.min(25_000_000, remaining));
        return System.nanoTime() < deadline && !Thread.currentThread().isInterrupted();
    }
}
