package com.febrie.backend.media.application;

import java.time.Duration;
import java.util.function.LongSupplier;

/** 从通知入口起共享同一个单调时钟预算；只协作式检查，不抢占线程或响应。 */
public final class ProcessingDeadline {
    private final LongSupplier ticker;
    private final long start;
    private final long budget;
    public ProcessingDeadline(Duration duration) { this(duration, System::nanoTime); }
    public ProcessingDeadline(Duration duration, LongSupplier ticker) {
        this.ticker = ticker; this.start = ticker.getAsLong(); this.budget = duration.toNanos();
    }
    public int remainingMillis() {
        long remaining = budget - (ticker.getAsLong() - start);
        if (remaining <= 0) { throw new TimedOut(); }
        // SDK的0毫秒通常表示无限等待，剩余不足1毫秒时也不能传0。
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, remaining / 1_000_000));
    }
    public boolean expired() { return ticker.getAsLong() - start >= budget; }
    public void check() { if (expired()) { throw new TimedOut(); } }
    public long elapsedMillis() { return (ticker.getAsLong() - start) / 1_000_000; }
    /** 使用专用异常映射超时，不将SDK错误内容返回客户端。 */
    public static final class TimedOut extends RuntimeException {
        public TimedOut() { }
        public TimedOut(Throwable cause) { super(cause); }
    }
}
