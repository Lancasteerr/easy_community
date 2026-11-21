package com.febrie.backend.article.like.infrastructure.messaging;

import com.febrie.backend.article.like.application.*;
import com.febrie.backend.article.like.domain.LikeCommand;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.*;
import org.springframework.amqp.core.Message;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionException;

/** 每批按文章并行提交；只有监听线程可以确认当前Channel的原始投递。 */
public class LikeConsumer {
    private static final Logger log = LoggerFactory.getLogger(LikeConsumer.class);
    private final LikeCodec codec;
    private final LikeSqlService sql;
    private final LikeProjection projection;
    private final LikeTelemetry telemetry;
    private final LikeProperties properties;
    private final LikePartitionRouter router;
    private final LikeArticleExecutor executor;
    private final LikeBatchReducer reducer = new LikeBatchReducer();
    private final Delay delay;

    public LikeConsumer(LikeCodec codec, LikeSqlService sql, LikeProjection projection,
                        LikeTelemetry telemetry, LikeProperties properties, LikeArticleExecutor executor) {
        this(codec, sql, projection, telemetry, properties, executor, Thread::sleep);
    }

    /** 退避可替换，单元测试通过屏障模拟中断而不真实等待。 */
    public LikeConsumer(LikeCodec codec, LikeSqlService sql, LikeProjection projection,
                        LikeTelemetry telemetry, LikeProperties properties, LikeArticleExecutor executor, Delay delay) {
        this.codec = codec;
        this.sql = sql;
        this.projection = projection;
        this.telemetry = telemetry;
        this.properties = properties;
        this.executor = executor;
        this.delay = delay;
        this.router = new LikePartitionRouter(properties);
    }

    public void consumeBatch(int partition, List<Message> messages, Channel channel) {
        try {
            checkInterrupted();
            telemetry.batch(partition, messages.size());
            // 生产端只发布独立命令，拒绝超界输入而不建立跨批次缓冲。
            if (messages.size() > properties.batchSize()) {
                for (var message : messages) { isolate(partition, channel, tag(message), "batch_too_large"); }
                return;
            }
            var articles = prepareArticles(partition, messages, channel);
            if (!articles.isEmpty()) {
                dispatchArticles(partition, articles, channel);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            abort(channel, partition, "interrupted");
        } catch (IOException | ShutdownSignalException transportFailure) {
            // 已部分确认的批次不能统一拒绝；关闭Channel让Broker重投剩余消息。
            abort(channel, partition, "channel_failure");
        } catch (ExecutionException | CancellationException | RejectedExecutionException workerFailure) {
            var cause = workerFailure instanceof ExecutionException ? workerFailure.getCause() : workerFailure;
            log.warn("点赞文章任务中止 partition={} errorType={}", partition, cause.getClass().getSimpleName());
            abort(channel, partition, "worker_failure");
        }
    }

    private List<ArticleWork> prepareArticles(int partition, List<Message> messages, Channel channel) throws IOException {
        Map<Long, List<Delivery>> articles = new LinkedHashMap<>();
        for (Message message : messages) {
            long deliveryTag = tag(message);
            LikeCommand command;
            try {
                command = codec.decode(message.getBody());
            } catch (RuntimeException invalid) {
                isolate(partition, channel, deliveryTag, "invalid_message");
                continue;
            }
            if (router.partition(command.articleId()) != partition) {
                isolate(partition, channel, deliveryTag, "wrong_partition");
                continue;
            }
            telemetry.queueAge(command.occurredAt());
            articles.computeIfAbsent(command.articleId(), ignored -> new ArrayList<>())
                    .add(new Delivery(deliveryTag, command));
        }

        List<ArticleWork> work = new ArrayList<>(articles.size());
        for (var article : articles.entrySet()) {
            var deliveries = article.getValue();
            var reduced = reducer.reduce(deliveries.stream().map(Delivery::command).toList());
            for (int index : reduced.rejected()) {
                isolate(partition, channel, deliveries.get(index).tag(), "version_conflict");
            }
            if (!reduced.commands().isEmpty()) {
                var tags = reduced.accepted().stream().map(index -> deliveries.get(index).tag()).toList();
                telemetry.articleGroup(partition, tags.size(), reduced.commands().size());
                work.add(new ArticleWork(article.getKey(), reduced.commands(), tags));
            }
        }
        return work;
    }

    private void dispatchArticles(int partition, List<ArticleWork> articles, Channel channel)
            throws IOException, InterruptedException, ExecutionException {
        var pending = articles.iterator();
        Set<ArticleTask> submitted = new HashSet<>();
        Queue<ArticleTask> completed = new ConcurrentLinkedQueue<>();
        ShutdownListener onShutdown = ignored -> executor.signalChange();
        channel.addShutdownListener(onShutdown);
        try {
            var next = new ArticleTask(partition, pending.next(), completed);
            while (next != null || !submitted.isEmpty()) {
                long observedVersion = executor.changeVersion();
                checkInterrupted();
                if (!channel.isOpen()) {
                    throw new IOException("点赞消费Channel已关闭");
                }
                // 优先确认已完成的组；池满时等待出队、完成或断线通知。
                ArticleTask finished = completed.poll();
                if (finished != null) {
                    submitted.remove(finished);
                    acknowledge(partition, finished.work, finished.get(), channel);
                } else if (next != null && executor.tryExecute(next)) {
                    submitted.add(next);
                    next = pending.hasNext() ? new ArticleTask(partition, pending.next(), completed) : null;
                } else {
                    executor.awaitChange(observedVersion);
                }
            }
        } finally {
            executor.cancelAll(submitted);
            channel.removeShutdownListener(onShutdown);
        }
    }

    /** 事务、退避和缓存维护在线程池执行，不持有任何Channel。 */
    private ArticleOutcome applyArticle(int partition, ArticleWork work) throws InterruptedException {
        LikeSqlService.ArticleResult result = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            checkInterrupted();
            long started = System.nanoTime();
            try {
                result = sql.applyArticle(work.articleId(), work.commands());
                break;
            } catch (TransientDataAccessException | RecoverableDataAccessException | DataAccessResourceFailureException | TransactionException retryable) {
                telemetry.event("consume", "retry");
                telemetry.partitionEvent(partition, "retry");
                if (attempt == 4) {
                    return ArticleOutcome.RETRY_EXHAUSTED;
                }
            } catch (RuntimeException fatal) {
                log.error("点赞文章事务失败 articleId={} errorType={}", work.articleId(), fatal.getClass().getSimpleName());
                return ArticleOutcome.SQL_INVARIANT_OR_ERROR;
            } finally {
                telemetry.time("sql", started);
                telemetry.articleTime(partition, started);
            }
            // 事务代理已退出，退避不持有数据库锁，也不计入SQL耗时。
            delay.await(1000L << attempt);
        }
        if (result == null) {
            throw new IllegalStateException("SQL文章消费未得到结果");
        }
        projectArticle(partition, result);
        return ArticleOutcome.COMMITTED;
    }

