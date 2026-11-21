package com.febrie.backend.article.like.infrastructure.messaging;

import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Web实例共享文章任务线程池；监听器暂停时保留线程池，应用关闭时统一释放。 */
public final class LikeArticleExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LikeArticleExecutor.class);
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(5, 8, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), Thread.ofPlatform().name("like-article-", 1).factory(),
            new ThreadPoolExecutor.AbortPolicy()) {
        @Override protected void beforeExecute(Thread thread, Runnable task) {
            super.beforeExecute(thread, task);
            // 任务出队就通知监听器，此时可以补入一个任务，无需等到SQL完成。
            signalChange();
        }
    };
    private long changeVersion;

    public LikeArticleExecutor(LikeTelemetry telemetry) {
        telemetry.articleExecutor(pool);
    }

    boolean tryExecute(FutureTask<?> task) {
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            if (pool.isShutdown()) {
                throw rejected;
            }
            return false;
        }
    }

    void cancelAll(Collection<? extends FutureTask<?>> tasks) {
        // 先移除排队任务，再中断运行中的任务，避免释放的工作线程捡到本批已放弃的任务。
        boolean removed = false;
        for (var task : tasks) {
            removed |= pool.remove(task);
        }
        tasks.forEach(task -> task.cancel(true));
        if (removed) {
            signalChange();
        }
    }

    synchronized long changeVersion() {
        return changeVersion;
    }

    synchronized void signalChange() {
        changeVersion++;
        notifyAll();
    }

    /** 检查完成结果和尝试提交之前取版本，避免遗漏两者与等待之间的通知。 */
    synchronized void awaitChange(long observedVersion) throws InterruptedException {
        while (!pool.isShutdown() && changeVersion == observedVersion) {
            wait();
        }
        if (pool.isShutdown()) {
            throw new RejectedExecutionException("点赞文章执行器已关闭");
        }
    }

    @Override public void close() {
        // 容器先完成停机宽限，剩余任务由未确认消息重投；这里只负责释放本地任务。
        for (Runnable task : pool.shutdownNow()) {
            ((FutureTask<?>) task).cancel(false);
        }
        signalChange();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("点赞文章执行器停止超时");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
