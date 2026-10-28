package com.febrie.backend.media.infrastructure.config;

import java.util.concurrent.*;

/** 专用截止计时器不暴露为ScheduledExecutorService Bean，避免被@Scheduled维护任务占用。 */
public final class NotificationDeadlineTimers implements AutoCloseable {
    private final ScheduledThreadPoolExecutor executor;
    public NotificationDeadlineTimers() {
        executor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon(true).name("media-deadline-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
    }
    public ScheduledFuture<?> schedule(Runnable timeout, int delayMillis) {
        return executor.schedule(timeout, delayMillis, TimeUnit.MILLISECONDS);
    }
    @Override public void close() { executor.shutdownNow(); }
}