    private void projectArticle(int partition, LikeSqlService.ArticleResult result) {
        for (var relation : result.results()) {
            telemetry.event("consume", relation.status().name().toLowerCase(Locale.ROOT));
        }
        try {
            projection.committedArticle(result);
        } catch (RuntimeException cacheFailure) {
            telemetry.event("cache", "commit_failure");
            telemetry.partitionEvent(partition, "cache_failure");
            log.warn("点赞文章已提交，缓存维护失败 articleId={}", result.articleId());
        }
    }

    private void acknowledge(int partition, ArticleWork work, ArticleOutcome outcome, Channel channel)
            throws IOException, InterruptedException {
        for (long tag : work.tags()) {
            checkInterrupted();
            if (outcome == ArticleOutcome.COMMITTED) {
                channel.basicAck(tag, false);
            } else {
                isolate(partition, channel, tag, outcome.name().toLowerCase(Locale.ROOT));
            }
        }
        if (outcome == ArticleOutcome.COMMITTED) {
            telemetry.partitionEvent(partition, "committed");
        }
    }

    private void isolate(int partition, Channel channel, long tag, String reason) throws IOException {
        telemetry.event("consume", reason);
        telemetry.partitionEvent(partition, reason);
        log.error("点赞消息隔离 partition={} reason={} deliveryTag={}", partition, reason, tag);
        // 依赖Broker可靠死信转发，不能先ACK再自行发送死信。
        channel.basicNack(tag, false, false);
    }

    private void abort(Channel channel, int partition, String reason) {
        telemetry.partitionEvent(partition, reason);
        try { channel.abort(); }
        catch (Exception failure) { log.warn("点赞消费Channel终止失败 partition={}", partition); }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("点赞消费任务已中断");
        }
    }

    private final class ArticleTask extends FutureTask<ArticleOutcome> {
        private final ArticleWork work;
        private final Queue<ArticleTask> completed;

        private ArticleTask(int partition, ArticleWork work, Queue<ArticleTask> completed) {
            super(() -> LikeConsumer.this.applyArticle(partition, work));
            this.work = work;
            this.completed = completed;
        }

        @Override protected void done() {
            completed.add(this);
            executor.signalChange();
        }
    }

    private static long tag(Message message) { return message.getMessageProperties().getDeliveryTag(); }
    private record Delivery(long tag, LikeCommand command) { }
    private record ArticleWork(long articleId, List<LikeCommand> commands, List<Long> tags) { }
    private enum ArticleOutcome { COMMITTED, RETRY_EXHAUSTED, SQL_INVARIANT_OR_ERROR }
    @FunctionalInterface public interface Delay { void await(long milliseconds) throws InterruptedException; }
}
