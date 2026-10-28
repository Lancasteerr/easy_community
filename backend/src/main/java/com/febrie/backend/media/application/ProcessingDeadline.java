package com.febrie.backend.media.application;

import java.time.Duration;
import java.util.function.LongSupplier;

/** 单调时钟预算只属于本次调用，不落库；保留后续SQL时间后才允许外部调用。 */
public final class ProcessingDeadline {
    public static final int COMMIT_RESERVE_MS = 1250;
    private final LongSupplier ticker;
    private final long start;
    private final long budget;
    public ProcessingDeadline(Duration duration) { this(duration, System::nanoTime); }
    public ProcessingDeadline(Duration duration, LongSupplier ticker) {
        this.ticker = ticker; this.start = ticker.getAsLong(); this.budget = duration.toNanos();
    }
    public int remainingMillis(int reserve) {
        long remaining = (budget - (ticker.getAsLong() - start)) / 1_000_000 - reserve;
        if (remaining <= 0) { throw new TimedOut(); }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }
    public void check() { remainingMillis(0); }
    public long elapsedMillis() { return (ticker.getAsLong() - start) / 1_000_000; }
    /** 使用专用异常映射超时，不将SDK错误内容返回客户端。 */
    public static final class TimedOut extends RuntimeException { }
}
