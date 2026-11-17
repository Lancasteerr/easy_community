package com.febrie.backend.article.like.infrastructure.messaging;

import com.febrie.backend.article.like.application.*;
import com.febrie.backend.article.like.domain.LikeCommand;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.util.*;
import org.slf4j.*;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** 混合批次仅用于收集；事务、缓存维护和确认都以文章组为边界。 */
@Component
public class LikeConsumer {
    private static final Logger log = LoggerFactory.getLogger(LikeConsumer.class);
    private final LikeCodec codec;
    private final LikeSqlService sql;
    private final LikeProjection projection;
    private final LikeTelemetry telemetry;
    private final LikeProperties properties;
    private final LikePartitionRouter router;
    private final LikeBatchReducer reducer = new LikeBatchReducer();
    private final Delay delay;

    @Autowired
    public LikeConsumer(LikeCodec codec, LikeSqlService sql, LikeProjection projection,
                        LikeTelemetry telemetry, LikeProperties properties) {
        this(codec, sql, projection, telemetry, properties, Thread::sleep);
    }

    /** 退避可替换，单元测试通过屏障模拟中断而不真实等待。 */
    public LikeConsumer(LikeCodec codec, LikeSqlService sql, LikeProjection projection,
                        LikeTelemetry telemetry, LikeProperties properties, Delay delay) {
        this.codec = codec; this.sql = sql; this.projection = projection;
        this.telemetry = telemetry; this.properties = properties; this.delay = delay;
        this.router = new LikePartitionRouter(properties);
    }

    public void consumeBatch(int partition, List<Message> messages, Channel channel) {
        try {
            if (Thread.currentThread().isInterrupted()) { throw new InterruptedException("消费线程已中断"); }
            telemetry.batch(partition, messages.size());
            // 生产端只发布独立命令，拒绝超界输入而不建立跨批次缓冲。
            if (messages.size() > properties.batchSize()) {
                for (var message : messages) { isolate(partition, channel, tag(message), "batch_too_large"); }
                return;
            }
            Map<Long, List<Delivery>> articles = new LinkedHashMap<>();
            for (Message message : messages) {
                LikeCommand command;
                try { command = codec.decode(message.getBody()); }
                catch (RuntimeException invalid) { isolate(partition, channel, tag(message), "invalid_message"); continue; }
                if (router.partition(command.articleId()) != partition) {
                    isolate(partition, channel, tag(message), "wrong_partition"); continue;
                }
                telemetry.queueAge(command.occurredAt());
                articles.computeIfAbsent(command.articleId(), ignored -> new ArrayList<>()).add(new Delivery(message, command));
            }
            for (var article : articles.entrySet()) { consumeArticle(partition, article.getKey(), article.getValue(), channel); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            abort(channel, partition, "interrupted");
        } catch (IOException | ShutdownSignalException transportFailure) {
            // 不把已部分确认的批次交给容器统一拒绝；关闭Channel让Broker重投剩余消息。
            abort(channel, partition, "channel_failure");
        }
    }

    private void consumeArticle(int partition, long articleId, List<Delivery> deliveries, Channel channel)
            throws IOException, InterruptedException {
        var reduced = reducer.reduce(deliveries.stream().map(Delivery::command).toList());
        for (int index : reduced.rejected()) { isolate(partition, channel, tag(deliveries.get(index).message()), "version_conflict"); }
        if (reduced.commands().isEmpty()) { return; }
        telemetry.articleGroup(partition, reduced.accepted().size(), reduced.commands().size());
        LikeSqlService.ArticleResult result = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            long started = System.nanoTime();
            try { result = sql.applyArticle(articleId, reduced.commands()); break; }
            catch (TransientDataAccessException | RecoverableDataAccessException | DataAccessResourceFailureException | TransactionException retryable) {
                telemetry.event("consume", "retry");
                telemetry.partitionEvent(partition, "retry");
                if (attempt == 4) { isolateGroup(partition, channel, deliveries, reduced.accepted(), "retry_exhausted"); return; }
            } catch (RuntimeException fatal) {
                log.error("点赞文章事务失败 articleId={} errorType={}", articleId, fatal.getClass().getSimpleName());
                isolateGroup(partition, channel, deliveries, reduced.accepted(), "sql_invariant_or_error"); return;
            } finally { telemetry.time("sql", started); telemetry.articleTime(partition, started); }
            // 事务代理已经退出；退避不持有数据库锁，也不计入SQL耗时。
            delay.await(1000L << attempt);
        }
        if (result == null) { throw new IllegalStateException("SQL文章消费未得到结果"); }
        for (var relation : result.results()) {
            telemetry.event("consume", relation.status().name().toLowerCase(Locale.ROOT));
        }
        try { projection.committedArticle(result); }
        catch (RuntimeException cacheFailure) {
            telemetry.event("cache", "commit_failure");
            telemetry.partitionEvent(partition, "cache_failure");
            log.warn("点赞文章已提交，缓存维护失败 articleId={}", articleId);
        }
        for (int index : reduced.accepted()) { channel.basicAck(tag(deliveries.get(index).message()), false); }
        telemetry.partitionEvent(partition, "committed");
    }

    private void isolateGroup(int partition, Channel channel, List<Delivery> deliveries,
                              List<Integer> indices, String reason) throws IOException {
        for (int index : indices) { isolate(partition, channel, tag(deliveries.get(index).message()), reason); }
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

    private static long tag(Message message) { return message.getMessageProperties().getDeliveryTag(); }
    private record Delivery(Message message, LikeCommand command) { }
    @FunctionalInterface public interface Delay { void await(long milliseconds) throws InterruptedException; }
}
